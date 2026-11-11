import { createSupabaseClient } from "./client.ts";
import { readStoredSession } from "../auth-storage.ts";
import {
  DASHBOARD_ERROR_CODES,
  dashboardReadError,
  signedOutError,
} from "../dashboard-errors.ts";
import { isPersonalMode, SENSITIVE_HABIT_KEYS } from "../feature-flags.ts";
import { mergeActiveHabitCatalog } from "../analysis/habit-catalog.ts";
import { pragueDateKey } from "../date.ts";
import { buildEntryRow, type EntryRowInput } from "../entry-row.ts";
import type { CheckInPayload } from "../types.ts";
import type { EntrySaveBaseline, PendingSaveRequest } from "../checkin/save-v2.ts";
import { validatePendingSaveRequest } from "../checkin/save-v2.ts";
import {
  CheckInError,
  isCheckInError,
  loadFailedError,
  photoError,
  saveFailedError,
  saveNetworkError,
} from "../checkin/errors.ts";
import { isStoredPhotoUrl, PHOTO_BUCKET, photoUploadStoragePath } from "../checkin/photo.ts";

export type { CheckInPayload } from "../types.ts";

// ── Typy ──

export interface Profile {
  id: string;
  github_user?: string;
  default_repo?: string;
  created_at: string;
}

export interface Entry {
  id: string;
  /** Server-owned CAS token. Check-in reads require it before enabling saves. */
  revision?: number;
  user_id: string;
  date: string;
  mood: number;
  mood_emoji: string;
  sleep_quality?: number;
  stress?: number;
  activities: string[];
  habits: Record<string, boolean>;
  gratitude: string[];
  note: string;
  weather?: string[];
  scale_values?: Record<string, number>;
  phone_screen_time?: number | null;
  phone_unlocks?: number | null;
  phone_top_apps?: { app: string; time_sec?: number; minutes?: number }[];
  photo_path?: string | null;
  ai_reflection?: string | null;
  created_at: string;
}

// ── Helpers ──

function getSupabase() {
  return createSupabaseClient();
}

/** Get the stored access token from localStorage (bypasses supabase-js auth issues) */
export function getAccessToken(): string | null {
  const session = readStoredSession();
  return session?.access_token || null;
}

/** Vytvoří Supabase klienta s explicitním session tokenem pro obcházení "Invalid API key" */
function getAuthenticatedClient() {
  const token = getAccessToken();
  if (!token) return getSupabase();
  
  const sb = getSupabase();
  // Don't await — we just need the client to have the session in its internal state
  // The session will be used for subsequent queries
  sb.auth.setSession({ access_token: token, refresh_token: '' }).catch(() => {});
  return sb;
}

/**
 * A client with the user's JWT in the request headers for Storage uploads and
 * the strict load. It does not call `setSession`: that mutates localStorage and
 * an empty refresh token can invalidate a valid session.
 */
async function getAuthenticatedClientReady() {
  const token = getAccessToken();
  return createSupabaseClient(token ?? undefined);
}

/** Získá aktuálně přihlášeného uživatele — z localStorage (rychlé, offline) */
export async function getCurrentUser() {
  // Try localStorage first (set by callback page)
  const session = readStoredSession();
  if (session?.user) return session.user;

  // Fallback: Supabase API
  const sb = getSupabase();
  const { data } = await sb.auth.getUser();
  return data.user ?? null;
}

/** Vráti profil přihlášeného uživatele (vytvoří ho, pokud neexistuje) */
export async function getProfile(): Promise<Profile | null> {
  const user = await getCurrentUser();
  if (!user) return null;

  const sb = getSupabase();
  const { data } = await sb
    .from("profiles")
    .select("*")
    .eq("id", user.id)
    .single();

  if (!data) {
    const { data: created } = await (sb
      .from("profiles") as any)
      .insert([{ id: user.id, github_user: user.user_metadata?.user_name ?? null }])
      .select()
      .single();
    return created ?? null;
  }

  return data;
}

// ── Entries ──

/** Token that was loaded from the server before editing this date. */
export interface CheckInSaveSnapshot {
  baseline: EntrySaveBaseline;
  requestId: string;
}

