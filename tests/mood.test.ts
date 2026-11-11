import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
  averageMood,
  CHART_MOOD_COLORS,
  isRecordedMood,
  moodCounts,
  recordedMood,
  recordedMoods,
} from "../src/lib/mood.ts";
import { cs } from "../src/lib/i18n/translations/cs.ts";
import { en } from "../src/lib/i18n/translations/en.ts";

function source(relativePath: string): string {
  return readFileSync(new URL(relativePath, import.meta.url), "utf8");
}

test("pins the Android beta5 chart palette, including cyan mood 5", () => {
  assert.deepEqual(CHART_MOOD_COLORS, {
    1: "#ef4444",
    2: "#f97316",
    3: "#fbbf24",
    4: "#34d399",
    5: "#22d3ee",
  });
});

test("chart components render the exported chart palette, not the chip palette", () => {
  for (const file of [
    "../src/components/stats/MetricTimelineCard.tsx",
    "../src/components/YearInPixels.tsx",
  ]) {
    const component = source(file);
    assert.match(component, /\bCHART_MOOD_COLORS\b/, `${file} must use the chart palette`);
    assert.doesNotMatch(
      component,
      /\bMOOD_COLORS\b/,
      `${file} must not fall back to the chip palette`
    );
  }

  // The chip palette still exists for chip-style UI and stays visually
  // distinct: mood 5 is green on chips, cyan on charts.
  const stats = source("../src/lib/stats.ts");
  assert.match(stats, /export const MOOD_COLORS/);
  assert.match(stats, /5: "#22c55e"/);
  assert.notEqual(CHART_MOOD_COLORS[5], "#22c55e");
});

test("Year in Pixels count label says days with mood in both locales", () => {
  assert.equal(cs.yearInPixels.days_tracked, "Dny s náladou");
  assert.equal(en.yearInPixels.days_tracked, "Days with mood");
});

test("link labels and the new-tab hint exist in both locales", () => {
  for (const dictionary of [cs, en]) {
    assert.equal(typeof dictionary.links.github, "string");
    assert.equal(typeof dictionary.links.android_app, "string");
    assert.equal(typeof dictionary.links.download_apk, "string");
    assert.match(dictionary.links.opens_in_new_tab, /\{label\}/);
  }
});

test("only integer moods from 1 to 5 count as recorded", () => {
  for (const mood of [1, 2, 3, 4, 5]) {
    assert.equal(isRecordedMood(mood), true, `${mood} must count`);
    assert.equal(recordedMood(mood), mood);
  }
});

test("missing, zero and invalid moods never count and never become 3", () => {
  const invalid = [0, 6, -1, 3.5, Number.NaN, Number.POSITIVE_INFINITY, null, undefined, "3", true];
  for (const value of invalid) {
    assert.equal(isRecordedMood(value), false, `${String(value)} must not count`);
    assert.equal(recordedMood(value), null, `${String(value)} must stay unanswered`);
  }
});

test("recordedMoods filters unanswered rows instead of defaulting them", () => {
  const entries = [
    { mood: 5 },
    { mood: 0 },
    { mood: 3 },
    { mood: null },
    { mood: 4 },
  ];
  assert.deepEqual(recordedMoods(entries), [5, 3, 4]);
});

test("averageMood ignores unanswered rows and returns null when nothing is recorded", () => {
  assert.equal(averageMood([{ mood: 5 }, { mood: 0 }, { mood: 3 }]), 4);
  assert.equal(averageMood([{ mood: 0 }, { mood: null }]), null);
  assert.equal(averageMood([]), null);
});

test("moodCounts counts only recorded moods", () => {
  const counts = moodCounts([
    { mood: 1 },
    { mood: 3 },
    { mood: 3 },
    { mood: 0 },
    { mood: null },
    { mood: 6 },
  ]);
  assert.deepEqual(counts, { 1: 1, 2: 0, 3: 2, 4: 0, 5: 0 });
});
