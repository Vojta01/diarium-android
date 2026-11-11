import assert from "node:assert/strict";
import test from "node:test";

import {
  createDraftWriter,
  draftLockKey,
  draftKey,
  readDraft,
  readDraftWithStatus,
  type DraftStorage,
} from "../src/lib/checkin/draft.ts";
import { emptyCheckIn } from "../src/lib/checkin/model.ts";
import {
  clearPendingSave,
  createPendingSaveRequest,
  releaseRejectedPendingSave,
  writePendingSave,
  readPendingSave,
} from "../src/lib/checkin/pending-save.ts";
import { restorePendingSaveData } from "../src/lib/checkin/save-recovery.ts";
import { recoverOnlineLoad, resolveDraftConflict } from "../src/lib/checkin/draft-recovery.ts";
import { TestDraftLocks, testDraftLockResolver } from "./draft-lock-test-utils.ts";

const OWNER = "11111111-1111-4111-8111-111111111111";
const OTHER_OWNER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const DATE = "2026-11-06";
const OTHER_DATE = "2026-11-05";
const ROW_ID = "22222222-2222-4222-8222-222222222222";
const REQUEST_ID = "55555555-5555-4555-8555-555555555555";
const STAGED_PHOTO =
  "https://project.supabase.co/storage/v1/object/public/diary-photos/11111111-1111-4111-8111-111111111111/2026-11-06-upload-a.jpg";

function memoryStorage(): DraftStorage {
  const values = new Map<string, string>();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
  };
}

function draft(note: string, photoPath: string | null = null) {
  return { ...emptyCheckIn(), mood: 4, note, photoPath };
}

function requestFor(data: ReturnType<typeof draft>, draftRevision: number | null) {
  return createPendingSaveRequest({
    ownerId: OWNER,
    date: DATE,
    baseline: { exists: false, id: null, revision: null },
    requestId: REQUEST_ID,
    draftRevision,
    payload: {
      date: DATE,
      mood: data.mood,
      note: data.note,
      ...(data.photoPath ? { photo_path: data.photoPath } : {}),
    },
    draftSnapshot: data,
  });
}

test("two independent writers serialize S2 before delayed S1 cleanup and preserve S2 plus its photo", async () => {
  assert.notEqual(draftLockKey(DATE, OWNER), draftLockKey(DATE, OTHER_OWNER));
  assert.notEqual(draftLockKey(DATE, OWNER), draftLockKey(OTHER_DATE, OWNER));
  const storage = memoryStorage();
  const locks = new TestDraftLocks();
  const resolveLocks = testDraftLockResolver(locks);
  const tabA = createDraftWriter(() => storage, OWNER, resolveLocks);
  const tabB = createDraftWriter(() => storage, OWNER, resolveLocks);
  tabA.observe(DATE, readDraftWithStatus(storage, DATE, OWNER));

  const s1 = draft("S1 pending save");
  const savedS1 = await tabA.flushWithStatus(DATE, s1);
  assert.equal(savedS1.status, "written");
  assert.ok(savedS1.revision);
  const pending = requestFor(s1, savedS1.revision!);
  assert.equal(await writePendingSave(storage, pending, resolveLocks), true);

  // Tab B independently loads S1 before making its newer edit.
  tabB.observe(DATE, readDraftWithStatus(storage, DATE, OWNER));
  const s2 = draft("S2 newer edit", STAGED_PHOTO);
  const lock = locks.holdNext(draftLockKey(DATE, OWNER));
  const writeS2 = tabB.flushWithStatus(DATE, s2);
  await lock.entered;
  const delayedSaveS1 = tabA.markSaved(DATE, savedS1.revision, s1);
  lock.release();

  const [writeResult, saveResult] = await Promise.all([writeS2, delayedSaveS1]);
  assert.equal(writeResult.status, "written");
  assert.equal(saveResult.status, "preserved");

  // A fresh tab restores the newer durable S2, not the immutable replay S1.
  const remountedPending = readPendingSave(storage, DATE, OWNER);
  assert.equal(remountedPending.status, "found");
  if (remountedPending.status !== "found") return;
  const restored = restorePendingSaveData(
    remountedPending.request,
    readDraftWithStatus(storage, DATE, OWNER)
  );
  assert.equal(restored.status, "ready");
  if (restored.status !== "ready") return;
  assert.equal(restored.data.note, "S2 newer edit");
  assert.equal(restored.data.photoPath, STAGED_PHOTO);

  assert.equal(
    await clearPendingSave(storage, DATE, OWNER, pending.requestId, resolveLocks),
    true
  );
  assert.equal(readDraft(storage, DATE, OWNER)?.note, "S2 newer edit");
  assert.equal(readDraft(storage, DATE, OWNER)?.photoPath, STAGED_PHOTO);
});

