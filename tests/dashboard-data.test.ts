import assert from "node:assert/strict";
import test from "node:test";

import {
  fetchDashboardData,
  type DashboardDataDeps,
} from "../src/lib/dashboard-data.ts";
import {
  DASHBOARD_ERROR_CODES,
  DashboardDataError,
  isDashboardAuthError,
} from "../src/lib/dashboard-errors.ts";
import { DASHBOARD_LOAD_DAYS } from "../src/lib/dashboard.ts";
import { GOAL_STREAK_LOOKBACK_DAYS } from "../src/lib/goal-progress.ts";
import { targetMemoryDates } from "../src/lib/on-this-day.ts";
import { cs } from "../src/lib/i18n/translations/cs.ts";
import { en } from "../src/lib/i18n/translations/en.ts";
import { entry } from "./dashboard-fixtures.ts";

const TODAY = "2026-11-07";
const MEMORY_DATES = targetMemoryDates(TODAY).map((item) => item.date);

interface Calls {
  entries: { from: string; to: string } | null;
  memory: readonly string[] | null;
  goals: { from: string; to: string } | null;
}

function makeDeps(
  overrides: Partial<DashboardDataDeps> = {},
  calls: Calls = { entries: null, memory: null, goals: null }
): { deps: DashboardDataDeps; calls: Calls } {
  const deps: DashboardDataDeps = {
    fetchEntries: async (from, to) => {
      calls.entries = { from, to };
      return [];
    },
    fetchMemoryEntries: async (dates) => {
      calls.memory = dates;
      return [];
    },
    fetchGoalEntries: async (from, to) => {
      calls.goals = { from, to };
      return [];
    },
    getGoals: async () => [],
    getScales: async () => [],
    getHabits: async () => [],
    ...overrides,
  };
  return { deps, calls };
}

test("the core read is the 30-day window, so older cards cannot appear", async () => {
  const old = entry("2026-10-08", {
    mood: 5,
    note: "starý den",
    gratitude: ["stará vděčnost"],
    ai_reflection: "stará reflexe",
    phone_top_apps: [
      { app: "Old A", time_sec: 600 },
      { app: "Old B", time_sec: 500 },
      { app: "Old C", time_sec: 400 },
    ],
  });
  const today = entry(TODAY, { mood: 4, note: "dnes" });

  const { deps, calls } = makeDeps({
    fetchEntries: async (from, to) => {
      calls.entries = { from, to };
      // Even a careless source that returns everything is filtered by the
      // pure derivation, so the dashboard still matches Android.
      return [old, today];
    },
  });

  const bundle = await fetchDashboardData(TODAY, deps);

  assert.deepEqual(calls.entries, {
    from: "2026-10-09",
    to: TODAY,
  });
  assert.equal(bundle.data.entryByDate["2026-10-08"], undefined);
  assert.equal(bundle.data.reflection, null);
  assert.equal(bundle.data.topApps, null);
  assert.equal(bundle.data.rankedTopApps, null);
  assert.equal(bundle.data.lastGratitude, null);
  assert.equal(bundle.data.newestEntry?.date, TODAY);
  assert.equal(bundle.data.month.length, DASHBOARD_LOAD_DAYS);
});

test("a 31+ day top-apps snapshot and reflection stay hidden, memory still works", async () => {
  const { deps, calls } = makeDeps({
    fetchEntries: async () => [entry(TODAY, { mood: 4 })],
    fetchMemoryEntries: async (dates) => {
      calls.memory = dates;
      return [
        entry("2025-11-07", {
          mood: 3,
          note: "před rokem",
          ai_reflection: "stará reflexe",
          phone_top_apps: [
            { app: "Old A", time_sec: 600 },
            { app: "Old B", time_sec: 500 },
            { app: "Old C", time_sec: 400 },
          ],
        }),
      ];
    },
  });

  const bundle = await fetchDashboardData(TODAY, deps);

  assert.deepEqual(calls.memory, MEMORY_DATES);
  assert.equal(bundle.data.onThisDay?.date, "2025-11-07");
  assert.equal(bundle.data.onThisDay?.horizon, "one_year");
  assert.equal(bundle.data.entryByDate["2025-11-07"]?.note, "před rokem");
  // The memory is displayable in DayDetail, but it is not the dashboard's
  // newest reflection or its top-apps snapshot.
  assert.equal(bundle.data.reflection, null);
  assert.equal(bundle.data.topApps, null);
  assert.equal(bundle.data.rankedTopApps, null);
});

