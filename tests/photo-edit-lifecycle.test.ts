import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
  createDraftWriter,
  readDraft,
  type DraftStorage,
} from "../src/lib/checkin/draft.ts";
import { emptyCheckIn } from "../src/lib/checkin/model.ts";
import {
  beginPhotoEdit,
  clearPhoto,
  createPhotoEditLifecycle,
  finishPhotoUpload,
  photoSaveIntent,
} from "../src/lib/checkin/photo-edit.ts";
import { buildCheckInPayload } from "../src/lib/checkin/payload.ts";
import { performConditionalSave } from "../src/lib/checkin/save-v2.ts";
import { buildEntryRow } from "../src/lib/entry-row.ts";
import { stagedPhotoStoragePathFromUrl } from "../src/lib/checkin/photo.ts";
import { cs } from "../src/lib/i18n/translations/cs.ts";
import { en } from "../src/lib/i18n/translations/en.ts";
import { TestDraftLocks, testDraftLockResolver } from "./draft-lock-test-utils.ts";

const DATE = "2026-11-07";
const OWNER = "11111111-1111-4111-8111-111111111111";
const ORIGIN = "https://project.supabase.co";
const STORED = `${ORIGIN}/storage/v1/object/public/diary-photos/${OWNER}/${DATE}.jpg`;
const STAGED_A = `${ORIGIN}/storage/v1/object/public/diary-photos/${OWNER}/${DATE}-upload-a.jpg`;
const STAGED_B = `${ORIGIN}/storage/v1/object/public/diary-photos/${OWNER}/${DATE}-upload-b.jpg`;
const OTHER_DATE = "2026-11-08";
const OTHER_OWNER = "22222222-2222-4222-8222-222222222222";
const REQUEST_OWNER = "33333333-3333-4333-8333-333333333333";
const ROW_ID = "44444444-4444-4444-8444-444444444444";
const REQUEST_ID = "55555555-5555-4555-8555-555555555555";

function memoryStorage(): DraftStorage {
  const values = new Map<string, string>();
  return {
    get length() {
      return values.size;
    },
    key: (index) => [...values.keys()][index] ?? null,
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
  };
}

async function writePhotoDraft(
  storage: DraftStorage,
  ownerId: string,
  date: string,
  photoPath: string,
  resolveLocks: ReturnType<typeof testDraftLockResolver>
) {
  const writer = createDraftWriter(() => storage, ownerId, resolveLocks);
  writer.observe(date, { status: "missing" });
  const result = await writer.flushWithStatus(date, {
    ...emptyCheckIn(),
    mood: 4,
    note: "synthetic photo reference",
    photoPath,
  });
  assert.equal(result.status, "written");
}

