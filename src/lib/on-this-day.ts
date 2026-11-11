import { shiftMonthKey } from "./date.ts";
import { isRecordedMood } from "./mood.ts";
import type { DailyEntry } from "./stats.ts";

/**
 * How far back a memory reaches — the three lanes of „Tenhle den v historii".
 *
 * The declaration order is the ladder the picker walks: a year-old day is the
 * rarest, most surprising find; six months is the next best; a month ago is the
 * everyday fallback. The web translates the horizon through
 * `dashboard.onThisDay.horizons.<horizon>`; the data layer never carries Czech
 * copy (that was one of the production issues in the Android port).
 */
export type MemoryHorizon = "one_year" | "six_months" | "one_month";

/** Horizon order used by both the picker and the card's label lookup. */
export const MEMORY_HORIZONS: readonly MemoryHorizon[] = [
  "one_year",
  "six_months",
  "one_month",
] as const;

/** One past day worth re-reading, with the lane it was found in. */
export interface OnThisDayMemory {
  date: string;
  horizon: MemoryHorizon;
  entry: DailyEntry;
}

/**
 * The candidate dates for `today`, in priority order: one year, six months and
 * one month back.
 *
 * All arithmetic goes through `shiftMonthKey`, which clamps the day-of-month:
 * 2028-02-29 minus a year is 2027-02-28, and 2026-03-31 minus a month is
 * 2026-02-28 — never a rollover into the next month.
 */
export function targetMemoryDates(
  today: string
): readonly { horizon: MemoryHorizon; date: string }[] {
  return [
    { horizon: "one_year", date: shiftMonthKey(today, -12) },
    { horizon: "six_months", date: shiftMonthKey(today, -6) },
    { horizon: "one_month", date: shiftMonthKey(today, -1) },
  ];
}

/**
 * A day qualifies only when it is a *recorded* day (mood 1..5) that also
 * carries something worth re-reading — a non-blank note, at least one
 * non-blank gratitude line, or a photo.
 *
 * A mood-only day is deliberately not a memory: the mood alone says how the
 * day felt, not what happened. A phone-sync row without a mood fails the first
 * gate for the same reason it fails the streak.
 */
export function isMemoryWorthy(entry: DailyEntry): boolean {
  if (!isRecordedMood(entry.mood)) return false;
  return (
    entry.note.trim().length > 0 ||
    entry.gratitude.some((line) => line.trim().length > 0) ||
    (typeof entry.photo_path === "string" && entry.photo_path.trim().length > 0)
  );
}

/**
 * Picks the most interesting memory for the dashboard: the first horizon (in
 * priority order) whose date holds a qualifying entry.
 *
 * Returns null when no candidate date qualifies, so the card hides instead of
 * rendering an empty memory. Duplicate dates are collapsed last-wins, matching
 * the rest of the data layer.
 */
export function pickBestMemory(
  today: string,
  entries: readonly DailyEntry[]
): OnThisDayMemory | null {
  const byDate = new Map<string, DailyEntry>();
  for (const entry of entries) {
    if (entry.date) byDate.set(entry.date, entry);
  }

  for (const candidate of targetMemoryDates(today)) {
    const entry = byDate.get(candidate.date);
    if (entry && isMemoryWorthy(entry)) {
      return { date: candidate.date, horizon: candidate.horizon, entry };
    }
  }
  return null;
}
