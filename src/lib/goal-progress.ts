import { shiftDateKey, shiftMonthKey } from "./date.ts";

/** Normalized goal period. Anything unknown in the database behaves as weekly. */
export type GoalFrequency = "daily" | "weekly" | "monthly";

/** The part of a goal row the pure progress maths needs. */
export interface GoalDefinition {
  id: string;
  activity_key: string;
  name: string;
  target_count: number;
  frequency: string;
  is_active: boolean;
  created_at?: string;
}

/** The part of an entry row the pure progress maths needs. */
export interface ActivityEntry {
  date: string;
  activities?: readonly string[] | null;
  /**
   * `entries.habits` key→Boolean. Android also tolerates the column arriving as
   * a JSON-encoded string, so the web parser accepts both shapes.
   */
  habits?: Record<string, boolean> | string | null;
}

/** One day's logged values, unioned across that day's duplicate entries. */
export interface DayActivities {
  date: string;
  /** Raw `entries.activities` values (historically labels such as `Chůze`). */
  keys: string[];
  /** `entries.habits` keys whose value is `true`. */
  habitKeys: string[];
}

/** One judged period of a goal's history. */
export interface GoalPeriodResult {
  /** Whole calendar start (inclusive). */
  start: string;
  /** Whole calendar end (inclusive); the running period still reports its real end. */
  end: string;
  /** Logged days inside the scored window, oldest first. */
  dates: string[];
  /** `null` = no entries rows in the window at all ("no data", never a miss). */
  met: boolean | null;
  /** Days in the window that logged the activity. */
  count: number;
}

/** A goal plus its current period's progress and recent calendar history. */
export interface GoalProgressData<G extends GoalDefinition = GoalDefinition> {
  goal: G;
  frequency: GoalFrequency;
  /** Days in the goal's period that logged the activity — one per day. */
  count: number;
  /** Consecutive days up to the newest logged day; 0 when none. */
  streak: number;
  /** Recent periods, OLDEST first, the current period LAST. */
  periods: GoalPeriodResult[];
  targetMet: boolean;
  remaining: number;
  /** 0..1, clamped, so a progress bar can never overflow its track. */
  fraction: number;
  /** The period right before the current one; null when unknown/unavailable. */
  previousMet: boolean | null;
  /** `periods` reduced to met/missed/no-data flags, oldest first. */
  history: (boolean | null)[];
  metPeriods: number;
  knownPeriods: number;
}

/** A year of history is read so a long daily streak survives. */
export const GOAL_STREAK_LOOKBACK_DAYS = 365;

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

function isDateKey(value: string): boolean {
  if (!ISO_DATE.test(value)) return false;
  const parsed = new Date(`${value}T12:00:00Z`);
  return (
    !Number.isNaN(parsed.getTime()) &&
    parsed.toISOString().slice(0, 10) === value
  );
}

function pad(value: number, width: number): string {
  return String(value).padStart(width, "0");
}

/**
 * The one normalisation every activity comparison runs through: a stored LABEL
 * (`Chůze`, `Trénink`, `CTENI`) and a goal's slug KEY (`chuze`, `trenink`,
 * `cteni`) both land on the same string, so counting can no longer miss.
 *
 * NFD-decompose, drop combining marks, lower-case, every run of non-`[a-z0-9]`
 * becomes `_`, trim `_`. Values that are already slugs are unchanged.
 *
 * The Android port falls back to Java's `String.hashCode()` for punctuation-only
 * values. That number is not reproducible in JavaScript, so this port uses a
 * stable FNV-1a hash instead. The fallback only matters for a value with no
 * alphanumeric character at all; two such values must still not collapse.
 */
export function normalizeActivityValue(value: string): string {
  const ascii = value.normalize("NFD").replace(/\p{Mn}+/gu, "");
  const slug = ascii
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");
  if (slug) return slug;
  return `vlastni_${fnv1a(value)}`;
}

/** Stable 32-bit FNV-1a, base 36 — only used for punctuation-only fallbacks. */
function fnv1a(value: string): string {
  let hash = 0x811c9dc5;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash.toString(36);
}

/** Anything the database holds that is not daily/monthly behaves as weekly. */
export function normalizeFrequency(raw: string): GoalFrequency {
  switch (raw.trim().toLowerCase()) {
    case "daily":
    case "den":
    case "denne":
    case "denně":
      return "daily";
    case "monthly":
    case "mesic":
    case "měsíc":
    case "mesicne":
    case "měsíčně":
      return "monthly";
    default:
      return "weekly";
  }
}

/** Monday of the ISO week containing `iso` (Czech weeks run Monday–Sunday). */
export function weekStart(iso: string): string {
  const parsed = new Date(`${iso}T12:00:00Z`);
  if (Number.isNaN(parsed.getTime())) return iso;
  const dayOfWeek = parsed.getUTCDay(); // 0 = Sunday
  const daysBack = dayOfWeek === 0 ? 6 : dayOfWeek - 1;
  return shiftDateKey(iso, -daysBack);
}

