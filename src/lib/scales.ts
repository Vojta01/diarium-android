import { createSupabaseClient } from "./supabase/client.ts";
import { readStoredSession } from "./auth-storage.ts";
import {
  DASHBOARD_ERROR_CODES,
  dashboardReadError,
  signedOutError,
} from "./dashboard-errors.ts";
import { missingDefaultScales } from "./checkin/scales.ts";

export interface Scale {
  id: string;
  user_id: string;
  name: string;
  emoji: string;
  min_value: number;
  max_value: number;
  unit?: string;
  color: string;
  sort_order: number;
  is_active: boolean;
  created_at: string;
}

export interface ScaleEntry {
  id: string;
  user_id: string;
  scale_id: string;
  date: string;
  value: number;
  created_at: string;
}

function getSupabase() {
  return createSupabaseClient();
}

function getAccessToken(): string | null {
  const session = readStoredSession();
  return session?.access_token || null;
}

async function getAuthenticatedClient() {
  const token = getAccessToken();
  const sb = getSupabase();
  if (token) {
    await sb.auth.setSession({ access_token: token, refresh_token: '' }).catch(() => {});
  }
  return sb;
}

async function getCurrentUserId(): Promise<string | null> {
  const session = readStoredSession();
  if (session?.user?.id) return session.user.id;
  
  const token = getAccessToken();
  if (token) {
    try {
      const payload = JSON.parse(atob(token.split('.')[1]));
      return payload.sub;
    } catch {}
  }
  
  const sb = getSupabase();
  const { data } = await sb.auth.getUser();
  return data.user?.id || null;
}

/**
 * Strict scales read for the dashboard: throws instead of turning a failed read
 * into an empty list. The legacy `getScales` keeps its old tolerant behaviour
 * for the existing statistics screen.
 */
export async function getScalesOrThrow(includeInactive = false): Promise<Scale[]> {
  const userId = await getCurrentUserId();
  if (!userId) throw signedOutError();

  const sb = await getAuthenticatedClient();
  let query = sb
    .from("scales")
    .select("*")
    .eq("user_id", userId);
  if (!includeInactive) query = query.eq("is_active", true);
  const { data, error, status } = await query.order("sort_order", { ascending: true });

  if (error) {
    throw dashboardReadError(error, DASHBOARD_ERROR_CODES.scales, status);
  }
  return data || [];
}

export async function getScales(): Promise<Scale[]> {
  try {
    return await getScalesOrThrow();
  } catch (error) {
    console.error("getScales error:", error);
    return [];
  }
}

export async function createScale(scale: Omit<Scale, 'id' | 'user_id' | 'created_at'>): Promise<Scale> {
  const userId = await getCurrentUserId();
  if (!userId) throw new Error("Not authenticated");
  
  const sb = await getAuthenticatedClient();
  const { data, error } = await sb
    .from("scales")
    .insert({ ...scale, user_id: userId })
    .select()
    .single();
  
  if (error) throw error;
  return data;
}

export async function updateScale(id: string, updates: Partial<Scale>): Promise<void> {
  const sb = await getAuthenticatedClient();
  const { error } = await sb
    .from("scales")
    .update(updates)
    .eq("id", id);
  
  if (error) throw error;
}

export async function deleteScale(id: string): Promise<void> {
  const sb = await getAuthenticatedClient();
  const { error } = await sb
    .from("scales")
    .delete()
    .eq("id", id);
  
  if (error) throw error;
}

export async function saveScaleEntry(scaleId: string, date: string, value: number): Promise<void> {
  const userId = await getCurrentUserId();
  if (!userId) throw new Error("Not authenticated");
  
  const sb = await getAuthenticatedClient();
  const { error } = await sb
    .from("scale_entries")
    .upsert({
      user_id: userId,
      scale_id: scaleId,
      date,
      value,
    }, { onConflict: 'user_id,scale_id,date' });
  
  if (error) throw error;
}

export async function getScaleEntries(scaleId: string, days: number = 30): Promise<ScaleEntry[]> {
  const userId = await getCurrentUserId();
  if (!userId) return [];
  
  const sb = await getAuthenticatedClient();
  const fromDate = new Date();
  fromDate.setDate(fromDate.getDate() - days);
  
  const { data, error } = await sb
    .from("scale_entries")
    .select("*")
    .eq("user_id", userId)
    .eq("scale_id", scaleId)
    .gte("date", fromDate.toISOString().split('T')[0])
    .order("date", { ascending: true });
  
  if (error) {
    console.error("getScaleEntries error:", error);
    return [];
  }
  return data || [];
}

export async function getScaleDistribution(scaleId: string, days: number = 30): Promise<Record<number, number>> {
  const entries = await getScaleEntries(scaleId, days);
  const distribution: Record<number, number> = {};
  
  entries.forEach(entry => {
    distribution[entry.value] = (distribution[entry.value] || 0) + 1;
  });
  
  return distribution;
}

export async function getScaleAverage(scaleId: string, days: number = 30): Promise<number> {
  const entries = await getScaleEntries(scaleId, days);
  if (entries.length === 0) return 0;
  
  const sum = entries.reduce((acc, entry) => acc + entry.value, 0);
  return sum / entries.length;
}

// Seed default Daylio-inspired scales for new users. The defaults live in
// `checkin/scales.ts` so the additive rule is unit-testable without Supabase.

export interface ScaleSeedDeps {
  /** Strict read; must reject on failure so seeding never runs blind. */
  read?: () => Promise<Scale[]>;
  create?: (scale: Omit<Scale, "id" | "user_id" | "created_at">) => Promise<Scale>;
}

/**
 * Create only the missing default scales.
 *
 * Deliberately never deletes or updates an existing scale: a custom scale, a
 * renamed default, or a default with a different range is the user's data, and
 * deleting it also cascades to `scale_entries` history. A failed read rejects
 * (see `getScalesOrThrow`) so the caller shows an error/retry instead of
 * seeding into an account whose scales are simply unknown.
 */
export async function seedDefaultScales(
  deps: ScaleSeedDeps = {}
): Promise<Scale[]> {
  // Inactive rows count as "existing": deactivating a default must not
  // resurrect a duplicate copy on the next load.
  const read = deps.read ?? (() => getScalesOrThrow(true));
  const create = deps.create ?? createScale;

  const existing = await read();
  const missing = missingDefaultScales(existing).map((def) => ({
    ...def,
    is_active: true,
  }));
  if (missing.length === 0) return existing;

  const created: Scale[] = [];
  for (const def of missing) {
    try {
      created.push(await create(def));
    } catch {
      // Keep the scales that were created; the caller can retry the rest.
    }
  }
  return [...existing, ...created];
}
