import assert from "node:assert/strict";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

import { cs } from "../src/lib/i18n/translations/cs.ts";
import { en } from "../src/lib/i18n/translations/en.ts";
import {
  ACTIVITY_CATEGORY_ORDER,
  WEATHER_OPTIONS,
} from "../src/lib/checkin/options.ts";

const ROOT = new URL("../", import.meta.url).pathname;

function walk(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) walk(full, out);
    else if (full.endsWith(".tsx")) out.push(full);
  }
  return out;
}

/** Every literal `t("a.b")` used by the check-in UI. */
function usedKeys(): Map<string, string[]> {
  const keys = new Map<string, string[]>();
  const files = [
    ...walk(join(ROOT, "src/components/checkin")),
    join(ROOT, "src/components/HabitEditDialog.tsx"),
  ];
  for (const file of files) {
    const source = readFileSync(file, "utf8");
    for (const match of source.matchAll(/\bt\(\s*"([a-zA-Z0-9_.]+)"/g)) {
      const key = match[1];
      const list = keys.get(key) ?? [];
      if (!list.includes(file)) list.push(file);
      keys.set(key, list);
    }
  }
  return keys;
}

function lookup(tree: Record<string, unknown>, key: string): unknown {
  return key.split(".").reduce<unknown>((node, part) => {
    if (node && typeof node === "object" && part in (node as object)) {
      return (node as Record<string, unknown>)[part];
    }
    return undefined;
  }, tree);
}

test("every check-in translation key exists in Czech and English", () => {
  const missing: string[] = [];
  for (const [key, files] of usedKeys()) {
    for (const [lang, tree] of [
      ["cs", cs],
      ["en", en],
    ] as const) {
      const value = lookup(tree as unknown as Record<string, unknown>, key);
      if (typeof value !== "string" || value.trim() === "") {
        missing.push(`${lang}:${key} (${files[0].replace(ROOT, "")})`);
      }
    }
  }
  assert.deepEqual(missing, []);
});

test("dynamic translation families cover every rendered option", () => {
  const missing: string[] = [];
  const families: [string[], Record<string, unknown>][] = [
    [
      ACTIVITY_CATEGORY_ORDER.map((category) => `categoryGroups.${category}`),
      cs as unknown as Record<string, unknown>,
    ],
    [
      ACTIVITY_CATEGORY_ORDER.map((category) => `categoryGroups.${category}`),
      en as unknown as Record<string, unknown>,
    ],
    [
      WEATHER_OPTIONS.map((option) => `dashboard.weather.${option.key}`),
      cs as unknown as Record<string, unknown>,
    ],
    [
      WEATHER_OPTIONS.map((option) => `dashboard.weather.${option.key}`),
      en as unknown as Record<string, unknown>,
    ],
    [
      ["checkin.date_today", "checkin.date_yesterday", "checkin.date_tomorrow"],
      cs as unknown as Record<string, unknown>,
    ],
    [
      ["checkin.date_today", "checkin.date_yesterday", "checkin.date_tomorrow"],
      en as unknown as Record<string, unknown>,
    ],
  ];

  for (const [keys, tree] of families) {
    for (const key of keys) {
      const value = lookup(tree, key);
      if (typeof value !== "string" || value.trim() === "") missing.push(key);
    }
  }
  assert.deepEqual(missing, []);
});

test("check-in section titles are distinct in both languages", () => {
  const keys = [
    "mood_label",
    "sleep_label",
    "stress_label",
    "activities_title",
    "habits_title",
    "gratitude_title",
    "weather_title",
    "photo_title",
    "scales_title",
    "note_title",
    "goals_readonly_title",
    "screen_time_title",
    "reflection_title",
  ];
  for (const tree of [cs, en] as unknown as Record<string, Record<string, string>>[]) {
    const titles = keys.map((key) => tree.checkin[key]);
    assert.equal(new Set(titles).size, keys.length, titles.join(" | "));
  }
});