export interface CheckInSaveResponse {
  entry: Entry;
  replayed: boolean;
}

/** Save a foreground check-in through the authenticated conditional-save route. */
export function saveEntry(
  request: PendingSaveRequest
): Promise<CheckInSaveResponse>;
export function saveEntry(
  payload: CheckInPayload,
  snapshot: CheckInSaveSnapshot
): Promise<CheckInSaveResponse>;
export async function saveEntry(
  payloadOrRequest: CheckInPayload | PendingSaveRequest,
  snapshot?: CheckInSaveSnapshot
): Promise<CheckInSaveResponse> {
  const user = await getCurrentUser();
  if (!user) {
    throw new CheckInError("signed_out", "checkin.error_save_auth", 401);
  }

  if ("version" in payloadOrRequest && payloadOrRequest.version === 1) {
    try {
      validatePendingSaveRequest(payloadOrRequest);
    } catch (error) {
      throw new CheckInError("save_failed", "checkin.error_save_validation", 400, {
        cause: error,
      });
    }
    if (payloadOrRequest.ownerId !== user.id) {
      throw new CheckInError("save_failed", "checkin.error_save_forbidden", 403);
    }
    // The serialized request body is immutable. A retry never reconstructs it
    // from a potentially newer form or stores/replays a bearer credential.
    return postConditionalEntryBody(payloadOrRequest.body);
  }

  if (!snapshot) {
    throw new CheckInError("save_failed", "checkin.error_save_validation", 400);
  }
  // The pending-save branch above is the only runtime shape with a `version`
  // tag; the overload leaves the legacy payload branch as CheckInPayload.
  const payload = payloadOrRequest as CheckInPayload;
  const entryDate = payload.date || pragueDateKey();

  let row: Record<string, unknown>;
  try {
    row = buildEntryRow({
      ...payload,
      user_id: user.id,
      date: entryDate,
    } as EntryRowInput);
  } catch (error) {
    throw isCheckInError(error)
      ? error
      : new CheckInError("save_failed", "checkin.error_save_validation", 400, {
          cause: error,
        });
  }

  const body: Record<string, unknown> = {
    ...row,
    expected_exists: snapshot.baseline.exists,
    expected_id: snapshot.baseline.exists ? snapshot.baseline.id : null,
    expected_revision: snapshot.baseline.exists
      ? snapshot.baseline.revision
      : null,
    request_id: snapshot.requestId,
  };
  if (payload.clear_photo === true) body.clear_photo = true;
  if (payload.clear_scale_ids !== undefined) {
    body.clear_scale_ids = [...payload.clear_scale_ids];
  }

  return postConditionalEntryBody(JSON.stringify(body));
}

/** Persist an AI reflection as a partial patch; no form fields are resent. */
export async function patchEntry(patch: {
  date: string;
  ai_reflection: string;
}): Promise<Entry> {
  const user = await getCurrentUser();
  if (!user) {
    throw new CheckInError("signed_out", "checkin.error_save_auth", 401);
  }

  const row = buildEntryRow({
    user_id: user.id,
    date: patch.date,
    ai_reflection: patch.ai_reflection,
  });
  return postEntryRow(row);
}

async function postConditionalEntryBody(body: string): Promise<CheckInSaveResponse> {
  const token = getAccessToken();
  if (!token) {
    throw new CheckInError("signed_out", "checkin.error_save_auth", 401);
  }
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    Authorization: `Bearer ${token}`,
  };

  let res: Response;
  try {
    res = await fetch("/api/save-entry", {
      method: "POST",
      headers,
      body,
    });
  } catch (error) {
    throw saveNetworkError(error);
  }

  const bodyJson = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw saveFailedError(res.status, bodyJson);
  }
  const replayHeader = res.headers.get("X-Diarium-Replayed");
  if (
    !bodyJson ||
    typeof bodyJson !== "object" ||
    typeof bodyJson.id !== "string" ||
    typeof bodyJson.user_id !== "string" ||
    typeof bodyJson.date !== "string" ||
    typeof bodyJson.revision !== "number" ||
    !Number.isSafeInteger(bodyJson.revision) ||
    bodyJson.revision < 1 ||
    (replayHeader !== "true" && replayHeader !== "false")
  ) {
    throw saveFailedError(503, new Error("Conditional save response omitted its row revision or replay status"));
  }
  return { entry: bodyJson as Entry, replayed: replayHeader === "true" };
}

