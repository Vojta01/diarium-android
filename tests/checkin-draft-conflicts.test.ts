import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
  createDraftWriter,
  draftLockKey,
  isDraftFlushDurableForNavigation,
  readDraft,
  readDraftConflictSnapshot,
  resolveDraftConflictCandidate,
  type DraftStorage,
} from "../src/lib/checkin/draft.ts";
import {
  createPendingSaveRequest,
  pendingSaveKey,
  writePendingSave,
} from "../src/lib/checkin/pending-save.ts";
import { emptyCheckIn } from "../src/lib/checkin/model.ts";
import {
  createPhotoEditLifecycle,
  persistDraftBeforePhotoTrackingReset,
} from "../src/lib/checkin/photo-edit.ts";
import { TestDraftLocks, testDraftLockResolver } from "./draft-lock-test-utils.ts";

const OWNER = "11111111-1111-4111-8111-111111111111";
const DATE = "2026-11-06";
const STAGED_PHOTO =
  "https://project.supabase.co/storage/v1/object/public/diary-photos/11111111-1111-4111-8111-111111111111/2026-11-06-upload-b.jpg";

function memoryStorage(): DraftStorage {
  const values = new Map<string, string>();
  return {
    get length() {
      return values.size;
    },
    key(index) {
      return [...values.keys()][index] ?? null;
    },
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
  };
}

function draft(note: string, photoPath: string | null = null) {
  return { ...emptyCheckIn(), mood: 4, note, photoPath };
}

test("two interleaved tabs preserve a stale autosave as a durable owner/date conflict candidate", async () => {
  const storage = memoryStorage();
  const locks = new TestDraftLocks();
  const resolveLocks = testDraftLockResolver(locks);
  const tabA = createDraftWriter(() => storage, OWNER, resolveLocks);
  const tabB = createDraftWriter(() => storage, OWNER, resolveLocks);

  tabA.observe(DATE, { status: "missing" });
  const s1 = draft("S1 saved from tab A");
  const initial = await tabA.flushWithStatus(DATE, s1);
  assert.equal(initial.status, "written");
  if (initial.status !== "written") return;
  const pending = createPendingSaveRequest({
    ownerId: OWNER,
    date: DATE,
    baseline: { exists: false, id: null, revision: null },
    requestId: "55555555-5555-4555-8555-555555555555",
    draftRevision: initial.revision,
    payload: { date: DATE, mood: s1.mood, note: s1.note },
    draftSnapshot: s1,
  });
  assert.equal(await writePendingSave(storage, pending, resolveLocks), true);
  const pendingRaw = storage.getItem(pendingSaveKey(DATE, OWNER));

  // B independently loaded the same revision before A's successful save clears it.
  tabB.observe(DATE, {
    status: "found",
    data: s1,
    revision: initial.revision,
    updatedAt: initial.updatedAt,
  });
  const gate = locks.holdNext(draftLockKey(DATE, OWNER));
  const saveA = tabA.markSaved(DATE, initial.revision, s1);
  await gate.entered;
  const s2 = draft("S2 kept from tab B", STAGED_PHOTO);
  const autosaveB = tabB.flushWithStatus(DATE, s2);
  gate.release();

  assert.equal((await saveA).status, "cleared");
  const conflict = await autosaveB;
  assert.equal(conflict.status, "conflict");
  if (conflict.status !== "conflict") return;
  assert.equal(conflict.candidate.data.note, "S2 kept from tab B");
  assert.equal(conflict.candidate.data.photoPath, STAGED_PHOTO);

  // A's successful save remains authoritative in the active slot; B is separate.
  assert.equal(readDraft(storage, DATE, OWNER), null);
  const recovered = await readDraftConflictSnapshot(storage, DATE, OWNER, resolveLocks);
  assert.equal(recovered.status, "ready");
  if (recovered.status !== "ready") return;
  assert.equal(recovered.current.status, "missing");
  assert.equal(recovered.current.revision, initial.revision + 1);
  assert.equal(recovered.candidates.length, 1);
  assert.equal(recovered.candidates[0].id, conflict.candidate.id);
  assert.equal(recovered.candidates[0].ownerId, OWNER);
  assert.equal(recovered.candidates[0].date, DATE);

  // Reloaded candidate selection promotes only after the reviewed current state
  // and exact candidate have been re-read under the shared lock.
  const resolution = await resolveDraftConflictCandidate({
    storage,
    ownerId: OWNER,
    date: DATE,
    conflict: recovered.candidates[0],
    expectedCurrentRevision: recovered.current.revision ?? null,
    choice: "candidate",
    confirmed: true,
    resolveLocks,
  });
  assert.equal(resolution.status, "resolved");
  if (resolution.status !== "resolved") return;
  assert.equal(resolution.data?.note, "S2 kept from tab B");
  assert.equal(readDraft(storage, DATE, OWNER)?.note, "S2 kept from tab B");
  assert.equal(readDraft(storage, DATE, OWNER)?.photoPath, STAGED_PHOTO);
  assert.equal(storage.getItem(pendingSaveKey(DATE, OWNER)), pendingRaw);
  const after = await readDraftConflictSnapshot(storage, DATE, OWNER, resolveLocks);
  assert.equal(after.status, "ready");
  if (after.status === "ready") assert.equal(after.candidates.length, 0);
});

