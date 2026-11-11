import assert from "node:assert/strict";
import test from "node:test";

import {
  averageMood,
  moodCounts,
  recentMoodWindow,
} from "../src/lib/mood.ts";

interface TestEntry {
  date: string;
  mood: unknown;
}

function entry(date: string, mood: unknown): TestEntry {
  return { date, mood };
}

const answered = Array.from({ length: 25 }, (_, i) =>
  entry(`2026-01-${String(i + 1).padStart(2, "0")}`, 5)
);
const newestUnanswered = Array.from({ length: 10 }, (_, i) =>
  entry(`2026-02-${String(i + 1).padStart(2, "0")}`, 0)
);

test("newest unanswered rows shrink the answered count instead of pulling older rows", () => {
  const entries = [...answered, ...newestUnanswered];
  assert.equal(entries.length, 35);

  const window = recentMoodWindow(entries);

  // Last 30 entries are answered[5..24] + the 10 unanswered rows. Filtering
  // after slicing leaves 20 answered days; filtering first would leave 25 and
  // silently widen the window to 35 entries.
  assert.equal(window.length, 20);
  assert.deepEqual(
    window.map((e) => e.date),
    answered.slice(5).map((e) => e.date)
  );
  assert.ok(
    !window.some((e) => e.date === answered[0].date),
    "the oldest answered row is outside the 30-entry window and must stay out"
  );
});

test("a fully answered history uses exactly the last 30 entries", () => {
  const entries = Array.from({ length: 35 }, (_, i) =>
    entry(`2026-03-${String(i + 1).padStart(2, "0")}`, 4)
  );

  const window = recentMoodWindow(entries);

  assert.equal(window.length, 30);
  assert.equal(window[0].date, entries[5].date);
  assert.equal(window[29].date, entries[34].date);
});

test("the window is selected before filtering, not after", () => {
  const entries = [entry("d1", 1), entry("d2", 0), entry("d3", 5), entry("d4", 4)];

  assert.deepEqual(
    recentMoodWindow(entries, 3).map((e) => e.date),
    ["d3", "d4"]
  );
});

test("missing moods stay unanswered and never become the neutral 3", () => {
  const entries = [
    entry("d1", 5),
    entry("d2", null),
    entry("d3", undefined),
    entry("d4", 0),
    entry("d5", Number.NaN),
    entry("d6", 4),
  ];

  const window = recentMoodWindow(entries);

  assert.deepEqual(window.map((e) => e.mood), [5, 4]);
  assert.ok(!window.some((e) => e.mood === 3));
  assert.equal(averageMood(window), 4.5);
  assert.deepEqual(moodCounts(window), { 1: 0, 2: 0, 3: 0, 4: 1, 5: 1 });
});

test("a window with no answered day yields no data, not a fabricated average", () => {
  const entries = [entry("d1", 0), entry("d2", null), entry("d3", undefined)];

  assert.deepEqual(recentMoodWindow(entries), []);
  assert.equal(averageMood(recentMoodWindow(entries)), null);
});

test("the helper does not mutate the caller's entries", () => {
  const entries = [entry("d1", 5), entry("d2", 0), entry("d3", 4)];
  const snapshot = JSON.stringify(entries);

  recentMoodWindow(entries);

  assert.equal(JSON.stringify(entries), snapshot);
});
