import assert from "node:assert/strict";
import test from "node:test";

import {
  formatCi,
  formatCoverage,
  formatDayLabel,
  formatDiff,
  formatInterval,
  formatMetric,
} from "../src/lib/analysis/format.ts";
import type { MetricDef } from "../src/lib/analysis/metric-series.ts";
import type { DiffEstimate } from "../src/lib/analysis/uncertainty.ts";

const mood: MetricDef = {
  key: "mood",
  render: "line",
  unit: "points",
  min: 1,
  max: 5,
  labelKey: "analysis.metric.mood",
};
const screen: MetricDef = {
  key: "screen_time",
  render: "bar",
  unit: "minutes",
  min: 0,
  max: null,
  labelKey: "analysis.metric.screen_time",
};
const unlocks: MetricDef = {
  key: "unlocks",
  render: "bar",
  unit: "count",
  min: 0,
  max: null,
  labelKey: "analysis.metric.unlocks",
};

function estimate(ciLow: number | null, ciHigh: number | null): DiffEstimate {
  return {
    nA: 10,
    nB: 10,
    meanA: 4,
    meanB: 3,
    diff: 1,
    se: 0.2,
    df: 12,
    ciLow,
    ciHigh,
    degenerate: false,
    verdict: "higher",
  };
}

test("missing values are an em dash, never zero", () => {
  assert.equal(formatMetric(null, mood, "en"), "—");
  assert.equal(formatMetric(0, screen, "en"), "0 min");
});

test("diff keeps a sign and the unit", () => {
  assert.equal(formatDiff(0.3, mood), "+0.3");
  assert.equal(formatDiff(-12, screen), "\u221212 min");
  assert.equal(formatDiff(0, mood), "0");
});

test("average counts preserve fractional values instead of rounding them away", () => {
  assert.equal(formatMetric(3.5, unlocks, "en"), "3.5");
  assert.equal(formatDiff(0.5, unlocks), "+0.5");
  assert.equal(formatMetric(4, unlocks, "en"), "4");
});

test("CI is omitted when the interval is undefined", () => {
  assert.equal(formatCi(estimate(null, null), mood), null);
  assert.equal(formatInterval(estimate(null, null), mood), null);
  assert.equal(formatCi(estimate(-0.3, 0.9), mood), "95 % CI \u22120.3–0.9");
  assert.equal(formatInterval(estimate(0.1, 1.1), mood), "0.1–1.1");
});

test("coverage and dates are stable", () => {
  assert.equal(formatCoverage(24, 30), "24/30");
  assert.equal(formatDayLabel("2026-11-07", "en"), "7 Nov");
  assert.equal(formatDayLabel("2026-11-07", "cs"), "7. 11.");
});
