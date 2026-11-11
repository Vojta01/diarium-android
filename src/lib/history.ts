/**
 * Pure calendar and day-detail logic for the History screen.
 *
 * The parts that are easy to get subtly wrong — the Monday-first month grid,
 * the leading/trailing blanks, leap February, December → January, "today"
 * comparisons against a supplied Prague date — live here so they can be unit
 * tested without a DOM or a network. The components only turn these results
 * into markup.
 *
 * Deliberate rules, matching the Android `ui/history/HistoryCalendar.kt` and
 * `ui/history/DayDetail.kt`:
 *
 * 1. A day counts as a record only when its mood is filled (1–5). A row the
 *    phone synced with screen time but no mood is still listed, but it is not
 *    a record and must not be painted as one.
 * 2. Future days are inert: they can never be opened.
 * 3. Missing numbers stay missing. `0` and `null` are not the same value, and
 *    neither is invented as a neutral answer.
 */
import { monthName, weekdayNames } from "./calendar-labels.ts";
import {
  dayDetailSections,
  dayForm,
  formatDashboardDate,
  safePhotoSrc,
  type DayDetailSections,
} from "./dashboard-ui.ts";
import { isRecordedMood, type Mood } from "./mood.ts";
import type { DailyEntry, TopApp } from "./stats.ts";

/** The grid is always seven cells wide, Monday first. */
export const HISTORY_DAYS_PER_WEEK = 7;

/**
 * Android's `moodColor()` palette for the calendar and day detail. Note that
 * 5 is cyan, not the green of the check-in chip palette.
 */
export const HISTORY_MOOD_COLORS: Record<number, string> = {
  1: "#ef4444",
  2: "#f97316",
  3: "#fbbf24",
  4: "#34d399",
  5: "#22d3ee",
};

/** A month in the local conventions of the web app: `monthIndex` is 0-based. */
export interface HistoryMonth {
  year: number;
  /** 0 = January … 11 = December, like `Date#getMonth`. */
  monthIndex: number;
}

/** Minimal shape of the i18n `t` function, so this module stays React-free. */
export type HistoryTranslate = (
  path: string,
  params?: Record<string, string | number>
) => string;

const pad2 = (value: number): string => String(value).padStart(2, "0");

function utcFirstOfMonth(year: number, monthIndex: number): Date {
  return new Date(Date.UTC(year, monthIndex, 1, 12));
}

/** How many days the month has — 28/29/30/31, leap year included. */
export function historyDaysInMonth(year: number, monthIndex: number): number {
  return new Date(Date.UTC(year, monthIndex + 1, 0, 12)).getUTCDate();
}

/**
 * Empty cells before day 1, Monday-based: Monday is 0 … Sunday is 6.
 *
 * This is the one number that decides whether the grid lines up with the
 * weekday headers; the old web calendar computed it with `getDay()` on a local
 * `Date`, which broke for browsers outside the app's time zone.
 */
export function historyLeadingBlanks(year: number, monthIndex: number): number {
  // getUTCDay(): Sunday 0 … Saturday 6 → Monday 0 … Sunday 6.
  return (utcFirstOfMonth(year, monthIndex).getUTCDay() + 6) % 7;
}

/**
 * Empty cells after the month's last day, so the final week is a full row.
 *
 * November 2026 ends with Monday the 30th alone; without this padding the last
 * CSS-grid row would have a single cell and visually stretch it or leave the
 * columns ragged.
 */
export function historyTrailingBlanks(year: number, monthIndex: number): number {
  const cells = historyLeadingBlanks(year, monthIndex) + historyDaysInMonth(year, monthIndex);
  return (HISTORY_DAYS_PER_WEEK - (cells % HISTORY_DAYS_PER_WEEK)) % HISTORY_DAYS_PER_WEEK;
}

