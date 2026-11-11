import { isRecordedMood } from "../mood.ts";
import type { DailyEntry } from "../stats.ts";

/**
 * The metrics the analysis compares and plots.
 *
 * Ratings (1..5) render as lines because they have a meaningful bounded scale.
 * Screen time and unlocks render as bars and always start at zero; their axes
 * are never truncated.
 */
export type MetricKey = "mood" | "sleep" | "stress" | "screen_time" | "unlocks";
export type MetricRender = "line" | "bar";
export type MetricUnit = "points" | "minutes" | "count";

export interface MetricDef {
  key: MetricKey;
  render: MetricRender;
  unit: MetricUnit;
  min: number;
  max: number | null;
  labelKey: string;
}

export const ANALYSIS_METRICS: readonly MetricDef[] = [
  {
    key: "mood",
    render: "line",
    unit: "points",
    min: 1,
    max: 5,
    labelKey: "analysis.metric.mood",
  },
  {
    key: "sleep",
    render: "line",
    unit: "points",
    min: 1,
    max: 5,
    labelKey: "analysis.metric.sleep",
  },
  {
    key: "stress",
    render: "line",
    unit: "points",
    min: 1,
    max: 5,
    labelKey: "analysis.metric.stress",
  },
  {
    key: "screen_time",
    render: "bar",
    unit: "minutes",
    min: 0,
    max: null,
    labelKey: "analysis.metric.screen_time",
  },
  {
    key: "unlocks",
    render: "bar",
    unit: "count",
    min: 0,
    max: null,
    labelKey: "analysis.metric.unlocks",
  },
];

/** Minimum baseline sample size before a p25–p75 band is drawn. */
export const MIN_BASELINE_N = 14;

function rating(value: unknown): number | null {
  return typeof value === "number" &&
    Number.isInteger(value) &&
    value >= 1 &&
    value <= 5
    ? value
    : null;
}

/**
 * One metric value with the project's null-vs-zero contract applied.
 *
 * - mood: only 1..5 counts; the mapper's 0 means "not recorded".
 * - sleep/stress: only 1..5 counts; legacy rows can hold a real 0, which is
 *   not a valid answer and must stay missing.
 * - screen_time (seconds in the row): null/undefined means no sync, 0 is a
 *   measured zero and stays 0. Converted to minutes.
 * - unlocks: null/undefined means no sync, 0 is a measured zero.
 */
export function metricValue(
  entry: DailyEntry | undefined,
  key: MetricKey
): number | null {
  if (!entry) return null;
  switch (key) {
    case "mood":
      return isRecordedMood(entry.mood) ? entry.mood : null;
    case "sleep":
      return rating(entry.sleep_quality);
    case "stress":
      return rating(entry.stress);
    case "screen_time":
      return typeof entry.phone_screen_time === "number"
        ? entry.phone_screen_time / 60
        : null;
    case "unlocks":
      return typeof entry.phone_unlocks === "number"
        ? entry.phone_unlocks
        : null;
  }
}

export interface MetricSeries {
  key: MetricKey;
  dates: string[];
  values: (number | null)[];
}

/** Values aligned one-to-one with `axis`; a missing entry produces `null`. */
export function buildSeries(
  entries: readonly DailyEntry[],
  axis: readonly string[],
  key: MetricKey
): MetricSeries {
  const byDate = new Map(entries.map((entry) => [entry.date, entry]));
  return {
    key,
    dates: [...axis],
    values: axis.map((date) => metricValue(byDate.get(date), key)),
  };
}

/**
 * R-7 / Excel `PERCENTILE.INC` on an already sorted array.
 *
 * Linear interpolation between the closest ranks. Returns NaN for an empty
 * array because there is no honest percentile of no observations.
 */
export function percentile(sorted: readonly number[], p: number): number {
  const count = sorted.length;
  if (count === 0) return Number.NaN;
  const bounded = Math.max(0, Math.min(1, p));
  const h = (count - 1) * bounded;
  const low = Math.floor(h);
  const fraction = h - low;
  const high = Math.min(low + 1, count - 1);
  return sorted[low] + fraction * (sorted[high] - sorted[low]);
}

/**
 * The personal baseline band: p25–p75 with the sample size behind it.
 *
 * `null` before 14 observations; a band drawn from fewer days would present
 * noise as a personal norm.
 */
export function baselineBand(
  values: readonly (number | null)[]
): { p25: number; p75: number; n: number } | null {
  const observed = values.filter(
    (value): value is number => typeof value === "number" && Number.isFinite(value)
  );
  if (observed.length < MIN_BASELINE_N) return null;
  const sorted = [...observed].sort((left, right) => left - right);
  return {
    p25: percentile(sorted, 0.25),
    p75: percentile(sorted, 0.75),
    n: observed.length,
  };
}
