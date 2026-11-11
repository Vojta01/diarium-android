import assert from "node:assert/strict";
import test from "node:test";

import {
  createDraftWriter,
  DRAFT_PREFIX,
  draftKey,
  readDraft,
  readDraftWithStatus,
  type DraftStorage,
} from "../src/lib/checkin/draft.ts";
import { emptyCheckIn } from "../src/lib/checkin/model.ts";
import { TestDraftLocks, testDraftLockResolver } from "./draft-lock-test-utils.ts";

const DATE = "2026-11-06";
const ACCOUNT_A = "account-a";
const ACCOUNT_B = "account-b";
const STAGED_PHOTO =
  "https://project.supabase.co/storage/v1/object/public/diary-photos/user-1/2026-11-06-upload-a.jpg";
const resolveLocks = testDraftLockResolver(new TestDraftLocks());

function memoryStorage(): DraftStorage & { dump(): Map<string, string> } {
  const map = new Map<string, string>();
  return {
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => { map.set(key, value); },
    removeItem: (key) => { map.delete(key); },
    dump: () => map,
  };
}

function makeWriter(storage: DraftStorage | null, owner: string, ...dates: string[]) {
  const writer = createDraftWriter(() => storage, owner, resolveLocks);
  for (const date of dates) {
    writer.observe(date, readDraftWithStatus(storage, date, owner));
  }
  return writer;
}

function filled() {
  const data = emptyCheckIn();
  data.mood = 4;
  data.note = "Dnes jsem běhal.";
  return data;
}

test("a successful save clears the draft and beats a stale unmount flush", async () => {
  const storage = memoryStorage();
  const writer = makeWriter(storage, ACCOUNT_A, DATE);
  const data = filled();
  const staleUnmountFlush = () => writer.flush(DATE, data);

  assert.equal(await staleUnmountFlush(), true);
  assert.ok(storage.getItem(draftKey(DATE, ACCOUNT_A)));
  const revision = writer.revision(DATE);
  assert.equal((await writer.markSaved(DATE, revision, data)).status, "cleared");
  assert.equal(readDraft(storage, DATE, ACCOUNT_A), null);

  // The cleanup that runs as `saved` flips to true still closes over the
  // pre-save render; it must not recreate the draft it just cleared.
  assert.equal(await staleUnmountFlush(), false);
  assert.equal(storage.getItem(draftKey(DATE, ACCOUNT_A)), null);
  assert.equal(readDraft(storage, DATE, ACCOUNT_A), null);
});

test("editing after a save resumes draft writes for the new content", async () => {
  const storage = memoryStorage();
  const writer = makeWriter(storage, ACCOUNT_A, DATE);
  const original = filled();

  assert.equal(await writer.flush(DATE, original), true);
  assert.equal((await writer.markSaved(DATE, writer.revision(DATE), original)).status, "cleared");
  assert.equal(storage.getItem(draftKey(DATE, ACCOUNT_A)), null);

  writer.markEdited();
  const edited = filled();
  edited.note = "Později jsem četl.";
  assert.equal(await writer.flush(DATE, edited), true);

  assert.equal(readDraft(storage, DATE, ACCOUNT_A)?.note, "Později jsem četl.");
});

test("markSaved clears only its own day and keeps other drafts", async () => {
  const storage = memoryStorage();
  const other = "2026-11-05";
  const writer = makeWriter(storage, ACCOUNT_A, DATE, other);
  const first = filled();
  const second = filled();
  second.note = "other date";

  assert.equal(await writer.flush(DATE, first), true);
  assert.equal(await writer.flush(other, second), true);
  assert.equal((await writer.markSaved(DATE, writer.revision(DATE), first)).status, "cleared");

  assert.equal(storage.getItem(draftKey(DATE, ACCOUNT_A)), null);
  assert.ok(storage.getItem(draftKey(other, ACCOUNT_A)));
});

test("navigating away keeps the saved day suppressed but drafts the new day", async () => {
  const storage = memoryStorage();
  const other = "2026-11-05";
  const writer = makeWriter(storage, ACCOUNT_A, DATE, other);
  const saved = filled();

  assert.equal(await writer.flush(DATE, saved), true);
  assert.equal((await writer.markSaved(DATE, writer.revision(DATE), saved)).status, "cleared");

  // The old day's cleanup must not recreate its draft; a different day may draft.
  assert.equal(await writer.flush(DATE, saved), false);
  assert.equal(readDraft(storage, DATE, ACCOUNT_A), null);
  assert.equal(await writer.flush(other, filled()), true);
  assert.ok(storage.getItem(draftKey(other, ACCOUNT_A)));
});

