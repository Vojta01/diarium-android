import assert from "node:assert/strict";
import test from "node:test";

import {
  bandRect,
  segmentRuns,
  xAt,
  yAt,
  type PlotPoint,
} from "../src/lib/analysis/geometry.ts";

function point(index: number): PlotPoint {
  return { index, value: 3, x: index, y: 0 };
}

test("segmentRuns splits on a missing day", () => {
  const runs = segmentRuns([point(0), point(1), point(3), point(4), point(7)]);
  assert.deepEqual(
    runs.map((run) => run.map((item) => item.index)),
    [
      [0, 1],
      [3, 4],
      [7],
    ]
  );
});

test("segmentRuns keeps a single point as one run", () => {
  const runs = segmentRuns([point(5)]);
  assert.equal(runs.length, 1);
  assert.equal(runs[0].length, 1);
  assert.deepEqual(segmentRuns([]), []);
});

test("xAt is monotonic and hits both padded endpoints exactly", () => {
  const count = 30;
  const first = xAt(0, count, 320, 22, 10);
  const last = xAt(count - 1, count, 320, 22, 10);
  assert.equal(first, 22);
  assert.equal(last, 310);

  let previous = first;
  for (let index = 1; index < count; index += 1) {
    const value = xAt(index, count, 320, 22, 10);
    assert.ok(value > previous);
    previous = value;
  }

  assert.equal(xAt(0, 1, 320, 22, 10), 22 + (320 - 32) / 2);
});

test("yAt inverts ratings: 5 is top, 1 is bottom", () => {
  assert.equal(yAt(5, 1, 5, 40, 6, 6), 6);
  assert.equal(yAt(1, 1, 5, 40, 6, 6), 34);
  assert.ok(yAt(4, 1, 5, 40, 6, 6) < yAt(2, 1, 5, 40, 6, 6));
  assert.equal(yAt(3, 3, 3, 40, 6, 6), 20);
});

test("bandRect collapses to zero height for a constant baseline", () => {
  assert.deepEqual(bandRect(3, 3, 1, 5, 40, 6, 6), { y: 20, height: 0 });
  const band = bandRect(2, 4, 1, 5, 40, 6, 6);
  assert.ok(band.height > 0);
  assert.ok(band.y < 20);
});
