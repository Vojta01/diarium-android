import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
  createDraftWriter,
  readDraft,
  readDraftConflictSnapshot,
  resolveDraftConflictCandidate,
  type DraftStorage,
} from "../src/lib/checkin/draft.ts";
import { emptyCheckIn } from "../src/lib/checkin/model.ts";
import { createPhotoEditLifecycle } from "../src/lib/checkin/photo-edit.ts";
import { TestDraftLocks, testDraftLockResolver } from "./draft-lock-test-utils.ts";

const OWNER = "11111111-1111-4111-8111-111111111111";
const OTHER_OWNER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const DATE = "2026-11-06";
const ORIGIN = "https://project.supabase.co";
const STAGED_A = `${ORIGIN}/storage/v1/object/public/diary-photos/${OWNER}/${DATE}-upload-a.jpg`;
const STAGED_B = `${ORIGIN}/storage/v1/object/public/diary-photos/${OWNER}/${DATE}-upload-b.jpg`;

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

async function conflictFixture(options: {
  currentPhoto?: string | null;
  candidatePhotos: string[];
}) {
  const storage = memoryStorage();
  const locks = new TestDraftLocks();
  const resolveLocks = testDraftLockResolver(locks);
  const currentWriter = createDraftWriter(() => storage, OWNER, resolveLocks);
  currentWriter.observe(DATE, { status: "missing" });
  const initialData = draft("initial current");
  const initial = await currentWriter.flushWithStatus(DATE, initialData);
  assert.equal(initial.status, "written");
  if (initial.status !== "written") throw new Error("initial draft was not written");

  const staleWriters = options.candidatePhotos.map((_, index) => {
    const writer = createDraftWriter(() => storage, OWNER, resolveLocks);
    writer.observe(DATE, {
      status: "found",
      data: initialData,
      revision: initial.revision,
      updatedAt: initial.updatedAt,
    });
    return writer;
  });

  const current = await currentWriter.flushWithStatus(
    DATE,
    draft("active current", options.currentPhoto ?? null)
  );
  assert.equal(current.status, "written");
  if (current.status !== "written") throw new Error("current draft update was not written");

  for (const [index, photoPath] of options.candidatePhotos.entries()) {
    const result = await staleWriters[index].flushWithStatus(
      DATE,
      draft(`recovered candidate ${index}`, photoPath)
    );
    assert.equal(result.status, "conflict");
  }

  return { storage, locks, resolveLocks };
}

function photoLifecycle() {
  return createPhotoEditLifecycle(ORIGIN);
}

async function resolveChoice(options: {
  storage: DraftStorage;
  resolveLocks: ReturnType<typeof testDraftLockResolver>;
  choice: "candidate" | "current";
  candidateIndex?: number;
  photoPath?: string;
}) {
  const snapshot = await readDraftConflictSnapshot(
    options.storage,
    DATE,
    OWNER,
    options.resolveLocks
  );
  assert.equal(snapshot.status, "ready");
  if (snapshot.status !== "ready") throw new Error("candidate snapshot unavailable");
  const candidate = options.photoPath
    ? snapshot.candidates.find((item) => item.data.photoPath === options.photoPath)
    : snapshot.candidates[options.candidateIndex ?? 0];
  assert.ok(candidate, "expected candidate exists");
  const result = await resolveDraftConflictCandidate({
    storage: options.storage,
    ownerId: OWNER,
    date: DATE,
    conflict: candidate,
    expectedCurrentRevision: snapshot.current.revision ?? null,
    choice: options.choice,
    confirmed: true,
    resolveLocks: options.resolveLocks,
  });
  return { snapshot, candidate, result };
}

