import assert from "node:assert/strict";
import test from "node:test";

import {
  PHOTO_MAX_INPUT_BYTES,
  isLegacyPhotoValue,
  isStoredPhotoUrl,
  photoStoragePath,
  validatePhotoFile,
} from "../src/lib/checkin/photo.ts";

test("allowed image types pass and other types are rejected", () => {
  assert.equal(
    validatePhotoFile({ type: "image/jpeg", size: 1024 }),
    null
  );
  assert.equal(
    validatePhotoFile({ type: "image/png", size: 1024 }),
    null
  );
  assert.equal(
    validatePhotoFile({ type: "image/webp", size: 1024 }),
    null
  );
  assert.equal(
    validatePhotoFile({ type: "image/gif", size: 1024 }),
    "checkin.photo_error_type"
  );
  assert.equal(
    validatePhotoFile({ type: "application/pdf", size: 1024 }),
    "checkin.photo_error_type"
  );
});

test("the input size guard is 12 MB and an empty file is a read error", () => {
  assert.equal(
    validatePhotoFile({ type: "image/jpeg", size: PHOTO_MAX_INPUT_BYTES }),
    null
  );
  assert.equal(
    validatePhotoFile({
      type: "image/jpeg",
      size: PHOTO_MAX_INPUT_BYTES + 1,
    }),
    "checkin.photo_error_size"
  );
  assert.equal(
    validatePhotoFile({ type: "image/jpeg", size: 0 }),
    "checkin.photo_error_read"
  );
});

test("only a public https URL is a stored photo", () => {
  assert.equal(
    isStoredPhotoUrl("https://example.supabase.co/storage/v1/object/public/x.jpg"),
    true
  );
  assert.equal(isStoredPhotoUrl("http://example.test/x.jpg"), false);
  assert.equal(isStoredPhotoUrl("data:image/jpeg;base64,AAAA"), false);
  assert.equal(isStoredPhotoUrl(""), false);
  assert.equal(isStoredPhotoUrl(null), false);
});

test("legacy base64 is recognised but never accepted as a path", () => {
  assert.equal(isLegacyPhotoValue("data:image/png;base64,AAAA"), true);
  assert.equal(isLegacyPhotoValue("https://example.test/x.jpg"), false);
  assert.equal(isStoredPhotoUrl("data:image/png;base64,AAAA"), false);
});

test("storage path is one deterministic object per user and day", () => {
  assert.equal(
    photoStoragePath("user-1", "2026-11-07"),
    "user-1/2026-11-07.jpg"
  );
  assert.throws(() => photoStoragePath("", "2026-11-07"));
  assert.throws(() => photoStoragePath("user-1", "07/11/2026"));
});