test("a changed current revision re-prompts instead of applying a stale candidate choice", async () => {
  const storage = memoryStorage();
  const locks = new TestDraftLocks();
  const resolveLocks = testDraftLockResolver(locks);
  const tabA = createDraftWriter(() => storage, OWNER, resolveLocks);
  const tabB = createDraftWriter(() => storage, OWNER, resolveLocks);
  const first = draft("S1");
  tabA.observe(DATE, { status: "missing" });
  const initial = await tabA.flushWithStatus(DATE, first);
  assert.equal(initial.status, "written");
  if (initial.status !== "written") return;
  tabB.observe(DATE, { status: "found", data: first, revision: initial.revision, updatedAt: initial.updatedAt });
  assert.equal((await tabA.markSaved(DATE, initial.revision, first)).status, "cleared");
  const candidateData = draft("candidate S2");
  const rejected = await tabB.flushWithStatus(DATE, candidateData);
  assert.equal(rejected.status, "conflict");
  if (rejected.status !== "conflict") return;

  const shown = await readDraftConflictSnapshot(storage, DATE, OWNER, resolveLocks);
  assert.equal(shown.status, "ready");
  if (shown.status !== "ready") return;
  const newerTab = createDraftWriter(() => storage, OWNER, resolveLocks);
  newerTab.observe(DATE, shown.current);
  assert.equal((await newerTab.flushWithStatus(DATE, draft("S3 current"))).status, "written");

  const staleChoice = await resolveDraftConflictCandidate({
    storage,
    ownerId: OWNER,
    date: DATE,
    conflict: shown.candidates[0],
    expectedCurrentRevision: shown.current.revision ?? null,
    choice: "candidate",
    confirmed: true,
    resolveLocks,
  });
  assert.equal(staleChoice.status, "stale");
  if (staleChoice.status !== "stale" || staleChoice.snapshot?.status !== "ready") return;
  assert.equal(staleChoice.snapshot.current.status, "found");
  if (staleChoice.snapshot.current.status === "found") {
    assert.equal(staleChoice.snapshot.current.data.note, "S3 current");
  }
  assert.equal(staleChoice.snapshot.candidates.length, 1);
  assert.equal(readDraft(storage, DATE, OWNER)?.note, "S3 current");

  const notConfirmed = await resolveDraftConflictCandidate({
    storage,
    ownerId: OWNER,
    date: DATE,
    conflict: staleChoice.snapshot.candidates[0],
    expectedCurrentRevision: staleChoice.snapshot.current.revision ?? null,
    choice: "current",
    confirmed: false,
    resolveLocks,
  });
  assert.equal(notConfirmed.status, "confirmation_required");

  const confirmed = await resolveDraftConflictCandidate({
    storage,
    ownerId: OWNER,
    date: DATE,
    conflict: staleChoice.snapshot.candidates[0],
    expectedCurrentRevision: staleChoice.snapshot.current.revision ?? null,
    choice: "current",
    confirmed: true,
    resolveLocks,
  });
  assert.equal(confirmed.status, "resolved");
  assert.equal(readDraft(storage, DATE, OWNER)?.note, "S3 current");
  const remaining = await readDraftConflictSnapshot(storage, DATE, OWNER, resolveLocks);
  assert.equal(remaining.status, "ready");
  if (remaining.status === "ready") assert.equal(remaining.candidates.length, 0);
});