async function postEntryRow(row: Record<string, unknown>): Promise<Entry> {
  const token = getAccessToken();
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (token) headers["Authorization"] = `Bearer ${token}`;

  let res: Response;
  try {
    res = await fetch("/api/save-entry", {
      method: "POST",
      headers,
      body: JSON.stringify(row),
    });
  } catch (error) {
    // `fetch` rejects with TypeError on DNS/offline/CORS failure. That is a
    // retryable network error, not a validation error.
    throw saveNetworkError(error);
  }

  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw saveFailedError(res.status, body?.error ?? body);
  }

  return (await res.json()) as Entry;
}

/**
 * Upload a compressed check-in photo to the existing public `diary-photos`
 * bucket and return the public URL. The row only ever stores this URL.
 */
export async function uploadCheckInPhoto(
  blob: Blob,
  date: string
): Promise<string> {
  const user = await getCurrentUser();
  if (!user) {
    throw photoError("checkin.photo_error_upload", "signed out");
  }

  const sb = await getAuthenticatedClientReady();
  // A fresh object per pick: an abandoned edit must not overwrite the stored
  // photo, and a replacement URL must change so the <img> cache cannot show the
  // previous image.
  const nonce =
    typeof crypto !== "undefined" && "randomUUID" in crypto
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  const path = photoUploadStoragePath(user.id, date, nonce);

  const { data } = sb.storage.from(PHOTO_BUCKET).getPublicUrl(path);
  const publicUrl = data.publicUrl.trim();
  if (!isStoredPhotoUrl(publicUrl)) {
    throw photoError("checkin.photo_error_upload", "invalid public URL");
  }

  try {
    const { error } = await sb.storage.from(PHOTO_BUCKET).upload(path, blob, {
      contentType: "image/jpeg",
      upsert: false,
    });
    if (error) throw error;
  } catch (error) {
    // A failed response can follow a server-side write. Keep the generated
    // object: browser-local references cannot prove global non-ownership.
    throw photoError("checkin.photo_error_upload", error);
  }

  return publicUrl;
}

/** Načte entries pro dané období */
export async function getEntries(from?: string, to?: string): Promise<Entry[]> {
  const user = await getCurrentUser();
  if (!user) return [];

  const sb = getAuthenticatedClient();
  let query = sb
    .from("entries")
    .select("*")
    .eq("user_id", user.id)
    .order("date", { ascending: false });

  if (from) query = query.gte("date", from);
  if (to) query = query.lte("date", to);

  const { data, error } = await query;
  if (error) {
    console.error("Chyba při načítání entries:", error);
    return [];
  }
  return (data as Entry[]) ?? [];
}

/**
 * Strict single-day read for the check-in.
 *
 * `null` means "there is no entry for this date" and is safe to render as an
 * empty form. A query/auth failure throws, so the screen can show retry instead
 * of an empty form that would overwrite the stored day on save.
 */
export async function getEntryOrThrow(date: string): Promise<Entry | null> {
  const user = await getCurrentUser();
  if (!user) {
    throw new CheckInError("signed_out", "checkin.error_load", 401);
  }

  const sb = await getAuthenticatedClientReady();
  const { data: rows, error, status } = await sb
    .from("entries")
    .select("*")
    .eq("user_id", user.id)
    .eq("date", date)
    .limit(2);

  if (error) {
    throw loadFailedError(error, status);
  }
  if (!rows || rows.length === 0) return null;

  const raw = rows[0] as Record<string, unknown>;
  const revision =
    typeof raw.revision === "number"
      ? raw.revision
      : typeof raw.revision === "string" && /^\d+$/.test(raw.revision)
        ? Number(raw.revision)
        : NaN;
  if (
    typeof raw.id !== "string" ||
    !Number.isSafeInteger(revision) ||
    revision < 1
  ) {
    // Absence of the v2 revision column is not an "empty day". Keep the local
    // draft and block editing rather than permitting a blind legacy save.
    throw loadFailedError(new Error("Loaded entry has no usable CAS revision"), 503);
  }
  return normalizeEntryRow({ ...raw, revision });
}

