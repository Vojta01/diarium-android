import assert from "node:assert/strict";
import test from "node:test";

import {
  isMemoryWorthy,
  pickBestMemory,
  targetMemoryDates,
} from "../src/lib/on-this-day.ts";
import { entry } from "./dashboard-fixtures.ts";

const TODAY = "2026-11-07";

test("memory candidates are one year, six months and one month back", () => {
  assert.deepEqual(targetMemoryDates(TODAY), [
    { horizon: "one_year", date: "2025-11-07" },
    { horizon: "six_months", date: "2026-05-07" },
    { horizon: "one_month", date: "2026-10-07" },
  ]);
});

test("month-end lookbacks clamp instead of rolling into the next month", () => {
  assert.deepEqual(targetMemoryDates("2026-03-31"), [
    { horizon: "one_year", date: "2025-03-31" },
    { horizon: "six_months", date: "2025-09-30" },
    { horizon: "one_month", date: "2026-02-28" },
  ]);
});

test("a leap day clamps a year back", () => {
  assert.deepEqual(targetMemoryDates("2028-02-29"), [
    { horizon: "one_year", date: "2027-02-28" },
    { horizon: "six_months", date: "2027-08-29" },
    { horizon: "one_month", date: "2028-01-29" },
  ]);
});

test("the furthest horizon with a worthy entry wins", () => {
  const memory = pickBestMemory(TODAY, [
    entry("2025-11-07", { mood: 3, note: "před rokem" }),
    entry("2026-05-07", { mood: 4, note: "před půl rokem" }),
    entry("2026-10-07", { mood: 5, note: "před měsícem" }),
  ]);

  assert.equal(memory?.horizon, "one_year");
  assert.equal(memory?.date, "2025-11-07");
  assert.equal(memory?.entry.note, "před rokem");
});

test("a mood-only day is not a memory", () => {
  const memory = pickBestMemory(TODAY, [
    entry("2025-11-07", { mood: 5 }),
    entry("2026-05-07", { mood: 4, gratitude: ["", "   "] }),
    entry("2026-10-07", { mood: 3, note: "měsíc" }),
  ]);

  assert.equal(memory?.horizon, "one_month");
  assert.equal(memory?.date, "2026-10-07");
});

test("a photo or one real gratitude line qualifies; blanks do not", () => {
  assert.equal(isMemoryWorthy(entry("d", { mood: 4, photo_path: "https://x/y.jpg" })), true);
  assert.equal(isMemoryWorthy(entry("d", { mood: 4, gratitude: ["", "kafe"] })), true);
  assert.equal(isMemoryWorthy(entry("d", { mood: 4, gratitude: ["", " "] })), false);
  assert.equal(isMemoryWorthy(entry("d", { mood: 0, note: "text" })), false);
});

test("gratitude-only and photo-only days still become the memory", () => {
  const gratitudeOnly = pickBestMemory(TODAY, [
    entry("2026-10-07", {
      mood: 4,
      gratitude: ["", "kafe", "  ", "klid"],
    }),
  ]);
  assert.equal(gratitudeOnly?.date, "2026-10-07");
  assert.equal(gratitudeOnly?.entry.note, "");

  const photoOnly = pickBestMemory(TODAY, [
    entry("2025-11-07", { mood: 3, photo_path: "https://x/y.jpg" }),
  ]);
  assert.equal(photoOnly?.horizon, "one_year");
  assert.equal(photoOnly?.entry.gratitude.length, 0);
});

test("a synced row without a mood is not a memory", () => {
  const memory = pickBestMemory(TODAY, [
    entry("2025-11-07", {
      mood: 0,
      note: "phone sync only",
      phone_screen_time: 7200,
    }),
  ]);

  assert.equal(memory, null);
});

test("no qualifying memory hides the card", () => {
  assert.equal(pickBestMemory(TODAY, []), null);
  assert.equal(
    pickBestMemory(TODAY, [entry("2026-10-07", { mood: 4 })]),
    null
  );
});

test("duplicate memory dates are last-wins", () => {
  const lastWins = pickBestMemory(TODAY, [
    entry("2025-11-07", { mood: 0, note: "starý řádek" }),
    entry("2025-11-07", { mood: 5, note: "nový řádek" }),
  ]);
  assert.equal(lastWins?.entry.note, "nový řádek");

  const lastIsUnworthy = pickBestMemory(TODAY, [
    entry("2025-11-07", { mood: 5, note: "starý řádek" }),
    entry("2025-11-07", { mood: 0, note: "nový řádek" }),
    entry("2026-05-07", { mood: 4, note: "náhradní" }),
  ]);
  assert.equal(lastIsUnworthy?.horizon, "six_months");
});
