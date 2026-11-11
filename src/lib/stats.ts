"use client";

import { createSupabaseClient } from "./supabase/client.ts";
import { readStoredSession } from "./auth-storage.ts";
import {
  DASHBOARD_ERROR_CODES,
  dashboardReadError,
  signedOutError,
} from "./dashboard-errors.ts";
import { recordedMood } from "./mood.ts";

/** One app used most on the phone that day, in the single shape the dashboard reads. */
export interface TopApp {
  app: string;
  /** Seconds of use. The phone worker stores seconds; legacy rows stored minutes. */
  time_sec: number;
}

export interface DailyEntry {
  date: string;
  /** Recorded mood 1..5; 0 means the day has no recorded mood. */
  mood: number;
  moodEmoji: string;
  /** 0/null means the sleep question was not answered. */
  sleep_quality: number | null;
  /** 0/null means the stress question was not answered. */
  stress: number | null;
  activities: string[];
  habits: Record<string, boolean>;
  gratitude: string[];
  note: string;
  weather: string[];
  /** Only scales the user actually touched are stored (value > 0). */
  scale_values: Record<string, number>;
  /** AI-generated reflection, or null when none was generated. */
  ai_reflection: string | null;
  phone_screen_time?: number | null;
  phone_unlocks?: number | null;
  /** Always normalized to {app, time_sec}; legacy minute rows are converted. */
  phone_top_apps?: TopApp[];
  /** Public URL of the day's photo (null if no photo). */
  photo_path: string | null;
  /** Row creation timestamp; null for legacy rows. Used only for stable dedupe. */
  created_at: string | null;
}

/** A goal-progress row: only the columns the pure calendar maths reads. */
export interface GoalActivityEntry {
  date: string;
  activities: string[];
  habits: Record<string, boolean>;
}

/** Supabase/PostgREST returns at most this many rows per request by default. */
const ENTRY_PAGE_SIZE = 1000;

