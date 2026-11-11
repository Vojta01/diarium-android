import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import { answeredHabitEntries, mergeActiveHabitCatalog } from "../src/lib/analysis/habit-catalog.ts";
import { habitImpacts } from "../src/lib/analysis/impact.ts";
import type { HabitDef } from "../src/lib/supabase/db.ts";
import { entry } from "./dashboard-fixtures.ts";

function habit(key: string, overrides: Partial<HabitDef> = {}): HabitDef {
  return {
    key,
    label: key,
    icon: "✓",
    category: "general",
    color: "#000000",
    is_negative: false,
    source: "default",
    ...overrides,
  };
}

test("inactive overrides hide defaults; missing history keys stay excluded and false stays answered", () => {
  const catalog = [
    habit("hidden_default"),
    habit("visible_default"),
    { ...habit("inactive_catalog_default"), is_active: false },
  ];
  const userRows = [
    { ...habit("hidden_default"), is_active: false },
    { ...habit("custom_active", { source: "custom" }), is_active: true },
  ];
  const active = mergeActiveHabitCatalog(catalog, userRows);
  assert.deepEqual(active.map(({ key }) => key), ["visible_default", "custom_active"]);

  const historical = entry("2026-11-01", {
    mood: 5,
    habits: {
      hidden_default: true,
      visible_default: false,
      custom_active: false,
      deleted_habit: true,
    },
  });
  const originalHistory = structuredClone(historical.habits);
  const dayAnswers = answeredHabitEntries(historical.habits, active);
  assert.deepEqual(dayAnswers.map(({ key, value }) => [key, value]), [
    ["visible_default", false],
    ["custom_active", false],
  ]);

  const impacts = new Map(
    habitImpacts([historical], [historical.date], active).map((row) => [row.key, row])
  );
  assert.deepEqual(
    [impacts.get("visible_default")?.nWith, impacts.get("visible_default")?.nWithout],
    [0, 1]
  );
  assert.deepEqual(
    [impacts.get("custom_active")?.nWith, impacts.get("custom_active")?.nWithout],
    [0, 1]
  );
  assert.equal(impacts.has("hidden_default"), false);
  assert.equal(impacts.has("deleted_habit"), false);
  assert.deepEqual(historical.habits, originalHistory, "catalogue filtering must not erase historical data");
});

test("database habit reads and DayPeek use the shared active catalogue projection", () => {
  const database = readFileSync(new URL("../src/lib/supabase/db.ts", import.meta.url), "utf8");
  const dayPeek = readFileSync(new URL("../src/components/stats/DayPeek.tsx", import.meta.url), "utf8");

  assert.ok(database.includes("mergeActiveHabitCatalog(defaults, userHabits)"));
  assert.ok(database.includes("mergeActiveHabitCatalog(\n    ((defaultRes.data ?? [])"));
  assert.ok(database.includes("(userRes.data ?? []) as (HabitDef & { is_active?: boolean })[]"));
  assert.match(dayPeek, /answeredHabitEntries\(entry\.habits, habits\)/);
});
