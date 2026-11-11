/**
 * Pure UI logic for the dashboard: what to show, in which order, at which span,
 * and how each card decides between its states.
 *
 * Kept out of the React components so every rule that can be reasoned about
 * without a DOM is unit-tested in `tests/dashboard-ui.test.ts`. The components
 * only turn these results into markup.
 */
import {
  DASHBOARD_CARD_ORDER,
  RANKED_TOP_APPS_MIN,
  TOP_APPS_LIMIT,
  filteredGratitude,
  isRecordedDay,
  metricDayValue,
  rankTopApps,
  topAppsForCard,
  type DashboardCardId,
  type DashboardData,
  type DashboardDay,
  type DashboardMetricKind,
  type DashboardTopApps,
} from "./dashboard.ts";
import type { DashboardDataBundle } from "./dashboard-data.ts";
import { EM_DASH } from "./dashboard-format.ts";
import { normalizeActivityValue, type GoalDefinition, type GoalProgressData } from "./goal-progress.ts";
import { isRecordedMood } from "./mood.ts";
import type { DailyEntry, TopApp } from "./stats.ts";

/** The two windows every usage and mood chart can switch between. */
export const CHART_RANGES = ["week", "month"] as const;
export type ChartRange = (typeof CHART_RANGES)[number];

/** How many days a chart range covers. */
export function chartRangeDays(range: ChartRange): number {
  return range === "week" ? 7 : 30;
}

/** The days a chart draws: the last seven of the month window, or all thirty. */
export function chartDays(
  month: readonly DashboardDay[],
  range: ChartRange
): DashboardDay[] {
  return range === "week" ? month.slice(-7) : [...month];
}

/** The subset of a dashboard load the card-visibility rules need. */
export interface DashboardCardSources {
  data: DashboardData;
  goals: readonly GoalProgressData[] | null;
  goalsError: string | null;
}

export interface DashboardCardOptions {
  /**
   * B1 deliberately ships no Entries card (its sub-screens land in B2). The
   * flag keeps the order model complete without rendering a dead link.
   */
  entries?: boolean;
}

/**
 * Whether a card has something honest to render.
 *
 * `goals` is true for both a real list and a failed read: the card itself
 * distinguishes empty from error. A hidden card is reserved for data that
 * genuinely does not exist (no memory, no app snapshot, no reflection at all).
 */
export function isDashboardCardVisible(
  id: DashboardCardId,
  sources: DashboardCardSources,
  options: DashboardCardOptions = {}
): boolean {
  switch (id) {
    case "hero_stats":
    case "streak":
    case "week":
    case "mood_chart":
    case "screen_time":
    case "unlocks":
      return true;
    case "on_this_day":
      return sources.data.onThisDay !== null;
    case "goals":
      return sources.goals !== null || sources.goalsError !== null;
    case "top_apps":
      return topAppsForCard(sources.data) !== null;
    case "reflection":
      return (
        sources.data.newestRecorded !== null ||
        sources.data.reflection !== null
      );
    case "entries":
      return options.entries === true;
  }
}

/** The card ids to render, in Android beta5 order, conditional cards omitted. */
export function visibleDashboardCards(
  sources: DashboardCardSources,
  options: DashboardCardOptions = {}
): DashboardCardId[] {
  return DASHBOARD_CARD_ORDER.filter((id) =>
    isDashboardCardVisible(id, sources, options)
  );
}

/** Cards that always take the full two-column width. */
const FULL_WIDTH_CARDS: ReadonlySet<DashboardCardId> = new Set([
  "hero_stats",
  "mood_chart",
  "entries",
]);

/**
 * The pair a card belongs to. When one half is absent the other expands to the
 * full width, so a missing OnThisDay never leaves a ragged empty column.
 */
const CARD_PARTNER: Partial<Record<DashboardCardId, DashboardCardId>> = {
  streak: "week",
  week: "streak",
  on_this_day: "goals",
  goals: "on_this_day",
  screen_time: "unlocks",
  unlocks: "screen_time",
  top_apps: "reflection",
  reflection: "top_apps",
};

/** Desktop grid span for a card: 2 = full width, 1 = half of a pair. */
export function dashboardCardSpan(
  id: DashboardCardId,
  visible: readonly DashboardCardId[]
): 1 | 2 {
  if (FULL_WIDTH_CARDS.has(id)) return 2;
  const partner = CARD_PARTNER[id];
  if (partner && !visible.includes(partner)) return 2;
  return 1;
}

