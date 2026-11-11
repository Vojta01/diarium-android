"use client";

import {
  attachOnThisDay,
  deriveDashboard,
  DASHBOARD_LOAD_DAYS,
  type DashboardData,
} from "./dashboard.ts";
import {
  DASHBOARD_ERROR_CODES,
  dashboardErrorCode,
  toDashboardError,
  type DashboardErrorCode,
} from "./dashboard-errors.ts";
import {
  computeGoalProgressList,
  GOAL_STREAK_LOOKBACK_DAYS,
  type GoalProgressData,
} from "./goal-progress.ts";
import { getGoals, type Goal } from "./goals.ts";
import { pragueDateKey, shiftDateKey } from "./date.ts";
import { getScalesOrThrow, type Scale } from "./scales.ts";
import {
  fetchDailyEntries,
  fetchEntriesByDates,
  fetchGoalActivityEntries,
  type DailyEntry,
  type GoalActivityEntry,
} from "./stats.ts";
import { targetMemoryDates } from "./on-this-day.ts";
import { getHabitsOrThrow, type HabitDef } from "./supabase/db.ts";

/**
 * Everything one dashboard load produces.
 *
 * `data` is the only required part: if the 30-day entries read fails (including
 * a signed-out session), this function throws a `DashboardDataError` and the
 * screen shows its error card. The optional resources are independent cards,
 * so a goals/scales/habits failure carries a semantic `errors.*` code and
 * `null` data instead of an empty list. `null` means "could not verify";
 * `[]` means "this account really has none". The UI must render those two
 * states differently (error + retry vs empty CTA), exactly like Android.
 */
export interface DashboardDataBundle {
  data: DashboardData;
  goals: GoalProgressData<Goal>[] | null;
  goalsError: DashboardErrorCode | null;
  scales: Scale[] | null;
  scalesError: DashboardErrorCode | null;
  habits: HabitDef[] | null;
  habitsError: DashboardErrorCode | null;
}

/**
 * The reads one dashboard load performs.
 *
 * Exposed so tests can pin the window, the memory dates and the goal read
 * without a network, and so a future UI can substitute a cached source without
 * rewriting the orchestration.
 */
export interface DashboardDataDeps {
  fetchEntries: (from: string, to: string) => Promise<DailyEntry[]>;
  fetchMemoryEntries: (dates: readonly string[]) => Promise<DailyEntry[]>;
  fetchGoalEntries: (
    from: string,
    to: string
  ) => Promise<GoalActivityEntry[]>;
  getGoals: () => Promise<Goal[]>;
  getScales: () => Promise<Scale[]>;
  getHabits: () => Promise<HabitDef[]>;
}

const defaultDeps: DashboardDataDeps = {
  fetchEntries: (from, to) => fetchDailyEntries({ from, to }),
  fetchMemoryEntries: (dates) => fetchEntriesByDates(dates),
  fetchGoalEntries: (from, to) => fetchGoalActivityEntries(from, to),
  getGoals,
  getScales: getScalesOrThrow,
  getHabits: getHabitsOrThrow,
};

/**
 * Load and derive the dashboard for `today` (Europe/Prague by default).
 *
 * Three reads, matching Android's split:
 * 1. the `DASHBOARD_LOAD_DAYS`-day core that feeds every metric and card;
 * 2. the exact 1-year / 6-month / 1-month memory dates, best effort;
 * 3. a separate year of `date, activities, habits` rows for goal progress,
 *    because a 30-day window cannot carry monthly history or a long streak.
 *
 * No fake fallbacks: a failed or signed-out core read rejects with a semantic
 * `DashboardDataError`; a failed memory read only hides the memory card; every
 * optional resource failure is reported as `*Error` with `null` data.
 */
export async function fetchDashboardData(
  today: string = pragueDateKey(),
  overrides: Partial<DashboardDataDeps> = {}
): Promise<DashboardDataBundle> {
  const deps: DashboardDataDeps = { ...defaultDeps, ...overrides };
  const coreFrom = shiftDateKey(today, -(DASHBOARD_LOAD_DAYS - 1));
  const goalsFrom = shiftDateKey(today, -GOAL_STREAK_LOOKBACK_DAYS);

  const [entriesResult, goalsResult, scalesResult, habitsResult] =
    await Promise.allSettled([
      deps.fetchEntries(coreFrom, today),
      deps.getGoals(),
      deps.getScales(),
      deps.getHabits(),
    ]);

  if (entriesResult.status === "rejected") {
    // A signed-out session is preserved as `signed_out`/401; a real read
    // failure becomes `errors.entries_load`. Neither is ever an empty screen.
    throw toDashboardError(
      entriesResult.reason,
      DASHBOARD_ERROR_CODES.entries
    );
  }

  const coreEntries: DailyEntry[] = entriesResult.value;
  let data = deriveDashboard(today, coreEntries);

  // The memory lane is best effort: its dates sit outside the core window and
  // a failed lookback just leaves the card off, exactly like Android.
  try {
    const memoryEntries = await deps.fetchMemoryEntries(
      targetMemoryDates(today).map((item) => item.date)
    );
    data = attachOnThisDay(data, today, memoryEntries);
  } catch {
    // No memory today; the rest of the dashboard is already complete.
  }

  let goals: GoalProgressData<Goal>[] | null = null;
  let goalsError: DashboardErrorCode | null = null;

  if (goalsResult.status === "rejected") {
    goalsError = dashboardErrorCode(
      goalsResult.reason,
      DASHBOARD_ERROR_CODES.goals
    );
  } else if (goalsResult.value.length === 0) {
    goals = [];
  } else {
    try {
      const goalEntries = await deps.fetchGoalEntries(goalsFrom, today);
      goals = computeGoalProgressList(goalsResult.value, goalEntries, today);
    } catch (error) {
      goalsError = dashboardErrorCode(error, DASHBOARD_ERROR_CODES.goals);
    }
  }

  return {
    data,
    goals,
    goalsError,
    scales: scalesResult.status === "fulfilled" ? scalesResult.value : null,
    scalesError:
      scalesResult.status === "rejected"
        ? dashboardErrorCode(scalesResult.reason, DASHBOARD_ERROR_CODES.scales)
        : null,
    habits: habitsResult.status === "fulfilled" ? habitsResult.value : null,
    habitsError:
      habitsResult.status === "rejected"
        ? dashboardErrorCode(habitsResult.reason, DASHBOARD_ERROR_CODES.habits)
        : null,
  };
}
