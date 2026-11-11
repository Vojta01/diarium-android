import assert from "node:assert/strict";
import test from "node:test";

import { cs } from "../src/lib/i18n/translations/cs.ts";
import { en } from "../src/lib/i18n/translations/en.ts";

type Tree = { [key: string]: string | Tree };

function flatten(tree: Tree, prefix = ""): string[] {
  return Object.entries(tree).flatMap(([key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key;
    return typeof value === "string" ? [path] : flatten(value, path);
  });
}

function at(tree: Tree, path: string): unknown {
  return path.split(".").reduce<unknown>((node, part) => {
    if (node && typeof node === "object" && part in node) {
      return (node as Tree)[part];
    }
    return undefined;
  }, tree);
}

const enDashboard = en.dashboard as unknown as Tree;
const csDashboard = cs.dashboard as unknown as Tree;

test("the dashboard subtree has identical CS and EN keys", () => {
  assert.deepEqual(flatten(enDashboard).sort(), flatten(csDashboard).sort());
});

test("every dashboard leaf is a non-empty string in both languages", () => {
  for (const path of flatten(enDashboard)) {
    const english = at(enDashboard, path);
    const czech = at(csDashboard, path);
    assert.equal(typeof english, "string", `EN ${path}`);
    assert.equal(typeof czech, "string", `CS ${path}`);
    assert.ok((english as string).trim().length > 0, `EN ${path} is empty`);
    assert.ok((czech as string).trim().length > 0, `CS ${path} is empty`);
  }
});

test("the B1 card strings all exist under dashboard.*", () => {
  const required = [
    "loading",
    "load_failed_title",
    "retry",
    "hero.screen_time",
    "hero.unlocks",
    "hero.mood",
    "hero.no_screen_time",
    "hero.no_mood",
    "streak.title",
    "streak.none",
    "streak.today_missing",
    "streak.longest",
    "week.title",
    "week.hint",
    "week.no_mood",
    "mood_chart.title",
    "mood_chart.average_label",
    "mood_chart.summary",
    "mood_chart.table_caption",
    "on_this_day.title",
    "on_this_day.horizon_one_year",
    "on_this_day.photo_alt",
    "on_this_day.photo_unavailable",
    "goals.title",
    "goals.empty",
    "goals.empty_hint",
    "goals.error_title",
    "usage.screen_time_title",
    "usage.unlocks_title",
    "usage.empty_screen_time",
    "usage.summary",
    "top_apps.title",
    "top_apps.not_enough_title",
    "reflection.title",
    "reflection.generate",
    "reflection.error_cooldown",
    "metric.last_14",
    "metric.this_week",
    "metric.best_day",
    "day_detail.title",
    "day_detail.unrecorded_hint",
    "day_detail.scales",
    "day_detail.edit",
    "weather.slunecno",
    "weather.bourka",
  ];
  for (const path of required) {
    assert.equal(typeof at(enDashboard, path), "string", `EN dashboard.${path}`);
    assert.equal(typeof at(csDashboard, path), "string", `CS dashboard.${path}`);
  }
});

test("English dashboard strings never fall back to Czech", () => {
  // The same rule the global parity test applies, scoped to the new subtree.
  const czechOnly = /[áčďéěíňóřšťúůýž]/i;
  for (const path of flatten(enDashboard)) {
    const value = at(enDashboard, path);
    assert.equal(
      czechOnly.test(value as string),
      false,
      `EN dashboard.${path} contains Czech diacritics: ${value}`
    );
  }
});

test("all eight weather picker values have CS and EN labels", () => {
  for (const key of [
    "slunecno",
    "zatazeno",
    "dest",
    "snih",
    "mraz",
    "horko",
    "bourka",
    "vitr",
  ]) {
    assert.equal(typeof at(enDashboard, `weather.${key}`), "string");
    assert.equal(typeof at(csDashboard, `weather.${key}`), "string");
  }
});
