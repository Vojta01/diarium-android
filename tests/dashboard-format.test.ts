import assert from "node:assert/strict";
import test from "node:test";

import { trendOf, type MetricTrend } from "../src/lib/dashboard.ts";
import {
  EM_DASH,
  formatAverageMood,
  formatCompactMinutes,
  formatDurationMinutes,
  formatMetricValue,
  formatPercentChange,
  formatSecondsLabel,
  formatTopAppDuration,
  moodFace,
  moodWord,
  trendCaption,
} from "../src/lib/dashboard-format.ts";

test("null is an em dash, never a zero", () => {
  assert.equal(EM_DASH, "—");
  assert.equal(formatDurationMinutes(null), "—");
  assert.equal(formatDurationMinutes(undefined), "—");
  assert.equal(formatCompactMinutes(null), "—");
  assert.equal(formatSecondsLabel(null), "—");
  assert.equal(formatAverageMood(null), "—");
  assert.equal(formatMetricValue(null, "unlocks"), "—");
  assert.equal(formatMetricValue(undefined, "mood"), "—");
  assert.equal(formatTopAppDuration(null), "—");
});

test("metric durations follow Android's hours-without-trailing-min", () => {
  assert.equal(formatDurationMinutes(0), "0 min");
  assert.equal(formatDurationMinutes(45), "45 min");
  assert.equal(formatDurationMinutes(60), "1 h");
  assert.equal(formatDurationMinutes(132), "2 h 12");
  assert.equal(formatDurationMinutes(-5), "0 min");
});

test("compact bar labels have no spaces", () => {
  assert.equal(formatCompactMinutes(0), "0m");
  assert.equal(formatCompactMinutes(45), "45m");
  assert.equal(formatCompactMinutes(358), "5h58");
  assert.equal(formatCompactMinutes(480), "8h");
  assert.equal(formatSecondsLabel(0), "0m");
  assert.equal(formatSecondsLabel(358 * 60), "5h58");
});

test("average mood keeps one decimal and a dot", () => {
  assert.equal(formatAverageMood(3.84), "3.8");
  assert.equal(formatAverageMood(4), "4.0");
});

test("metric values use the right unit per kind", () => {
  assert.equal(formatMetricValue(90, "screen_time"), "1 h 30");
  assert.equal(formatMetricValue(0, "unlocks"), "0");
  assert.equal(formatMetricValue(3.75, "mood"), "3.8 / 5");
});

test("top-app durations round and never say 0 min", () => {
  assert.equal(formatTopAppDuration(0), "<1 min");
  assert.equal(formatTopAppDuration(30), "<1 min");
  assert.equal(formatTopAppDuration(60), "1 min");
  assert.equal(formatTopAppDuration(3900), "1 h 5 min");
  assert.equal(formatTopAppDuration(3600), "1 h");
});

test("mood face and word thresholds match beta5", () => {
  assert.equal(moodFace(4.5), "😄");
  assert.equal(moodFace(4.49), "🙂");
  assert.equal(moodFace(3.5), "🙂");
  assert.equal(moodFace(3.49), "😐");
  assert.equal(moodFace(2.5), "😐");
  assert.equal(moodFace(2.49), "🙁");
  assert.equal(moodFace(null), null);

  assert.equal(moodWord(4.5), "excellent");
  assert.equal(moodWord(3.5), "good");
  assert.equal(moodWord(2.5), "average");
  assert.equal(moodWord(1), "poor");
  assert.equal(moodWord(null), null);
});

function trend(partial: Partial<MetricTrend>): MetricTrend {
  return {
    current: 100,
    previous: 100,
    direction: "flat",
    percentChange: 0,
    ...partial,
  };
}

test("trend captions are semantic keys, not hardcoded Czech", () => {
  assert.equal(trendCaption(trend({ current: null, direction: "unknown" }), "usage"), null);

  assert.deepEqual(trendCaption(trend({ direction: "unknown", percentChange: null }), "usage"), {
    key: "no_comparison",
    percent: null,
  });
  assert.deepEqual(trendCaption(trend({ direction: "flat" }), "usage"), {
    key: "same",
    percent: null,
  });
  assert.deepEqual(
    trendCaption(trend({ direction: "up", percentChange: 12 }), "usage"),
    { key: "more", percent: 12 }
  );
  assert.deepEqual(
    trendCaption(trend({ direction: "up", percentChange: 12 }), "mood"),
    { key: "higher", percent: 12 }
  );
  assert.deepEqual(
    trendCaption(trend({ direction: "down", percentChange: -12 }), "usage"),
    { key: "less", percent: 12 }
  );
  assert.deepEqual(
    trendCaption(trend({ direction: "down", percentChange: -12 }), "mood"),
    { key: "lower", percent: 12 }
  );
  assert.deepEqual(
    trendCaption(trend({ direction: "up", percentChange: null }), "usage"),
    { key: "new", percent: null }
  );
});

test("the dialog's change row keeps null and zero apart", () => {
  assert.equal(formatPercentChange(trendOf(null, 100)), "—");
  assert.equal(formatPercentChange(trendOf(100, 0)), "—");
  assert.equal(formatPercentChange(trendOf(100, 100)), "0 %");
  assert.equal(formatPercentChange(trendOf(120, 100)), "+20 %");
  assert.equal(formatPercentChange(trendOf(80, 100)), "-20 %");
});