/**
 * The overview grid: one column below 1024px, two only once the shell widens
 * at `lg`. The shell is capped at 620px below `lg`, so splitting it earlier
 * would squeeze every card into a phone-width half column.
 */
export const DASHBOARD_GRID_CLASS =
  "grid min-w-0 grid-cols-1 gap-4 lg:grid-cols-2";

/** A full-width card's span, aligned with `DASHBOARD_GRID_CLASS`. */
export const DASHBOARD_FULL_SPAN_CLASS = "lg:col-span-2";

/** How many gratitude lines the On This Day card carries, like Android. */
export const MEMORY_GRATITUDE_LIMIT = 3;

/**
 * The gratitude lines the memory card shows: trimmed, blanks dropped, capped
 * at three. A memory may qualify through gratitude alone, so an empty result
 * is meaningful — the card must not fall back to a "no note" placeholder.
 */
export function memoryGratitudeLines(
  entry: Pick<DailyEntry, "gratitude">
): string[] {
  return entry.gratitude
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .slice(0, MEMORY_GRATITUDE_LIMIT);
}

/**
 * A photo URL the browser may load: an absolute http(s) URL or an inline image
 * data URL. Everything else (javascript:, file:, blank) is treated as missing.
 * This is the same shape the check-in writes to `photo_path`.
 */
export function safePhotoSrc(
  value: string | null | undefined
): string | null {
  if (typeof value !== "string") return null;
  const src = value.trim();
  if (!src) return null;
  if (
    src.startsWith("https://") ||
    src.startsWith("http://") ||
    src.startsWith("data:image/")
  ) {
    return src;
  }
  return null;
}

/** The memory's displayable photo, or null when it has none/is unsafe. */
export function memoryPhotoSrc(
  entry: Pick<DailyEntry, "photo_path">
): string | null {
  return safePhotoSrc(entry.photo_path);
}

/** Goals shown on the overview; the rest live in the Goals screen. */
export const GOAL_CARD_LIMIT = 4;

/**
 * The goals the card lists: unmet first, stable within each group, capped.
 * A nudge, not a trophy case.
 */
export function goalCardRows<G extends GoalDefinition>(
  goals: readonly GoalProgressData<G>[],
  limit: number = GOAL_CARD_LIMIT
): GoalProgressData<G>[] {
  return [...goals]
    .sort((left, right) => Number(left.targetMet) - Number(right.targetMet))
    .slice(0, Math.max(0, limit));
}

/** Sum of one metric over the days that actually carry it; null when none do. */
export function sumMetric(
  days: readonly DashboardDay[],
  kind: DashboardMetricKind
): number | null {
  const values = days
    .map((day) => metricDayValue(day, kind))
    .filter((value): value is number => value !== null);
  if (values.length === 0) return null;
  return values.reduce((sum, value) => sum + value, 0);
}

/** One value per day, null where the day carries no reading. */
export function metricSeries(
  days: readonly DashboardDay[],
  kind: DashboardMetricKind
): (number | null)[] {
  return days.map((day) => metricDayValue(day, kind));
}

export type TopAppsCardState =
  | { kind: "hidden" }
  | { kind: "not_enough"; date: string; apps: TopApp[] }
  | { kind: "ranked"; date: string; apps: TopApp[] };

/**
 * The top-apps card's state, using the data layer's own fallback rule: prefer
 * the newest rankable snapshot, else the newest snapshot of any size — and let
 * the card say "not enough data" when even that cannot fill a ranking.
 */
export function topAppsCardState(
  data: Pick<DashboardData, "topApps" | "rankedTopApps">
): TopAppsCardState {
  const source: DashboardTopApps | null = topAppsForCard(data);
  if (!source) return { kind: "hidden" };
  const apps = rankTopApps(source.apps, TOP_APPS_LIMIT);
  if (apps.length < RANKED_TOP_APPS_MIN) {
    return { kind: "not_enough", date: source.date, apps };
  }
  return { kind: "ranked", date: source.date, apps };
}

