import { createSupabaseClient } from "./supabase/client";
import { readStoredSession } from "./auth-storage";

export interface Achievement {
  id: string;
  user_id: string;
  achievement_key: string;
  unlocked_at: string | null;
  progress: number;
  target: number;
}

export interface AchievementDef {
  key: string;
  name: string;
  description: string;
  emoji: string;
  target: number;
  category: 'streak' | 'count' | 'feature' | 'mood' | 'special';
}

// Achievement definitions (inspired by Daylio)
export const ACHIEVEMENTS: AchievementDef[] = [
  // Streaks
  { key: 'first_entry', name: 'První zápis', description: 'Vytvoř svůj první záznam', emoji: '🌱', target: 1, category: 'count' },
  { key: 'streak_7', name: 'Týden v kuse', description: '7 dní v řadě', emoji: '🔥', target: 7, category: 'streak' },
  { key: 'streak_30', name: 'Měsíc v kuse', description: '30 dní v řadě', emoji: '💪', target: 30, category: 'streak' },
  { key: 'streak_100', name: 'Sto dní', description: '100 dní v řadě', emoji: '🏆', target: 100, category: 'streak' },
  { key: 'streak_365', name: 'Rok v kuse', description: '365 dní v řadě', emoji: '👑', target: 365, category: 'streak' },

  // Counts
  { key: 'entries_10', name: 'Desítka', description: '10 záznamů', emoji: '📝', target: 10, category: 'count' },
  { key: 'entries_100', name: 'Stovka', description: '100 záznamů', emoji: '📚', target: 100, category: 'count' },
  { key: 'entries_365', name: 'Rok deníků', description: '365 záznamů', emoji: '📖', target: 365, category: 'count' },

  // Features
  { key: 'add_photo', name: 'Fotograf', description: 'Přidej fotku', emoji: '📸', target: 1, category: 'feature' },
  { key: 'use_scale', name: 'Měřič', description: 'Použij škálu', emoji: '📊', target: 1, category: 'feature' },
  { key: 'create_goal', name: 'Cílový', description: 'Vytvoř cíl', emoji: '🎯', target: 1, category: 'feature' },
  { key: 'complete_goal', name: 'Splněno', description: 'Splň cíl', emoji: '✅', target: 1, category: 'feature' },

  // Moods
  { key: 'all_moods_week', name: 'Emoční spektrum', description: '3 různé nálady v týdnu', emoji: '🌈', target: 1, category: 'mood' },
  { key: 'perfect_week', name: 'Perfektní týden', description: '7 dní v řadě skvělá nálada', emoji: '✨', target: 1, category: 'mood' },

  // Special
  { key: 'early_bird', name: 'Ranní ptáče', description: 'Záznam před 9:00', emoji: '🐦', target: 1, category: 'special' },
  { key: 'night_owl', name: 'Noční sova', description: 'Záznam po 23:00', emoji: '🦉', target: 1, category: 'special' },
  { key: 'weekend_warrior', name: 'Víkendový bojovník', description: 'Záznam v sobotu i neděli', emoji: '⚔️', target: 1, category: 'special' },
];

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

// ── Pure computation helpers ────────────────────────────────────────────────

/** Day difference between two YYYY-MM-DD date strings (calendar days). */
function dayDiff(a: string, b: string): number {
  const da = new Date(a + 'T00:00:00Z').getTime();
  const db = new Date(b + 'T00:00:00Z').getTime();
  return Math.round((da - db) / 86400000);
}

/** Longest run of consecutive calendar days in the given dates (ascending sorted). */
function computeMaxStreak(dates: string[]): number {
  if (dates.length === 0) return 0;
  const uniqueSorted = Array.from(new Set(dates)).sort();
  let maxStreak = 1;
  let current = 1;
  for (let i = 1; i < uniqueSorted.length; i++) {
    if (dayDiff(uniqueSorted[i], uniqueSorted[i - 1]) === 1) {
      current++;
      if (current > maxStreak) maxStreak = current;
    } else {
      current = 1;
    }
  }
  return maxStreak;
}

/** True if there is a run of ≥7 consecutive days all with mood === 5. */
function hasPerfectWeek(entries: any[]): boolean {
  const sorted = [...entries]
    .filter((e) => e.mood === 5)
    .sort((a, b) => a.date.localeCompare(b.date));
  if (sorted.length < 7) return false;
  let run = 1;
  for (let i = 1; i < sorted.length; i++) {
    if (dayDiff(sorted[i].date, sorted[i - 1].date) === 1) {
      run++;
      if (run >= 7) return true;
    } else {
      run = 1;
    }
  }
  return false;
}