/** The ISO key of one day, built from UTC parts so no local time zone can shift it. */
function historyIso(year: number, monthIndex: number, day: number): string {
  return `${year}-${pad2(monthIndex + 1)}-${pad2(day)}`;
}

/**
 * The month as whole week rows, every row exactly seven cells.
 *
 * `null` is an inert padding cell — a weekday header slot with no date behind
 * it. The grid iterates these rows, never a flat `chunked` array, so a partial
 * trailing week cannot stretch a lone day across the row.
 */
export function historyMonthWeeks(
  year: number,
  monthIndex: number
): (string | null)[][] {
  const days = historyDaysInMonth(year, monthIndex);
  const leading = historyLeadingBlanks(year, monthIndex);
  const trailing = historyTrailingBlanks(year, monthIndex);

  const cells: (string | null)[] = [];
  for (let index = 0; index < leading; index += 1) cells.push(null);
  for (let day = 1; day <= days; day += 1) {
    cells.push(historyIso(year, monthIndex, day));
  }
  for (let index = 0; index < trailing; index += 1) cells.push(null);

  const weeks: (string | null)[][] = [];
  for (let index = 0; index < cells.length; index += HISTORY_DAYS_PER_WEEK) {
    weeks.push(cells.slice(index, index + HISTORY_DAYS_PER_WEEK));
  }
  return weeks;
}

/** Steps whole months, rolling the year over in both directions. */
export function historyStepMonth(
  year: number,
  monthIndex: number,
  delta: number
): HistoryMonth {
  const moved = new Date(Date.UTC(year, monthIndex + delta, 1, 12));
  return { year: moved.getUTCFullYear(), monthIndex: moved.getUTCMonth() };
}

/** The `YYYY-MM` prefix of a month. */
export function historyMonthKey(year: number, monthIndex: number): string {
  return `${year}-${pad2(monthIndex + 1)}`;
}

/** Parse `YYYY-MM-DD` into a month, or null when the key is malformed. */
export function historyMonthOf(dateKey: string): HistoryMonth | null {
  const match = /^(\d{4})-(\d{2})-\d{2}$/.exec(dateKey);
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  if (month < 1 || month > 12) return null;
  return { year, monthIndex: month - 1 };
}

/** True for a day after the supplied Prague "today" key. */
export function historyIsFuture(dateKey: string, todayKey: string): boolean {
  return dateKey > todayKey;
}

/** Every day up to and including today reacts to a tap; future days are inert. */
export function historyIsSelectable(dateKey: string, todayKey: string): boolean {
  return !historyIsFuture(dateKey, todayKey);
}

/**
 * Inclusive bounds of the read for one month, or null when the whole month is
 * still in the future (nothing can exist there, so no request is made).
 *
 * The upper bound is today, never the month's end: a future-dated row cannot
 * exist, and asking for it would fetch days the calendar already draws inert.
 */
export function historyMonthRange(
  year: number,
  monthIndex: number,
  todayKey: string
): { from: string; to: string } | null {
  const from = historyIso(year, monthIndex, 1);
  const last = historyIso(year, monthIndex, historyDaysInMonth(year, monthIndex));
  if (from > todayKey) return null;
  return { from, to: last > todayKey ? todayKey : last };
}

/** "Listopad 2026" / "November 2026", matching the Android month header. */
export function historyMonthTitle(
  lang: "cs" | "en",
  year: number,
  monthIndex: number
): string {
  const safeMonth = Math.max(0, Math.min(11, monthIndex));
  const name = monthName(lang, safeMonth, year);
  return `${name.charAt(0).toLocaleUpperCase(lang === "cs" ? "cs-CZ" : "en-US")}${name.slice(1)} ${year}`;
}

/**
 * "7. listopadu 2026" / "November 7, 2026" — the detail header date.
 *
 * No weekday: Android's `longDateOf` uses `d. MMMM yyyy`, and the weekday is
 * already readable from the calendar above. Anchored at UTC noon so a DST
 * change cannot move it to another day.
 */