/**
 * Tolerant compatibility wrapper. New check-in code must use `getEntryOrThrow`;
 * this stays for callers that intentionally treat a failed read as "no data".
 */
export async function getEntry(date: string): Promise<Entry | null> {
  try {
    return await getEntryOrThrow(date);
  } catch {
    return null;
  }
}

function normalizeEntryRow(raw: Record<string, unknown>): Entry {
  const entry = { ...raw } as Record<string, unknown>;
  if (entry.mood == null) entry.mood = 0;
  if (!entry.mood_emoji) entry.mood_emoji = "";
  if (!Array.isArray(entry.activities)) entry.activities = [];
  if (!entry.habits || typeof entry.habits !== "object") entry.habits = {};
  if (!Array.isArray(entry.gratitude)) entry.gratitude = [];
  if (entry.note == null) entry.note = "";
  if (!Array.isArray(entry.weather)) entry.weather = [];
  if (
    !entry.scale_values ||
    typeof entry.scale_values !== "object" ||
    Array.isArray(entry.scale_values)
  ) {
    entry.scale_values = {};
  }
  if (entry.photo_path === "") entry.photo_path = null;
  return entry as unknown as Entry;
}

// ── Aktivity & Návyky ──

export interface ActivityDef {
  key: string;
  label: string;
  labelEn?: string;
  icon: string;
  category: string;
  color: string;
  source: "default" | "custom";
}

export interface HabitDef {
  key: string;
  label: string;
  labelEn?: string;
  icon: string;
  category: string;
  color: string;
  is_negative: boolean;
  source: "default" | "custom";
}

