import { shiftDateKey } from "./date.ts";
import { isRecordedMood } from "./mood.ts";
import {
  pickBestMemory,
  targetMemoryDates,
  type OnThisDayMemory,
} from "./on-this-day.ts";
import type { DailyEntry, TopApp } from "./stats.ts";

/** Days fetched for the dashboard's month view and longest-run calculation. */
export const DASHBOARD_LOAD_DAYS = 30;
/** Calendar days in the week overview, the hero totals and the previous window. */
export const DASHBOARD_WEEK_DAYS = 7;
/** Apps the top-apps card lists. */
export const TOP_APPS_LIMIT = 5;
/** Sub-minute rows are launch noise; they must not push a real leader off the list. */
export const TOP_APPS_MIN_SECONDS = 60;
/** Below this a list cannot show a ranking; the card says so instead. */
export const RANKED_TOP_APPS_MIN = 3;
/** Below this a move is rounding noise, not a trend. */
export const TREND_FLAT_PERCENT = 5;

/**
 * The exact card order of the Android beta5 overview. The web renders this
 * order from the data, never from CSS `order`; conditional cards are skipped
 * without disturbing the pairs around them.
 */
export const DASHBOARD_CARD_ORDER = [
  "hero_stats",
  "streak",
  "week",
  "mood_chart",
  "on_this_day",
  "goals",
  "screen_time",
  "unlocks",
  "top_apps",
  "reflection",
  "entries",
] as const;

export type DashboardCardId = (typeof DASHBOARD_CARD_ORDER)[number];

/** One day of the dashboard's week/month window, as the UI draws it. */
export interface DashboardDay {
  date: string;
  /** Recorded mood 1..5; 0 means no mood was recorded. */
  mood: number;
  /**
   * A row exists for the day. This is **not** "the day was journaled": the
   * phone sync writes a row for every day, so only `mood` decides that.
   */
  hasEntry: boolean;
  /** Seconds the sync worker stored, or null when that day was never synced. */
  screenTimeSeconds: number | null;
  unlocks: number | null;
}

/** The latest day that has an AI reflection, with the text to render. */
export interface DashboardReflection {
  date: string;
  text: string;
}

/** The newest logged day and the reflection it carries, if any. */
export interface DashboardNewestEntry {
  date: string;
  entry: DailyEntry;
  /** Null exactly when that day has no non-blank reflection. */
  reflection: string | null;
}

/** The latest day that has gratitude lines. */
export interface DashboardGratitude {
  date: string;
  lines: string[];
  moodEmoji: string;
}

/** The latest day the sync worker captured top apps for. */
export interface DashboardTopApps {
  date: string;
  apps: TopApp[];
}

/** Everything the dashboard screen renders, derived from one list of entries. */
export interface DashboardData {
  today: string;
  todayEntry: DailyEntry | null;
  streak: number;
  longestStreak: number;
  /** The seven calendar days ending today, oldest first. */
  week: DashboardDay[];
  /** The seven days before `week`; empty only if the caller built it by hand. */
  previousWeek: DashboardDay[];
  /** The 30-day window ending today, oldest first — the mood chart's month view. */
  month: DashboardDay[];
  averageMood: number | null;
  screenTimeMinutes: number | null;
  unlocks: number | null;
  topApps: DashboardTopApps | null;
  /** Same, restricted to the newest day whose list is long enough to rank. */
  rankedTopApps: DashboardTopApps | null;
  reflection: DashboardReflection | null;
  /** The newest *recorded* day (mood filled) — the reflection card's anchor. */
  newestRecorded: DashboardNewestEntry | null;
  /** The newest logged day, whatever it holds. */
  newestEntry: DashboardNewestEntry | null;
  lastGratitude: DashboardGratitude | null;
  /** Every loaded day's full record, keyed by ISO date (30-day core + memory dates). */
  entryByDate: Record<string, DailyEntry>;
  /**
   * Best memory among the 1-year / 6-month / 1-month lookbacks.
   *
   * `deriveDashboard` always leaves this null: the lookback dates sit outside
   * the 30-day core and are fetched separately, then attached with
   * `attachOnThisDay`. That split is what stops an old reflection or an old
   * top-app snapshot from leaking into the dashboard.
   */
  onThisDay: OnThisDayMemory | null;
}

