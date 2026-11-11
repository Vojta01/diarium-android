import type { DailyEntry } from "../src/lib/stats.ts";

/**
 * One complete `DailyEntry` for the dashboard tests.
 *
 * The mapper always returns every field, so tests build the same shape instead
 * of relying on partial objects that would hide a missing field.
 */
export function entry(
  date: string,
  overrides: Partial<DailyEntry> = {}
): DailyEntry {
  return {
    date,
    mood: 0,
    moodEmoji: "",
    sleep_quality: null,
    stress: null,
    activities: [],
    habits: {},
    gratitude: [],
    note: "",
    weather: [],
    scale_values: {},
    ai_reflection: null,
    phone_screen_time: null,
    phone_unlocks: null,
    phone_top_apps: undefined,
    photo_path: null,
    created_at: null,
    ...overrides,
  };
}
