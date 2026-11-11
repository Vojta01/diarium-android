import assert from "node:assert/strict";
import test from "node:test";

import {
  createStatsOverviewLoader,
  fetchStatsOverview,
  statsOverviewRange,
} from "../src/lib/stats-dashboard-data.ts";

test("the overview makes one bounded read shared by analysis and the stats tabs", async () => {
  const calls: Array<{ from: string; to: string }> = [];
  const entries = await fetchStatsOverview(async (range) => {
    calls.push(range);
    return [{ date: range.to }];
  }, "2026-11-08");

  assert.deepEqual(calls, [{ from: "2026-05-13", to: "2026-11-08" }]);
  assert.deepEqual(entries, [{ date: "2026-11-08" }]);
});

test("range helper stays inclusive at exactly 180 calendar days", () => {
  assert.deepEqual(statsOverviewRange("2026-11-08"), {
    from: "2026-05-13",
    to: "2026-11-08",
  });
});

test("concurrent dashboard mounts share one bounded read but refreshes stay fresh", async () => {
  const calls: Array<{ from: string; to: string }> = [];
  const resolvers: Array<(value: string) => void> = [];
  const load = createStatsOverviewLoader((range) => {
    calls.push(range);
    return new Promise<string>((resolve) => resolvers.push(resolve));
  });

  const first = load("2026-11-08");
  const strictModeMount = load("2026-11-08");
  assert.equal(first, strictModeMount);
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0], { from: "2026-05-13", to: "2026-11-08" });

  const forcedRefresh = load("2026-11-08", true);
  assert.notEqual(forcedRefresh, first);
  assert.equal(calls.length, 2, "an entries-save event during an in-flight read must refetch");
  resolvers[0]("initial");
  assert.equal(await first, "initial");
  resolvers[1]("fresh");
  assert.equal(await forcedRefresh, "fresh");

  const refreshed = load("2026-11-08");
  assert.notEqual(refreshed, first);
  assert.equal(calls.length, 3, "later refresh must issue a fresh bounded query");
  resolvers[2]("after-settlement");
  assert.equal(await refreshed, "after-settlement");
});

test("an entries refresh at a new local date shifts the bounded range", async () => {
  const calls: Array<{ from: string; to: string }> = [];
  const resolvers: Array<(value: string) => void> = [];
  const load = createStatsOverviewLoader((range) => {
    calls.push(range);
    return new Promise<string>((resolve) => resolvers.push(resolve));
  });

  const yesterday = load("2026-11-08");
  const today = load("2026-11-09");
  assert.notEqual(today, yesterday);
  assert.deepEqual(calls, [
    { from: "2026-05-13", to: "2026-11-08" },
    { from: "2026-05-14", to: "2026-11-09" },
  ]);
  resolvers[0]("yesterday");
  resolvers[1]("today");
  assert.equal(await yesterday, "yesterday");
  assert.equal(await today, "today");
});