export function historyDetailDate(dateKey: string, lang: "cs" | "en"): string {
  const parsed = new Date(`${dateKey}T12:00:00Z`);
  if (Number.isNaN(parsed.getTime())) return dateKey;
  return new Intl.DateTimeFormat(lang === "cs" ? "cs-CZ" : "en-US", {
    day: "numeric",
    month: "long",
    year: "numeric",
    timeZone: "UTC",
  }).format(parsed);
}

/** Monday-first weekday captions: `Po`…`Ne` / `Mon`…`Sun`. */
export function historyWeekdayHeaders(lang: "cs" | "en"): string[] {
  return weekdayNames(lang).map(
    (name) =>
      `${name.charAt(0).toLocaleUpperCase(lang === "cs" ? "cs-CZ" : "en-US")}${name.slice(1)}`
  );
}

/** Index entries by date, last wins for duplicate dates. */
export function historyEntryMap(
  entries: readonly DailyEntry[]
): Map<string, DailyEntry> {
  const map = new Map<string, DailyEntry>();
  for (const entry of entries) {
    if (entry.date) map.set(entry.date, entry);
  }
  return map;
}

export interface HistoryMonthStats {
  /** Days whose mood is filled — the only days that count as records. */
  recorded: number;
  /** Rows the phone synced without a mood; listed, but not records. */
  syncedOnly: number;
  /** Whether the month has any row at all. */
  hasRows: boolean;
}

/** Counts for the month hint: records, synced-only rows, and rows in total. */
export function historyMonthStats(
  entries: readonly DailyEntry[],
  year: number,
  monthIndex: number
): HistoryMonthStats {
  const prefix = `${historyMonthKey(year, monthIndex)}-`;
  let recorded = 0;
  let rows = 0;
  for (const entry of entries) {
    if (!entry.date.startsWith(prefix)) continue;
    rows += 1;
    if (isRecordedMood(entry.mood)) recorded += 1;
  }
  return { recorded, syncedOnly: rows - recorded, hasRows: rows > 0 };
}

/**
 * The one cell that participates in the tab order.
 *
 * A grid of forty-plus buttons would otherwise force forty Tabs; the selected
 * day wins, then today, then the first day of the month that is not in the
 * future. Keyboard arrow keys move focus from there.
 */
export function historyRovingDate(input: {
  selected: string | null;
  year: number;
  monthIndex: number;
  todayKey: string;
  weeks: readonly (readonly (string | null)[])[];
}): string | null {
  const dates = input.weeks
    .flat()
    .filter((date): date is string => typeof date === "string");

  if (input.selected && dates.includes(input.selected)) return input.selected;
  if (dates.includes(input.todayKey)) return input.todayKey;
  return dates.find((date) => historyIsSelectable(date, input.todayKey)) ?? null;
}

/** `history.count_one` / `_few` / `_many`, using the shared plural rule. */
export function historyMonthCountKey(count: number, lang: "cs" | "en"): string {
  return `history.count_${dayForm(Math.max(0, count), lang)}`;
}

/** `history.synced_note_one` / `_few` / `_many`. */
export function historySyncedNoteKey(count: number, lang: "cs" | "en"): string {
  return `history.synced_note_${dayForm(Math.max(0, count), lang)}`;
}

/** One phone-usage block of the detail, kept only when something is present. */
export interface HistoryPhoneUsage {
  screenTimeSeconds: number | null;
  unlocks: number | null;
  topApps: TopApp[];
}

/** A normalized, render-ready reading of one day. */
export interface HistoryDetailView {
  recorded: boolean;
  mood: Mood | null;
  /** 0/null means the question was not answered, so it renders as "—". */
  sleepQuality: number | null;
  stress: number | null;
  weather: string[];
  activities: string[];
  habits: { key: string; done: boolean }[];
  gratitude: string[];
  note: string;
  scales: { id: string; value: number }[];
  phone: HistoryPhoneUsage | null;
  photoSrc: string | null;
  reflection: string | null;
  sections: DayDetailSections;
}