test("successful save clears the exact matching owner/date revision only", async () => {
  const storage = memoryStorage();
  const locks = new TestDraftLocks();
  const resolveLocks = testDraftLockResolver(locks);
  const writer = createDraftWriter(() => storage, OWNER, resolveLocks);
  writer.observe(DATE, readDraftWithStatus(storage, DATE, OWNER));
  const snapshot = draft("saved draft");
  const written = await writer.flushWithStatus(DATE, snapshot);
  assert.equal(written.status, "written");
  assert.deepEqual(await writer.markSaved(DATE, written.revision, snapshot), {
    status: "cleared",
    revision: written.revision! + 1,
  });
  assert.equal(readDraft(storage, DATE, OWNER), null);
  writer.markEdited();
  const afterSaveEdit = await writer.flushWithStatus(DATE, draft("edit after saved clear"));
  assert.equal(afterSaveEdit.status, "written");
  assert.equal(readDraft(storage, DATE, OWNER)?.note, "edit after saved clear");

  const anotherDay = createDraftWriter(() => storage, OWNER, resolveLocks);
  anotherDay.observe(OTHER_DATE, readDraftWithStatus(storage, OTHER_DATE, OWNER));
  await anotherDay.flushWithStatus(OTHER_DATE, draft("another date"));
  assert.equal(readDraft(storage, OTHER_DATE, OWNER)?.note, "another date");
});

test("empty online recovery carries the cleared draft revision into the next autosave", async () => {
  const storage = memoryStorage();
  const locks = new TestDraftLocks();
  const resolveLocks = testDraftLockResolver(locks);
  const writer = createDraftWriter(() => storage, OWNER, resolveLocks);
  writer.observe(DATE, readDraftWithStatus(storage, DATE, OWNER));
  const snapshot = draft("saved before empty recovery");
  const written = await writer.flushWithStatus(DATE, snapshot);
  assert.equal(written.status, "written");
  if (written.status !== "written") return;
  const saved = await writer.markSaved(DATE, written.revision, snapshot);
  assert.equal(saved.status, "cleared");
  if (saved.status !== "cleared") return;

  const recovered = await recoverOnlineLoad(
    { status: "empty" },
    storage,
    DATE,
    OWNER,
    () => emptyCheckIn(),
    resolveLocks
  );
  assert.equal(recovered.status, "empty");
  if (recovered.status !== "empty") return;
  assert.equal(recovered.draftRevision, saved.revision);

  const nextWriter = createDraftWriter(() => storage, OWNER, resolveLocks);
  nextWriter.observeRevision(DATE, recovered.draftRevision);
  nextWriter.markEdited();
  assert.equal((await nextWriter.flushWithStatus(DATE, draft("new after recovery"))).status, "written");
});

test("a cleared revision cannot be reused to overwrite a later draft", async () => {
  const storage = memoryStorage();
  const locks = new TestDraftLocks();
  const resolveLocks = testDraftLockResolver(locks);
  const seedWriter = createDraftWriter(() => storage, OWNER, resolveLocks);
  seedWriter.observe(DATE, readDraftWithStatus(storage, DATE, OWNER));
  const s1 = draft("first saved revision");
  const seeded = await seedWriter.flushWithStatus(DATE, s1);
  assert.equal(seeded.status, "written");
  if (seeded.status !== "written") return;

  const staleWriter = createDraftWriter(() => storage, OWNER, resolveLocks);
  staleWriter.observe(DATE, readDraftWithStatus(storage, DATE, OWNER));
  const saver = createDraftWriter(() => storage, OWNER, resolveLocks);
  saver.observe(DATE, readDraftWithStatus(storage, DATE, OWNER));
  assert.equal((await saver.markSaved(DATE, seeded.revision, s1)).status, "cleared");

  const newerWriter = createDraftWriter(() => storage, OWNER, resolveLocks);
  newerWriter.observe(DATE, readDraftWithStatus(storage, DATE, OWNER));
  assert.equal((await newerWriter.flushWithStatus(DATE, draft("newer draft"))).status, "written");

  assert.equal((await staleWriter.flushWithStatus(DATE, draft("stale overwrite"))).status, "conflict");
  assert.equal(readDraft(storage, DATE, OWNER)?.note, "newer draft");
});