/** Coerce a database numeric value without turning null/undefined into zero. */
function numberOrNull(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

/** Coerce a database boolean map, ignoring arrays and non-boolean values. */
function booleanMap(value: unknown): Record<string, boolean> {
  let parsed: unknown = value;
  // PostgREST normally returns jsonb as an object, but an older/edge response
  // can carry it JSON-encoded as a string. Android tolerates that; so do we.
  if (typeof parsed === "string") {
    try {
      parsed = JSON.parse(parsed);
    } catch {
      return {};
    }
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {};
  return Object.fromEntries(
    Object.entries(parsed as Record<string, unknown>).filter(
      (entry): entry is [string, boolean] => typeof entry[1] === "boolean"
    )
  );
}

/** Coerce a JSON object of numeric scale values, dropping unknown entries. */
function numericMap(value: unknown): Record<string, number> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  return Object.fromEntries(
    Object.entries(value as Record<string, unknown>).filter(
      (entry): entry is [string, number] =>
        typeof entry[1] === "number" && Number.isFinite(entry[1])
    )
  );
}

/**
 * Normalize one `entries` row to the dashboard shape.
 *
 * The previous mapper dropped sleep, stress, weather, scales, the AI reflection
 * and the photo, so every card that needed them read `undefined`. It also left
 * `phone_top_apps` in two shapes: older rows store `{app, minutes}`, newer ones
 * `{app, time_sec}`. Both are converted here so downstream code has one key.
 */
export function mapDailyEntryRow(row: Record<string, unknown>): DailyEntry {
  const rawApps = Array.isArray(row.phone_top_apps) ? row.phone_top_apps : [];
  const apps: TopApp[] = [];
  for (const raw of rawApps) {
    if (!raw || typeof raw !== "object") continue;
    const app = (raw as { app?: unknown }).app;
    if (typeof app !== "string" || !app.trim()) continue;

    const seconds = (raw as { time_sec?: unknown }).time_sec;
    const minutes = (raw as { minutes?: unknown }).minutes;
    const timeSec =
      typeof seconds === "number" && Number.isFinite(seconds)
        ? seconds
        : typeof minutes === "number" && Number.isFinite(minutes)
          ? Math.round(minutes * 60)
          : null;
    if (timeSec == null || timeSec < 0) continue;

    apps.push({ app, time_sec: timeSec });
  }

  return {
    date: typeof row.date === "string" ? row.date : "",
    // Never invent a neutral 3 for an unanswered day; 0 means "not recorded".
    mood: recordedMood(row.mood) ?? 0,
    moodEmoji: typeof row.mood_emoji === "string" ? row.mood_emoji : "",
    sleep_quality: numberOrNull(row.sleep_quality),
    stress: numberOrNull(row.stress),
    activities: Array.isArray(row.activities)
      ? row.activities.filter((item): item is string => typeof item === "string")
      : [],
    habits: booleanMap(row.habits),
    gratitude: Array.isArray(row.gratitude)
      ? row.gratitude.filter((item): item is string => typeof item === "string")
      : [],
    note: typeof row.note === "string" ? row.note : "",
    weather: Array.isArray(row.weather)
      ? row.weather.filter((item): item is string => typeof item === "string")
      : [],
    scale_values: numericMap(row.scale_values),
    ai_reflection:
      typeof row.ai_reflection === "string" && row.ai_reflection.trim()
        ? row.ai_reflection
        : null,
    phone_screen_time: numberOrNull(row.phone_screen_time),
    phone_unlocks: numberOrNull(row.phone_unlocks),
    phone_top_apps: apps.length > 0 ? apps : undefined,
    photo_path:
      typeof row.photo_path === "string" && row.photo_path.trim()
        ? row.photo_path
        : null,
    created_at:
      typeof row.created_at === "string" && row.created_at.trim()
        ? row.created_at
        : null,
  };
}

/**
 * Collapse duplicate dates, newest write wins.
 *
 * A day should have one row, but sync workers and retried saves have produced
 * duplicates in production. Ordering is explicit rather than trusted from the
 * query: date ascending, then `created_at` ascending with null first, then the
 * caller's order. That makes a row with a real timestamp always beat a legacy
 * row with no timestamp, and gives duplicate null timestamps a stable winner
 * (the later row in the input). The result is ascending by date, which is the
 * order every statistics view expects.
 */
export function dedupeDailyEntries(
  entries: readonly DailyEntry[]
): DailyEntry[] {
  const ordered = entries
    .map((entry, index) => ({ entry, index }))
    .filter(({ entry }) => entry.date.length > 0)
    .sort((left, right) => {
      const byDate = left.entry.date.localeCompare(right.entry.date);
      if (byDate !== 0) return byDate;
      const byCreated = (left.entry.created_at ?? "").localeCompare(
        right.entry.created_at ?? ""
      );
      if (byCreated !== 0) return byCreated;
      return left.index - right.index;
    });

  const byDate = new Map<string, DailyEntry>();
  for (const { entry } of ordered) {
    byDate.set(entry.date, entry);
  }
  return [...byDate.values()];
}

/** Resolve the signed-in user the same way the rest of the web data layer does. */
async function resolveUserId(
  sb: ReturnType<typeof createSupabaseClient>
): Promise<string | null> {
  const session = readStoredSession();
  if (session?.access_token) {
    // Always set session so RLS has auth.uid() context.
    await sb.auth
      .setSession({
        access_token: session.access_token,
        refresh_token: session.refresh_token || "",
      })
      .catch(() => {});

    const sessionUserId = session.user?.id || null;
    if (sessionUserId) return sessionUserId;
    try {
      const payload = JSON.parse(atob(session.access_token.split(".")[1]));
      if (typeof payload.sub === "string") return payload.sub;
    } catch {
      // Fall through to supabase-js.
    }
  }

  const { data } = await sb.auth.getUser();
  return data.user?.id ?? null;
}

/** Optional calendar bounds for a dashboard-sized read. */
export interface FetchDailyEntriesOptions {
  /** Inclusive ISO date; omitted means "from the oldest row". */
  from?: string;
  /** Inclusive ISO date; omitted means "through the newest row". */
  to?: string;
}

/**
 * Load daily entries for the signed-in user, oldest first.
 *
 * With no bounds this is the full history the statistics screens need; the
 * dashboard passes a 30-day window so its numbers stay identical to Android.
 * The read is paginated until a short page arrives. A read failure throws a
 * semantic `DashboardDataError` instead of returning `[]`, because "no
 * entries" and "the request died" must not look the same. A missing session
 * throws `signed_out` (401) so the UI can show the login-expired state instead
 * of an empty dashboard.
 */
export async function fetchDailyEntries(
  options: FetchDailyEntriesOptions = {}
): Promise<DailyEntry[]> {
  const sb = createSupabaseClient();
  const userId = await resolveUserId(sb);
  if (!userId) throw signedOutError();

  const rows: Record<string, unknown>[] = [];
  let offset = 0;

  for (;;) {
    let query = sb
      .from("entries")
      .select("*")
      .eq("user_id", userId)
      .order("date", { ascending: true })
      .order("created_at", { ascending: true });
    if (options.from) query = query.gte("date", options.from);
    if (options.to) query = query.lte("date", options.to);

    const { data, error, status } = await query.range(
      offset,
      offset + ENTRY_PAGE_SIZE - 1
    );

    if (error) {
      // A 401 here is an expired session even though `resolveUserId` returned
      // the stored id; it must become `signed_out`, not a retryable read error.
      throw dashboardReadError(
        error,
        DASHBOARD_ERROR_CODES.entries,
        status
      );
    }

    const page = (data ?? []) as Record<string, unknown>[];
    rows.push(...page);
    if (page.length < ENTRY_PAGE_SIZE) break;
    offset += ENTRY_PAGE_SIZE;
  }

  return dedupeDailyEntries(rows.map(mapDailyEntryRow));
}

/**
 * Load only the exact dates the memory lane asks for.
 *
 * Android reads the 1-year / 6-month / 1-month lookbacks separately from the
 * 30-day dashboard range; doing the same here keeps old top apps and old
 * reflections out of the dashboard while still letting a memory open its day
 * detail. Any failure is the caller's to treat as "no memory today".
 */
export async function fetchEntriesByDates(
  dates: readonly string[]
): Promise<DailyEntry[]> {
  const unique = [...new Set(dates.filter((date) => date.length > 0))];
  if (unique.length === 0) return [];

  const sb = createSupabaseClient();
  const userId = await resolveUserId(sb);
  if (!userId) throw signedOutError();

  const { data, error, status } = await sb
    .from("entries")
    .select("*")
    .eq("user_id", userId)
    .in("date", unique)
    .order("date", { ascending: true })
    .order("created_at", { ascending: true });

  if (error) {
    throw dashboardReadError(error, DASHBOARD_ERROR_CODES.entries, status);
  }

  return dedupeDailyEntries(
    ((data ?? []) as Record<string, unknown>[]).map(mapDailyEntryRow)
  );
}

/**
 * Read the year of `date, activities, habits` rows the goal maths needs.
 *
 * Goals are their own feature in Android: the dashboard's 30-day window cannot
 * carry a monthly history or a long daily streak, so this read is deliberately
 * separate from `fetchDailyEntries`' dashboard window.
 */
export async function fetchGoalActivityEntries(
  from: string,
  to: string
): Promise<GoalActivityEntry[]> {
  const sb = createSupabaseClient();
  const userId = await resolveUserId(sb);
  if (!userId) throw signedOutError();

  const rows: Record<string, unknown>[] = [];
  let offset = 0;

  for (;;) {
    const { data, error, status } = await sb
      .from("entries")
      .select("date,activities,habits")
      .eq("user_id", userId)
      .gte("date", from)
      .lte("date", to)
      .order("date", { ascending: true })
      .range(offset, offset + ENTRY_PAGE_SIZE - 1);

    if (error) {
      throw dashboardReadError(error, DASHBOARD_ERROR_CODES.goals, status);
    }

    const page = (data ?? []) as Record<string, unknown>[];
    rows.push(...page);
    if (page.length < ENTRY_PAGE_SIZE) break;
    offset += ENTRY_PAGE_SIZE;
  }

  return rows.map((row) => ({
    date: typeof row.date === "string" ? row.date : "",
    activities: Array.isArray(row.activities)
      ? row.activities.filter(
          (item): item is string => typeof item === "string"
        )
      : [],
    habits: booleanMap(row.habits),
  }));
}

export const MOOD_COLORS: Record<number, string> = {
  5: "#22c55e",
  4: "#3b82f6",
  3: "#eab308",
  2: "#f97316",
  1: "#ef4444",
};

export const MOOD_LABELS: Record<number, string> = {
  5: "😄 Skvěle",
  4: "🙂 Dobře",
  3: "😐 Jde to",
  2: "😟 Špatně",
  1: "😡 Hrozně",
};

export const MOOD_EMOJIS: Record<number, string> = {
  5: "😄",
  4: "🙂",
  3: "😐",
  2: "😟",
  1: "😡",
};