/** True if any Saturday and its following Sunday both have entries. */
function hasWeekendPair(entries: any[]): boolean {
  const byDate = Array.from(new Set(entries.map((e) => e.date)));
  for (const dateStr of byDate) {
    const d = new Date(dateStr + 'T00:00:00Z');
    if (d.getUTCDay() === 6) {
      // Saturday → check Sunday (date + 1 day)
      const sunday = new Date(d.getTime() + 86400000).toISOString().split('T')[0];
      if (byDate.includes(sunday)) return true;
    }
  }
  return false;
}

function hourOf(iso: string | null | undefined): number | null {
  if (!iso) return null;
  const d = new Date(iso);
  if (isNaN(d.getTime())) return null;
  return d.getHours();
}

/** Max number of (unique) dates falling within any window of `windowDays` calendar days. */
function maxCountInWindow(dates: string[], windowDays: number): number {
  const uniq = Array.from(new Set(dates)).sort();
  let maxCount = 0;
  for (let i = 0; i < uniq.length; i++) {
    let count = 0;
    for (let j = i; j < uniq.length; j++) {
      if (dayDiff(uniq[j], uniq[i]) < windowDays) count++;
      else break;
    }
    if (count > maxCount) maxCount = count;
  }
  return maxCount;
}

/** True if a goal's activity was done ≥ target_count times within a single frequency window, ever. */
function goalEverCompleted(goal: any, entries: any[]): boolean {
  const freq = goal.frequency;
  const windowDays = freq === 'daily' ? 1 : freq === 'weekly' ? 7 : 30;
  const target = goal.target_count;
  const dates = entries
    .filter((e) => Array.isArray(e.activities) && e.activities.includes(goal.activity_key))
    .map((e) => e.date);
  return maxCountInWindow(dates, windowDays) >= target;
}

/**
 * Compute the *true* progress for every achievement from the raw data.
 * Returns a map key → progress (0..target). Feature achievements are monotonic:
 * once unlocked they stay unlocked (the sync layer enforces that).
 */
function computeProgress(
  entries: any[],
  goals: any[],
  live: { hasPhoto?: boolean; hasScale?: boolean } = {}
): Map<string, number> {
  const p = new Map<string, number>();
  const n = entries.length;

  // Counts
  p.set('first_entry', n >= 1 ? 1 : 0);
  p.set('entries_10', Math.min(n, 10));
  p.set('entries_100', Math.min(n, 100));
  p.set('entries_365', Math.min(n, 365));

  // Streaks (max historical, not just the current tail)
  const maxStreak = computeMaxStreak(entries.map((e) => e.date));
  p.set('streak_7', Math.min(maxStreak, 7));
  p.set('streak_30', Math.min(maxStreak, 30));
  p.set('streak_100', Math.min(maxStreak, 100));
  p.set('streak_365', Math.min(maxStreak, 365));

  // Features (derived from persisted data + live form flags)
  const hasScaleInDb = entries.some((e) => {
    const sv = e.scale_values;
    if (!sv || typeof sv !== 'object') return false;
    return Object.values(sv).some((v) => Number(v) > 0);
  });
  const hasPhotoInDb = entries.some((e) => !!e.photo_path);
  p.set('use_scale', hasScaleInDb || live.hasScale ? 1 : 0);
  p.set('add_photo', hasPhotoInDb || live.hasPhoto ? 1 : 0);
  p.set('create_goal', goals.length >= 1 ? 1 : 0);
  p.set('complete_goal', goals.some((g) => goalEverCompleted(g, entries)) ? 1 : 0);

  // Moods — last 7 entries
  const last7 = entries.slice(-7);
  const uniqueMoods = new Set(last7.map((e) => e.mood).filter((m) => m >= 1 && m <= 5));
  p.set('all_moods_week', uniqueMoods.size >= 3 ? 1 : 0);
  p.set('perfect_week', hasPerfectWeek(entries) ? 1 : 0);

  // Special
  const anyEarly = entries.some((e) => {
    const h = hourOf(e.created_at);
    return h !== null && h < 9;
  });
  const anyNight = entries.some((e) => {
    const h = hourOf(e.created_at);
    return h !== null && h >= 23;
  });
  p.set('early_bird', anyEarly ? 1 : 0);
  p.set('night_owl', anyNight ? 1 : 0);
  p.set('weekend_warrior', hasWeekendPair(entries) ? 1 : 0);

  return p;
}

