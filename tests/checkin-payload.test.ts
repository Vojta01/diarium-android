import assert from "node:assert/strict";
import test from "node:test";

import { buildCheckInPayload } from "../src/lib/checkin/payload.ts";
import { buildEntryRow } from "../src/lib/entry-row.ts";
import { emptyCheckIn } from "../src/lib/checkin/model.ts";
import { CheckInError } from "../src/lib/checkin/errors.ts";

const DATE = "2026-11-07";
const USER = "user-1";
const PHOTO = "https://example.supabase.co/storage/v1/object/public/diary-photos/u/2026-11-07.jpg";

function filled() {
  return {
    ...emptyCheckIn(),
    mood: 4,
    moodEmoji: "🙂",
    sleepQuality: 3,
    stress: 2,
    activities: ["Sport"],
    habits: { sport: true },
    gratitude: ["rodina", "", "káva"],
    note: "text",
    weather: ["dest"],
    photoPath: PHOTO,
    scaleValues: { energy: 4, focus: 0 },
    phoneScreenTime: 5400,
    phoneUnlocks: 42,
    phoneTopApps: [{ app: "Instagram", time_sec: 1200 }],
    aiReflection: "AI text",
  };
}

test("form payload persists weather and photo, and never sends worker/AI fields", () => {
  const payload = buildCheckInPayload(filled(), DATE);

  assert.deepEqual(payload.weather, ["dest"]);
  assert.equal(payload.photo_path, PHOTO);
  assert.deepEqual(payload.scale_values, { energy: 4, focus: 0 });
  assert.deepEqual(payload.gratitude, ["rodina", "káva"]);
  assert.equal("phone_screen_time" in payload, false);
  assert.equal("phone_unlocks" in payload, false);
  assert.equal("phone_top_apps" in payload, false);
  assert.equal("ai_reflection" in payload, false);
});

test("removing a photo is explicit and never implied by a null path", () => {
  const withoutPhoto = { ...filled(), photoPath: null };
  const kept = buildCheckInPayload(withoutPhoto, DATE);
  assert.equal("photo_path" in kept, false);
  assert.equal("clear_photo" in kept, false);

  const cleared = buildCheckInPayload(withoutPhoto, DATE, { clearPhoto: true });
  assert.equal(cleared.clear_photo, true);
  assert.equal("photo_path" in cleared, false);
});

test("buildEntryRow omits absent fields and preserves an explicit weather clear", () => {
  const patch = buildEntryRow({ user_id: USER, date: DATE });
  assert.deepEqual(patch, { user_id: USER, date: DATE });

  const cleared = buildEntryRow({
    user_id: USER,
    date: DATE,
    weather: [],
  });
  assert.deepEqual(cleared.weather, []);
});

test("sleep_quality 0 never overwrites a stored value", () => {
  const row = buildEntryRow({
    user_id: USER,
    date: DATE,
    sleep_quality: 0,
  });
  assert.equal("sleep_quality" in row, false);
});

test("a null photo_path is ignored unless clear_photo is true", () => {
  const ignored = buildEntryRow({
    user_id: USER,
    date: DATE,
    photo_path: null,
  });
  assert.equal("photo_path" in ignored, false);

  const cleared = buildEntryRow({
    user_id: USER,
    date: DATE,
    photo_path: null,
    clear_photo: true,
  });
  assert.equal(cleared.photo_path, null);
});

test("base64 photo values are rejected, not written to the row", () => {
  assert.throws(
    () =>
      buildEntryRow({
        user_id: USER,
        date: DATE,
        photo_path: "data:image/jpeg;base64,AAAA",
      }),
    (error: unknown) =>
      error instanceof CheckInError &&
      error.code === "checkin.error_save_validation"
  );
});

test("clear_photo cannot be combined with a new photo", () => {
  assert.throws(
    () =>
      buildEntryRow({
        user_id: USER,
        date: DATE,
        photo_path: PHOTO,
        clear_photo: true,
      }),
    (error: unknown) =>
      error instanceof CheckInError &&
      error.code === "checkin.error_save_validation"
  );
});

test("an AI patch touches only ai_reflection", () => {
  const row = buildEntryRow({
    user_id: USER,
    date: DATE,
    ai_reflection: "Shrnutí.",
  });
  assert.deepEqual(row, {
    user_id: USER,
    date: DATE,
    ai_reflection: "Shrnutí.",
  });
});

test("an empty AI reflection never erases the stored one", () => {
  const row = buildEntryRow({
    user_id: USER,
    date: DATE,
    ai_reflection: "   ",
  });
  assert.equal("ai_reflection" in row, false);
});

test("scale zeros stay in the row so the route can delete mirror rows", () => {
  const row = buildEntryRow({
    user_id: USER,
    date: DATE,
    scale_values: { energy: 4, focus: 0 },
  });
  assert.deepEqual(row.scale_values, { energy: 4, focus: 0 });
});
