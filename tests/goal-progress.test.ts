import assert from "node:assert/strict";
import test from "node:test";

import {
  activityDates,
  computeGoalHistory,
  computeGoalProgress,
  computeGoalProgressList,
  dayActivitiesFromEntries,
  firstOfMonth,
  lastDayOfMonth,
  normalizeActivityValue,
  normalizeFrequency,
  periodEndBack,
  periodFullEnd,
  periodStart,
  streakOf,
  weekStart,
  type GoalDefinition,
} from "../src/lib/goal-progress.ts";
import { goalProgressForWidget } from "../src/lib/goals-data.ts";

const TODAY = "2026-11-07";

function goal(overrides: Partial<GoalDefinition> = {}): GoalDefinition {
  return {
    id: "goal-1",
    activity_key: "trenink",
    name: "Trénink",
    target_count: 3,
    frequency: "weekly",
    is_active: true,
    created_at: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

test("calendar weeks run Monday to Sunday", () => {
  assert.equal(weekStart("2026-11-07"), "2026-11-02"); // Saturday
  assert.equal(weekStart("2026-11-08"), "2026-11-02"); // Sunday
  assert.equal(weekStart("2026-11-09"), "2026-11-09"); // Monday

  assert.equal(periodStart(TODAY, "weekly"), "2026-11-02");
  assert.equal(periodFullEnd(TODAY, "weekly"), "2026-11-08");
  assert.equal(periodEndBack(TODAY, "weekly", 1), "2026-11-01");
  assert.equal(periodStart(periodEndBack(TODAY, "weekly", 1), "weekly"), "2026-10-26");
});

test("calendar months clamp at month-end", () => {
  assert.equal(firstOfMonth("2026-03-31"), "2026-03-01");
  assert.equal(lastDayOfMonth("2026-02-10"), "2026-02-28");
  assert.equal(lastDayOfMonth("2024-02-10"), "2024-02-29");
  assert.equal(periodEndBack("2026-03-31", "monthly", 1), "2026-02-28");
  assert.equal(periodStart("2026-02-28", "monthly"), "2026-02-01");
  assert.equal(periodFullEnd("2026-03-31", "monthly"), "2026-03-31");
});

test("weekly progress counts distinct days and matches label slugs to keys", () => {
  const progress = computeGoalProgress(
    goal(),
    [
      { date: "2026-10-26", activities: ["trenink"] },
      { date: "2026-10-27", habits: { trenink: true } },
      { date: "2026-10-28", activities: ["trenink"] },
      { date: "2026-11-02", activities: ["Trénink"] },
      { date: "2026-11-03", activities: ["trenink", "trenink"] },
      { date: "2026-11-03", activities: ["trenink"] },
      { date: "2026-11-04", habits: { trenink: true } },
      { date: "2026-11-05", activities: ["Chůze"] },
      { date: "2026-11-07", activities: ["TRÉNINK"] },
    ],
    TODAY
  );

  assert.equal(progress.frequency, "weekly");
  assert.equal(progress.count, 4);
  assert.equal(progress.targetMet, true);
  assert.equal(progress.remaining, 0);
  assert.equal(progress.fraction, 1);
  assert.equal(progress.periods.length, 8);
  assert.deepEqual(progress.periods.at(-1), {
    start: "2026-11-02",
    end: "2026-11-08",
    dates: ["2026-11-02", "2026-11-03", "2026-11-04", "2026-11-07"],
    met: true,
    count: 4,
  });
  assert.equal(progress.previousMet, true);
  assert.equal(progress.history.at(-1), true);
  assert.equal(progress.history.at(-2), true);

  // The newest logged day is 11-07; the next one is 11-04, so the run is one.
  assert.equal(progress.streak, 1);
});

test("a daily goal counts today once and keeps a 14-day history", () => {
  const progress = computeGoalProgress(
    goal({ activity_key: "cteni", frequency: "daily", target_count: 1 }),
    [
      { date: "2026-11-05", activities: ["Čtení"] },
      { date: "2026-11-06", activities: ["cteni"] },
      { date: "2026-11-07", activities: ["cteni"] },
    ],
    TODAY
  );

  assert.equal(progress.frequency, "daily");
  assert.equal(progress.count, 1);
  assert.equal(progress.streak, 3);
  assert.equal(progress.periods.length, 14);
  assert.equal(progress.periods.at(-1)?.start, TODAY);
  assert.equal(progress.periods.at(-1)?.end, TODAY);
});

test("a monthly goal uses the whole calendar month and reports month-end", () => {
  const progress = computeGoalProgress(
    goal({
      activity_key: "chuze",
      frequency: "monthly",
      target_count: 1,
    }),
    [
      { date: "2026-02-28", activities: ["chuze"] },
      { date: "2026-03-01", activities: ["Chůze"] },
      { date: "2026-03-15", activities: ["chuze"] },
      { date: "2026-03-31", activities: ["chuze"] },
    ],
    "2026-03-31"
  );

  assert.equal(progress.frequency, "monthly");
  assert.equal(progress.count, 3);
  assert.equal(progress.periods.length, 6);
  assert.equal(progress.periods.at(-1)?.start, "2026-03-01");
  assert.equal(progress.periods.at(-1)?.end, "2026-03-31");
  assert.equal(progress.periods.at(-2)?.start, "2026-02-01");
  assert.equal(progress.periods.at(-2)?.end, "2026-02-28");
  assert.equal(progress.periods.at(-2)?.met, true);
  assert.equal(progress.previousMet, true);
});

test("a period with no entries is no data; a period with entries but no activity is missed", () => {
  const progress = computeGoalProgress(
    goal({ target_count: 2 }),
    [
      { date: "2026-11-06", activities: ["neco_jineho"] },
      { date: "2026-11-07", activities: [] },
    ],
    TODAY
  );

  const current = progress.periods.at(-1);
  assert.equal(current?.met, false);
  assert.equal(current?.count, 0);

  const previous = progress.periods.at(-2);
  assert.equal(previous?.met, null);
  assert.deepEqual(previous?.dates, []);
});

test("habit booleans fulfil a goal only when true", () => {
  const days = dayActivitiesFromEntries([
    { date: "2026-11-06", habits: { trenink: true } },
    { date: "2026-11-07", habits: { trenink: false } },
  ]);

  assert.deepEqual(activityDates(days, "trenink", "2026-11-01", TODAY), [
    "2026-11-06",
  ]);
});

test("one count per day, even when the activity repeats across duplicate rows", () => {
  const days = dayActivitiesFromEntries([
    { date: "2026-11-06", activities: ["trenink", "trenink"] },
    { date: "2026-11-06", activities: ["trenink"] },
    { date: "2026-11-07", activities: ["trenink"] },
  ]);

  assert.deepEqual(activityDates(days, "trenink", "2026-11-01", TODAY), [
    "2026-11-07",
    "2026-11-06",
  ]);
});

test("activity normalization makes labels and slugs comparable", () => {
  assert.equal(normalizeActivityValue("Trénink"), "trenink");
  assert.equal(normalizeActivityValue("Chůze"), "chuze");
  assert.equal(normalizeActivityValue("CTENI"), "cteni");
  assert.equal(normalizeActivityValue("trenink"), "trenink");
  assert.equal(normalizeActivityValue("  Trénink  "), "trenink");

  // Punctuation-only values must not collapse into one another.
  assert.notEqual(normalizeActivityValue("!!!"), normalizeActivityValue("???"));
  assert.match(normalizeActivityValue("!!!"), /^vlastni_/);
});

test("frequency aliases normalize; unknown values behave weekly", () => {
  assert.equal(normalizeFrequency("daily"), "daily");
  assert.equal(normalizeFrequency("denně"), "daily");
  assert.equal(normalizeFrequency("Měsíčně"), "monthly");
  assert.equal(normalizeFrequency("měsíc"), "monthly");
  assert.equal(normalizeFrequency("weekly"), "weekly");
  assert.equal(normalizeFrequency("something-else"), "weekly");
});

test("streakOf counts the run ending at the newest logged day", () => {
  assert.equal(streakOf([]), 0);
  assert.equal(streakOf(["2026-11-07"]), 1);
  assert.equal(streakOf(["2026-11-07", "2026-11-06", "2026-11-05"]), 3);
  assert.equal(
    streakOf(["2026-11-07", "2026-11-05", "2026-11-04", "2026-11-03"]),
    1
  );
  assert.equal(
    streakOf(["2026-11-07", "2026-11-07", "2026-11-06"]),
    2
  );
});

test("computeGoalProgressList keeps active goals only and preserves order", () => {
  const progress = computeGoalProgressList(
    [
      goal({ id: "a", name: "A" }),
      goal({ id: "b", name: "B", is_active: false }),
      goal({ id: "c", name: "C" }),
    ],
    [{ date: TODAY, activities: ["trenink"] }],
    TODAY
  );

  assert.deepEqual(
    progress.map((item) => item.goal.id),
    ["a", "c"]
  );
});

test("computeGoalHistory keeps the current period last and the previous period second-last", () => {
  const history = computeGoalHistory(
    dayActivitiesFromEntries([{ date: TODAY, activities: ["trenink"] }]),
    goal({ target_count: 1 }),
    TODAY
  );

  assert.equal(history.length, 8);
  assert.equal(history.at(-1)?.start, "2026-11-02");
  assert.equal(history.at(-1)?.end, "2026-11-08");
  assert.equal(history.at(-1)?.met, true);
  assert.equal(history.at(-2)?.start, "2026-10-26");
  assert.equal(history.at(-2)?.end, "2026-11-01");
});

test("habit keys arrive as a JSON string and still fulfil the goal", () => {
  const days = dayActivitiesFromEntries([
    { date: "2026-11-06", habits: '{"trenink":true,"kava":false}' },
    { date: "2026-11-07", habits: '{"trenink":true}' },
  ]);

  assert.deepEqual(activityDates(days, "trenink", "2026-11-01", TODAY), [
    "2026-11-07",
    "2026-11-06",
  ]);
});

test("metPeriods and knownPeriods exclude no-data periods from the ratio", () => {
  const progress = computeGoalProgress(
    goal({ target_count: 1 }),
    [
      // Current week met.
      { date: "2026-11-02", activities: ["trenink"] },
      // Previous week has entries but not the goal: a known miss.
      { date: "2026-10-28", activities: ["neco_jineho"] },
    ],
    TODAY
  );

  assert.equal(progress.history.length, 8);
  assert.equal(progress.metPeriods, 1);
  assert.equal(progress.knownPeriods, 2);
  assert.deepEqual(progress.history.slice(-2), [false, true]);
  assert.deepEqual(progress.history.slice(0, -2), [null, null, null, null, null, null]);
});

test("weekly and monthly periods cross the year boundary", () => {
  const january = "2026-01-05";

  assert.equal(periodStart(january, "weekly"), "2026-01-05");
  assert.equal(periodEndBack(january, "weekly", 1), "2026-01-04");
  assert.equal(
    periodStart(periodEndBack(january, "weekly", 1), "weekly"),
    "2025-12-29"
  );
  assert.equal(periodFullEnd(january, "weekly"), "2026-01-11");

  assert.equal(periodStart(january, "monthly"), "2026-01-01");
  assert.equal(periodEndBack(january, "monthly", 1), "2025-12-31");
  assert.equal(
    periodStart(periodEndBack(january, "monthly", 1), "monthly"),
    "2025-12-01"
  );
  assert.equal(periodFullEnd(january, "monthly"), "2026-01-31");

  const progress = computeGoalProgress(
    goal({ frequency: "weekly", target_count: 1 }),
    [
      { date: "2025-12-30", activities: ["trenink"] },
      { date: "2026-01-05", activities: ["trenink"] },
    ],
    january
  );

  assert.equal(progress.periods.at(-1)?.start, "2026-01-05");
  assert.equal(progress.periods.at(-1)?.met, true);
  assert.equal(progress.periods.at(-2)?.start, "2025-12-29");
  assert.equal(progress.periods.at(-2)?.end, "2026-01-04");
  assert.equal(progress.periods.at(-2)?.met, true);
  assert.equal(progress.previousMet, true);
});

test("the widget's goal progress uses calendar weeks and months, not rolling windows", () => {
  const weekly = goalProgressForWidget(
    [goal({ frequency: "weekly", target_count: 2 })],
    [
      // Sunday belongs to the previous calendar week; a rolling 7-day window
      // would count it and show 3/2 instead of the calendar week's 2/2.
      { date: "2026-11-01", activities: ["trenink"] },
      { date: "2026-11-02", activities: ["trenink"] },
      { date: "2026-11-07", activities: ["trenink"] },
    ],
    TODAY
  );
  assert.equal(weekly[0].count, 2);
  assert.equal(weekly[0].periods.at(-1)?.start, "2026-11-02");
  assert.equal(weekly[0].periods.at(-2)?.start, "2026-10-26");

  const monthly = goalProgressForWidget(
    [goal({ frequency: "monthly", target_count: 2 })],
    [
      // 31 October is outside November; a rolling month window would include it.
      { date: "2026-10-31", activities: ["trenink"] },
      { date: "2026-11-01", activities: ["trenink"] },
    ],
    TODAY
  );
  assert.equal(monthly[0].count, 1);
  assert.equal(monthly[0].periods.at(-1)?.start, "2026-11-01");
  assert.equal(monthly[0].periods.at(-2)?.start, "2026-10-01");
});