// Hardcoded fallback — original activity catalog, used when DB is empty
const FALLBACK_ACTIVITIES: ActivityDef[] = [
  // Společenské
  { key: "rodina", label: "Rodina", labelEn: "Family", icon: "👨‍👩‍👧", category: "sociální", color: "#8b5cf6", source: "default" },
  { key: "pratele", label: "Přátelé", labelEn: "Friends", icon: "👥", category: "sociální", color: "#8b5cf6", source: "default" },
  { key: "rande", label: "Rande", labelEn: "Date", icon: "💑", category: "sociální", color: "#ec4899", source: "default" },
  { key: "party", label: "Párty", labelEn: "Party", icon: "🎉", category: "sociální", color: "#f97316", source: "default" },
  { key: "office", label: "Office", labelEn: "Office", icon: "🏢", category: "sociální", color: "#3b82f6", source: "default" },
  // Záliby
  { key: "filmy_a_tv", label: "Filmy a TV", labelEn: "Movies & TV", icon: "🎬", category: "volný čas", color: "#eab308", source: "default" },
  { key: "cteni", label: "Čtení", labelEn: "Reading", icon: "📖", category: "volný čas", color: "#a855f7", source: "default" },
  { key: "hrani_her", label: "Hraní her", labelEn: "Gaming", icon: "🎮", category: "volný čas", color: "#22c55e", source: "default" },
  { key: "sport", label: "Sport", labelEn: "Sports", icon: "🏃", category: "volný čas", color: "#22c55e", source: "default" },
  { key: "relax", label: "Relax", labelEn: "Relax", icon: "😌", category: "volný čas", color: "#8b5cf6", source: "default" },
  { key: "hudba", label: "Hudba", labelEn: "Music", icon: "🎵", category: "volný čas", color: "#ef4444", source: "default" },
  // Jídlo
  { key: "jist_zdrave", label: "Jíst zdravě", labelEn: "Eat healthy", icon: "🥗", category: "jídlo", color: "#22c55e", source: "default" },
  { key: "rychle_obcerstveni", label: "Rychlé občerstvení", labelEn: "Fast food", icon: "🍔", category: "jídlo", color: "#f97316", source: "default" },
  { key: "domaci_vyroba", label: "Domácí výroba", labelEn: "Home cooking", icon: "🍳", category: "jídlo", color: "#f59e0b", source: "default" },
  { key: "restaurace", label: "Restaurace", labelEn: "Restaurant", icon: "🍽️", category: "jídlo", color: "#f97316", source: "default" },
  { key: "donaska", label: "Donáška", labelEn: "Delivery", icon: "📦", category: "jídlo", color: "#f97316", source: "default" },
  { key: "den_bez_masa", label: "Den bez masa", labelEn: "Meatless day", icon: "🥬", category: "jídlo", color: "#22c55e", source: "default" },
  { key: "zadne_sladkosti", label: "Žádné sladkosti", labelEn: "No sweets", icon: "🚫🍰", category: "jídlo", color: "#ef4444", source: "default" },
  { key: "zadne_limonady", label: "Žádné limonády", labelEn: "No soda", icon: "🚫🥤", category: "jídlo", color: "#ef4444", source: "default" },
  // Zdraví / Sport
  { key: "trenink", label: "Trénink", labelEn: "Workout", icon: "🏋️", category: "sport", color: "#16a34a", source: "default" },
  { key: "pit_vody", label: "Pít vodu", labelEn: "Drink water", icon: "💧", category: "sport", color: "#3b82f6", source: "default" },
  { key: "chuze", label: "Chůze", labelEn: "Walking", icon: "🚶", category: "sport", color: "#84cc16", source: "default" },
  { key: "kolo", label: "Kolo", labelEn: "Cycling", icon: "🚴", category: "sport", color: "#84cc16", source: "default" },
  { key: "plavani", label: "Plavání", labelEn: "Swimming", icon: "🏊", category: "sport", color: "#06b6d4", source: "default" },
  { key: "paddleboard", label: "Paddleboard", labelEn: "Paddleboarding", icon: "🏄", category: "sport", color: "#06b6d4", source: "default" },
  { key: "snooker", label: "Snooker", labelEn: "Snooker", icon: "🎱", category: "sport", color: "#ef4444", source: "default" },
  // Mé lepší já
  { key: "meditovat", label: "Meditovat", labelEn: "Meditation", icon: "🧘", category: "wellness", color: "#7c3aed", source: "default" },
  { key: "laskavost", label: "Laskavost", labelEn: "Kindness", icon: "💝", category: "wellness", color: "#ec4899", source: "default" },
  { key: "naslouchani", label: "Naslouchání", labelEn: "Listening", icon: "👂", category: "wellness", color: "#8b5cf6", source: "default" },
  { key: "darcovstvi", label: "Dárcovství", labelEn: "Giving", icon: "💰", category: "wellness", color: "#22c55e", source: "default" },
  { key: "dej_darek", label: "Dej dárek", labelEn: "Gift", icon: "🎁", category: "wellness", color: "#f97316", source: "default" },
  { key: "terapie", label: "Terapie", labelEn: "Therapy", icon: "🛋️", category: "wellness", color: "#8b5cf6", source: "default" },
  { key: "integrita", label: "Integrita", labelEn: "Integrity", icon: "⚖️", category: "wellness", color: "#6366f1", source: "default" },
  // Domácí práce
  { key: "nakupovani", label: "Nakupování", labelEn: "Shopping", icon: "🛒", category: "domácí práce", color: "#f59e0b", source: "default" },
  { key: "uklizeni", label: "Uklízení", labelEn: "Cleaning", icon: "🧹", category: "domácí práce", color: "#3b82f6", source: "default" },
  { key: "vareni", label: "Vaření", labelEn: "Cooking", icon: "🍲", category: "domácí práce", color: "#f97316", source: "default" },
  { key: "prani", label: "Praní", labelEn: "Laundry", icon: "🧺", category: "domácí práce", color: "#3b82f6", source: "default" },
  { key: "zehleni", label: "Žehlení", labelEn: "Ironing", icon: "👕", category: "domácí práce", color: "#ef4444", source: "default" },
  // Počasí
  { key: "slunecno", label: "Slunečno", labelEn: "Sunny", icon: "☀️", category: "počasí", color: "#eab308", source: "default" },
  { key: "zatazeno", label: "Zataženo", labelEn: "Cloudy", icon: "☁️", category: "počasí", color: "#9ca3af", source: "default" },
  { key: "dest", label: "Déšť", labelEn: "Rain", icon: "🌧️", category: "počasí", color: "#3b82f6", source: "default" },
  { key: "snih", label: "Sníh", labelEn: "Snow", icon: "❄️", category: "počasí", color: "#e0e7ff", source: "default" },
  { key: "mraz", label: "Mráz", labelEn: "Freezing", icon: "🥶", category: "počasí", color: "#93c5fd", source: "default" },
  { key: "horko", label: "Horko", labelEn: "Heatwave", icon: "🌡️", category: "počasí", color: "#ef4444", source: "default" },
  { key: "bourka", label: "Bouřka", labelEn: "Thunderstorm", icon: "🌩️", category: "počasí", color: "#f59e0b", source: "default" },
  { key: "vitr", label: "Vítr", labelEn: "Windy", icon: "💨", category: "počasí", color: "#9ca3af", source: "default" },
];