test("rejected S1 recovery retains a newer durable S2 and releases only S1", async () => {
  const storage = memoryStorage();
  const locks = new TestDraftLocks();
  const resolveLocks = testDraftLockResolver(locks);
  const tabA = createDraftWriter(() => storage, OWNER, resolveLocks);
  tabA.observe(DATE, readDraftWithStatus(storage, DATE, OWNER));
  const s1 = draft("S1 rejected");
  const s1write = await tabA.flushWithStatus(DATE, s1);
  assert.equal(s1write.status, "written");
  const request = requestFor(s1, s1write.revision!);
  assert.equal(await writePendingSave(storage, request, resolveLocks), true);

  const tabB = createDraftWriter(() => storage, OWNER, resolveLocks);
  tabB.observe(DATE, readDraftWithStatus(storage, DATE, OWNER));
  const s2 = draft("S2 newer after request", STAGED_PHOTO);
  assert.equal((await tabB.flushWithStatus(DATE, s2)).status, "written");

  const released = await releaseRejectedPendingSave(storage, request, s1, resolveLocks);
  assert.equal(released.status, "released");
  if (released.status === "released") {
    assert.equal(released.draftStatus, "preserved");
    assert.equal(released.data.note, "S2 newer after request");
  }
  assert.equal(readPendingSave(storage, DATE, OWNER).status, "missing");
  assert.equal(readDraft(storage, DATE, OWNER)?.note, "S2 newer after request");
  assert.equal(readDraft(storage, DATE, OWNER)?.photoPath, STAGED_PHOTO);
});

test("unreadable durable draft blocks pending replay instead of masquerading as missing", () => {
  const unreadable: DraftStorage = {
    getItem: () => "{not-json",
    setItem: () => {},
    removeItem: () => {},
  };
  const request = requestFor(draft("S1 pending"), null);
  assert.deepEqual(
    restorePendingSaveData(request, readDraftWithStatus(unreadable, DATE, OWNER)),
    { status: "blocked" }
  );
});

test("a cloud choice cannot clear a draft revision written after the conflict was shown", async () => {
  const storage = memoryStorage();
  const locks = new TestDraftLocks();
  const resolveLocks = testDraftLockResolver(locks);
  const tabA = createDraftWriter(() => storage, OWNER, resolveLocks);
  tabA.observe(DATE, readDraftWithStatus(storage, DATE, OWNER));
  const s1 = draft("local draft at conflict time");
  await tabA.flushWithStatus(DATE, s1);
  const recovery = await recoverOnlineLoad(
    { status: "entry", entry: { id: ROW_ID } },
    storage,
    DATE,
    OWNER,
    () => draft("cloud data"),
    resolveLocks
  );
  assert.equal(recovery.status, "conflict");
  if (recovery.status !== "conflict") return;

  const tabB = createDraftWriter(() => storage, OWNER, resolveLocks);
  tabB.observe(DATE, readDraftWithStatus(storage, DATE, OWNER));
  await tabB.flushWithStatus(DATE, draft("S2 newer than conflict"));
  const result = await resolveDraftConflict({
    storage,
    conflict: recovery.conflict,
    choice: "cloud",
    confirmed: true,
    activeOwnerId: OWNER,
    activeDate: DATE,
    resolveLocks,
  });
  assert.equal(result.status, "stale");
  assert.equal(readDraft(storage, DATE, OWNER)?.note, "S2 newer than conflict");
});

test("missing Web Locks support fails closed and leaves the stored draft untouched", async () => {
  const storage = memoryStorage();
  const noLocks = () => null;
  const writer = createDraftWriter(() => storage, OWNER, noLocks);
  writer.observe(DATE, { status: "missing" });
  assert.equal((await writer.flushWithStatus(DATE, draft("cannot safely persist"))).status, "unavailable");
  assert.equal(storage.getItem(draftKey(DATE, OWNER)), null);
});

