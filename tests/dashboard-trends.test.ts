import assert from "node:assert/strict";
import test from "node:test";

import {
  averagePerDay,
  metricDayValue,
  metricTrend,
  trendOf,
  trendOfAverage,
  weekMinutes,
  weekMood,
  weekUnlocks,
  type DashboardDay,
} from "../src/lib/dashboard.ts";

function day(
  date: string,
  overrides: Partial<DashboardDay> = {}
): DashboardDay {
  return {
    date,
    mood: 0,
    hasEntry: false,
    screenTimeSeconds: null,
    unlocks: null,
    ...overrides,
  };
}

test("a synced zero is data; only null means no sync", () => {
  assert.equal(weekMinutes([day("d1", { screenTimeSeconds: 0 })]), 0);
  assert.equal(weekMinutes([day("d1", { screenTimeSeconds: null })]), null);
  assert.equal(weekMinutes([]), null);

  assert.equal(weekUnlocks([day("d1", { unlocks: 0 })]), 0);
  assert.equal(weekUnlocks([day("d1", { unlocks: null })]), null);
  assert.equal(weekUnlocks([]), null);
});

test("week minutes are rounded from seconds and skip unsynced days", () => {
  assert.equal(
    weekMinutes([
      day("d1", { screenTimeSeconds: 1800 }),
      day("d2", { screenTimeSeconds: 3600 }),
      day("d3", { screenTimeSeconds: null }),
    ]),
    90
  );
});

test("week mood averages recorded days only; zero is not a mood", () => {
  assert.equal(
    weekMood([
      day("d1", { mood: 5 }),
      day("d2", { mood: 0 }),
      day("d3", { mood: 4 }),
      day("d4", { mood: 0 }),
    ]),
    4.5
  );
  assert.equal(weekMood([day("d1", { mood: 0 })]), null);
});

test("averagePerDay divides by days that carry the metric, never all days", () => {
  const days = [
    day("d1", { screenTimeSeconds: 1800 }),
    day("d2", { screenTimeSeconds: 3600 }),
    day("d3", { screenTimeSeconds: null }),
  ];
  assert.equal(averagePerDay(days, "screen_time"), 45);
  assert.equal(averagePerDay([day("d1", { unlocks: 10 })], "unlocks"), 10);
  assert.equal(averagePerDay([day("d1", { mood: 0 })], "mood"), null);
});

test("metricDayValue keeps null and zero apart", () => {
  assert.equal(metricDayValue(day("d1", { screenTimeSeconds: 0 }), "screen_time"), 0);
  assert.equal(metricDayValue(day("d1", { screenTimeSeconds: null }), "screen_time"), null);
  assert.equal(metricDayValue(day("d1", { unlocks: 0 }), "unlocks"), 0);
  assert.equal(metricDayValue(day("d1", { mood: 0 }), "mood"), null);
  assert.equal(metricDayValue(day("d1", { mood: 3 }), "mood"), 3);
});

test("trendOf handles unknown, flat noise, growth and a zero baseline", () => {
  assert.deepEqual(trendOf(120, 100), {
    current: 120,
    previous: 100,
    direction: "up",
    percentChange: 20,
  });
  assert.equal(trendOf(100, 100).direction, "flat");
  assert.equal(trendOf(103, 100).direction, "flat");
  assert.equal(trendOf(106, 100).direction, "up");
  assert.equal(trendOf(90, 100).direction, "down");
  assert.deepEqual(trendOf(100, null), {
    current: 100,
    previous: null,
    direction: "unknown",
    percentChange: null,
  });
  assert.deepEqual(trendOf(100, 0), {
    current: 100,
    previous: 0,
    direction: "up",
    percentChange: null,
  });
  assert.equal(trendOf(0, 0).direction, "flat");
});

test("trendOfAverage compares tenths, not rounded whole moods", () => {
  const move = trendOfAverage(3.8, 3.6);
  assert.equal(move.direction, "up");
  assert.equal(move.percentChange, 6);

  const noise = trendOfAverage(3.8, 3.7);
  assert.equal(noise.direction, "flat");

  assert.equal(trendOfAverage(null, 3.8).direction, "unknown");
});

test("metricTrend uses the card's own aggregation for each metric", () => {
  const week = [
    day("d1", { screenTimeSeconds: 3600, unlocks: 10, mood: 4 }),
    day("d2", { screenTimeSeconds: 1800, unlocks: 20, mood: 5 }),
  ];
  const previous = [
    day("p1", { screenTimeSeconds: 5400, unlocks: 30, mood: 3 }),
    day("p2", { screenTimeSeconds: 0, unlocks: 0, mood: 4 }),
  ];

  const screen = metricTrend(week, previous, "screen_time");
  assert.equal(screen.current, 90);
  assert.equal(screen.previous, 90);
  assert.equal(screen.direction, "flat");

  const unlocks = metricTrend(week, previous, "unlocks");
  assert.equal(unlocks.current, 30);
  assert.equal(unlocks.previous, 30);
  assert.equal(unlocks.direction, "flat");

  const mood = metricTrend(week, previous, "mood");
  assert.equal(mood.current, 45); // 4.5 scaled to tenths
  assert.equal(mood.previous, 35); // 3.5 scaled to tenths
  assert.equal(mood.direction, "up");
});

test("a week with no synced day compares as unknown, not as zero", () => {
  const trend = metricTrend(
    [day("d1", { screenTimeSeconds: null })],
    [day("p1", { screenTimeSeconds: 3600 })],
    "screen_time"
  );
  assert.equal(trend.current, null);
  assert.equal(trend.direction, "unknown");
});
