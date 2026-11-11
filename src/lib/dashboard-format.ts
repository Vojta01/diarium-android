import type {
  DashboardMetricKind,
  MetricTrend,
  TrendTone,
} from "./dashboard.ts";

/** The one "no value" glyph. Null is a dash, never a zero. */
export const EM_DASH = "—";

/**
 * Android's `formatMetricMinutes`: 132 -> "2 h 12", 45 -> "45 min".
 *
 * The trailing "min" is dropped once hours appear, because the card already
 * carries its own unit; only the top-apps list keeps "1 h 5 min".
 */
export function formatDurationMinutes(
  minutes: number | null | undefined
): string {
  if (minutes === null || minutes === undefined) return EM_DASH;
  const total = Math.max(0, Math.round(minutes));
  if (total < 60) return `${total} min`;
  const hours = Math.floor(total / 60);
  const rest = total % 60;
  return rest === 0 ? `${hours} h` : `${hours} h ${rest}`;
}

/**
 * Compact duration for a value printed under a bar: `358` -> `"5h58"`,
 * `45` -> `"45m"`, `480` -> `"8h"`. A synced zero is a real reading and prints
 * `"0m"`; "never synced" is `formatSecondsLabel`'s separate dash.
 */
export function formatCompactMinutes(
  minutes: number | null | undefined
): string {
  if (minutes === null || minutes === undefined) return EM_DASH;
  const total = Math.max(0, Math.trunc(minutes));
  const hours = Math.floor(total / 60);
  const rest = total % 60;
  if (hours === 0) return `${rest}m`;
  return rest === 0 ? `${hours}h` : `${hours}h${rest}`;
}

/** The label under a screen-time bar; null is "never synced", 0 is "0m". */
export function formatSecondsLabel(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return EM_DASH;
  return formatCompactMinutes(Math.trunc(seconds / 60));
}

/**
 * The check-in total reads as a sentence, not a chart axis:
 * `13140` seconds -> `"3 h 39 min"`, `2700` -> `"45 min"`.
 */
export function formatScreenTimeDuration(
  seconds: number | null | undefined
): string {
  if (seconds === null || seconds === undefined) return EM_DASH;
  const total = Math.max(0, Math.trunc(seconds / 60));
  const hours = Math.floor(total / 60);
  const rest = total % 60;
  if (hours === 0) return `${rest} min`;
  return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`;
}

/** The web/Android average keeps a dot (`toFixed(1)`), not a Czech comma. */
export function formatAverageMood(value: number | null | undefined): string {
  return value === null || value === undefined ? EM_DASH : value.toFixed(1);
}

/** How a metric reads on the cards and in the list. */
export function formatMetricValue(
  value: number | null | undefined,
  kind: DashboardMetricKind
): string {
  if (value === null || value === undefined) return EM_DASH;
  switch (kind) {
    case "screen_time":
      return formatDurationMinutes(Math.round(value));
    case "unlocks":
      return String(Math.round(value));
    case "mood":
      return `${value.toFixed(1)} / 5`;
  }
}

/**
 * A top-app duration: `23 min`, `1 h 15 min`. A value under a minute reads
 * `<1 min`, never the ambiguous `0 min`; fractions round to the nearest minute.
 */
export function formatTopAppDuration(
  seconds: number | null | undefined
): string {
  if (seconds === null || seconds === undefined) return EM_DASH;
  const minutes = Math.max(0, seconds) / 60;
  if (minutes < 1) return "<1 min";
  const total = Math.round(minutes);
  const hours = Math.floor(total / 60);
  const rest = total % 60;
  if (hours === 0) return `${total} min`;
  return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`;
}

/** "😄 / 🙂 / 😐 / 🙁" for a 1–5 average, or null when there is no average. */
export function moodFace(value: number | null | undefined): string | null {
  if (value === null || value === undefined) return null;
  if (value >= 4.5) return "😄";
  if (value >= 3.5) return "🙂";
  if (value >= 2.5) return "😐";
  return "🙁";
}

/** Semantic mood word; the UI translates it through `dashboard.mood_words.*`. */
export type MoodWord = "excellent" | "good" | "average" | "poor";

export function moodWord(value: number | null | undefined): MoodWord | null {
  if (value === null || value === undefined) return null;
  if (value >= 4.5) return "excellent";
  if (value >= 3.5) return "good";
  if (value >= 2.5) return "average";
  return "poor";
}

/**
 * Semantic trend captions. The UI renders
 * `t(\`dashboard.trends.${caption.key}\`, { percent: caption.percent })`.
 *
 * The data layer never carries Czech copy: the Android port hardcoded these
 * sentences, which is why the English dashboard leaked Czech. `null` means
 * there is no current figure, so no caption should be drawn at all.
 */
export type TrendCaptionKey =
  | "no_comparison"
  | "same"
  | "new"
  | "more"
  | "less"
  | "higher"
  | "lower";

export interface TrendCaption {
  key: TrendCaptionKey;
  /** Absolute percentage for `more`/`less`/`higher`/`lower`; null otherwise. */
  percent: number | null;
}

export function trendCaption(
  trend: MetricTrend,
  tone: TrendTone
): TrendCaption | null {
  if (trend.current === null) return null;

  if (trend.direction === "unknown") {
    return { key: "no_comparison", percent: null };
  }
  if (trend.direction === "flat") {
    return { key: "same", percent: null };
  }

  if (trend.percentChange === null) {
    return { key: "new", percent: null };
  }

  const percent = Math.abs(trend.percentChange);
  if (trend.direction === "up") {
    return { key: tone === "mood" ? "higher" : "more", percent };
  }
  return { key: tone === "mood" ? "lower" : "less", percent };
}

/** The metric dialog's "Změna" row: null stays a dash, zero is "0 %". */
export function formatPercentChange(
  trend: MetricTrend
): string {
  if (trend.current === null || trend.percentChange === null) return EM_DASH;
  if (trend.percentChange === 0) return "0 %";
  return trend.percentChange > 0
    ? `+${trend.percentChange} %`
    : `${trend.percentChange} %`;
}