test("a memory read failure hides only the memory card", async () => {
  const { deps } = makeDeps({
    fetchEntries: async () => [entry(TODAY, { mood: 4 })],
    fetchMemoryEntries: async () => {
      throw new Error("offline");
    },
  });

  const bundle = await fetchDashboardData(TODAY, deps);
  assert.equal(bundle.data.onThisDay, null);
  assert.equal(bundle.data.todayEntry?.mood, 4);
});

test("signed out rejects with a coherent auth error, not an empty bundle", async () => {
  const authError = new DashboardDataError(
    "signed_out",
    DASHBOARD_ERROR_CODES.signedOut,
    401
  );
  const { deps } = makeDeps({
    fetchEntries: async () => {
      throw authError;
    },
  });

  await assert.rejects(fetchDashboardData(TODAY, deps), (error: unknown) => {
    assert.ok(error instanceof DashboardDataError);
    assert.equal(error.kind, "signed_out");
    assert.equal(error.status, 401);
    assert.equal(error.code, "errors.signed_out");
    assert.equal(error.message, "errors.signed_out");
    assert.equal(isDashboardAuthError(error), true);
    return true;
  });
});

test("optional read failures carry semantic codes, never Czech sentences", async () => {
  const { deps } = makeDeps({
    fetchEntries: async () => [entry(TODAY, { mood: 4 })],
    getGoals: async () => {
      throw new Error("Cíle se nepodařilo načíst: boom");
    },
    getScales: async () => {
      throw new Error("Škály se nepodařilo načíst: boom");
    },
    getHabits: async () => {
      throw new Error("Návyky se nepodařilo načíst: boom");
    },
  });

  const bundle = await fetchDashboardData(TODAY, deps);
  assert.equal(bundle.goals, null);
  assert.equal(bundle.goalsError, "errors.goals_load");
  assert.equal(bundle.scales, null);
  assert.equal(bundle.scalesError, "errors.scales_load");
  assert.equal(bundle.habits, null);
  assert.equal(bundle.habitsError, "errors.habits_load");
});

test("goals read their own year of activity, not the 30-day dashboard window", async () => {
  const { deps, calls } = makeDeps({
    fetchEntries: async () => [
      entry(TODAY, { mood: 4, activities: ["trenink"] }),
    ],
    getGoals: async () => [
      {
        id: "goal-1",
        user_id: "user-1",
        activity_key: "trenink",
        name: "Trénink",
        target_count: 3,
        frequency: "weekly",
        is_active: true,
        created_at: "2026-01-01T00:00:00Z",
      },
    ],
    fetchGoalEntries: async (from, to) => {
      calls.goals = { from, to };
      // No training rows in the goal read: if the loader wrongly reused the
      // dashboard core, the count would be 1.
      return [];
    },
  });

  const bundle = await fetchDashboardData(TODAY, deps);

  assert.deepEqual(calls.goals, {
    from: "2025-11-07",
    to: TODAY,
  });
  assert.equal(bundle.goals?.[0].count, 0);
  assert.equal(bundle.goals?.[0].frequency, "weekly");
  assert.equal(
    GOAL_STREAK_LOOKBACK_DAYS,
    365,
    "the goal read keeps a full year for a long daily streak"
  );
});

test("an empty goal list is a successful empty state, not an error", async () => {
  const { deps, calls } = makeDeps({
    fetchEntries: async () => [entry(TODAY, { mood: 4 })],
    getGoals: async () => [],
  });

  const bundle = await fetchDashboardData(TODAY, deps);
  assert.deepEqual(bundle.goals, []);
  assert.equal(bundle.goalsError, null);
  assert.equal(calls.goals, null, "no goal entries are read without goals");
});

test("every dashboard error code resolves to copy in both languages", () => {
  function resolve(dictionary: unknown, path: string): unknown {
    return path
      .split(".")
      .reduce<unknown>(
        (node, part) =>
          node && typeof node === "object"
            ? (node as Record<string, unknown>)[part]
            : undefined,
        dictionary
      );
  }

  for (const code of Object.values(DASHBOARD_ERROR_CODES)) {
    assert.equal(
      typeof resolve(en, code),
      "string",
      `${code} must resolve in English`
    );
    assert.equal(
      typeof resolve(cs, code),
      "string",
      `${code} must resolve in Czech`
    );
  }
});
