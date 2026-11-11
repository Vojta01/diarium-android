import assert from "node:assert/strict";
import test from "node:test";

import {
  baselineBand,
  buildSeries,
  metricValue,
  percentile,
} from "../src/lib/analysis/metric-series.ts";
import { entry } from "./dashboard-fixtures.ts";

test("mood 0 and null are untracked, never a neutral value", () => {
  assert.equal(metricValue(entry("2026-01-01", { mood: 0 }), "mood"), null);
  assert.equal(metricValue(undefined, "mood"), null);
  assert.equal(metricValue(entry("2026-01-01", { mood: 4 }), "mood"), 4);
});

test("sleep and stress require an integer 1..5; legacy zero is missing", () => {
  assert.equal(metricValue(entry("2026-01-01", { sleep_quality: 0 }), "sleep"), null);
  assert.equal(metricValue(entry("2026-01-01", { sleep_quality: null }), "sleep"), null);
  assert.equal(metricValue(entry("2026-01-01", { sleep_quality: 3 }), "sleep"), 3);
  assert.equal(metricValue(entry("2026-01-01", { stress: 0 }), "stress"), null);
  assert.equal(metricValue(entry("2026-01-01", { stress: 2.5 }), "stress"), null);
  assert.equal(metricValue(entry("2026-01-01", { stress: 6 }), "stress"), null);
  assert.equal(metricValue(entry("2026-01-01", { stress: 5 }), "stress"), 5);
});

test("screen time and unlocks: 0 is real, null/undefined is missing", () => {
  assert.equal(
    metricValue(entry("2026-01-01", { phone_screen_time: 0 }), "screen_time"),
    0
  );
  assert.equal(
    metricValue(entry("2026-01-01", { phone_screen_time: 600 }), "screen_time"),
    10
  );
  assert.equal(
    metricValue(entry("2026-01-01", { phone_screen_time: null }), "screen_time"),
    null
  );
  assert.equal(metricValue(entry("2026-01-01"), "screen_time"), null);

  assert.equal(metricValue(entry("2026-01-01", { phone_unlocks: 0 }), "unlocks"), 0);
  assert.equal(metricValue(entry("2026-01-01", { phone_unlocks: 42 }), "unlocks"), 42);
  assert.equal(metricValue(entry("2026-01-01"), "unlocks"), null);
});

test("buildSeries keeps one value per axis day and index-aligns dates", () => {
  const entries = [
    entry("2026-01-02", { mood: 4, phone_screen_time: 0 }),
    entry("2026-01-03", { mood: 0, phone_screen_time: 1200 }),
  ];
  const axis = ["2026-01-01", "2026-01-02", "2026-01-03"];

  const mood = buildSeries(entries, axis, "mood");
  assert.deepEqual(mood.values, [null, 4, null]);
  assert.deepEqual(mood.dates, axis);

  const screen = buildSeries(entries, axis, "screen_time");
  assert.deepEqual(screen.values, [null, 0, 20]);
});

test("percentile uses R-7 linear interpolation", () => {
  assert.equal(percentile([1, 2, 3, 4], 0.25), 1.75);
  assert.equal(percentile([1, 2, 3, 4], 0.5), 2.5);
  assert.equal(percentile([5], 0.5), 5);
  assert.ok(Number.isNaN(percentile([], 0.5)));
});

test("baselineBand needs 14 observations and can collapse to a line", () => {
  const thirteen = Array.from({ length: 13 }, (_, i) => 1 + (i % 5));
  assert.equal(baselineBand(thirteen), null);

  const fourteen = [...thirteen, 5];
  const band = baselineBand(fourteen);
  assert.ok(band);
  assert.equal(band.n, 14);
  assert.ok(band.p25 <= band.p75);

  const constant = baselineBand(Array.from({ length: 14 }, () => 3));
  assert.deepEqual(constant, { p25: 3, p75: 3, n: 14 });

  const withMissing = baselineBand([...fourteen, null, null]);
  assert.equal(withMissing?.n, 14);
});
