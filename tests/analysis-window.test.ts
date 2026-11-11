import assert from "node:assert/strict";
import test from "node:test";

import { shiftDateKey } from "../src/lib/date.ts";
import {
  analysisWindows,
  dayAxis,
  statsWindow,
} from "../src/lib/analysis/stats-window.ts";

const TODAY = "2026-11-07";

test("30-day windows: current, previous and baseline never overlap", () => {
  const windows = analysisWindows(TODAY, 30);

  assert.deepEqual(windows.current, {
    start: "2026-10-09",
    end: "2026-11-07",
    days: 30,
  });
  assert.deepEqual(windows.previous, {
    start: "2026-09-09",
    end: "2026-10-08",
    days: 30,
  });
  assert.deepEqual(windows.baseline, {
    start: "2026-07-11",
    end: "2026-10-08",
    days: 90,
  });

  assert.equal(shiftDateKey(windows.previous.end, 1), windows.current.start);
  assert.equal(shiftDateKey(windows.baseline.end, 1), windows.current.start);
});

test("7-day windows abut and still carry a 90-day baseline", () => {
  const windows = analysisWindows(TODAY, 7);
  assert.equal(windows.current.days, 7);
  assert.equal(windows.current.start, "2026-11-01");
  assert.equal(windows.previous.end, "2026-10-31");
  assert.equal(shiftDateKey(windows.previous.end, 1), windows.current.start);
  assert.equal(windows.baseline.days, 90);
  assert.equal(shiftDateKey(windows.baseline.end, 1), windows.current.start);
});

test("90-day windows make previous and baseline the same 90 days", () => {
  const windows = analysisWindows(TODAY, 90);
  assert.deepEqual(windows.previous, windows.baseline);
  assert.equal(windows.current.start, "2026-08-10");
  assert.equal(windows.previous.end, "2026-08-09");
  assert.equal(windows.baseline.start, "2026-05-12");
});

test("dayAxis is inclusive, oldest first, and exactly window.days long", () => {
  for (const range of [7, 30, 90] as const) {
    const window = statsWindow(TODAY, range);
    const axis = dayAxis(window);
    assert.equal(axis.length, range);
    assert.equal(axis[0], window.start);
    assert.equal(axis[axis.length - 1], window.end);
    assert.equal(new Set(axis).size, range);
    for (let index = 1; index < axis.length; index += 1) {
      assert.equal(shiftDateKey(axis[index - 1], 1), axis[index]);
    }
  }
});

test("DST transitions do not change the number of calendar days", () => {
  // Europe/Prague spring forward (2026-03-29) and fall back (2026-10-25).
  for (const today of ["2026-03-30", "2026-10-26"]) {
    const axis = dayAxis(statsWindow(today, 30));
    assert.equal(axis.length, 30);
    assert.equal(new Set(axis).size, 30);
    assert.equal(axis[0], shiftDateKey(today, -29));
    assert.equal(axis[axis.length - 1], today);
  }

  const spring = analysisWindows("2026-03-30", 7);
  assert.ok(dayAxis(spring.current).includes("2026-03-29"));
});
