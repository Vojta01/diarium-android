import assert from "node:assert/strict";
import test from "node:test";

import {
  CHECKIN_RENDERED_SECTIONS,
  CHECKIN_SECTION_ORDER,
  emptyCheckIn,
  hasContent,
} from "../src/lib/checkin/model.ts";
import { toggleWeatherKey, WEATHER_OPTIONS } from "../src/lib/checkin/options.ts";
import { templateChange } from "../src/lib/checkin/note.ts";
import { buildCheckInPayload } from "../src/lib/checkin/payload.ts";

test("section order matches Android beta5 and renders all thirteen", () => {
  assert.deepEqual(CHECKIN_SECTION_ORDER, [
    "mood",
    "sleep",
    "stress",
    "activities",
    "habits",
    "gratitude",
    "weather",
    "photo",
    "scales",
    "note",
    "goals",
    "screenTime",
    "reflection",
  ]);
  assert.equal(CHECKIN_SECTION_ORDER.length, 13);
  // Phase 2B renders every section; the read-only tail is exactly goals →
  // screen time → reflection, after note.
  assert.deepEqual(CHECKIN_RENDERED_SECTIONS, CHECKIN_SECTION_ORDER);
  assert.deepEqual(CHECKIN_SECTION_ORDER.slice(-3), [
    "goals",
    "screenTime",
    "reflection",
  ]);
});

test("a template replaces the note instead of appending to it", () => {
  const written = "Dnes jsem běhal a četl.";

  const overEmpty = templateChange("", "Ráno: ___\nVečer: ___");
  assert.equal(overEmpty.needsConfirm, false);
  assert.equal(overEmpty.next, "Ráno: ___\nVečer: ___");

  const overWritten = templateChange(written, "Ráno: ___");
  assert.equal(overWritten.needsConfirm, true);
  assert.equal(overWritten.next, "Ráno: ___");
  assert.ok(!overWritten.next.includes(written));

  // Whitespace is not content, so no confirmation is needed.
  assert.equal(templateChange("   \n ", "Šablona").needsConfirm, false);
  // Re-picking the same text is a no-op, not a prompt.
  assert.equal(templateChange("Šablona", "Šablona").needsConfirm, false);
});

test("weather is a multi-select over canonical keys in catalogue order", () => {
  const first = WEATHER_OPTIONS[0].key;
  const second = WEATHER_OPTIONS[1].key;

  assert.deepEqual(toggleWeatherKey([], first), [first]);
  assert.deepEqual(toggleWeatherKey([second], first), [first, second]);
  assert.deepEqual(toggleWeatherKey([first, second], first), [second]);
  // Toggling twice restores the original set and never duplicates.
  assert.deepEqual(
    toggleWeatherKey(toggleWeatherKey([], first), first),
    []
  );
  // Unknown legacy values survive instead of being silently dropped.
  assert.deepEqual(toggleWeatherKey(["legacy_key"], first), [first, "legacy_key"]);
});

test("weather alone makes an otherwise empty day saveable", () => {
  const data = emptyCheckIn();
  assert.equal(hasContent(data), false);
  data.weather = [WEATHER_OPTIONS[0].key];
  assert.equal(hasContent(data), true);
  assert.deepEqual(
    buildCheckInPayload(data, "2026-03-04").weather,
    [WEATHER_OPTIONS[0].key]
  );
});

test("photo removal clears the column, a legacy photo is left untouched", () => {
  const data = emptyCheckIn();
  data.mood = 3;
  data.photoPath = "data:image/jpeg;base64,AAAA";

  const kept = buildCheckInPayload(data, "2026-03-04");
  assert.equal("clear_photo" in kept, false);
  assert.equal("photo_path" in kept, false);

  const cleared = buildCheckInPayload(data, "2026-03-04", { clearPhoto: true });
  assert.equal(cleared.clear_photo, true);

  data.photoPath = "https://cdn.example.com/checkin/2026-03-04.jpg";
  const replaced = buildCheckInPayload(data, "2026-03-04");
  assert.equal(
    replaced.photo_path,
    "https://cdn.example.com/checkin/2026-03-04.jpg"
  );
  assert.equal("clear_photo" in replaced, false);
});
