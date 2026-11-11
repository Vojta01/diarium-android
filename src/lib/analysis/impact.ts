import type { HabitDef } from "../supabase/db.ts";
import { normalizeActivityValue } from "../goal-progress.ts";
import { isRecordedMood } from "../mood.ts";
import type { DailyEntry } from "../stats.ts";
import { welchDiff, type DiffEstimate } from "./uncertainty.ts";

/** Impact requires five days on each side before it will state a verdict. */
export const IMPACT_MIN_N = 5;

export interface ImpactResult {
  kind: "activity" | "habit";
  /** Normalized activity key, or the raw habit key. */
  key: string;
  /** Most frequent original label; the raw key when no definition is known. */
  label: string;
  labelEn?: string;
  /** Only affects wording, never the maths. */
  negative?: boolean;
  nWith: number;
  nWithout: number;
  meanWith: number | null;
  meanWithout: number | null;
  diff: DiffEstimate;
  sourceDaysWith: string[];
  sourceDaysWithout: string[];
}

/** The same deterministic normalization goals use, so labels and slugs meet. */
export function normalizeKey(label: string): string {
  return normalizeActivityValue(label);
}

function mean(values: readonly number[]): number | null {
  if (values.length === 0) return null;
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

interface MoodDay {
  date: string;
  mood: number;
  entry: DailyEntry;
}

/** Days from `axis` that have a recorded mood, oldest first. */
export function moodDays(
  entries: readonly DailyEntry[],
  axis: readonly string[]
): MoodDay[] {
  const byDate = new Map(entries.map((entry) => [entry.date, entry]));
  const days: MoodDay[] = [];
  for (const date of axis) {
    const entry = byDate.get(date);
    if (!entry || !isRecordedMood(entry.mood)) continue;
    days.push({ date, mood: entry.mood, entry });
  }
  return days;
}

function buildResult(
  kind: "activity" | "habit",
  key: string,
  label: string,
  withDays: readonly MoodDay[],
  withoutDays: readonly MoodDay[],
  extra: { labelEn?: string; negative?: boolean } = {}
): ImpactResult {
  const withValues = withDays.map((day) => day.mood);
  const withoutValues = withoutDays.map((day) => day.mood);
  return {
    kind,
    key,
    label,
    ...extra,
    nWith: withValues.length,
    nWithout: withoutValues.length,
    meanWith: mean(withValues),
    meanWithout: mean(withoutValues),
    diff: welchDiff(withValues, withoutValues, IMPACT_MIN_N),
    sourceDaysWith: withDays.map((day) => day.date),
    sourceDaysWithout: withoutDays.map((day) => day.date),
  };
}

interface LabelTally {
  count: number;
  spelling: string;
}

/**
 * Activity with-vs-without mood comparison.
 *
 * A recorded-mood day whose activity list does not contain the normalized key
 * is a real "without": activities and mood live in the same check-in form, so
 * an answered day with an empty list means the user selected nothing.
 */
export function activityImpacts(
  entries: readonly DailyEntry[],
  axis: readonly string[]
): ImpactResult[] {
  const days = moodDays(entries, axis);
  const labels = new Map<string, Map<string, LabelTally>>();
  const daysWith = new Map<string, MoodDay[]>();

  for (const day of days) {
    const seen = new Set<string>();
    for (const raw of day.entry.activities) {
      if (typeof raw !== "string" || !raw.trim()) continue;
      const spelling = raw.trim();
      const key = normalizeKey(spelling);
      if (!key || seen.has(key)) continue;
      seen.add(key);

      const spellings = labels.get(key) ?? new Map<string, LabelTally>();
      const tally = spellings.get(spelling) ?? { count: 0, spelling };
      tally.count += 1;
      spellings.set(spelling, tally);
      labels.set(key, spellings);

      const list = daysWith.get(key) ?? [];
      list.push(day);
      daysWith.set(key, list);
    }
  }

  const results: ImpactResult[] = [];
  for (const [key, spellings] of labels) {
    const chosen = [...spellings.values()].sort(
      (left, right) =>
        right.count - left.count || left.spelling.localeCompare(right.spelling)
    )[0];
    const withDays = daysWith.get(key) ?? [];
    const withDates = new Set(withDays.map((day) => day.date));
    const withoutDays = days.filter((day) => !withDates.has(day.date));
    results.push(
      buildResult("activity", key, chosen.spelling, withDays, withoutDays)
    );
  }
  return results;
}

function habitKeysInWindow(
  entries: readonly DailyEntry[],
  axis: readonly string[]
): string[] {
  const byDate = new Map(entries.map((entry) => [entry.date, entry]));
  const keys = new Set<string>();
  for (const date of axis) {
    const habits = byDate.get(date)?.habits;
    if (!habits || typeof habits !== "object") continue;
    for (const [key, value] of Object.entries(habits)) {
      if (typeof value === "boolean") keys.add(key);
    }
  }
  return [...keys].sort();
}

/**
 * Habit with-vs-without mood comparison.
 *
 * `habits[key] === true` is "with", `false` is "without", and a missing key is
 * "not answered" — it enters neither group. When habit definitions are known,
 * the analysed keys are intersected with them so a deleted, hidden or
 * sensitive habit cannot resurrect itself from old entry rows.
 */
export function habitImpacts(
  entries: readonly DailyEntry[],
  axis: readonly string[],
  habits: readonly HabitDef[] | null
): ImpactResult[] {
  // Without a readable active catalogue, raw historical keys may include
  // habits that were hidden or removed. Fail closed; preserve entry JSON.
  if (habits === null) return [];
  const days = moodDays(entries, axis);
  const tracked = habitKeysInWindow(entries, axis);
  const definitions = new Map(habits.map((habit) => [habit.key, habit]));
  const keys = tracked.filter((key) => definitions.has(key));

  const results: ImpactResult[] = [];
  for (const key of keys) {
    const withDays = days.filter((day) => day.entry.habits[key] === true);
    const withoutDays = days.filter((day) => day.entry.habits[key] === false);
    const definition = definitions.get(key);
    if (!definition) continue;
    results.push(
      buildResult(
        "habit",
        key,
        definition.label,
        withDays,
        withoutDays,
        definition
          ? { labelEn: definition.labelEn, negative: definition.is_negative }
          : {}
      )
    );
  }
  return results;
}

function comparable(result: ImpactResult): boolean {
  return result.diff.verdict !== "insufficient" && result.diff.diff !== null;
}

function ciWidth(result: ImpactResult): number {
  const { ciLow, ciHigh } = result.diff;
  return ciLow === null || ciHigh === null ? Number.POSITIVE_INFINITY : ciHigh - ciLow;
}

/**
 * Comparable rows first by effect size, then narrower CI, then more days.
 * Insufficient rows stay visible at the bottom, ordered by how much data they
 * already have.
 */
export function rankImpacts(results: readonly ImpactResult[]): ImpactResult[] {
  return [...results].sort((left, right) => {
    const leftComparable = comparable(left);
    const rightComparable = comparable(right);
    if (leftComparable !== rightComparable) return leftComparable ? -1 : 1;

    if (leftComparable && rightComparable) {
      const byEffect =
        Math.abs(right.diff.diff as number) - Math.abs(left.diff.diff as number);
      if (byEffect !== 0) return byEffect;
      const byWidth = ciWidth(left) - ciWidth(right);
      if (byWidth !== 0 && Number.isFinite(byWidth)) return byWidth;
      const byN =
        right.nWith + right.nWithout - (left.nWith + left.nWithout);
      if (byN !== 0) return byN;
    } else {
      const byN =
        right.nWith + right.nWithout - (left.nWith + left.nWithout);
      if (byN !== 0) return byN;
    }

    if (left.label !== right.label) return left.label.localeCompare(right.label);
    return left.key.localeCompare(right.key);
  });
}