test("promoting a recovered candidate preserves its photo through local tracking reset and reload", async () => {
  const fixture = await conflictFixture({ candidatePhotos: [STAGED_A] });
  const lifecycle = photoLifecycle();
  const snapshot = await readDraftConflictSnapshot(
    fixture.storage,
    DATE,
    OWNER,
    fixture.resolveLocks
  );
  assert.equal(snapshot.status, "ready");
  if (snapshot.status !== "ready") return;
  const candidate = snapshot.candidates[0];
  assert.equal(lifecycle.track(OWNER, DATE, STAGED_A), true);
  assert.equal(lifecycle.preserve(OWNER, DATE, STAGED_A), true);

  const promoted = await resolveDraftConflictCandidate({
    storage: fixture.storage,
    ownerId: OWNER,
    date: DATE,
    conflict: candidate,
    expectedCurrentRevision: snapshot.current.revision ?? null,
    choice: "candidate",
    confirmed: true,
    resolveLocks: fixture.resolveLocks,
  });
  assert.equal(promoted.status, "resolved");
  assert.equal(readDraft(fixture.storage, DATE, OWNER)?.photoPath, STAGED_A);

  // Dropping stale local tracking does not discard the durable draft or blob.
  await lifecycle.discard(OWNER, DATE, STAGED_A);
  assert.equal(lifecycle.track(OWNER, DATE, STAGED_A), true);
  await lifecycle.abandonAll();

  // A fresh process has no in-memory lifecycle map; the persisted draft is the
  // source of truth and must restore the staged URL without deleting it.
  const reloaded = readDraft(fixture.storage, DATE, OWNER);
  assert.equal(reloaded?.photoPath, STAGED_A);
  const remountedLifecycle = photoLifecycle();
  assert.equal(remountedLifecycle.track(OWNER, DATE, reloaded!.photoPath!), true);
  assert.equal(remountedLifecycle.preserve(OWNER, DATE, reloaded!.photoPath!), true);
  await remountedLifecycle.abandonAll();
  assert.equal(readDraft(fixture.storage, DATE, OWNER)?.photoPath, STAGED_A);
});

test("ignored duplicate candidates remove local copies without deleting staged blobs", async () => {
  const fixture = await conflictFixture({ candidatePhotos: [STAGED_A, STAGED_A, STAGED_B] });
  const lifecycle = photoLifecycle();
  const initial = await readDraftConflictSnapshot(
    fixture.storage,
    DATE,
    OWNER,
    fixture.resolveLocks
  );
  assert.equal(initial.status, "ready");
  if (initial.status !== "ready") return;
  for (const candidate of initial.candidates) {
    assert.equal(lifecycle.track(OWNER, DATE, candidate.data.photoPath!), true);
    assert.equal(lifecycle.preserve(OWNER, DATE, candidate.data.photoPath!), true);
  }

  const ignoredFirst = await resolveChoice({
    storage: fixture.storage,
    resolveLocks: fixture.resolveLocks,
    choice: "current",
    photoPath: STAGED_A,
  });
  assert.equal(ignoredFirst.result.status, "resolved");
  await lifecycle.discard(OWNER, DATE, STAGED_A);
  assert.equal(lifecycle.track(OWNER, DATE, STAGED_A), true);
  const afterFirst = await readDraftConflictSnapshot(
    fixture.storage,
    DATE,
    OWNER,
    fixture.resolveLocks
  );
  assert.equal(afterFirst.status, "ready");
  if (afterFirst.status === "ready") {
    assert.equal(afterFirst.candidates.filter(({ data }) => data.photoPath === STAGED_A).length, 1);
  }

  const ignoredDuplicate = await resolveChoice({
    storage: fixture.storage,
    resolveLocks: fixture.resolveLocks,
    choice: "current",
    photoPath: STAGED_A,
  });
  assert.equal(ignoredDuplicate.result.status, "resolved");
  await lifecycle.discard(OWNER, DATE, STAGED_A);
  const afterDuplicate = await readDraftConflictSnapshot(
    fixture.storage,
    DATE,
    OWNER,
    fixture.resolveLocks
  );
  assert.equal(afterDuplicate.status, "ready");
  if (afterDuplicate.status === "ready") {
    assert.equal(afterDuplicate.candidates.some(({ data }) => data.photoPath === STAGED_A), false);
  }

  const ignoredUnreferenced = await resolveChoice({
    storage: fixture.storage,
    resolveLocks: fixture.resolveLocks,
    choice: "current",
    photoPath: STAGED_B,
  });
  assert.equal(ignoredUnreferenced.result.status, "resolved");
  await lifecycle.discard(OWNER, DATE, STAGED_B);
});

