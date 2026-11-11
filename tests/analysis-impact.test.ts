import assert from "node:assert/strict";
import test from "node:test";

import {
  activityImpacts,
  habitImpacts,
  rankImpacts,
  type ImpactResult,
} from "../src/lib/analysis/impact.ts";
import type { DiffEstimate, Verdict } from "../src/lib/analysis/uncertainty.ts";
import type { HabitDef } from "../src/lib/supabase/db.ts";
import { entry } from "./dashboard-fixtures.ts";

function habit(key: string, label: string, isNegative = false): HabitDef {
  return {
    key,
    label,
    labelEn: label,
    icon: "",
    category: "",
    color: "#000000",
    is_negative: isNegative,
    source: "default",
  };
}

function estimate(
  verdict: Verdict,
  diff: number | null,
  ciLow: number | null = null,
  ciHigh: number | null = null
): DiffEstimate {
  return {
    nA: 5,
    nB: 5,
    meanA: null,
    meanB: null,
    diff,
    se: 0.1,
    df: 5,
    ciLow,
    ciHigh,
    degenerate: false,
    verdict,
  };
}

function result(
  key: string,
  diff: DiffEstimate,
  nWith: number,
  nWithout: number
): ImpactResult {
  return {
    kind: "activity",
    key,
    label: key,
    nWith,
    nWithout,
    meanWith: null,
    meanWithout: null,
    diff,
    sourceDaysWith: [],
    sourceDaysWithout: [],
  };
}

test("missing habit key enters neither group; false counts as without", () => {
  const axis = ["2026-11-01", "2026-11-02", "2026-11-03"];
  const entries = [
    entry(axis[0], { mood: 5, habits: { meditace: true } }),
    // Absent key = question not answered, not "did not do it".
    entry(axis[1], { mood: 3, habits: {} }),
    entry(axis[2], { mood: 4, habits: { meditace: false } }),
  ];

  const [meditation] = habitImpacts(entries, axis, [habit("meditace", "Meditace")]);
  assert.ok(meditation);
  assert.equal(meditation.nWith, 1);
  assert.equal(meditation.nWithout, 1);
  assert.deepEqual(meditation.sourceDaysWith, ["2026-11-01"]);
  assert.deepEqual(meditation.sourceDaysWithout, ["2026-11-03"]);
  assert.equal(meditation.diff.verdict, "insufficient");
});

test("days without a recorded mood enter no impact group", () => {
  const axis = ["2026-11-01", "2026-11-02"];
  const entries = [
    entry(axis[0], { mood: 0, habits: { meditace: true } }),
    entry(axis[1], { mood: 4, habits: { meditace: true } }),
  ];
  const [meditation] = habitImpacts(entries, axis, [habit("meditace", "Meditace")]);
  assert.equal(meditation.nWith, 1);
  assert.equal(meditation.nWithout, 0);
});

test("missing definitions fail closed instead of resurrecting hidden historical keys", () => {
  const axis = ["2026-11-01", "2026-11-02"];
  const entries = [
    entry(axis[0], { mood: 5, habits: { koureni: true } }),
    entry(axis[1], { mood: 3, habits: { koureni: false, cteni: true } }),
  ];

  const withDefinitions = habitImpacts(entries, axis, [habit("cteni", "Čtení")]);
  assert.deepEqual(withDefinitions.map((row) => row.key), ["cteni"]);

  // A failed definition read cannot prove a key is still active.
  const withoutDefinitions = habitImpacts(entries, axis, null);
  assert.deepEqual(withoutDefinitions, []);
});

test("activity labels normalize, merge only exact keys, and keep one spelling", () => {
  const axis = [
    "2026-11-01",
    "2026-11-02",
    "2026-11-03",
    "2026-11-04",
    "2026-11-05",
    "2026-11-06",
  ];
  const entries = [
    entry(axis[0], { mood: 4, activities: ["Sport", "Čtení"] }),
    entry(axis[1], { mood: 3, activities: ["Sport", "čtení"] }),
    entry(axis[2], { mood: 5, activities: ["SPORT"] }),
    entry(axis[3], { mood: 2, activities: ["cvičení"] }),
    entry(axis[4], { mood: 3, activities: [] }),
    entry(axis[5], { mood: 4, activities: [] }),
  ];

  const results = activityImpacts(entries, axis);
  const sport = results.find((row) => row.key === "sport");
  const cviceni = results.find((row) => row.key === "cviceni");
  const cteni = results.find((row) => row.key === "cteni");

  assert.ok(sport);
  assert.ok(cviceni);
  assert.ok(cteni);
  // "Sport" is the most frequent spelling (2 vs 1 for "sport"/"SPORT" merged).
  assert.equal(sport.label, "Sport");
  assert.equal(sport.nWith, 3);
  assert.equal(sport.nWithout, 3);
  assert.equal(sport.sourceDaysWith.length, 3);
  assert.equal(cviceni.nWith, 1);
  assert.equal(cviceni.nWithout, 5);
  // Normalization strips diacritics but "cvičení" never merges with "sport".
  assert.notEqual(cviceni.key, sport.key);
});

test("source days and means agree with the entries", () => {
  const axis = ["2026-11-01", "2026-11-02", "2026-11-03"];
  const entries = [
    entry(axis[0], { mood: 5, activities: ["Běh"] }),
    entry(axis[1], { mood: 4, activities: ["běh"] }),
    entry(axis[2], { mood: 2, activities: [] }),
  ];
  const [run] = activityImpacts(entries, axis);

  const moodByDate = new Map(entries.map((item) => [item.date, item.mood]));
  const withMean =
    run.sourceDaysWith.reduce((sum, date) => sum + (moodByDate.get(date) ?? 0), 0) /
    run.sourceDaysWith.length;
  assert.equal(run.meanWith, withMean);
  assert.equal(run.meanWithout, 2);
});

test("a habit never missed is insufficient, not an error", () => {
  const axis = Array.from({ length: 6 }, (_, i) => `2026-11-0${i + 1}`);
  const entries = axis.map((date) => entry(date, { mood: 4, habits: { cteni: true } }));
  const [reading] = habitImpacts(entries, axis, [habit("cteni", "Čtení")]);

  assert.equal(reading.nWith, 6);
  assert.equal(reading.nWithout, 0);
  assert.equal(reading.diff.verdict, "insufficient");
});

test("ranking: comparable rows by effect, then CI width, then n; insufficient last", () => {
  const ranked = rankImpacts([
    result("small", estimate("not_clear", 0.2, -0.1, 0.5), 10, 10),
    result("insufficient-big-n", estimate("insufficient", 3), 30, 30),
    result("large", estimate("higher", 1.4, 0.6, 2.2), 6, 6),
    result("medium-wide", estimate("higher", 0.9, -0.2, 2.0), 8, 8),
    result("medium-narrow", estimate("higher", 0.9, 0.1, 1.7), 7, 7),
    result("insufficient-small-n", estimate("insufficient", null), 2, 2),
  ]);

  assert.deepEqual(
    ranked.map((row) => row.key),
    [
      "large",
      "medium-narrow",
      "medium-wide",
      "small",
      "insufficient-big-n",
      "insufficient-small-n",
    ]
  );
});
