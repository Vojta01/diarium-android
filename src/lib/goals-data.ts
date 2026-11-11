"use client";

import { pragueDateKey, shiftDateKey } from "./date.ts";
import {
  computeGoalProgressList,
  GOAL_STREAK_LOOKBACK_DAYS,
  type ActivityEntry,
  type GoalDefinition,
  type GoalProgressData,
} from "./goal-progress.ts";
import { getGoals, type Goal } from "./goals.ts";
import { fetchGoalActivityEntries } from "./stats.ts";

/**
 * The one goal-progress path the UI renders.
 *
 * Every widget and screen goes through the pure calendar-period maths; the old
 * rolling-window `getGoalProgress` has been removed so two surfaces can no
 * longer disagree about "this week".
 */
export function goalProgressForWidget<G extends GoalDefinition>(
  goals: readonly G[],
  entries: readonly ActivityEntry[],
  today: string
): GoalProgressData<G>[] {
  return computeGoalProgressList(goals, entries, today);
}

/**
 * Load the active goals and the year of activity rows their calendar periods
 * and streak need. The dashboard's 30-day window is deliberately not reused:
 * a monthly history and a long daily streak live outside it.
 */
export async function fetchGoalProgress(
  today: string = pragueDateKey()
): Promise<GoalProgressData<Goal>[]> {
  const goals = await getGoals();
  if (goals.length === 0) return [];

  const entries = await fetchGoalActivityEntries(
    shiftDateKey(today, -GOAL_STREAK_LOOKBACK_DAYS),
    today
  );
  return goalProgressForWidget(goals, entries, today);
}
