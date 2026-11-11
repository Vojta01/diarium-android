/**
 * Canonical recorded-mood rule for web statistics.
 *
 * A day is "answered" only when its mood is an integer from 1 to 5.
 * Missing values, null, undefined, 0 and anything out of range are unanswered:
 * they must never be counted in averages, distributions or denominators, and
 * they must never be coerced to the neutral value 3.
 */
export const MOOD_MIN = 1;
export const MOOD_MAX = 5;

export type Mood = 1 | 2 | 3 | 4 | 5;

/**
 * Android beta5 chart/calendar palette. This is the palette chart surfaces
 * (bars, pixels, legends) must render; the legacy chip palette in stats.ts is
 * deliberately different and stays with chip-style UI such as CalendarView.
 */
const CHART_PALETTE = {
  1: "#ef4444",
  2: "#f97316",
  3: "#fbbf24",
  4: "#34d399",
  5: "#22d3ee",
} as const satisfies Record<Mood, string>;

export const CHART_MOOD_COLORS: Record<number, string> = CHART_PALETTE;

/** Number of most recent entries that mood statistics look at. */
export const MOOD_WINDOW_SIZE = 30;

/**
 * Last `size` entries, then only the answered ones.
 *
 * The window is selected before filtering on purpose: a newest unanswered row
 * consumes a slot, so it reduces the answered count instead of pulling an
 * older answered row back into the statistics. Filtering first would silently
 * widen the window and change every average and distribution.
 */
export function recentMoodWindow<T extends { mood: unknown }>(
  entries: readonly T[],
  size: number = MOOD_WINDOW_SIZE
): T[] {
  if (size <= 0) return [];
  return entries.slice(-size).filter((entry) => isRecordedMood(entry.mood));
}

export function isRecordedMood(value: unknown): value is Mood {
  return (
    typeof value === "number" &&
    Number.isInteger(value) &&
    value >= MOOD_MIN &&
    value <= MOOD_MAX
  );
}

/** Returns the recorded mood, or null when the value does not count as answered. */
export function recordedMood(value: unknown): Mood | null {
  return isRecordedMood(value) ? value : null;
}

export function recordedMoods<T extends { mood: unknown }>(
  entries: readonly T[]
): Mood[] {
  return entries.map((entry) => entry.mood).filter(isRecordedMood);
}

/** Average of recorded moods only; null when the window has no answered day. */
export function averageMood(
  entries: readonly { mood: unknown }[]
): number | null {
  const moods = recordedMoods(entries);
  if (moods.length === 0) return null;
  return moods.reduce((sum, mood) => sum + mood, 0) / moods.length;
}

export function moodCounts(
  entries: readonly { mood: unknown }[]
): Record<number, number> {
  const counts: Record<number, number> = { 1: 0, 2: 0, 3: 0, 4: 0, 5: 0 };
  for (const entry of entries) {
    if (isRecordedMood(entry.mood)) counts[entry.mood]++;
  }
  return counts;
}