export async function getActivities(): Promise<ActivityDef[]> {
  // Always start with hardcoded fallback — guarantees activities show even if DB fails
  let defaults: ActivityDef[] = [...FALLBACK_ACTIVITIES];
  let customs: ActivityDef[] = [];

  try {
    const user = await getCurrentUser();
    const sb = getAuthenticatedClient();

    const [defaultRes, customRes, hiddenRes] = await Promise.all([
      sb.from("activity_catalog").select("*").eq("is_default", true).order("sort_order"),
      user
        ? sb.from("user_activities").select("*").eq("user_id", user.id).eq("is_active", true)
        : Promise.resolve({ data: [] }),
      user
        ? sb.from("user_activities").select("*").eq("user_id", user.id).eq("is_active", false)
        : Promise.resolve({ data: [] }),
    ]);

    // Only override defaults if DB returned valid data
    if (defaultRes.data && defaultRes.data.length > 0 && !('error' in defaultRes && defaultRes.error)) {
      defaults = defaultRes.data.map((a: any) => ({ ...a, source: "default" as const }));
      
      // Filter out defaults that user has hidden
      const hiddenKeys = new Set((hiddenRes.data || []).map((h: any) => h.key));
      defaults = defaults.filter(d => !hiddenKeys.has(d.key));
    }

    if (customRes.data && customRes.data.length > 0 && !('error' in customRes && customRes.error)) {
      customs = customRes.data.map((a: any) => ({ ...a, source: "custom" as const }));
    }

    // If DB is empty, try seeding in background
    if ((!defaultRes.data || defaultRes.data.length === 0) && customs.length === 0) {
      fetch("/api/seed-activities", { method: "POST" }).catch(() => {});
    }
  } catch (e) {
    console.error("getActivities() DB error, using fallback:", e);
  }

  return [...defaults, ...customs];
}

/** Returns activities that user has hidden (is_active=false) — for the "restore" UI */
export async function getHiddenActivities(): Promise<ActivityDef[]> {
  const user = await getCurrentUser();
  if (!user) return [];
  const sb = getAuthenticatedClient();

  const { data } = await sb
    .from("user_activities")
    .select("*")
    .eq("user_id", user.id)
    .eq("is_active", false);

  if (!data || data.length === 0) return [];

  // Enrich with default catalog data if available
  const hiddenKeys = data.map((h: any) => h.key);
  const { data: catalogData } = await sb
    .from("activity_catalog")
    .select("*")
    .in("key", hiddenKeys);

  const catalogMap: Record<string, any> = {};
  (catalogData || []).forEach((c: any) => { catalogMap[c.key] = c; });

  return data.map((h: any) => {
    const catalog = catalogMap[h.key];
    return {
      key: h.key,
      label: catalog?.label || h.label || h.key,
      icon: catalog?.icon || h.icon || "📌",
      category: catalog?.category || "skryté",
      color: catalog?.color || "#6366f1",
      source: "default" as const,
    };
  });
}