function assertNoClientPhotoStorageDeletion() {
  const modules = [
    readFileSync(new URL("../src/lib/checkin/photo-edit.ts", import.meta.url), "utf8"),
    readFileSync(new URL("../src/lib/supabase/db.ts", import.meta.url), "utf8"),
    readFileSync(new URL("../src/components/checkin/CheckInScreen.tsx", import.meta.url), "utf8"),
    readFileSync(new URL("../src/components/checkin/sections/PhotoSection.tsx", import.meta.url), "utf8"),
    readFileSync(new URL("../src/components/checkin/DraftCandidateConflictPanel.tsx", import.meta.url), "utf8"),
    readFileSync(new URL("../src/app/api/save-entry/route.ts", import.meta.url), "utf8"),
  ];
  for (const source of modules) {
    assert.doesNotMatch(source, /\.storage[\s\S]{0,180}\.remove\(/);
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

test("only same-owner, same-day unique staged paths are recognized", () => {
  assert.equal(
    stagedPhotoStoragePathFromUrl(STAGED_A, OWNER, DATE, ORIGIN),
    `${OWNER}/${DATE}-upload-a.jpg`
  );
  assert.equal(stagedPhotoStoragePathFromUrl(STORED, OWNER, DATE, ORIGIN), null);
  assert.equal(stagedPhotoStoragePathFromUrl(STAGED_A, "other-user", DATE, ORIGIN), null);
  assert.equal(stagedPhotoStoragePathFromUrl(STAGED_A, OWNER, "2026-11-06", ORIGIN), null);
  assert.equal(
    stagedPhotoStoragePathFromUrl(
      STAGED_A.replace(ORIGIN, "https://attacker.example"),
      OWNER,
      DATE,
      ORIGIN
    ),
    null
  );
  assert.equal(
    stagedPhotoStoragePathFromUrl(
      `${ORIGIN}/storage/v1/object/public/diary-photos/${OWNER}/${DATE}-nested%2Fother.jpg`,
      OWNER,
      DATE,
      ORIGIN
    ),
    null
  );
});

test("the lifecycle rejects staged URLs from another origin", () => {
  const lifecycle = createPhotoEditLifecycle(ORIGIN);
  assert.equal(
    lifecycle.track(
      OWNER,
      DATE,
      STAGED_A.replace(ORIGIN, "https://attacker.example")
    ),
    false
  );
});

test("abandonment removes only local tracking and retains the staged object", async () => {
  const lifecycle = createPhotoEditLifecycle(ORIGIN);
  lifecycle.track(OWNER, DATE, STAGED_A);

  await lifecycle.discard(OWNER, DATE, STAGED_A);

  assert.equal(lifecycle.track(OWNER, DATE, STAGED_A), true);
  assertNoClientPhotoStorageDeletion();
});

test("unreadable local storage cannot authorize physical photo deletion", async () => {
  const unreadable: DraftStorage = {
    getItem: () => { throw new Error("SecurityError"); },
    setItem: () => {},
    removeItem: () => {},
  };
  const locks = new TestDraftLocks();
  const resolveLocks = testDraftLockResolver(locks);
  const durable = await (await import("../src/lib/checkin/pending-save.ts"))
    .readPendingSaveAndDraft(unreadable, DATE, OWNER, resolveLocks);
  assert.equal(durable.status, "ready");
  if (durable.status === "ready") assert.equal(durable.draft.status, "failed");

  const lifecycle = createPhotoEditLifecycle(ORIGIN);
  lifecycle.track(OWNER, DATE, STAGED_A);
  await lifecycle.abandonAll();
  assertNoClientPhotoStorageDeletion();
});

test("cross-date and cross-owner shared drafts remain intact after staged discard", async () => {
  const storage = memoryStorage();
  const locks = new TestDraftLocks();
  const resolveLocks = testDraftLockResolver(locks);
  await writePhotoDraft(storage, OWNER, OTHER_DATE, STAGED_A, resolveLocks);
  await writePhotoDraft(storage, OTHER_OWNER, DATE, STAGED_A, resolveLocks);

  const lifecycle = createPhotoEditLifecycle(ORIGIN);
  lifecycle.track(OWNER, DATE, STAGED_A);
  await lifecycle.discard(OWNER, DATE, STAGED_A);

  assert.equal(readDraft(storage, OTHER_DATE, OWNER)?.photoPath, STAGED_A);
  assert.equal(readDraft(storage, DATE, OTHER_OWNER)?.photoPath, STAGED_A);
  assertNoClientPhotoStorageDeletion();
});

test("a cross-tab photo reference written during discard remains intact", async () => {
  const storage = memoryStorage();
  const locks = new TestDraftLocks();
  const resolveLocks = testDraftLockResolver(locks);
  const tabA = createPhotoEditLifecycle(ORIGIN);
  const tabB = createPhotoEditLifecycle(ORIGIN);
  tabA.track(OWNER, DATE, STAGED_A);
  const localDiscard = tabA.discard(OWNER, DATE, STAGED_A);
  tabB.track(OWNER, DATE, STAGED_A);
  tabB.preserve(OWNER, DATE, STAGED_A);
  await writePhotoDraft(storage, OWNER, DATE, STAGED_A, resolveLocks);
  await localDiscard;

  assert.equal(readDraft(storage, DATE, OWNER)?.photoPath, STAGED_A);
  assertNoClientPhotoStorageDeletion();
});

test("removing a stored photo clears its entry reference without deleting the object", async () => {
  const intent = photoSaveIntent(clearPhoto(beginPhotoEdit(STORED)));
  assert.deepEqual(intent, { clear_photo: true });
  const checkInPayload = buildCheckInPayload({ ...emptyCheckIn(), photoPath: null }, DATE, {
    clearPhoto: intent.clear_photo === true,
  });
  const row = buildEntryRow({
    ...checkInPayload,
    user_id: REQUEST_OWNER,
    date: DATE,
  });
  assert.equal(row.photo_path, null);
  const rpcPayload = {
    ...row,
    clear_photo: true,
  };
  const result = await performConditionalSave({
    payload: rpcPayload,
    baseline: { exists: true, id: ROW_ID, revision: 1 },
    requestId: REQUEST_ID,
    rpc: async ({ p_payload }) => {
      assert.equal(p_payload.clear_photo, true);
      assert.equal(p_payload.photo_path, null);
      return {
        data: {
          status: "saved",
          entry: { id: ROW_ID, revision: 2, photo_path: null },
          replayed: false,
        },
        error: null,
      };
    },
    readLatest: async () => ({ id: ROW_ID, revision: 2, photo_path: null }),
  });

  assert.equal(result.status, "saved");
  if (result.status === "saved") assert.equal(result.entry.photo_path, null);
  assertNoClientPhotoStorageDeletion();
});


test("an in-flight save keeps staged tracking until its result settles", async () => {
  const lifecycle = createPhotoEditLifecycle(ORIGIN);
  lifecycle.track(OWNER, DATE, STAGED_A);

  const save = lifecycle.beginSave(OWNER, DATE, STAGED_A);
  assert.ok(save);
  await lifecycle.abandonDate(DATE);
  assert.equal(lifecycle.beginSave(OWNER, DATE, STAGED_A), null);

  await save.finish(true);
  assert.equal(lifecycle.beginSave(OWNER, DATE, STAGED_A), null);
});

test("a failed save and local abandonment retain the remote staged object", async () => {
  const lifecycle = createPhotoEditLifecycle(ORIGIN);
  lifecycle.track(OWNER, DATE, STAGED_A);

  const save = lifecycle.beginSave(OWNER, DATE, STAGED_A);
  assert.ok(save);
  await lifecycle.abandonDate(DATE);

  await save.finish(false);
  assert.equal(lifecycle.track(OWNER, DATE, STAGED_A), true);
  assertNoClientPhotoStorageDeletion();
});

test("an ambiguous network save keeps its staged object after abandonment", async () => {
  const lifecycle = createPhotoEditLifecycle(ORIGIN);
  lifecycle.track(OWNER, DATE, STAGED_A);

  const save = lifecycle.beginSave(OWNER, DATE, STAGED_A);
  assert.ok(save);
  await lifecycle.abandonDate(DATE);
  await save.finish("unknown");

  assert.equal(lifecycle.track(OWNER, DATE, STAGED_A), true);
  assertNoClientPhotoStorageDeletion();
});

test("a failed save remains retryable while the edit is active", async () => {
  const lifecycle = createPhotoEditLifecycle(ORIGIN);
  lifecycle.track(OWNER, DATE, STAGED_A);

  const firstSave = lifecycle.beginSave(OWNER, DATE, STAGED_A);
  assert.ok(firstSave);
  await firstSave.finish(false);
  const retry = lifecycle.beginSave(OWNER, DATE, STAGED_A);
  assert.ok(retry);
  await retry.finish(true);
  await lifecycle.abandonDate(DATE);

  assert.equal(lifecycle.beginSave(OWNER, DATE, STAGED_A), null);
});

test("rapid same-day replacement drops local selections but keeps remote objects", async () => {
  const lifecycle = createPhotoEditLifecycle(ORIGIN);
  lifecycle.track(OWNER, DATE, STAGED_A);
  lifecycle.track(OWNER, DATE, STAGED_B);
  await lifecycle.discard(OWNER, DATE, STAGED_A);

  assert.equal(lifecycle.track(OWNER, DATE, STAGED_A), true);
  await lifecycle.abandonDate(DATE);
  assert.equal(lifecycle.track(OWNER, DATE, STAGED_B), true);
  assertNoClientPhotoStorageDeletion();
});

test("an upload finishing after cancellation is not staged and its remote blob is retained", async () => {
  const upload = deferred<string>();
  const staged: string[] = [];
  let active = true;

  const pending = finishPhotoUpload({
    upload: () => upload.promise,
    isCurrent: () => active,
    onStage: (url) => staged.push(url),
  });
  active = false;
  upload.resolve(STAGED_A);

  assert.equal(await pending, "discarded");
  assert.deepEqual(staged, []);
  assertNoClientPhotoStorageDeletion();
});

test("no browser or save-route photo cleanup path calls Storage.remove", () => {
  assertNoClientPhotoStorageDeletion();
});

test("both photo notices explain retained orphan storage", () => {
  const en = readFileSync(new URL("../src/lib/i18n/translations/en.ts", import.meta.url), "utf8");
  const cs = readFileSync(new URL("../src/lib/i18n/translations/cs.ts", import.meta.url), "utf8");
  assert.match(en, /photo_storage_notice:.*unused orphan.*automatic cleanup is disabled/i);
  assert.match(cs, /photo_storage_notice:.*může zůstat nepoužívaný.*automatické mazání je vypnuté/i);
});

test("discarding a recovered candidate explains that its photo file can remain in storage", () => {
  const panel = readFileSync(
    new URL("../src/components/checkin/DraftCandidateConflictPanel.tsx", import.meta.url),
    "utf8"
  );
  assert.match(
    panel,
    /body=\{t\(localChoice \? "checkin\.draft_candidate_use_body" : "checkin\.draft_candidate_keep_body"\)\}/
  );
  assert.match(en.checkin.draft_candidate_keep_body, /photo reference.*file may remain in storage.*does not permanently delete/i);
  assert.match(cs.checkin.draft_candidate_keep_body, /odkaz na fotku.*soubor může zůstat v úložišti.*nejde o trvalé smazání/i);
  assertNoClientPhotoStorageDeletion();
});

test("canceling photo attachment explains that an in-progress upload may remain in storage", () => {
  const section = readFileSync(
    new URL("../src/components/checkin/sections/PhotoSection.tsx", import.meta.url),
    "utf8"
  );
  assert.match(
    section,
    /onClick=\{cancelUpload\}[\s\S]*?\{t\("checkin\.photo_cancel_upload"\)\}/
  );
  assert.match(
    section,
    /<p role="note"[^>]*>\s*\{t\("checkin\.photo_cancel_upload_notice"\)\}\s*<\/p>/
  );
  assert.match(en.checkin.photo_cancel_upload, /stop attaching/i);
  assert.match(en.checkin.photo_cancel_upload_notice, /upload already in progress may finish.*file may remain in storage.*not permanently delete/i);
  assert.match(cs.checkin.photo_cancel_upload, /zrušit připojení fotky/i);
  assert.match(cs.checkin.photo_cancel_upload_notice, /nahrávání může doběhnout.*soubor může zůstat v úložišti.*nejde o trvalé smazání/i);
  assertNoClientPhotoStorageDeletion();
});