test("repeated conflicts create separate records and remain isolated by verified owner", async () => {
  const storage = memoryStorage();
  const locks = new TestDraftLocks();
  const resolveLocks = testDraftLockResolver(locks);
  const tabA = createDraftWriter(() => storage, OWNER, resolveLocks);
  const tabB = createDraftWriter(() => storage, OWNER, resolveLocks);
  const tabC = createDraftWriter(() => storage, OWNER, resolveLocks);
  const first = draft("shared revision");
  tabA.observe(DATE, { status: "missing" });
  const initial = await tabA.flushWithStatus(DATE, first);
  assert.equal(initial.status, "written");
  if (initial.status !== "written") return;
  const loaded = { status: "found" as const, data: first, revision: initial.revision, updatedAt: initial.updatedAt };
  tabB.observe(DATE, loaded);
  tabC.observe(DATE, loaded);
  assert.equal((await tabA.markSaved(DATE, initial.revision, first)).status, "cleared");
  assert.equal((await tabB.flushWithStatus(DATE, draft("candidate B"))).status, "conflict");
  assert.equal((await tabC.flushWithStatus(DATE, draft("candidate C"))).status, "conflict");

  const accountA = await readDraftConflictSnapshot(storage, DATE, OWNER, resolveLocks);
  assert.equal(accountA.status, "ready");
  if (accountA.status !== "ready") return;
  assert.equal(accountA.candidates.length, 2);
  assert.deepEqual(accountA.candidates.map(({ data }) => data.note).sort(), ["candidate B", "candidate C"]);

  const accountB = await readDraftConflictSnapshot(
    storage,
    DATE,
    "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    resolveLocks
  );
  assert.equal(accountB.status, "ready");
  if (accountB.status === "ready") assert.equal(accountB.candidates.length, 0);

  const crossAccountChoice = await resolveDraftConflictCandidate({
    storage,
    ownerId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    date: DATE,
    conflict: accountA.candidates[0],
    expectedCurrentRevision: accountA.current.revision ?? null,
    choice: "candidate",
    confirmed: true,
    resolveLocks,
  });
  assert.equal(crossAccountChoice.status, "stale");
  const stillAvailable = await readDraftConflictSnapshot(storage, DATE, OWNER, resolveLocks);
  assert.equal(stillAvailable.status, "ready");
  if (stillAvailable.status === "ready") assert.equal(stillAvailable.candidates.length, 2);
});