test("online recovery blocks when the draft cannot be read under a shared lock", async () => {
  const storage = memoryStorage();
  const locks = new TestDraftLocks();
  const writer = createDraftWriter(() => storage, OWNER, testDraftLockResolver(locks));
  writer.observe(DATE, readDraftWithStatus(storage, DATE, OWNER));
  assert.equal((await writer.flushWithStatus(DATE, draft("retain without locks"))).status, "written");

  const recovery = await recoverOnlineLoad(
    { status: "entry", entry: { id: ROW_ID } },
    storage,
    DATE,
    OWNER,
    () => draft("cloud data"),
    () => null
  );
  assert.equal(recovery.status, "failed");
  if (recovery.status === "failed") assert.equal(recovery.error.code, "checkin.error_draft_storage");
  assert.equal(readDraft(storage, DATE, OWNER)?.note, "retain without locks");
});

test("the pending request and draft are read as one locked recovery snapshot", async () => {
  const storage = memoryStorage();
  const locks = new TestDraftLocks();
  const saveModule = await import("../src/lib/checkin/pending-save.ts");
  assert.equal(typeof saveModule.readPendingSaveAndDraft, "function");

  const snapshot = await saveModule.readPendingSaveAndDraft(
    storage,
    DATE,
    OWNER,
    () => null
  );
  assert.deepEqual(snapshot, { status: "unavailable" });

  const writer = createDraftWriter(() => storage, OWNER, testDraftLockResolver(locks));
  writer.observe(DATE, readDraftWithStatus(storage, DATE, OWNER));
  const data = draft("one locked snapshot");
  const write = await writer.flushWithStatus(DATE, data);
  assert.equal(write.status, "written");
  const snapshotWithLocks = await saveModule.readPendingSaveAndDraft(
    storage,
    DATE,
    OWNER,
    testDraftLockResolver(locks)
  );
  assert.equal(snapshotWithLocks.status, "ready");
  if (snapshotWithLocks.status === "ready") {
    assert.equal(snapshotWithLocks.pending.status, "missing");
    assert.equal(snapshotWithLocks.draft.status, "found");
    if (snapshotWithLocks.draft.status === "found") {
      assert.equal(snapshotWithLocks.draft.data.note, "one locked snapshot");
      assert.equal(snapshotWithLocks.draft.revision, write.revision);
    }
  }
});

test("write and clear storage errors are reported without removing the last draft", async () => {
  const backing = memoryStorage();
  const locks = new TestDraftLocks();
  const resolveLocks = testDraftLockResolver(locks);
  const writer = createDraftWriter(() => backing, OWNER, resolveLocks);
  writer.observe(DATE, readDraftWithStatus(backing, DATE, OWNER));
  const initial = draft("keep this draft");
  const written = await writer.flushWithStatus(DATE, initial);
  assert.equal(written.status, "written");

  const writeErrorStorage: DraftStorage = {
    getItem: (key) => backing.getItem(key),
    setItem: () => { throw new Error("QuotaExceededError"); },
    removeItem: (key) => backing.removeItem(key),
  };
  const failingWriter = createDraftWriter(() => writeErrorStorage, OWNER, resolveLocks);
  failingWriter.observe(DATE, readDraftWithStatus(backing, DATE, OWNER));
  assert.equal((await failingWriter.flushWithStatus(DATE, draft("new data"))).status, "failed");
  assert.equal(readDraft(backing, DATE, OWNER)?.note, "keep this draft");

  const removeErrorStorage: DraftStorage = {
    getItem: (key) => backing.getItem(key),
    setItem: (key, value) => backing.setItem(key, value),
    removeItem: () => { throw new Error("SecurityError"); },
  };
  const clearWriter = createDraftWriter(() => removeErrorStorage, OWNER, resolveLocks);
  clearWriter.observe(DATE, readDraftWithStatus(backing, DATE, OWNER));
  assert.equal((await clearWriter.markSaved(DATE, written.revision, initial)).status, "failed");
  assert.equal(readDraft(backing, DATE, OWNER)?.note, "keep this draft");
});
