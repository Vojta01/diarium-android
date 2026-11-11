import assert from "node:assert/strict";
import test from "node:test";

import {
  emptyCheckIn,
  fromDraft,
  fromEntry,
  hasContent,
  normalizeWeather,
  positiveScaleValues,
} from "../src/lib/checkin/model.ts";

test("fromEntry maps every stored field without inventing values", () => {
  const data = fromEntry({
    mood: 4,
    mood_emoji: "🙂",
    sleep_quality: 3,
    stress: 2,
    activities: ["Sport", "Práce"],
    habits: { sport: true, reading: false },
    gratitude: ["rodina", "", "káva"],
    note: "dobrý den",
    weather: ["dest", "sunny"],
    photo_path: "https://example.supabase.co/storage/v1/object/public/diary-photos/u/2026-11-07.jpg",
    scale_values: { energy: 4, focus: 0 },
    phone_screen_time: 5400,
    phone_unlocks: 42,
    phone_top_apps: [{ app: "Instagram", time_sec: 1200 }],
    ai_reflection: "Shrnutí dne.",
  });

  assert.equal(data.mood, 4);
  assert.equal(data.sleepQuality, 3);
  assert.equal(data.stress, 2);
  assert.deepEqual(data.activities, ["Sport", "Práce"]);
  assert.deepEqual(data.habits, { sport: true, reading: false });
  assert.deepEqual(data.gratitude, ["rodina", "", "káva"]);
  assert.equal(data.note, "dobrý den");
  assert.deepEqual(data.weather, ["dest", "slunecno"]);
  assert.match(data.photoPath ?? "", /^https:\/\//);
  assert.deepEqual(data.scaleValues, { energy: 4, focus: 0 });
  assert.equal(data.phoneScreenTime, 5400);
  assert.equal(data.phoneUnlocks, 42);
  assert.deepEqual(data.phoneTopApps, [{ app: "Instagram", time_sec: 1200 }]);
  assert.equal(data.aiReflection, "Shrnutí dne.");
  assert.equal(data.photoLegacy, false);
});

test("a legacy data: photo is never treated as a public path", () => {
  const data = fromEntry({ mood: 3, photo_path: "data:image/jpeg;base64,AAAA" });
  assert.equal(data.photoPath, null);
  assert.equal(data.photoLegacy, true);
});

test("missing mood stays unanswered instead of becoming the neutral 3", () => {
  const data = fromEntry({ note: "jen poznámka" });
  assert.equal(data.mood, 0);
  assert.equal(data.sleepQuality, 0);
  assert.equal(data.stress, 0);
  assert.deepEqual(data.gratitude, ["", "", ""]);
});

test("weather normalises known labels and keeps unknown legacy values", () => {
  assert.deepEqual(normalizeWeather(["Rain", "sníh", "custom"]), [
    "dest",
    "snih",
    "custom",
  ]);
  assert.deepEqual(normalizeWeather("dest"), []);
});

test("fromDraft drops base64 and read-only worker fields", () => {
  const data = fromDraft({
    mood: 5,
    photoDataUrl: "data:image/png;base64,AAAA",
    phoneScreenTime: 999,
    aiReflection: "stale",
    weather: ["bourka"],
    gratitude: ["a"],
  });

  assert.equal(data.photoPath, null);
  assert.equal(data.photoLegacy, true);
  assert.equal(data.phoneScreenTime, null);
  assert.equal(data.aiReflection, null);
  assert.deepEqual(data.weather, ["bourka"]);
  assert.deepEqual(data.gratitude, ["a", "", ""]);
});

test("hasContent counts only fields the form owns", () => {
  const data = emptyCheckIn();
  assert.equal(hasContent(data), false);
  assert.equal(hasContent({ ...data, weather: ["dest"] }), true);
  assert.equal(hasContent({ ...data, habits: { sport: true } }), true);
  assert.equal(hasContent({ ...data, habits: { sport: false } }), false);
  assert.equal(
    hasContent({
      ...data,
      photoPath: "https://example.test/photo.jpg",
    }),
    true
  );
  assert.equal(
    hasContent({
      ...data,
      phoneScreenTime: 3600,
      aiReflection: "AI text",
    }),
    false
  );
});

test("positiveScaleValues keeps only recorded values", () => {
  assert.deepEqual(positiveScaleValues({ a: 0, b: 2, c: -1 }), { b: 2 });
});