function positiveNumber(value: number | null | undefined): number | null {
  return typeof value === "number" && Number.isFinite(value) && value > 0
    ? value
    : null;
}

function nonEmpty(values: readonly string[]): string[] {
  return values.map((value) => value.trim()).filter((value) => value.length > 0);
}

/** The day detail, normalized without inventing values. */
export function historyDetail(entry: DailyEntry): HistoryDetailView {
  const sections = dayDetailSections(entry);
  const topApps = entry.phone_top_apps ?? [];
  const hasPhone =
    entry.phone_screen_time != null ||
    entry.phone_unlocks != null ||
    topApps.length > 0;

  return {
    recorded: sections.recorded,
    mood: isRecordedMood(entry.mood) ? entry.mood : null,
    sleepQuality: positiveNumber(entry.sleep_quality),
    stress: positiveNumber(entry.stress),
    weather: nonEmpty(entry.weather),
    activities: nonEmpty(entry.activities),
    habits: Object.entries(entry.habits).map(([key, done]) => ({ key, done })),
    gratitude: nonEmpty(entry.gratitude),
    note: entry.note,
    scales: Object.entries(entry.scale_values)
      .filter(([, value]) => typeof value === "number" && Number.isFinite(value) && value > 0)
      .map(([id, value]) => ({ id, value })),
    phone: hasPhone
      ? {
          // `??` preserves a real 0-second/0-unlock reading; only null means
          // the phone never reported it.
          screenTimeSeconds: entry.phone_screen_time ?? null,
          unlocks: entry.phone_unlocks ?? null,
          topApps,
        }
      : null,
    photoSrc: safePhotoSrc(entry.photo_path),
    reflection:
      typeof entry.ai_reflection === "string" && entry.ai_reflection.trim()
        ? entry.ai_reflection
        : null,
    sections,
  };
}

/** Habit/scale ids that have no definition still read as words, not raw slugs. */
export function historyHumanizeKey(key: string): string {
  return key
    .replace(/[_-]+/g, " ")
    .replace(/^\p{Ll}/u, (letter) => letter.toUpperCase());
}

/**
 * The accessible name of one calendar cell.
 *
 * The visible cell is a number plus an emoji; a screen reader needs the full
 * date, the recorded value and its label, and whether the day carries a photo
 * or scale values. Nothing is announced for a padding cell (those are hidden).
 */
export function historyDayAriaLabel(input: {
  date: string;
  entry: Pick<DailyEntry, "mood" | "photo_path" | "scale_values"> | null;
  lang: "cs" | "en";
  t: HistoryTranslate;
  todayKey: string;
}): string {
  const dateLabel = formatDashboardDate(input.date, input.lang, "long");

  if (historyIsFuture(input.date, input.todayKey)) {
    return input.t("history.aria_future", { date: dateLabel });
  }

  const entry = input.entry;
  let base: string;
  if (!entry) {
    base = input.t("history.aria_empty", { date: dateLabel });
  } else if (isRecordedMood(entry.mood)) {
    base = input.t("history.aria_mood", {
      date: dateLabel,
      value: entry.mood,
      mood: input.t(`mood.mood_${entry.mood}`),
    });
  } else {
    base = input.t("history.aria_synced", { date: dateLabel });
  }

  const hasPhoto = Boolean(safePhotoSrc(entry?.photo_path));
  const hasScales = Object.values(entry?.scale_values ?? {}).some(
    (value) => typeof value === "number" && Number.isFinite(value) && value > 0
  );

  return [
    base,
    hasPhoto ? input.t("history.aria_photo") : null,
    hasScales ? input.t("history.aria_scales") : null,
  ]
    .filter((part): part is string => Boolean(part))
    .join("");
}