export type DashboardMetricKind = "screen_time" | "unlocks" | "mood";
export type TrendTone = "usage" | "mood";
export type TrendDirection = "up" | "down" | "flat" | "unknown";

/** One hero number compared with the seven days before it. */
export interface MetricTrend {
  current: number | null;
  previous: number | null;
  direction: TrendDirection;
  /** Null whenever a percentage would be meaningless (no previous total / zero). */
  percentChange: number | null;
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Strict ISO calendar date; rejects impossible days such as 2026-02-30. */
export function isDateKey(value: string): boolean {
  if (!ISO_DATE.test(value)) return false;
  const parsed = new Date(`${value}T12:00:00Z`);
  return (
    !Number.isNaN(parsed.getTime()) &&
    parsed.toISOString().slice(0, 10) === value
  );
}

/** A day is a record only when its mood is filled (1..5). */
export function isRecordedDay(
  entry: Pick<DailyEntry, "mood"> | null | undefined
): boolean {
  return isRecordedMood(entry?.mood);
}

/**
 * Index entries by date, last-wins for duplicate dates.
 *
 * The input is treated as an unordered set of unique dates, so `deriveDashboard`
 * does not depend on the order a query happened to return. Invalid dates are
 * skipped: they cannot participate in calendar arithmetic.
 */
export function indexEntries(
  entries: readonly DailyEntry[]
): Map<string, DailyEntry> {
  const byDate = new Map<string, DailyEntry>();
  for (const entry of entries) {
    if (!entry.date || !isDateKey(entry.date)) continue;
    byDate.set(entry.date, entry);
  }
  return byDate;
}

/** Consecutive recorded days, counting back from today; missing today starts yesterday. */
export function currentStreak(
  today: string,
  recordedDates: ReadonlySet<string>
): number {
  if (!isDateKey(today)) return 0;
  let cursor = today;
  if (!recordedDates.has(today)) cursor = shiftDateKey(today, -1);

  let count = 0;
  while (recordedDates.has(cursor)) {
    count += 1;
    cursor = shiftDateKey(cursor, -1);
  }
  return count;
}

/** Longest run of consecutive recorded days anywhere in the loaded window. */
export function longestStreak(recordedDates: ReadonlySet<string>): number {
  const days = [...recordedDates].filter(isDateKey).sort();
  let best = 0;
  let run = 0;
  let previous: string | null = null;

  for (const day of days) {
    run = previous !== null && shiftDateKey(previous, 1) === day ? run + 1 : 1;
    if (run > best) best = run;
    previous = day;
  }
  return best;
}

/** The seven calendar days ending on `today`, oldest first. */
export function weekWindow(today: string): string[] {
  return Array.from({ length: DASHBOARD_WEEK_DAYS }, (_, index) =>
    shiftDateKey(today, index - (DASHBOARD_WEEK_DAYS - 1))
  );
}

/** The seven calendar days before `weekWindow`: `today - 13 .. today - 7`. */
export function previousWindow(today: string): string[] {
  const end = shiftDateKey(today, -DASHBOARD_WEEK_DAYS);
  return Array.from({ length: DASHBOARD_WEEK_DAYS }, (_, index) =>
    shiftDateKey(end, index - (DASHBOARD_WEEK_DAYS - 1))
  );
}

/** The whole 30-day window ending on `today`, oldest first. */
export function monthWindow(today: string): string[] {
  return Array.from({ length: DASHBOARD_LOAD_DAYS }, (_, index) =>
    shiftDateKey(today, index - (DASHBOARD_LOAD_DAYS - 1))
  );
}

/**
 * One `DashboardDay` per date, missing days included as mood-less placeholders
 * so both week windows always have exactly seven entries.
 */
export function daysFor(
  dates: readonly string[],
  byDate: ReadonlyMap<string, DailyEntry>
): DashboardDay[] {
  return dates.map((date) => {
    const entry = byDate.get(date);
    return {
      date,
      mood: entry && isRecordedMood(entry.mood) ? entry.mood : 0,
      hasEntry: entry !== undefined,
      screenTimeSeconds:
        typeof entry?.phone_screen_time === "number"
          ? entry.phone_screen_time
          : null,
      unlocks:
        typeof entry?.phone_unlocks === "number" ? entry.phone_unlocks : null,
    };
  });
}

/** Newest date (ISO sorts lexicographically) whose entry matches `predicate`. */
function latestWith(
  byDate: ReadonlyMap<string, DailyEntry>,
  predicate: (entry: DailyEntry) => boolean
): { date: string; entry: DailyEntry } | null {
  for (const date of [...byDate.keys()].sort().reverse()) {
    const entry = byDate.get(date);
    if (entry && predicate(entry)) return { date, entry };
  }
  return null;
}

/** Top apps of the latest day that captured any — an old snapshot beats none. */
export function latestTopApps(
  byDate: ReadonlyMap<string, DailyEntry>
): DashboardTopApps | null {
  const found = latestWith(
    byDate,
    (entry) => (entry.phone_top_apps?.length ?? 0) > 0
  );
  return found
    ? { date: found.date, apps: found.entry.phone_top_apps ?? [] }
    : null;
}

/**
 * Top apps of the newest day whose raw list is long enough to rank.
 *
 * A morning sync with two apps must not replace yesterday's complete snapshot;
 * the date travels with the list, so an older snapshot never pretends to be
 * today's.
 */
export function rankedTopApps(
  byDate: ReadonlyMap<string, DailyEntry>
): DashboardTopApps | null {
  const found = latestWith(
    byDate,
    (entry) => (entry.phone_top_apps?.length ?? 0) >= RANKED_TOP_APPS_MIN
  );
  return found
    ? { date: found.date, apps: found.entry.phone_top_apps ?? [] }
    : null;
}

/**
 * The apps actually worth ranking: drops sub-minute noise, sorts by seconds
 * descending and keeps the top `limit`. Ties are broken by name for a stable
 * output.
 */
export function rankTopApps(
  apps: readonly TopApp[],
  limit: number = TOP_APPS_LIMIT
): TopApp[] {
  return apps
    .filter(
      (app) =>
        app.app.trim().length > 0 &&
        Number.isFinite(app.time_sec) &&
        app.time_sec >= TOP_APPS_MIN_SECONDS
    )
    .slice()
    .sort((a, b) => b.time_sec - a.time_sec || a.app.localeCompare(b.app))
    .slice(0, Math.max(0, limit));
}

/** The reflection the dashboard shows: the newest day that has one. */
export function latestReflection(
  byDate: ReadonlyMap<string, DailyEntry>
): DashboardReflection | null {
  const found = latestWith(
    byDate,
    (entry) => (entry.ai_reflection ?? "").trim().length > 0
  );
  return found
    ? { date: found.date, text: found.entry.ai_reflection ?? "" }
    : null;
}

/** The newest *recorded* day (mood filled) — the reflection card's anchor. */
export function newestRecorded(
  byDate: ReadonlyMap<string, DailyEntry>
): DashboardNewestEntry | null {
  const found = latestWith(byDate, (entry) => isRecordedDay(entry));
  return found
    ? {
        date: found.date,
        entry: found.entry,
        reflection: reflectionOf(found.entry),
      }
    : null;
}

/** The newest logged day, whatever it holds. */
export function newestEntry(
  byDate: ReadonlyMap<string, DailyEntry>
): DashboardNewestEntry | null {
  const found = latestWith(byDate, () => true);
  return found
    ? {
        date: found.date,
        entry: found.entry,
        reflection: reflectionOf(found.entry),
      }
    : null;
}

/** The newest day with gratitude lines (the web dashboard's "last gratitude"). */
export function latestGratitude(
  byDate: ReadonlyMap<string, DailyEntry>
): DashboardGratitude | null {
  const found = latestWith(
    byDate,
    (entry) => filteredGratitude(entry).length > 0
  );
  return found
    ? {
        date: found.date,
        lines: filteredGratitude(found.entry),
        moodEmoji: found.entry.moodEmoji,
      }
    : null;
}

function reflectionOf(entry: DailyEntry): string | null {
  return entry.ai_reflection?.trim() ? entry.ai_reflection : null;
}

/** Blank gratitude slots are dropped before display, exactly like the check-in. */
export function filteredGratitude(entry: DailyEntry): string[] {
  return entry.gratitude.filter((line) => line.trim().length > 0);
}

/**
 * Derive the whole dashboard from one entry list.
 *
 * Pure: same rows in, same dashboard out — no clock, no network, no browser.
 * `today` is passed in so tests and screenshots can pin it.
 *
 * Only the `DASHBOARD_LOAD_DAYS` window ending today participates: even if a
 * caller hands over the account's full history, a 31-day-old top-app snapshot,
 * reflection or gratitude line cannot become today's card, and a longer run is
 * capped exactly as Android's 30-day read caps it. `onThisDay` is always null
 * here; the loader attaches the separately fetched lookback dates afterwards.
 */
export function deriveDashboard(
  today: string,
  entries: readonly DailyEntry[]
): DashboardData {
  const window = new Set(monthWindow(today));
  const byDate = indexEntries(
    entries.filter((entry) => window.has(entry.date))
  );

  // The owner's rule: a day is a record only when the mood is filled. The
  // phone sync writes a row for every day, so the streak and longest run key
  // off recorded days, not row presence.
  const recordedDates = new Set(
    [...byDate.entries()]
      .filter(([, entry]) => isRecordedDay(entry))
      .map(([date]) => date)
  );

  const week = daysFor(weekWindow(today), byDate);
  const previousWeek = daysFor(previousWindow(today), byDate);
  const month = daysFor(monthWindow(today), byDate);

  const moods = week
    .map((day) => day.mood)
    .filter((mood): mood is 1 | 2 | 3 | 4 | 5 => isRecordedMood(mood));
  const seconds = week
    .map((day) => day.screenTimeSeconds)
    .filter((value): value is number => value !== null);
  const unlockCounts = week
    .map((day) => day.unlocks)
    .filter((value): value is number => value !== null);

  return {
    today,
    todayEntry: byDate.get(today) ?? null,
    streak: currentStreak(today, recordedDates),
    longestStreak: longestStreak(recordedDates),
    week,
    previousWeek,
    month,
    averageMood:
      moods.length === 0
        ? null
        : moods.reduce((sum, mood) => sum + mood, 0) / moods.length,
    screenTimeMinutes:
      seconds.length === 0
        ? null
        : Math.round(seconds.reduce((sum, value) => sum + value, 0) / 60),
    unlocks:
      unlockCounts.length === 0
        ? null
        : unlockCounts.reduce((sum, value) => sum + value, 0),
    topApps: latestTopApps(byDate),
    rankedTopApps: rankedTopApps(byDate),
    reflection: latestReflection(byDate),
    newestRecorded: newestRecorded(byDate),
    newestEntry: newestEntry(byDate),
    lastGratitude: latestGratitude(byDate),
    entryByDate: Object.fromEntries(byDate),
    onThisDay: null,
  };
}

/**
 * Attach the memory lane to an already-derived dashboard.
 *
 * `memoryEntries` is the result of a separate exact-date read (1 year, 6
 * months, 1 month back). Only those target dates are accepted: anything else
 * is ignored even if a caller passes it, so an old day can never sneak into
 * `entryByDate` or the memory card. The memory is merged into `entryByDate`
 * for DayDetail, but the already-derived cards (reflection, top apps, newest)
 * stay untouched — they were computed from the 30-day core alone.
 */
export function attachOnThisDay(
  data: DashboardData,
  today: string,
  memoryEntries: readonly DailyEntry[]
): DashboardData {
  const targets = new Set(targetMemoryDates(today).map((item) => item.date));
  const memoryByDate = new Map<string, DailyEntry>();
  for (const entry of memoryEntries) {
    if (entry.date && targets.has(entry.date)) {
      memoryByDate.set(entry.date, entry);
    }
  }
  if (memoryByDate.size === 0) return data;

  const entryByDate = { ...data.entryByDate };
  for (const [date, entry] of memoryByDate) {
    entryByDate[date] = entry;
  }

  return {
    ...data,
    entryByDate,
    onThisDay: pickBestMemory(today, [...memoryByDate.values()]),
  };
}

/**
 * The top-apps list the card renders: the newest rankable day when one exists,
 * otherwise the newest snapshot of any size (the card then decides whether the
 * list is long enough to rank). Kept here so the UI does not invent its own
 * fallback rule.
 */
export function topAppsForCard(
  data: Pick<DashboardData, "topApps" | "rankedTopApps">
): DashboardTopApps | null {
  return data.rankedTopApps ?? data.topApps;
}

/** The week's screen time in minutes, or null when no day was ever synced. */
export function weekMinutes(days: readonly DashboardDay[]): number | null {
  const values = days
    .map((day) => day.screenTimeSeconds)
    .filter((value): value is number => value !== null);
  if (values.length === 0) return null;
  return Math.round(values.reduce((sum, value) => sum + value, 0) / 60);
}

/** The week's unlocks, or null when no day was ever synced. */
export function weekUnlocks(days: readonly DashboardDay[]): number | null {
  const values = days
    .map((day) => day.unlocks)
    .filter((value): value is number => value !== null);
  return values.length === 0
    ? null
    : values.reduce((sum, value) => sum + value, 0);
}

/** The week's average mood over recorded days; null when none was answered. */
export function weekMood(days: readonly DashboardDay[]): number | null {
  const moods = days
    .map((day) => day.mood)
    .filter((mood): mood is 1 | 2 | 3 | 4 | 5 => isRecordedMood(mood));
  return moods.length === 0
    ? null
    : moods.reduce((sum, mood) => sum + mood, 0) / moods.length;
}

/** One day's value for a metric, or null when that day carries no data. */
export function metricDayValue(
  day: DashboardDay,
  kind: DashboardMetricKind
): number | null {
  switch (kind) {
    case "screen_time":
      return day.screenTimeSeconds === null
        ? null
        : day.screenTimeSeconds / 60;
    case "unlocks":
      return day.unlocks;
    case "mood":
      return isRecordedMood(day.mood) ? day.mood : null;
  }
}

/** The week figure the hero card shows, as a number a trend can compare. */
export function metricWeekValue(
  days: readonly DashboardDay[],
  kind: DashboardMetricKind
): number | null {
  switch (kind) {
    case "screen_time":
      return weekMinutes(days);
    case "unlocks":
      return weekUnlocks(days);
    case "mood":
      return weekMood(days);
  }
}

/** Average over the days that actually carry the metric, never the empty ones. */
export function averagePerDay(
  days: readonly DashboardDay[],
  kind: DashboardMetricKind
): number | null {
  const values = days
    .map((day) => metricDayValue(day, kind))
    .filter((value): value is number => value !== null);
  return values.length === 0
    ? null
    : values.reduce((sum, value) => sum + value, 0) / values.length;
}

/**
 * Compares two counts. Null on either side is "unknown", never zero; a previous
 * total of zero carries no percentage, so the caption can say "new" instead of
 * inventing "+100 %".
 */
export function trendOf(
  current: number | null,
  previous: number | null
): MetricTrend {
  if (current === null || previous === null) {
    return {
      current,
      previous,
      direction: "unknown",
      percentChange: null,
    };
  }

  const delta = current - previous;
  const percent =
    previous === 0 ? null : Math.round((delta * 100) / previous);
  const direction: TrendDirection =
    delta === 0
      ? "flat"
      : percent !== null && Math.abs(percent) < TREND_FLAT_PERCENT
        ? "flat"
        : delta > 0
          ? "up"
          : "down";

  return { current, previous, direction, percentChange: percent };
}

/** Compares two averages, scaled to tenths so 3.8 -> 3.6 is a real move. */
export function trendOfAverage(
  current: number | null,
  previous: number | null
): MetricTrend {
  return trendOf(
    current === null ? null : Math.round(current * 10),
    previous === null ? null : Math.round(previous * 10)
  );
}

/** This week against the seven days before it, using the card's own aggregation. */
export function metricTrend(
  week: readonly DashboardDay[],
  previousWeek: readonly DashboardDay[],
  kind: DashboardMetricKind
): MetricTrend {
  switch (kind) {
    case "screen_time":
      return trendOf(weekMinutes(week), weekMinutes(previousWeek));
    case "unlocks":
      return trendOf(weekUnlocks(week), weekUnlocks(previousWeek));
    case "mood":
      return trendOfAverage(weekMood(week), weekMood(previousWeek));
  }
}
