import assert from "node:assert/strict";
import test from "node:test";

import { PRAGUE_TIME_ZONE, pragueDateKey, pragueYear, shiftDateKey, shiftMonthKey } from "../src/lib/date.ts";

test("uses the canonical Europe/Prague time zone", () => {
  assert.equal(PRAGUE_TIME_ZONE, "Europe/Prague");
});

test("returns the Prague calendar date after midnight, not the UTC date", () => {
  assert.equal(pragueDateKey(new Date("2026-11-06T23:30:00Z")), "2026-11-07");
  assert.equal(pragueDateKey(new Date("2026-11-06T22:59:59Z")), "2026-11-06");
  assert.equal(pragueDateKey(new Date("2026-07-01T22:30:00Z")), "2026-07-02");
});

test("handles the spring DST transition", () => {
  // 2026-03-29 02:00 CET jumps to 03:00 CEST.
  assert.equal(pragueDateKey(new Date("2026-03-29T00:30:00Z")), "2026-03-29");
  assert.equal(pragueDateKey(new Date("2026-03-29T22:30:00Z")), "2026-03-30");
});

test("handles the autumn DST transition", () => {
  // 2026-10-25 03:00 CEST falls back to 02:00 CET.
  assert.equal(pragueDateKey(new Date("2026-10-25T00:30:00Z")), "2026-10-25");
  assert.equal(pragueDateKey(new Date("2026-10-25T23:30:00Z")), "2026-10-26");
});

test("formats the key as zero-padded YYYY-MM-DD", () => {
  assert.match(pragueDateKey(new Date("2026-01-05T12:00:00Z")), /^\d{4}-\d{2}-\d{2}$/);
  assert.equal(pragueDateKey(new Date("2026-01-05T12:00:00Z")), "2026-01-05");
});

test("returns the Prague year across New Year midnight", () => {
  // 2025-12-31 23:59:59 Prague is still 2025; 00:30 on 1 January is 2026.
  assert.equal(pragueYear(new Date("2025-12-31T22:59:59Z")), 2025);
  assert.equal(pragueYear(new Date("2025-12-31T23:30:00Z")), 2026);
  assert.equal(pragueYear(new Date("2026-07-01T12:00:00Z")), 2026);
});

test("shifts date keys across DST without landing on the wrong day", () => {
  assert.equal(shiftDateKey("2026-03-29", -1), "2026-03-28");
  assert.equal(shiftDateKey("2026-03-29", 1), "2026-03-30");
  assert.equal(shiftDateKey("2026-10-25", -1), "2026-10-24");
  assert.equal(shiftDateKey("2026-10-25", 1), "2026-10-26");
  assert.equal(shiftDateKey("2026-01-01", -1), "2025-12-31");
  assert.equal(shiftDateKey("2026-12-31", 1), "2027-01-01");
});

test("shifts month keys without a DST or midnight off-by-one", () => {
  assert.equal(shiftMonthKey("2026-11-07", -1), "2026-10-07");
  assert.equal(shiftMonthKey("2026-01-01", -1), "2025-12-01");
  assert.equal(shiftMonthKey("2026-03-15", -1), "2026-02-15");
  assert.equal(shiftMonthKey("2026-10-25", -1), "2026-09-25");
});

test("clamps month-end keys to the target month's last day", () => {
  assert.equal(shiftMonthKey("2026-03-31", -1), "2026-02-28");
  assert.equal(shiftMonthKey("2028-03-31", -1), "2028-02-29");
  assert.equal(shiftMonthKey("2026-01-31", -1), "2025-12-31");
});
