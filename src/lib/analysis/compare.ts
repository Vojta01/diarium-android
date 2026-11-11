import { metricValue, type MetricKey } from "./metric-series.ts";
import type { AnalysisWindows, StatsWindow } from "./stats-window.ts";
import { welchDiff, type DiffEstimate } from "./uncertainty.ts";
import type { DailyEntry } from "../stats.ts";

/** Period comparison keeps a 7-day range usable for a partial-week record. */
export const COMPARE_MIN_N = 3;

export interface PeriodComparison {
  key: MetricKey;
  current: StatsWindow;
  previous: StatsWindow;
  nCurrent: number;
  nPrevious: number;
  coverageCurrent: number;
  coveragePrevious: number;
  meanCurrent: number | null;
  meanPrevious: number | null;
  diff: DiffEstimate;
}

function observedValues(
  entries: readonly DailyEntry[],
  window: StatsWindow,
  key: MetricKey
): number[] {
  const values: number[] = [];
  for (const entry of entries) {
    if (entry.date < window.start || entry.date > window.end) continue;
    const value = metricValue(entry, key);
    if (value !== null) values.push(value);
  }
  return values;
}

function mean(values: readonly number[]): number | null {
  if (values.length === 0) return null;
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

/**
 * One honest comparison per metric: both means, both sample sizes, and a
 * 95 % Welch CI on the difference. An empty side yields `null`, never zero.
 */
export function comparePeriods(
  entries: readonly DailyEntry[],
  windows: AnalysisWindows
): PeriodComparison[] {
  return (["mood", "sleep", "stress", "screen_time", "unlocks"] as const).map(
    (key) => {
      const currentValues = observedValues(entries, windows.current, key);
      const previousValues = observedValues(entries, windows.previous, key);
      return {
        key,
        current: windows.current,
        previous: windows.previous,
        nCurrent: currentValues.length,
        nPrevious: previousValues.length,
        coverageCurrent: currentValues.length / windows.current.days,
        coveragePrevious: previousValues.length / windows.previous.days,
        meanCurrent: mean(currentValues),
        meanPrevious: mean(previousValues),
        diff: welchDiff(currentValues, previousValues, COMPARE_MIN_N),
      };
    }
  );
}