test("a staged photo URL is persisted with a local draft for safe recovery", async () => {
  const storage = memoryStorage();
  const writer = makeWriter(storage, ACCOUNT_A, DATE);
  const data = filled();
  data.photoPath = STAGED_PHOTO;
  data.photoCleared = false;

  assert.equal(await writer.flush(DATE, data), true);
  const restored = readDraft(storage, DATE, ACCOUNT_A);
  assert.equal(restored?.note, data.note);
  assert.equal(restored?.photoPath, STAGED_PHOTO);
  assert.equal(JSON.parse(storage.getItem(draftKey(DATE, ACCOUNT_A))!).photoPath, STAGED_PHOTO);
});

test("a clear-photo edit persists its explicit intent even with no other fields", async () => {
  const storage = memoryStorage();
  const writer = makeWriter(storage, ACCOUNT_A, DATE);
  const data = emptyCheckIn();
  data.photoCleared = true;

  assert.equal(await writer.flush(DATE, data), true);
  assert.equal(readDraft(storage, DATE, ACCOUNT_A)?.photoCleared, true);
});

test("an empty form removes the draft instead of storing a blank one", async () => {
  const storage = memoryStorage();
  const writer = makeWriter(storage, ACCOUNT_A, DATE);

  assert.equal(await writer.flush(DATE, filled()), true);
  assert.ok(storage.getItem(draftKey(DATE, ACCOUNT_A)));

  assert.equal(await writer.flush(DATE, emptyCheckIn()), true);
  assert.equal(storage.getItem(draftKey(DATE, ACCOUNT_A)), null);
});

test("an untouched local-only form preserves drafts until the user edits", async () => {
  const storage = memoryStorage();
  const writer = makeWriter(storage, ACCOUNT_A, DATE);
  const existing = filled();
  existing.note = "saved local draft";
  assert.equal(await writer.flush(DATE, existing), true);
  const original = storage.getItem(draftKey(DATE, ACCOUNT_A));

  writer.markUnedited();
  assert.equal(await writer.flush(DATE, emptyCheckIn()), false);
  assert.equal(storage.getItem(draftKey(DATE, ACCOUNT_A)), original);
  assert.equal(await writer.flush(DATE, filled()), false);
  assert.equal(readDraft(storage, DATE, ACCOUNT_A)?.note, "saved local draft");

  writer.markEdited();
  const edited = filled();
  edited.note = "edited while offline";
  assert.equal(await writer.flush(DATE, edited), true);
  assert.equal(readDraft(storage, DATE, ACCOUNT_A)?.note, "edited while offline");
});

test("a quota failure is reported, not thrown, and saving stays possible", async () => {
  const writer = makeWriter({
    getItem: () => null,
    setItem: () => { throw new Error("QuotaExceededError"); },
    removeItem: () => {},
  }, ACCOUNT_A, DATE);

  assert.equal(await writer.flush(DATE, filled()), false);
  // The save path itself is independent of draft storage.
  assert.equal((await writer.markSaved(DATE, null, filled())).status, "missing");
  assert.equal(await writer.flush(DATE, filled()), false);
});

test("no storage (SSR) is a silent skip", async () => {
  const writer = makeWriter(null, ACCOUNT_A, DATE);
  assert.equal(await writer.flush(DATE, filled()), false);
  assert.equal((await writer.markSaved(DATE, null, filled())).status, "unavailable");
  assert.equal(await writer.flush(DATE, filled()), false);
});

test("drafts are isolated when the active account changes", async () => {
  const storage = memoryStorage();
  const accountA = makeWriter(storage, ACCOUNT_A, DATE);
  const accountB = makeWriter(storage, ACCOUNT_B, DATE);
  const accountADraft = filled();
  accountADraft.note = "account A only";

  assert.equal(await accountA.flush(DATE, accountADraft), true);
  assert.equal(readDraft(storage, DATE, ACCOUNT_B), null);
  assert.equal(readDraft(storage, DATE, ACCOUNT_A)?.note, "account A only");

  const accountBDraft = filled();
  accountBDraft.note = "account B only";
  assert.equal(await accountB.flush(DATE, accountBDraft), true);
  assert.equal(readDraft(storage, DATE, ACCOUNT_A)?.note, "account A only");
  assert.equal(readDraft(storage, DATE, ACCOUNT_B)?.note, "account B only");
});

test("ownerless legacy drafts stay untouched and are not restored into an account", async () => {
  const storage = memoryStorage();
  const legacyKey = `${DRAFT_PREFIX}${DATE}`;
  const legacyValue = JSON.stringify({ mood: 4, note: "ownerless legacy draft" });
  storage.setItem(legacyKey, legacyValue);
  const accountA = makeWriter(storage, ACCOUNT_A, DATE);

  assert.equal(readDraft(storage, DATE, ACCOUNT_A), null);
  assert.equal((await accountA.markSaved(DATE, null, filled())).status, "missing");
  assert.equal(storage.getItem(legacyKey), legacyValue);
});