test("unavailable conflict storage fails closed and does not mutate the active draft", async () => {
  const backing = memoryStorage();
  const locks = new TestDraftLocks();
  const resolveLocks = testDraftLockResolver(locks);
  const tabA = createDraftWriter(() => backing, OWNER, resolveLocks);
  tabA.observe(DATE, { status: "missing" });
  const first = draft("current remains");
  const initial = await tabA.flushWithStatus(DATE, first);
  assert.equal(initial.status, "written");
  if (initial.status !== "written") return;

  const stale = createDraftWriter(() => backing, OWNER, resolveLocks);
  stale.observe(DATE, { status: "found", data: first, revision: initial.revision, updatedAt: initial.updatedAt });
  assert.equal((await tabA.markSaved(DATE, initial.revision, first)).status, "cleared");
  const before = backing.getItem("diarium_draft_revision_v1_2026-11-06_11111111-1111-4111-8111-111111111111");
  const failing: DraftStorage = {
    get length() {
      return backing.length;
    },
    key: (index) => backing.key?.(index) ?? null,
    getItem: (key) => backing.getItem(key),
    setItem(key, value) {
      if (key.startsWith("diarium_draft_conflict_v1_")) throw new Error("QuotaExceededError");
      backing.setItem(key, value);
    },
    removeItem: (key) => backing.removeItem(key),
  };
  const failedWriter = createDraftWriter(() => failing, OWNER, resolveLocks);
  failedWriter.observe(DATE, { status: "found", data: first, revision: initial.revision, updatedAt: initial.updatedAt });
  assert.equal((await failedWriter.flushWithStatus(DATE, draft("not durably stored"))).status, "failed");
  assert.equal(readDraft(backing, DATE, OWNER), null);
  assert.equal(backing.getItem("diarium_draft_revision_v1_2026-11-06_11111111-1111-4111-8111-111111111111"), before);
  const snapshot = await readDraftConflictSnapshot(backing, DATE, OWNER, resolveLocks);
  assert.equal(snapshot.status, "ready");
  if (snapshot.status === "ready") assert.equal(snapshot.candidates.length, 0);
});

test("a storage retry preserves this page's edits before surfacing older candidates", async () => {
  const storage = memoryStorage();
  const locks = new TestDraftLocks();
  const resolveLocks = testDraftLockResolver(locks);
  const tabA = createDraftWriter(() => storage, OWNER, resolveLocks);
  const tabB = createDraftWriter(() => storage, OWNER, resolveLocks);
  tabA.observe(DATE, { status: "missing" });
  const firstData = draft("active version");
  const first = await tabA.flushWithStatus(DATE, firstData);
  assert.equal(first.status, "written");
  if (first.status !== "written") return;
  tabB.observe(DATE, {
    status: "found",
    data: firstData,
    revision: first.revision,
    updatedAt: first.updatedAt,
  });
  assert.equal((await tabA.markSaved(DATE, first.revision, firstData)).status, "cleared");
  assert.equal((await tabB.flushWithStatus(DATE, draft("older candidate"))).status, "conflict");

  const beforeRetry = await readDraftConflictSnapshot(storage, DATE, OWNER, resolveLocks);
  assert.equal(beforeRetry.status, "ready");
  if (beforeRetry.status !== "ready") return;
  const retryWriter = createDraftWriter(() => storage, OWNER, resolveLocks);
  retryWriter.observe(DATE, beforeRetry.current);
  retryWriter.markEdited();
  const pageSnapshot = draft("unsaved page snapshot");
  assert.equal((await retryWriter.flushWithStatus(DATE, pageSnapshot)).status, "written");

  const afterRetry = await readDraftConflictSnapshot(storage, DATE, OWNER, resolveLocks);
  assert.equal(afterRetry.status, "ready");
  if (afterRetry.status !== "ready") return;
  assert.equal(afterRetry.current.status, "found");
  if (afterRetry.current.status === "found") assert.equal(afterRetry.current.data.note, pageSnapshot.note);
  assert.ok(afterRetry.candidates.some((candidate) => candidate.data.note === "older candidate"));
});