export interface ReflectionView {
  /** The text currently on screen, or null when nothing exists yet. */
  text: string | null;
  /** The day that text belongs to. */
  date: string | null;
  /** The newest recorded day still waiting for a reflection, or null. */
  missingDate: string | null;
  /** True exactly when the generate action is valid. */
  canGenerate: boolean;
}

/**
 * The reflection card's content rules (ported from Android):
 * 1. The card speaks about the newest recorded day (mood filled) first.
 * 2. When that day has no text yet, the newest existing reflection stays
 *    visible with its own date; the generate action belongs to the anchor.
 */
export function reflectionView(
  data: Pick<DashboardData, "newestRecorded" | "newestEntry" | "reflection">
): ReflectionView | null {
  const anchor = data.newestRecorded ?? data.newestEntry;
  const latest = data.reflection;
  if (!anchor && !latest) return null;

  const anchorText = anchor?.reflection?.trim() ? anchor.reflection : null;
  const text = anchorText ?? latest?.text ?? null;
  const date = anchorText ? (anchor?.date ?? null) : (latest?.date ?? null);
  const missingDate = anchor && !anchorText ? anchor.date : null;

  return { text, date, missingDate, canGenerate: missingDate !== null };
}

/** Czech needs three day forms (1 den, 2–4 dny, 5+ dní); English needs two. */
export type DayForm = "one" | "few" | "many";

export function dayForm(count: number, lang: "cs" | "en"): DayForm {
  if (count === 1) return "one";
  if (lang === "cs" && count >= 2 && count <= 4) return "few";
  return "many";
}

/** The i18n suffix for a goal period caption. */
export function goalPeriodKey(frequency: string): string {
  switch (frequency) {
    case "daily":
      return "period_daily";
    case "monthly":
      return "period_monthly";
    default:
      return "period_weekly";
  }
}

/**
 * Trailing moving average over a slot series: each slot is the mean of itself
 * and the `window - 1` before it, nulls skipped rather than averaged in. A slot
 * whose window holds nothing is null. Ported from Android `StatsMath`.
 */
export function movingAverageOf(
  values: readonly (number | null)[],
  window = 7
): (number | null)[] {
  const size = Math.max(1, window);
  return values.map((_, index) => {
    const from = Math.max(0, index - size + 1);
    const slice = values
      .slice(from, index + 1)
      .filter((value): value is number => value !== null);
    if (slice.length === 0) return null;
    return slice.reduce((sum, value) => sum + value, 0) / slice.length;
  });
}

/** The mood chart's 7-day moving average, aligned to the visible days. */
export function moodMovingAverage(
  days: readonly DashboardDay[],
  window = 7
): (number | null)[] {
  return movingAverageOf(
    days.map((day) => (isRecordedMood(day.mood) ? day.mood : null)),
    window
  );
}

/** The last non-null point of a moving average — the line's current height. */
export function latestAverage(
  values: readonly (number | null)[]
): number | null {
  for (let index = values.length - 1; index >= 0; index -= 1) {
    if (values[index] !== null) return values[index];
  }
  return null;
}

/** Highest and lowest day of a window, earliest wins a tie; null when empty. */
export function metricBestWorst(
  days: readonly DashboardDay[],
  kind: DashboardMetricKind
): {
  best: { day: DashboardDay; value: number } | null;
  worst: { day: DashboardDay; value: number } | null;
} {
  let best: { day: DashboardDay; value: number } | null = null;
  let worst: { day: DashboardDay; value: number } | null = null;

  for (const day of days) {
    const value = metricDayValue(day, kind);
    if (value === null) continue;
    if (!best || value > best.value) best = { day, value };
    if (!worst || value < worst.value) worst = { day, value };
  }
  return { best, worst };
}

const LOCALES = { cs: "cs-CZ", en: "en-US" } as const;

export type DashboardDateStyle = "long" | "numeric" | "dayMonth" | "weekday";