test("ignoring a candidate leaves the active draft's photo reference unchanged", async () => {
  const fixture = await conflictFixture({
    currentPhoto: STAGED_A,
    candidatePhotos: [STAGED_A],
  });
  const lifecycle = photoLifecycle();
  assert.equal(lifecycle.track(OWNER, DATE, STAGED_A), true);
  assert.equal(lifecycle.preserve(OWNER, DATE, STAGED_A), true);

  const ignored = await resolveChoice({
    storage: fixture.storage,
    resolveLocks: fixture.resolveLocks,
    choice: "current",
  });
  assert.equal(ignored.result.status, "resolved");
  await lifecycle.discard(OWNER, DATE, STAGED_A);

  assert.equal(readDraft(fixture.storage, DATE, OWNER)?.photoPath, STAGED_A);
  assert.equal(lifecycle.track(OWNER, DATE, STAGED_A), true);
  await lifecycle.abandonAll();
});

test("stale candidate snapshots and account switches do not discard staged photos", async () => {
  const fixture = await conflictFixture({ candidatePhotos: [STAGED_A] });
  const lifecycle = photoLifecycle();
  assert.equal(lifecycle.track(OWNER, DATE, STAGED_A), true);
  assert.equal(lifecycle.preserve(OWNER, DATE, STAGED_A), true);
  const shown = await readDraftConflictSnapshot(
    fixture.storage,
    DATE,
    OWNER,
    fixture.resolveLocks
  );
  assert.equal(shown.status, "ready");
  if (shown.status !== "ready") return;

  const currentWriter = createDraftWriter(() => fixture.storage, OWNER, fixture.resolveLocks);
  currentWriter.observe(DATE, shown.current);
  assert.equal(
    (await currentWriter.flushWithStatus(DATE, draft("newer active snapshot"))).status,
    "written"
  );
  const stale = await resolveDraftConflictCandidate({
    storage: fixture.storage,
    ownerId: OWNER,
    date: DATE,
    conflict: shown.candidates[0],
    expectedCurrentRevision: shown.current.revision ?? null,
    choice: "candidate",
    confirmed: true,
    resolveLocks: fixture.resolveLocks,
  });
  assert.equal(stale.status, "stale");
  assert.equal(readDraft(fixture.storage, DATE, OWNER)?.note, "newer active snapshot");

  const switchedAccount = await resolveDraftConflictCandidate({
    storage: fixture.storage,
    ownerId: OTHER_OWNER,
    date: DATE,
    conflict: shown.candidates[0],
    expectedCurrentRevision: shown.current.revision ?? null,
    choice: "current",
    confirmed: true,
    resolveLocks: fixture.resolveLocks,
  });
  assert.equal(switchedAccount.status, "stale");
  assert.equal(lifecycle.track(OWNER, DATE, STAGED_A), true);
});

test("a locally discarded candidate URL remains available for draft recovery", async () => {
  const lifecycle = photoLifecycle();
  assert.equal(lifecycle.track(OWNER, DATE, STAGED_A), true);

  await lifecycle.discard(OWNER, DATE, STAGED_A);

  assert.equal(lifecycle.track(OWNER, DATE, STAGED_A), true);
});

test("the check-in selection handler protects a chosen candidate instead of discarding it", () => {
  const screen = readFileSync(
    new URL("../src/components/checkin/CheckInScreen.tsx", import.meta.url),
    "utf8"
  );
  const start = screen.indexOf("const handleDraftCandidateResolution = async (");
  const end = screen.indexOf("const shiftDay =", start);
  assert.ok(start >= 0 && end > start, "candidate resolution handler exists");
  const handler = screen.slice(start, end);
  const resolveCall = handler.indexOf("const result = await resolveDraftConflictCandidate");
  const scopeGuard = handler.indexOf("if (!isActiveScope()) return;", resolveCall);
  const selectedBranch = handler.indexOf('if (choice === "candidate")');
  const discard = handler.indexOf("photoLifecycle.discard");
  assert.ok(resolveCall >= 0 && scopeGuard > resolveCall, "account/date scope is rechecked after resolution");
  assert.ok(scopeGuard < selectedBranch, "stale account/date snapshots cannot alter photo ownership");
  assert.ok(selectedBranch >= 0, "chosen candidate has a dedicated protection branch");
  assert.ok(discard > selectedBranch, "only the unselected-candidate branch may discard");
  assert.match(handler.slice(selectedBranch, discard), /photoLifecycle\.preserve/);
  assert.match(handler.slice(selectedBranch, discard), /else\s*\{/);
});