test("storage retry flushes the page snapshot before showing stored candidates", () => {
  const screen = readFileSync(
    new URL("../src/components/checkin/CheckInScreen.tsx", import.meta.url),
    "utf8"
  );
  const start = screen.indexOf("const handleRetryDraftStorage = async () =>");
  const end = screen.indexOf("// ── Date navigation ──", start);
  assert.ok(start >= 0 && end > start, "draft retry handler is present");
  const retry = screen.slice(start, end);
  const flush = retry.indexOf("await flushDraft(date, pageSnapshot)");
  const showCandidates = retry.indexOf("setDraftCandidateConflict(snapshot)");
  assert.ok(flush >= 0 && showCandidates > flush, "the page snapshot is persisted before the candidate panel is shown");
  assert.match(retry, /if \(!pageSnapshotMatches\)[\s\S]*?setUnsavedDraftCopy\(JSON\.stringify\(\{ date, data: toDraft\(pageSnapshot\)/);
});

test("date navigation requires a durable write or a verified owner/date conflict copy", () => {
  const scope = { ownerId: OWNER, date: DATE };
  assert.equal(isDraftFlushDurableForNavigation({ status: "written", revision: 1, updatedAt: 1 }, scope), true);
  assert.equal(isDraftFlushDurableForNavigation({ status: "cleared", revision: 1 }, scope), true);
  assert.equal(isDraftFlushDurableForNavigation({ status: "skipped" }, scope), true);
  assert.equal(isDraftFlushDurableForNavigation({ status: "failed" }, scope), false);
  assert.equal(isDraftFlushDurableForNavigation({ status: "unavailable", reason: "locks" }, scope), false);
  const candidate = {
    id: "conflict-id",
    ownerId: OWNER,
    date: DATE,
    expectedRevision: 1,
    observedRevision: 2,
    createdAt: 1,
    data: draft("recovered"),
  };
  assert.equal(
    isDraftFlushDurableForNavigation(
      { status: "conflict", candidate, current: { status: "missing", revision: 2 } },
      scope
    ),
    true
  );
  assert.equal(
    isDraftFlushDurableForNavigation(
      { status: "conflict", candidate, current: { status: "failed" } },
      scope
    ),
    false
  );
  assert.equal(
    isDraftFlushDurableForNavigation(
      { status: "conflict", candidate: { ...candidate, ownerId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" }, current: { status: "missing", revision: 2 } },
      scope
    ),
    false
  );
});

test("a staged photo referenced only by a conflict candidate remains persisted", async () => {
  const storage = memoryStorage();
  const locks = new TestDraftLocks();
  const resolveLocks = testDraftLockResolver(locks);
  const tabA = createDraftWriter(() => storage, OWNER, resolveLocks);
  tabA.observe(DATE, { status: "missing" });
  const initial = await tabA.flushWithStatus(DATE, draft("S1"));
  assert.equal(initial.status, "written");
  if (initial.status !== "written") return;
  const tabB = createDraftWriter(() => storage, OWNER, resolveLocks);
  tabB.observe(DATE, {
    status: "found",
    data: draft("S1"),
    revision: initial.revision,
    updatedAt: initial.updatedAt,
  });
  assert.equal((await tabA.markSaved(DATE, initial.revision, draft("S1"))).status, "cleared");
  assert.equal((await tabB.flushWithStatus(DATE, draft("candidate with photo", STAGED_PHOTO))).status, "conflict");

  const lifecycle = createPhotoEditLifecycle("https://project.supabase.co");
  assert.equal(lifecycle.track(OWNER, DATE, STAGED_PHOTO), true);
  await lifecycle.discard(OWNER, DATE, STAGED_PHOTO);
  const snapshot = await readDraftConflictSnapshot(storage, DATE, OWNER, resolveLocks);
  assert.equal(snapshot.status, "ready");
  if (snapshot.status === "ready") {
    assert.equal(snapshot.candidates[0]?.data.photoPath, STAGED_PHOTO);
  }
});

test("pagehide persistence is best-effort and never reports a lockless write as durable", async () => {
  const storage = memoryStorage();
  const writer = createDraftWriter(() => storage, OWNER, () => null);
  writer.observe(DATE, { status: "missing" });
  const result = await writer.flushWithStatus(DATE, draft("still only in page memory"));
  assert.deepEqual(result, { status: "unavailable", reason: "locks" });

  let abandoned = false;
  await persistDraftBeforePhotoTrackingReset({
    persist: async () => { throw new Error("pagehide write failed"); },
    ownerId: OWNER,
    date: DATE,
    photoUrl: null,
    preserve: () => undefined,
    abandon: async () => { abandoned = true; },
  });
  assert.equal(abandoned, true);
});