/** First day of `iso`'s calendar month. */
export function firstOfMonth(iso: string): string {
  if (!isDateKey(iso)) return iso;
  return `${iso.slice(0, 7)}-01`;
}

/** Last day of `iso`'s calendar month, leap-year aware. */
export function lastDayOfMonth(iso: string): string {
  if (!isDateKey(iso)) return iso;
  const year = Number(iso.slice(0, 4));
  const month = Number(iso.slice(5, 7));
  const nextFirst =
    month === 12
      ? `${pad(year + 1, 4)}-01-01`
      : `${pad(year, 4)}-${pad(month + 1, 2)}-01`;
  return shiftDateKey(nextFirst, -1);
}

/**
 * Start of the goal's current period, inclusive:
 * daily -> today, weekly -> this week's Monday (Mon–Sun), monthly -> the 1st.
 */
export function periodStart(today: string, frequency: string): string {
  switch (normalizeFrequency(frequency)) {
    case "daily":
      return today;
    case "monthly":
      return firstOfMonth(today);
    case "weekly":
      return weekStart(today);
  }
}

/**
 * End date of the period `periodsBack` whole calendar periods back.
 *
 * Daily -> that day; weekly -> that week's Sunday (the running week clamps to
 * today); monthly -> that month's last day (the running month clamps to today).
 */
export function periodEndBack(
  today: string,
  frequency: string,
  periodsBack: number
): string {
  switch (normalizeFrequency(frequency)) {
    case "daily":
      return shiftDateKey(today, -periodsBack);
    case "monthly":
      return periodsBack <= 0
        ? today
        : lastDayOfMonth(shiftMonthKey(today, -periodsBack));
    case "weekly":
      return periodsBack <= 0
        ? today
        : shiftDateKey(weekStart(today), -(7 * periodsBack - 6));
  }
}

/** The whole calendar end of the period `today` falls in. */
export function periodFullEnd(today: string, frequency: string): string {
  switch (normalizeFrequency(frequency)) {
    case "daily":
      return today;
    case "monthly":
      return lastDayOfMonth(today);
    case "weekly":
      return shiftDateKey(weekStart(today), 6);
  }
}

/** How many periods a card shows in history: 14 days, 8 weeks or 6 months. */
export function historyPeriods(frequency: string): number {
  switch (normalizeFrequency(frequency)) {
    case "daily":
      return 14;
    case "monthly":
      return 6;
    case "weekly":
      return 8;
  }
}

/**
 * `habits` as a boolean map. A JSON-encoded string is parsed first, exactly
 * like Android's `habitsObject`; malformed values count as no habits.
 */
