import assert from "node:assert/strict";
import test from "node:test";

import { tCrit975, welchDiff } from "../src/lib/analysis/uncertainty.ts";

function close(actual: number | null, expected: number, label: string): void {
  assert.ok(actual !== null, `${label} must not be null`);
  assert.ok(
    Math.abs(actual - expected) < 1e-6,
    `${label}: expected ${expected}, got ${actual}`
  );
}

test("F1: overlapping groups stay not_clear", () => {
  const estimate = welchDiff([5, 5, 4, 4, 3], [5, 4, 3, 3, 2], 3);

  close(estimate.meanA, 4.2, "meanA");
  close(estimate.meanB, 3.4, "meanB");
  close(estimate.diff, 0.8, "diff");
  close(estimate.se, 0.6324555, "se");
  close(estimate.df, 7.33945, "df");
  close(estimate.ciLow, -0.6957573, "ciLow");
  close(estimate.ciHigh, 2.2957573, "ciHigh");
  assert.equal(estimate.verdict, "not_clear");
  assert.equal(estimate.degenerate, false);
});

test("F2: clearly separated groups are higher with a CI above zero", () => {
  const estimate = welchDiff([5, 5, 5, 4, 4], [3, 3, 4, 3, 3], 3);

  close(estimate.meanA, 4.6, "meanA");
  close(estimate.meanB, 3.2, "meanB");
  close(estimate.diff, 1.4, "diff");
  close(estimate.se, 0.3162278, "se");
  close(estimate.df, 7.6923077, "df");
  close(estimate.ciLow, 0.6521213, "ciLow");
  close(estimate.ciHigh, 2.1478787, "ciHigh");
  assert.equal(estimate.verdict, "higher");
});

test("t critical values are the table values, not a normal approximation", () => {
  assert.equal(tCrit975(1), 12.706);
  assert.equal(tCrit975(2), 4.303);
  assert.equal(tCrit975(5), 2.571);
  assert.equal(tCrit975(7.9), 2.365);
  assert.equal(tCrit975(10), 2.228);
  assert.equal(tCrit975(20), 2.086);
  assert.equal(tCrit975(30), 2.042);
  assert.equal(tCrit975(31), 1.96);
  assert.equal(tCrit975(100), 1.96);
});

test("small samples return means but no invented CI", () => {
  const estimate = welchDiff([5, 4], [3, 2], 5);

  assert.equal(estimate.nA, 2);
  assert.equal(estimate.nB, 2);
  assert.equal(estimate.meanA, 4.5);
  assert.equal(estimate.meanB, 2.5);
  assert.equal(estimate.diff, 2);
  assert.equal(estimate.se, null);
  assert.equal(estimate.ciLow, null);
  assert.equal(estimate.ciHigh, null);
  assert.equal(estimate.verdict, "insufficient");
});

test("one empty side has no difference and no CI", () => {
  const estimate = welchDiff([], [3, 4, 5], 3);
  assert.equal(estimate.meanA, null);
  assert.equal(estimate.meanB, 4);
  assert.equal(estimate.diff, null);
  assert.equal(estimate.ciLow, null);
  assert.equal(estimate.verdict, "insufficient");
});

test("zero variance is degenerate: verdict kept, CI withheld", () => {
  const different = welchDiff(
    Array.from({ length: 10 }, () => 5),
    Array.from({ length: 10 }, () => 3),
    5
  );
  assert.equal(different.se, 0);
  assert.equal(different.degenerate, true);
  assert.equal(different.verdict, "higher");
  assert.equal(different.ciLow, null);
  assert.equal(different.ciHigh, null);

  const same = welchDiff(
    Array.from({ length: 10 }, () => 4),
    Array.from({ length: 10 }, () => 4),
    5
  );
  assert.equal(same.degenerate, true);
  assert.equal(same.verdict, "not_clear");
  assert.equal(same.ciHigh, null);
});

test("CI spanning zero is not_clear even with a positive point estimate", () => {
  const estimate = welchDiff([1, 5, 1, 5, 3], [1, 5, 1, 4, 4], 3);
  assert.equal(estimate.verdict, "not_clear");
  assert.ok((estimate.ciLow as number) < 0);
  assert.ok((estimate.ciHigh as number) > 0);
});
