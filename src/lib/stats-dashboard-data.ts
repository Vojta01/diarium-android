import { shiftDateKey } from "./date.ts";

/** Analysis consumes 90 days plus a disjoint 90-day baseline. */
export const STATS_OVERVIEW_DAYS = 180;

export interface StatsOverviewRange {
  from: string;
  to: string;
}

export function statsOverviewRange(today: string): StatsOverviewRange {
  return {
    from: shiftDateKey(today, -(STATS_OVERVIEW_DAYS - 1)),
    to: today,
  };
}

/** Make the single bounded overview read shared by AnalysisSection and its tabs. */
export function fetchStatsOverview<T>(
  read: (range: StatsOverviewRange) => Promise<T>,
  today: string
): Promise<T> {
  return read(statsOverviewRange(today));
}

/**
 * Coalesce overlapping overview reads (notably React Strict Mode's effect
 * replay) while allowing every later refresh to fetch fresh data.
 */
export function createStatsOverviewLoader<T>(
  read: (range: StatsOverviewRange) => Promise<T>
): (today: string, forceFresh?: boolean) => Promise<T> {
  let inFlight: { today: string; request: Promise<T> } | null = null;
  return (today, forceFresh = false) => {
    if (!forceFresh && inFlight?.today === today) return inFlight.request;

    const shared = fetchStatsOverview(read, today).finally(() => {
      if (inFlight?.request === shared) inFlight = null;
    });
    inFlight = { today, request: shared };
    return shared;
  };
}
