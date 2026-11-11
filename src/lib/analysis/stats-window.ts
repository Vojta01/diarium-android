import { shiftDateKey } from "../date.ts";

/** The ranges the analysis section offers. */
export type AnalysisRange = 7 | 30 | 90;

/** One inclusive calendar window, oldest-first when expanded with `dayAxis`. */
export interface StatsWindow {
  start: string;
  end: string;
  days: number;
}

export interface AnalysisWindows {
  /** The period the user is asking about. */
  current: StatsWindow;
  /** The equally long period immediately before `current`. */
  previous: StatsWindow;
  /** Up to 90 days immediately before `current`, never overlapping it. */
  baseline: StatsWindow;
}

/** Longest baseline the analysis reads. */
export const BASELINE_DAYS = 90;

/** Inclusive window of `days` calendar days ending on `end`. */
export function statsWindow(end: string, days: number): StatsWindow {
  return { start: shiftDateKey(end, -(days - 1)), end, days };
}

/**
 * Current, previous and baseline windows for one analysis range.
 *
 * The three windows never overlap:
 *   current  = [today-(R-1) .. today]
 *   previous = [today-(2R-1) .. today-R]
 *   baseline = [today-(R+89) .. today-R]
 *
 * For R = 90 the previous window and the baseline are the same 90 days, which
 * is exactly the largest read the section ever needs (180 days).
 */
export function analysisWindows(
  today: string,
  range: AnalysisRange
): AnalysisWindows {
  const current = statsWindow(today, range);
  const previous = statsWindow(shiftDateKey(today, -range), range);
  const baseline = statsWindow(shiftDateKey(current.start, -1), BASELINE_DAYS);
  return { current, previous, baseline };
}

/** Every date in a window, oldest first, both ends inclusive. */
export function dayAxis(window: StatsWindow): string[] {
  return Array.from({ length: window.days }, (_, index) =>
    shiftDateKey(window.start, index)
  );
}