export async function getHabits(): Promise<HabitDef[]> {
  const user = await getCurrentUser();
  const sb = getAuthenticatedClient();

  const [defaultRes, userRes] = await Promise.all([
    sb.from("habit_catalog").select("*").eq("is_default", true).order("sort_order"),
    user
      ? sb.from("user_habits").select("*").eq("user_id", user.id)
      : Promise.resolve({ data: [] }),
  ]);

  let defaults: HabitDef[] = defaultRes.data?.map((h: any) => ({ ...h, source: "default" as const })) ?? [];
  const userHabits: any[] = userRes.data ?? [];

  // Fallback: if DB returned empty, use hardcoded alkohol only + auto-seed
  if (defaults.length === 0) {
    defaults = [
      { key: "alkohol", label: "Alkohol", icon: "🍺", category: "zdraví", color: "#ef4444", is_negative: true, source: "default" as const },
    ];
    // Try to seed + clean up the DB in the background
    fetch("/api/seed-habits", { method: "POST" }).catch(() => {});
  }
  
  // Auto-seed if DB has more than just alkohol as default (old data)
  if (defaults.length > 1) {
    fetch("/api/seed-habits", { method: "POST" }).catch(() => {});
  }
  
  // Apply active overrides without deleting user rows or touching entries.habits.
  const visible = mergeActiveHabitCatalog(defaults, userHabits);

  // In core mode, filter out sensitive habits (kouření, studená sprcha)
  if (!isPersonalMode()) {
    const sensitiveSet = new Set(SENSITIVE_HABIT_KEYS);
    return visible.filter((habit) => !sensitiveSet.has(habit.key));
  }

  return visible;
}

/**
 * Strict habits read for the dashboard: throws on a failed query instead of
 * substituting the hardcoded fallback. The check-in keeps using `getHabits`,
 * whose fallback and seeding are deliberate there.
 */
export async function getHabitsOrThrow(): Promise<HabitDef[]> {
  const user = await getCurrentUser();
  if (!user) throw signedOutError();
  const sb = getAuthenticatedClient();

  const [defaultRes, userRes] = await Promise.all([
    sb.from("habit_catalog").select("*").eq("is_default", true).order("sort_order"),
    sb.from("user_habits").select("*").eq("user_id", user.id),
  ]);

  if (defaultRes.error) {
    throw dashboardReadError(
      defaultRes.error,
      DASHBOARD_ERROR_CODES.habits,
      defaultRes.status
    );
  }
  if (userRes.error) {
    throw dashboardReadError(
      userRes.error,
      DASHBOARD_ERROR_CODES.habits,
      userRes.status
    );
  }

  const visible = mergeActiveHabitCatalog(
    ((defaultRes.data ?? []) as HabitDef[]).map((habit) => ({
      ...habit,
      source: "default" as const,
    })),
    (userRes.data ?? []) as (HabitDef & { is_active?: boolean })[]
  );

  if (!isPersonalMode()) {
    const sensitive = new Set(SENSITIVE_HABIT_KEYS);
    return visible.filter((habit) => !sensitive.has(habit.key));
  }

  return visible;
}

/** Toggle a habit's visibility for the current user.
 *  Setting visible=false creates a user_habit with is_active=false (hides it).
 *  Setting visible=true removes the override (shows default) or creates an active custom habit. */
export async function setHabitVisibility(key: string, visible: boolean, habitInfo?: { label?: string; icon?: string; is_negative?: boolean }): Promise<void> {
  const user = await getCurrentUser();
  if (!user) return;
  const sb = getAuthenticatedClient();

  if (visible) {
    // Remove the "hide" override if it exists
    await sb.from("user_habits").delete().eq("user_id", user.id).eq("key", key).eq("is_active", false);
  } else {
    // Insert or update a "hide" override
    await sb.from("user_habits").upsert({
      user_id: user.id,
      key,
      label: habitInfo?.label || key,
      icon: habitInfo?.icon || '✅',
      is_negative: habitInfo?.is_negative ?? true,
      is_active: false,
    }, { onConflict: 'user_id,key' });
  }
}