function habitEntries(
  value: ActivityEntry["habits"]
): [string, boolean][] {
  let parsed: unknown = value;
  if (typeof parsed === "string") {
    try {
      parsed = JSON.parse(parsed);
    } catch {
      return [];
    }
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return [];
  return Object.entries(parsed as Record<string, unknown>).filter(
    (entry): entry is [string, boolean] => typeof entry[1] === "boolean"
  );
}

/**
 * Merge `entries` rows into one `DayActivities` per date.
 *
 * A day with several rows (duplicates, or the check-in plus a sync row) is
 * unioned, never counted twice. Only habit keys with a literal `true` count.
 */
export function dayActivitiesFromEntries(
  entries: readonly ActivityEntry[]
): Map<string, DayActivities> {
  const byDate = new Map<
    string,
    { keys: Set<string>; habitKeys: Set<string> }
  >();

  for (const entry of entries) {
    if (!entry.date || !isDateKey(entry.date)) continue;
    let day = byDate.get(entry.date);
    if (!day) {
      day = { keys: new Set(), habitKeys: new Set() };
      byDate.set(entry.date, day);
    }

    for (const key of entry.activities ?? []) {
      if (typeof key === "string" && key.trim()) day.keys.add(key);
    }
    for (const [key, fulfilled] of habitEntries(entry.habits)) {
      if (key.trim() && fulfilled === true) day.habitKeys.add(key);
    }
  }

  return new Map(
    [...byDate.entries()].map(([date, day]) => [
      date,
      {
        date,
        keys: [...day.keys],
        habitKeys: [...day.habitKeys],
      },
    ])
  );
}

/**
 * Days in `[from, to]` that logged `activityKey`, distinct and descending.
 * A day counts when any activity value OR a fulfilled habit key normalises to
 * the goal key; matching both still contributes a single date.
 */
export function activityDates(
  days: ReadonlyMap<string, DayActivities>,
  activityKey: string,
  from: string,
  to: string
): string[] {
  const target = normalizeActivityValue(activityKey);
  const dates: string[] = [];

  for (const day of days.values()) {
    if (day.date < from || day.date > to) continue;
    const matches =
      day.keys.some((key) => normalizeActivityValue(key) === target) ||
      day.habitKeys.some((key) => normalizeActivityValue(key) === target);
    if (matches) dates.push(day.date);
  }

  return dates.sort().reverse();
}

/** Days in the goal's period that logged `activityKey` — the goal's count. */
export function countActivityInPeriod(
  days: ReadonlyMap<string, DayActivities>,
  activityKey: string,
  from: string,
  to: string
): number {
  return activityDates(days, activityKey, from, to).length;
}

/** `count >= target` — a goal hit exactly on target is met. */
export function isTargetMet(count: number, targetCount: number): boolean {
  return count >= targetCount;
}

/**
 * The goal's recent periods, OLDEST first, the current period LAST.
 * Each period is judged over exactly the same window `countActivityInPeriod`
 * uses, moved back k whole periods. A window with no `entries` rows at all
 * carries `met: null` ("no data"), never a miss.
 */
export function computeGoalHistory(
  days: ReadonlyMap<string, DayActivities>,
  goal: GoalDefinition,
  today: string
): GoalPeriodResult[] {
  const frequency = normalizeFrequency(goal.frequency);
  const target = Math.max(1, goal.target_count);
  const results: GoalPeriodResult[] = [];

  for (let back = historyPeriods(frequency) - 1; back >= 0; back -= 1) {
    const end = periodEndBack(today, frequency, back);
    const from = periodStart(end, frequency);
    const fullEnd = back === 0 ? periodFullEnd(today, frequency) : end;

    let hasAnyEntry = false;
    for (const date of days.keys()) {
      if (date >= from && date <= end) {
        hasAnyEntry = true;
        break;
      }
    }

    if (!hasAnyEntry) {
      results.push({
        start: from,
        end: fullEnd,
        dates: [],
        met: null,
        count: 0,
      });
      continue;
    }

    const logged = activityDates(days, goal.activity_key, from, end).sort();
    results.push({
      start: from,
      end: fullEnd,
      dates: logged,
      met: isTargetMet(logged.length, target),
      count: logged.length,
    });
  }

  return results;
}

/**
 * The web's streak algorithm: newest-first dates, starts at 1 for the newest
 * logged day, +1 for every day whose gap to the previous one is exactly one,
 * and stops at the first gap. Input is de-duplicated and sorted here.
 */
export function streakOf(datesDescending: readonly string[]): number {
  const days = [...new Set(datesDescending)]
    .filter(isDateKey)
    .sort()
    .reverse();
  if (days.length === 0) return 0;

  let streak = 1;
  for (let index = 1; index < days.length; index += 1) {
    if (daysBetween(days[index], days[index - 1]) === 1) streak += 1;
    else break;
  }
  return streak;
}

/** Whole days from `from` to `to`; null when either string is not a date. */
export function daysBetween(from: string, to: string): number | null {
  if (!isDateKey(from) || !isDateKey(to)) return null;
  const fromTime = new Date(`${from}T12:00:00Z`).getTime();
  const toTime = new Date(`${to}T12:00:00Z`).getTime();
  return Math.round((toTime - fromTime) / 86_400_000);
}

/** The pure progress for one goal. */
export function computeGoalProgress<G extends GoalDefinition>(
  goal: G,
  entries: readonly ActivityEntry[],
  today: string
): GoalProgressData<G> {
  const frequency = normalizeFrequency(goal.frequency);
  const target = Math.max(1, goal.target_count);
  const days = dayActivitiesFromEntries(entries);

  const from = periodStart(today, frequency);
  const count = countActivityInPeriod(days, goal.activity_key, from, today);
  const streak = streakOf(
    activityDates(
      days,
      goal.activity_key,
      shiftDateKey(today, -GOAL_STREAK_LOOKBACK_DAYS),
      today
    )
  );
  const periods = computeGoalHistory(days, goal, today);
  const previous = periods.length >= 2 ? periods[periods.length - 2] : null;

  return {
    goal,
    frequency,
    count,
    streak,
    periods,
    targetMet: isTargetMet(count, target),
    remaining: Math.max(0, target - count),
    fraction: Math.max(0, Math.min(1, count / target)),
    previousMet: previous ? previous.met : null,
    history: periods.map((period) => period.met),
    metPeriods: periods.filter((period) => period.met === true).length,
    knownPeriods: periods.filter((period) => period.met !== null).length,
  };
}

/** Progress for every active goal, preserving the caller's order. */
export function computeGoalProgressList<G extends GoalDefinition>(
  goals: readonly G[],
  entries: readonly ActivityEntry[],
  today: string
): GoalProgressData<G>[] {
  return goals
    .filter((goal) => goal.is_active)
    .map((goal) => computeGoalProgress(goal, entries, today));
}