function utcNoon(date: string): Date | null {
  const parsed = new Date(`${date}T12:00:00Z`);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

/**
 * A dashboard date in the UI language, always from UTC noon so a DST change
 * cannot move it. An unparseable value is returned verbatim (legacy rows).
 */
export function formatDashboardDate(
  date: string,
  lang: "cs" | "en",
  style: DashboardDateStyle = "numeric"
): string {
  const parsed = utcNoon(date);
  if (!parsed) return date;

  const options: Intl.DateTimeFormatOptions =
    style === "long"
      ? { weekday: "long", day: "numeric", month: "long", year: "numeric" }
      : style === "numeric"
        ? { day: "numeric", month: "short", year: "numeric" }
        : style === "dayMonth"
          ? { day: "numeric", month: "short" }
          : { weekday: "short" };

  return new Intl.DateTimeFormat(LOCALES[lang], {
    ...options,
    timeZone: "UTC",
  }).format(parsed);
}

/** Sunday-first index, matching the `dashboard.day_labels` keys. */
export function weekdayIndex(date: string): number | null {
  const parsed = utcNoon(date);
  return parsed ? parsed.getUTCDay() : null;
}

const DAY_KEYS = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"] as const;

/** `dashboard.day_labels.*` key for a date, or null for an invalid one. */
export function dayLabelKey(date: string): string | null {
  const index = weekdayIndex(date);
  return index === null ? null : `dashboard.day_labels.${DAY_KEYS[index]}`;
}

/** The eight weather keys the Android picker writes. */
export const WEATHER_KEYS = [
  "slunecno",
  "zatazeno",
  "dest",
  "snih",
  "mraz",
  "horko",
  "bourka",
  "vitr",
] as const;

/** English display labels mapped back to the picker's stored key. */
const WEATHER_ALIASES: Readonly<Record<string, (typeof WEATHER_KEYS)[number]>> = {
  sunny: "slunecno",
  cloudy: "zatazeno",
  rain: "dest",
  rainy: "dest",
  snow: "snih",
  snowy: "snih",
  frost: "mraz",
  hot: "horko",
  heat: "horko",
  storm: "bourka",
  stormy: "bourka",
  windy: "vitr",
  wind: "vitr",
};

/**
 * The catalogue key for a stored weather value: the picker's key, its Czech
 * label or its English label all normalise to the same slug. Unknown values
 * stay null so the caller can print them verbatim instead of dropping them.
 */
export function weatherKey(raw: string): string | null {
  const key = normalizeActivityValue(raw);
  if ((WEATHER_KEYS as readonly string[]).includes(key)) return key;
  return WEATHER_ALIASES[key] ?? null;
}

/** Which sections a day's record actually has — empty ones are left out. */
export interface DayDetailSections {
  recorded: boolean;
  sleep: boolean;
  stress: boolean;
  weather: boolean;
  activities: boolean;
  habits: boolean;
  gratitude: boolean;
  note: boolean;
  scales: boolean;
  phone: boolean;
  photo: boolean;
  reflection: boolean;
}

export function dayDetailSections(entry: DailyEntry): DayDetailSections {
  const positiveScales = Object.values(entry.scale_values).filter(
    (value) => typeof value === "number" && value > 0
  );
  return {
    recorded: isRecordedDay(entry),
    sleep:
      typeof entry.sleep_quality === "number" && entry.sleep_quality > 0,
    stress: typeof entry.stress === "number" && entry.stress > 0,
    weather: entry.weather.some((value) => value.trim().length > 0),
    activities: entry.activities.some((value) => value.trim().length > 0),
    habits: Object.keys(entry.habits).length > 0,
    gratitude: filteredGratitude(entry).length > 0,
    note: entry.note.trim().length > 0,
    scales: positiveScales.length > 0,
    phone:
      entry.phone_screen_time !== null ||
      entry.phone_unlocks !== null ||
      (entry.phone_top_apps?.length ?? 0) > 0,
    photo: typeof entry.photo_path === "string" && entry.photo_path.trim() !== "",
    reflection:
      typeof entry.ai_reflection === "string" &&
      entry.ai_reflection.trim().length > 0,
  };
}

/** `3 / 5`, using the scale's real maximum and falling back to 5. */
export function scaleValueText(
  value: number,
  maxValue: number | null | undefined
): string {
  const max =
    typeof maxValue === "number" && Number.isFinite(maxValue) && maxValue > 0
      ? maxValue
      : 5;
  return `${value} / ${max}`;
}

/** The dash every empty metric renders; re-exported so components share one. */
export const DASHBOARD_DASH = EM_DASH;

/**
 * The dashboard bundle as the orchestrator receives it, narrowed to the fields
 * the pure selectors need. Exported for tests; no runtime dependency.
 */
export type DashboardSources = Pick<
  DashboardDataBundle,
  "data" | "goals" | "goalsError"
>;
