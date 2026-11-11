import assert from "node:assert/strict";
import test from "node:test";

import { comparePeriods } from "../src/lib/analysis/compare.ts";
import { analysisWindows } from "../src/lib/analysis/stats-window.ts";
import { entry } from "./dashboard-fixtures.ts";

const TODAY = "2026-11-07";

test("an empty read produces null means, never zero", () => {
  const rows = comparePeriods([], analysisWindows(TODAY, 30));

  assert.equal(rows.length, 5);
  for (const row of rows) {
    assert.equal(row.nCurrent, 0);
    assert.equal(row.nPrevious, 0);
    assert.equal(row.meanCurrent, null);
    assert.equal(row.meanPrevious, null);
    assert.equal(row.diff.diff, null);
    assert.equal(row.diff.ciLow, null);
    assert.equal(row.diff.verdict, "insufficient");
    assert.equal(row.coverageCurrent, 0);
    assert.equal(row.coveragePrevious, 0);
  }
});

test("one empty side leaves the difference null but keeps the other mean", () => {
  const entries = [entry(TODAY, { mood: 5 }), entry("2026-11-06", { mood: 4 })];
  const rows = comparePeriods(entries, analysisWindows(TODAY, 30));
  const mood = rows.find((row) => row.key === "mood");

  assert.ok(mood);
  assert.equal(mood.nCurrent, 2);
  assert.equal(mood.nPrevious, 0);
  assert.equal(mood.meanCurrent, 4.5);
  assert.equal(mood.meanPrevious, null);
  assert.equal(mood.diff.diff, null);
  assert.equal(mood.diff.verdict, "insufficient");
});

test("two days per side is too little for the 3-per-side gate", () => {
  const entries = [
    entry("2026-11-07", { mood: 5 }),
    entry("2026-11-06", { mood: 4 }),
    entry("2026-10-08", { mood: 3 }),
    entry("2026-10-07", { mood: 2 }),
  ];
  const rows = comparePeriods(entries, analysisWindows(TODAY, 30));
  const mood = rows.find((row) => row.key === "mood");

  assert.equal(mood?.nCurrent, 2);
  assert.equal(mood?.nPrevious, 2);
  assert.equal(mood?.diff.diff, 2);
  assert.equal(mood?.diff.verdict, "insufficient");
  assert.equal(mood?.diff.ciLow, null);
});

test("coverage is sample size over window days", () => {
  const entries = [
    entry("2026-11-07", { mood: 5 }),
    entry("2026-11-06", { mood: 4 }),
    entry("2026-11-05", { mood: 3 }),
    entry("2026-11-04", { mood: 2 }),
  ];
  const rows = comparePeriods(entries, analysisWindows(TODAY, 7));
  const mood = rows.find((row) => row.key === "mood");

  assert.equal(mood?.nCurrent, 4);
  assert.equal(mood?.coverageCurrent, 4 / 7);
});

test("screen-time mean is per answered day and a real zero counts", () => {
  const entries = [
    entry("2026-11-07", { phone_screen_time: 0 }),
    entry("2026-11-06", { phone_screen_time: 600 }),
  ];
  const rows = comparePeriods(entries, analysisWindows(TODAY, 30));
  const screen = rows.find((row) => row.key === "screen_time");

  assert.equal(screen?.nCurrent, 2);
  // 0 min and 10 min, divided by the two answered days — not by 30 calendar days.
  assert.equal(screen?.meanCurrent, 5);
  assert.equal(screen?.coverageCurrent, 2 / 30);
});

test("legacy sleep 0 is not counted while screen time 0 is", () => {
  const entries = [
    entry("2026-11-07", { sleep_quality: 0, phone_screen_time: 0 }),
    entry("2026-11-06", { sleep_quality: 4, phone_screen_time: 900 }),
  ];
  const rows = comparePeriods(entries, analysisWindows(TODAY, 30));
  const sleep = rows.find((row) => row.key === "sleep");
  const screen = rows.find((row) => row.key === "screen_time");

  assert.equal(sleep?.nCurrent, 1);
  assert.equal(sleep?.meanCurrent, 4);
  assert.equal(screen?.nCurrent, 2);
  assert.equal(screen?.meanCurrent, 7.5);
});
