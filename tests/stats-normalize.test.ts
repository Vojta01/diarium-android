import assert from "node:assert/strict";
import test from "node:test";

import { dedupeDailyEntries, mapDailyEntryRow } from "../src/lib/stats.ts";
import { entry } from "./dashboard-fixtures.ts";

test("the entry mapper keeps every dashboard field", () => {
  const mapped = mapDailyEntryRow({
    date: "2026-11-07",
    mood: 4,
    mood_emoji: "🙂",
    sleep_quality: 3,
    stress: 2,
    activities: ["Chůze", 7],
    habits: { trenink: true, kava: "yes", ne: false },
    gratitude: ["kafe", null, ""],
    note: "dnes",
    weather: ["sun"],
    scale_values: { energie: 4, broken: "x" },
    ai_reflection: "  text reflexe  ",
    phone_screen_time: 3600,
    phone_unlocks: 0,
    phone_top_apps: [
      { app: "A", minutes: 5.5 },
      { app: "B", time_sec: 90 },
      { app: "", time_sec: 1 },
      { app: "C", time_sec: -5 },
      { nope: true },
    ],
    photo_path: "https://example.test/photo.jpg",
    created_at: "2026-11-07T21:15:00Z",
  });

  assert.equal(mapped.date, "2026-11-07");
  assert.equal(mapped.mood, 4);
  assert.equal(mapped.moodEmoji, "🙂");
  assert.equal(mapped.sleep_quality, 3);
  assert.equal(mapped.stress, 2);
  assert.deepEqual(mapped.activities, ["Chůze"]);
  assert.deepEqual(mapped.habits, { trenink: true, ne: false });
  assert.deepEqual(mapped.gratitude, ["kafe", ""]);
  assert.equal(mapped.note, "dnes");
  assert.deepEqual(mapped.weather, ["sun"]);
  assert.deepEqual(mapped.scale_values, { energie: 4 });
  assert.equal(mapped.ai_reflection, "  text reflexe  ");
  assert.equal(mapped.phone_screen_time, 3600);
  assert.equal(mapped.phone_unlocks, 0);
  assert.deepEqual(mapped.phone_top_apps, [
    { app: "A", time_sec: 330 },
    { app: "B", time_sec: 90 },
  ]);
  assert.equal(mapped.photo_path, "https://example.test/photo.jpg");
  assert.equal(mapped.created_at, "2026-11-07T21:15:00Z");
});

test("missing values stay null, not zero", () => {
  const mapped = mapDailyEntryRow({
    date: "2026-11-07",
    mood: null,
    sleep_quality: null,
    stress: undefined,
    ai_reflection: "   ",
    photo_path: "",
    phone_screen_time: null,
    phone_unlocks: undefined,
    phone_top_apps: [],
  });

  assert.equal(mapped.mood, 0);
  assert.equal(mapped.sleep_quality, null);
  assert.equal(mapped.stress, null);
  assert.equal(mapped.ai_reflection, null);
  assert.equal(mapped.photo_path, null);
  assert.equal(mapped.phone_screen_time, null);
  assert.equal(mapped.phone_unlocks, null);
  assert.equal(mapped.phone_top_apps, undefined);
});

test("duplicate dates are last-wins and the result is oldest first", () => {
  const deduped = dedupeDailyEntries([
    entry("2026-11-07", { mood: 4 }),
    entry("2026-11-06", { mood: 5 }),
    entry("2026-11-06", { mood: 0 }),
  ]);

  assert.deepEqual(
    deduped.map((item) => [item.date, item.mood]),
    [
      ["2026-11-06", 0],
      ["2026-11-07", 4],
    ]
  );
});

test("habits tolerate a JSON-encoded object, exactly like Android", () => {
  const mapped = mapDailyEntryRow({
    date: "2026-11-07",
    habits: '{"trenink":true,"kava":false,"broken":"yes"}',
  });
  assert.deepEqual(mapped.habits, { trenink: true, kava: false });

  const malformed = mapDailyEntryRow({
    date: "2026-11-07",
    habits: "{not json",
  });
  assert.deepEqual(malformed.habits, {});
});

test("dedupe picks the real timestamp over a null created_at, whatever the order", () => {
  const legacy = entry("2026-11-06", { mood: 0, created_at: null });
  const timed = entry("2026-11-06", {
    mood: 5,
    created_at: "2026-11-06T20:00:00Z",
  });

  for (const input of [
    [legacy, timed],
    [timed, legacy],
  ]) {
    const deduped = dedupeDailyEntries(input);
    assert.equal(deduped.length, 1);
    assert.equal(deduped[0].mood, 5);
  }
});

test("two null created_at duplicates have a stable input-order winner", () => {
  const first = entry("2026-11-06", { mood: 1, created_at: null });
  const second = entry("2026-11-06", { mood: 4, created_at: null });

  assert.equal(dedupeDailyEntries([first, second])[0].mood, 4);
  assert.equal(dedupeDailyEntries([second, first])[0].mood, 1);
});