// ── Persistence ─────────────────────────────────────────────────────────────

export async function getAchievements(): Promise<Achievement[]> {
  const userId = await getCurrentUserId();
  if (!userId) return [];

  const sb = await getAuthenticatedClient();
  const { data, error } = await sb
    .from("achievements")
    .select("*")
    .eq("user_id", userId);

  if (error) {
    console.error("getAchievements error:", error);
    return [];
  }
  return data || [];
}

/**
 * Recompute every achievement from the actual data and persist the true progress.
 * Self-healing: call this on panel load and after every save. Progress is monotonic
 * (never decreases), so an unlocked achievement stays unlocked.
 */
export async function syncAchievements(
  live?: { hasPhoto?: boolean; hasScale?: boolean }
): Promise<{ progress: Map<string, number>; newlyUnlocked: string[] }> {
  const userId = await getCurrentUserId();
  if (!userId) return { progress: new Map(), newlyUnlocked: [] };

  const sb = await getAuthenticatedClient();

  const [entriesRes, goalsRes, existingRes] = await Promise.all([
    sb
      .from("entries")
      .select("date, mood, created_at, photo_path, scale_values, activities")
      .eq("user_id", userId)
      .order("date", { ascending: true })
      .limit(10000),
    sb.from("goals").select("id, completed_at, activity_key, target_count, frequency").eq("user_id", userId),
    sb
      .from("achievements")
      .select("id, achievement_key, progress, unlocked_at")
      .eq("user_id", userId),
  ]);

  const entries = entriesRes.data ?? [];
  const goals = goalsRes.data ?? [];
  const existing = existingRes.data ?? [];
  const existingMap = new Map(existing.map((a) => [a.achievement_key, a]));

  const computed = computeProgress(entries, goals, live ?? {});
  const newlyUnlocked: string[] = [];

  for (const def of ACHIEVEMENTS) {
    const computedProgress = computed.get(def.key) ?? 0;
    const ex = existingMap.get(def.key);
    // Monotonic: never lower an already-earned progress.
    const finalProgress = Math.max(computedProgress, ex?.progress ?? 0);
    const isUnlocked = finalProgress >= def.target;
    const wasUnlocked = !!ex && (ex.progress >= def.target || ex.unlocked_at != null);

    if (!ex) {
      const { error } = await sb.from("achievements").insert({
        user_id: userId,
        achievement_key: def.key,
        progress: finalProgress,
        target: def.target,
        unlocked_at: isUnlocked ? new Date().toISOString() : null,
      });
      if (!error && isUnlocked) newlyUnlocked.push(def.key);
    } else if (finalProgress !== ex.progress) {
      const { error } = await sb
        .from("achievements")
        .update({
          progress: finalProgress,
          unlocked_at:
            isUnlocked && ex.unlocked_at == null ? new Date().toISOString() : ex.unlocked_at,
        })
        .eq("id", ex.id);
      if (!error && isUnlocked && !wasUnlocked) newlyUnlocked.push(def.key);
    }
  }

  return { progress: computed, newlyUnlocked };
}

/**
 * Backwards-compatible wrapper used by the check-in save handler.
 * Syncs achievements from real data and returns the list of newly unlocked keys.
 */
export async function checkAndUnlockAchievements(entryData: {
  mood: number;
  hasPhoto: boolean;
  hasScale: boolean;
  createdAt?: string;
}): Promise<string[]> {
  const { newlyUnlocked } = await syncAchievements({
    hasPhoto: entryData.hasPhoto,
    hasScale: entryData.hasScale,
  });
  return newlyUnlocked;
}

export async function getAchievementProgress(): Promise<{ def: AchievementDef; unlocked: boolean; progress: number }[]> {
  // Self-heal on every load: recompute progress from real data.
  const { progress } = await syncAchievements();
  return ACHIEVEMENTS.map((def) => {
    const prog = progress.get(def.key) ?? 0;
    return {
      def,
      unlocked: prog >= def.target,
      progress: prog,
    };
  });
}
