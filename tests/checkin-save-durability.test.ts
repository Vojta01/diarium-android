import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { join } from "node:path";

import {
  createDraftWriter,
  readDraft,
  readDraftWithStatus,
  writeDraft,
  type DraftStorage,
} from "../src/lib/checkin/draft.ts";
import { TestDraftLocks, testDraftLockResolver } from "./draft-lock-test-utils.ts";
import { emptyCheckIn, hasContent } from "../src/lib/checkin/model.ts";
import { createPhotoEditLifecycle } from "../src/lib/checkin/photo-edit.ts";

const ROOT = join(import.meta.dirname, "..");
const OWNER = "11111111-1111-4111-8111-111111111111";
const OTHER_OWNER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const DATE = "2026-11-06";
const ROW_ID = "22222222-2222-4222-8222-222222222222";
const REQUEST_ID = "55555555-5555-4555-8555-555555555555";
const STAGED_PHOTO =
  "https://project.supabase.co/storage/v1/object/public/diary-photos/11111111-1111-4111-8111-111111111111/2026-11-06-upload-a.jpg";
const resolveLocks = testDraftLockResolver(new TestDraftLocks());

function makeWriter(storage: DraftStorage, ownerId = OWNER, date = DATE) {
  const writer = createDraftWriter(() => storage, ownerId, resolveLocks);
  writer.observe(date, readDraftWithStatus(storage, date, ownerId));
  return writer;
}

async function writeLatestDraft(storage: DraftStorage, date: string, ownerId: string, data: ReturnType<typeof checkIn>) {
  const prior = readDraftWithStatus(storage, date, ownerId);
  const expectedRevision = prior.status === "found" ? prior.revision : null;
  return writeDraft(storage, date, ownerId, data, expectedRevision, resolveLocks);
}

function memoryStorage(): DraftStorage & { dump(): Map<string, string> } {
  const values = new Map<string, string>();
  return {
    get length() {
      return values.size;
    },
    key: (index) => [...values.keys()][index] ?? null,
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
    dump: () => values,
  };
}

function checkIn(note = "Durable note") {
  const data = emptyCheckIn();
  data.mood = 4;
  data.note = note;
  return data;
}

test("a pending conditional save survives remount with exact body, snapshot, and owner/date scope", async () => {
  const saveV2 = await import("../src/lib/checkin/save-v2.ts");
  const storage = memoryStorage();
  const draft = checkIn();
  draft.photoPath = STAGED_PHOTO;
  draft.photoCleared = false;
  const pending = saveV2.createPendingSaveRequest({
    ownerId: OWNER,
    date: DATE,
    baseline: { exists: true, id: ROW_ID, revision: 7 },
    requestId: REQUEST_ID,
    payload: {
      date: DATE,
      mood: 4,
      note: draft.note,
      photo_path: STAGED_PHOTO,
      scale_values: { calm: 0 },
      clear_scale_ids: ["calm"],
    },
    draftSnapshot: {
      ...draft,
      access_token: "synthetic-secret-that-must-not-persist",
      session: { refresh_token: "synthetic-refresh-secret" },
    },
  });

  assert.equal((await saveV2.writePendingSave(storage, pending, resolveLocks)), true);
  const restored = saveV2.readPendingSave(storage, DATE, OWNER);
  assert.equal(restored.status, "found");
  if (restored.status !== "found") return;
  assert.equal(restored.request.requestId, REQUEST_ID);
  assert.equal(restored.request.body, pending.body);
  assert.deepEqual(restored.request.baseline, pending.baseline);
  assert.deepEqual(restored.request.draftSnapshot, pending.draftSnapshot);
  assert.equal("access_token" in restored.request.draftSnapshot, false);
  assert.equal(
    JSON.stringify(restored.request).includes("synthetic-refresh-secret"),
    false
  );
  const wire = JSON.parse(restored.request.body);
  assert.equal(wire.request_id, REQUEST_ID);
  assert.equal(wire.expected_revision, 7);
  assert.equal(wire.photo_path, STAGED_PHOTO);
  assert.deepEqual(wire.clear_scale_ids, ["calm"]);
  assert.equal("Authorization" in wire, false);
  assert.equal("access_token" in wire, false);
  assert.equal(saveV2.readPendingSave(storage, DATE, OTHER_OWNER).status, "missing");
  assert.equal(saveV2.readPendingSave(storage, "2026-11-05", OWNER).status, "missing");
});

test("a persisted request replays its same ID and baseline, confirms the fresh row, then clears", async () => {
  const saveV2 = await import("../src/lib/checkin/save-v2.ts");
  const storage = memoryStorage();
  const pending = saveV2.createPendingSaveRequest({
    ownerId: OWNER,
    date: DATE,
    baseline: { exists: true, id: ROW_ID, revision: 7 },
    requestId: REQUEST_ID,
    payload: { date: DATE, mood: 4, note: "already committed" },
    draftSnapshot: checkIn("already committed"),
  });
  assert.equal((await saveV2.writePendingSave(storage, pending, resolveLocks)), true);

  const latest = {
    id: ROW_ID,
    user_id: OWNER,
    date: DATE,
    revision: 8,
    note: "already committed",
  };
  let rpcCalls = 0;
  let latestReads = 0;
  const replay = await saveV2.performConditionalSave({
    payload: pending.payload,
    baseline: pending.baseline,
    requestId: pending.requestId,
    rpc: async (args) => {
      rpcCalls += 1;
      assert.equal(args.p_request_id, REQUEST_ID);
      assert.equal(args.p_expected_revision, 7);
      assert.equal(args.p_payload.note, "already committed");
      return {
        data: { status: "saved", entry_id: ROW_ID, revision: 8, replayed: true },
        error: null,
      };
    },
    readLatest: async () => {
      latestReads += 1;
      return latest;
    },
  });

  assert.deepEqual(replay, { status: "saved", entry: latest, replayed: true });
  assert.equal(rpcCalls, 1);
  assert.equal(latestReads, 1, "replay must verify the current row token before draft removal");
  assert.equal(
    await saveV2.clearPendingSave(storage, DATE, OWNER, REQUEST_ID, resolveLocks),
    true
  );
  assert.equal(saveV2.readPendingSave(storage, DATE, OWNER).status, "missing");
});

test("a newer auto-draft survives remount and delayed success of an older pending save", async () => {
  const recovery = await import("../src/lib/checkin/save-recovery.ts");
  const saveV2 = await import("../src/lib/checkin/save-v2.ts");
  const storage = memoryStorage();
  const snapshotS1 = checkIn("S1 pending snapshot");
  assert.equal((await writeLatestDraft(storage, DATE, OWNER, snapshotS1)).status, "written");
  const priorDraft = readDraftWithStatus(storage, DATE, OWNER);
  assert.equal(priorDraft.status, "found");
  if (priorDraft.status !== "found") return;
  assert.equal(typeof priorDraft.updatedAt, "number");

  const pending = saveV2.createPendingSaveRequest({
    ownerId: OWNER,
    date: DATE,
    baseline: { exists: true, id: ROW_ID, revision: 7 },
    requestId: REQUEST_ID,
    payload: { date: DATE, mood: 4, note: snapshotS1.note },
    draftSnapshot: snapshotS1,
    draftRevision: priorDraft.revision,
    createdAt: priorDraft.updatedAt! + 1,
  });
  assert.equal((await saveV2.writePendingSave(storage, pending, resolveLocks)), true);

  const snapshotS2 = checkIn("S2 newer local edit");
  assert.equal((await writeLatestDraft(storage, DATE, OWNER, snapshotS2)).status, "written");

  // This is the mounted-screen recovery decision after a reload: retain S2 on
  // screen while the immutable S1 request is still replayed in the background.
  const remountedPending = saveV2.readPendingSave(storage, DATE, OWNER);
  assert.equal(remountedPending.status, "found");
  if (remountedPending.status !== "found") return;
  const restoredResult = recovery.restorePendingSaveData(
    remountedPending.request,
    readDraftWithStatus(storage, DATE, OWNER)
  );
  assert.equal(restoredResult.status, "ready");
  if (restoredResult.status !== "ready") return;
  const restored = restoredResult.data;
  assert.equal(restored.note, "S2 newer local edit");
  assert.equal(recovery.pendingSaveMatchesForm(restored, remountedPending.request), false);

  // A delayed success for S1 clears only its pending record; the mounted
  // screen's mismatch path writes S2 back as the current durable draft.
  assert.equal(
    await saveV2.clearPendingSave(storage, DATE, OWNER, remountedPending.request.requestId, resolveLocks),
    true
  );
  if (!recovery.pendingSaveMatchesForm(restored, remountedPending.request)) {
    assert.equal((await writeLatestDraft(storage, DATE, OWNER, restored)).status, "written");
  }
  assert.equal(readDraft(storage, DATE, OWNER)?.note, "S2 newer local edit");
});

test("a superseded replay stays a conflict and does not clear the durable request", async () => {
  const saveV2 = await import("../src/lib/checkin/save-v2.ts");
  const storage = memoryStorage();
  const pending = saveV2.createPendingSaveRequest({
    ownerId: OWNER,
    date: DATE,
    baseline: { exists: true, id: ROW_ID, revision: 7 },
    requestId: REQUEST_ID,
    payload: { date: DATE, mood: 4, note: "older request" },
    draftSnapshot: checkIn("older request"),
  });
  assert.equal((await saveV2.writePendingSave(storage, pending, resolveLocks)), true);
  const latest = {
    id: ROW_ID,
    user_id: OWNER,
    date: DATE,
    revision: 9,
    note: "newer cloud edit",
  };
  const replay = await saveV2.performConditionalSave({
    payload: pending.payload,
    baseline: pending.baseline,
    requestId: pending.requestId,
    rpc: async () => ({
      data: { status: "saved", entry_id: ROW_ID, revision: 8, replayed: true },
      error: null,
    }),
    readLatest: async () => latest,
  });

  assert.deepEqual(replay, { status: "conflict", latest, reason: "replay_superseded" });
  assert.equal(saveV2.readPendingSave(storage, DATE, OWNER).status, "found");
});

test("pending-save storage failure prevents a durable intent from being claimed", async () => {
  const saveV2 = await import("../src/lib/checkin/save-v2.ts");
  const request = saveV2.createPendingSaveRequest({
    ownerId: OWNER,
    date: DATE,
    baseline: { exists: false, id: null, revision: null },
    requestId: REQUEST_ID,
    payload: { date: DATE, mood: 4, note: "keep me" },
    draftSnapshot: checkIn("keep me"),
  });
  const storage: DraftStorage = {
    getItem: () => null,
    setItem: () => {
      throw new Error("QuotaExceededError");
    },
    removeItem: () => {},
  };
  assert.equal(await saveV2.writePendingSave(storage, request, resolveLocks), false);
});

test("a definitive rejection is demoted to a durable draft before its request is released", async () => {
  const saveV2 = await import("../src/lib/checkin/save-v2.ts");
  const storage = memoryStorage();
  const request = saveV2.createPendingSaveRequest({
    ownerId: OWNER,
    date: DATE,
    baseline: { exists: false, id: null, revision: null },
    requestId: REQUEST_ID,
    payload: { date: DATE, mood: 4, note: "rejected request" },
    draftSnapshot: checkIn("rejected request"),
    draftRevision: null,
  });
  assert.equal(await saveV2.writePendingSave(storage, request, resolveLocks), true);

  const corrected = checkIn("corrected after validation error");
  assert.equal((await saveV2.releaseRejectedPendingSave(storage, request, corrected, resolveLocks)).status, "released");
  assert.equal(saveV2.readPendingSave(storage, DATE, OWNER).status, "missing");
  assert.equal(readDraft(storage, DATE, OWNER)?.note, corrected.note);
});

test("only the exact unavailable-RPC response is definitive and releases to a durable draft", async () => {
  const errors = await import("../src/lib/checkin/errors.ts");
  const saveV2 = await import("../src/lib/checkin/save-v2.ts");
  const unavailable = errors.saveFailedError(503, {
    code: "conditional_save_unavailable",
  });
  const genericServerError = errors.saveFailedError(503, {
    code: "conditional_save_failed",
  });
  const wrongStatus = errors.saveFailedError(500, {
    code: "conditional_save_unavailable",
  });
  const networkError = errors.saveNetworkError(new TypeError("Failed to fetch"));
  assert.equal(errors.isDefinitiveConditionalSaveRejection(unavailable), true);
  assert.equal(errors.isDefinitiveConditionalSaveRejection(genericServerError), false);
  assert.equal(errors.isDefinitiveConditionalSaveRejection(wrongStatus), false);
  assert.equal(errors.isDefinitiveConditionalSaveRejection(networkError), false);

  const storage = memoryStorage();
  const request = saveV2.createPendingSaveRequest({
    ownerId: OWNER,
    date: DATE,
    baseline: { exists: false, id: null, revision: null },
    requestId: REQUEST_ID,
    payload: { date: DATE, mood: 4, note: "server unavailable" },
    draftSnapshot: checkIn("server unavailable"),
    draftRevision: null,
  });
  assert.equal(await saveV2.writePendingSave(storage, request, resolveLocks), true);
  if (errors.isDefinitiveConditionalSaveRejection(unavailable)) {
    assert.equal(
      (await saveV2.releaseRejectedPendingSave(storage, request, checkIn("server unavailable"), resolveLocks)).status,
      "released"
    );
  }
  assert.equal(saveV2.readPendingSave(storage, DATE, OWNER).status, "missing");
  assert.equal(readDraft(storage, DATE, OWNER)?.note, "server unavailable");
});

test("a rejected request stays durable if its fallback draft cannot be written", async () => {
  const saveV2 = await import("../src/lib/checkin/save-v2.ts");
  const backing = memoryStorage();
  const request = saveV2.createPendingSaveRequest({
    ownerId: OWNER,
    date: DATE,
    baseline: { exists: false, id: null, revision: null },
    requestId: REQUEST_ID,
    payload: { date: DATE, mood: 4, note: "keep rejected snapshot" },
    draftSnapshot: checkIn("keep rejected snapshot"),
    draftRevision: null,
  });
  assert.equal(await saveV2.writePendingSave(backing, request, resolveLocks), true);
  const quotaStorage: DraftStorage = {
    getItem: (key) => backing.getItem(key),
    setItem: () => {
      throw new Error("QuotaExceededError");
    },
    removeItem: (key) => backing.removeItem(key),
  };

  assert.equal(
    (await saveV2.releaseRejectedPendingSave(quotaStorage, request, checkIn("edited"), resolveLocks)).status,
    "failed"
  );
  assert.equal(saveV2.readPendingSave(backing, DATE, OWNER).status, "found");
});

test("drafts retain staged photo and explicit clear intent across reload", async () => {
  const storage = memoryStorage();
  const data = checkIn();
  data.photoPath = STAGED_PHOTO;
  data.photoCleared = false;
  assert.equal((await writeLatestDraft(storage, DATE, OWNER, data)).status, "written");
  assert.equal(readDraft(storage, DATE, OWNER)?.photoPath, STAGED_PHOTO);

  data.photoPath = null;
  data.photoCleared = true;
  assert.equal((await writeLatestDraft(storage, DATE, OWNER, data)).status, "written");
  const restored = readDraft(storage, DATE, OWNER);
  assert.equal(restored?.photoPath, null);
  assert.equal(restored?.photoCleared, true);
  assert.equal(hasContent(restored!), true);
});

test("draft reads distinguish unavailable storage from a genuinely missing draft", async () => {
  const draft = await import("../src/lib/checkin/draft.ts");
  assert.deepEqual(draft.readDraftWithStatus(null, DATE, OWNER), { status: "unavailable" });
  assert.deepEqual(
    draft.readDraftWithStatus(
      {
        getItem: () => {
          throw new Error("SecurityError");
        },
        setItem: () => {},
        removeItem: () => {},
      },
      DATE,
      OWNER
    ),
    { status: "failed" }
  );
  assert.deepEqual(
    draft.readDraftWithStatus(memoryStorage(), DATE, OWNER),
    { status: "missing" }
  );
});

test("draft and pending-save photo references remain recoverable", async () => {
  const saveV2 = await import("../src/lib/checkin/save-v2.ts");
  const storage = memoryStorage();
  const data = checkIn();
  data.photoPath = STAGED_PHOTO;
  assert.equal((await writeLatestDraft(storage, DATE, OWNER, data)).status, "written");
  assert.equal(readDraft(storage, DATE, OWNER)?.photoPath, STAGED_PHOTO);

  const pending = saveV2.createPendingSaveRequest({
    ownerId: OWNER,
    date: DATE,
    baseline: { exists: false, id: null, revision: null },
    requestId: REQUEST_ID,
    payload: { date: DATE, mood: 4, photo_path: STAGED_PHOTO },
    draftSnapshot: data,
  });
  assert.equal(await saveV2.writePendingSave(storage, pending, resolveLocks), true);
  const restored = saveV2.readPendingSave(storage, DATE, OWNER);
  assert.equal(restored.status, "found");
  if (restored.status === "found") {
    assert.equal(restored.request.payload.photo_path, STAGED_PHOTO);
    assert.equal(restored.request.draftSnapshot.photoPath, STAGED_PHOTO);
  }
  assert.equal(await saveV2.clearPendingSave(storage, DATE, OWNER, REQUEST_ID, resolveLocks), true);
  assert.equal(readDraft(storage, DATE, OWNER)?.photoPath, STAGED_PHOTO);
});

test("draft persistence completes before local photo tracking is abandoned", async () => {
  const photoEdit = await import("../src/lib/checkin/photo-edit.ts");
  const storage = memoryStorage();
  const data = checkIn();
  data.photoPath = STAGED_PHOTO;
  const events: string[] = [];
  const lifecycle = createPhotoEditLifecycle("https://project.supabase.co");
  lifecycle.track(OWNER, DATE, STAGED_PHOTO);

  await photoEdit.persistDraftBeforePhotoTrackingReset({
    persist: async () => {
      events.push("persist");
      assert.equal((await writeLatestDraft(storage, DATE, OWNER, data)).status, "written");
    },
    ownerId: OWNER,
    date: DATE,
    photoUrl: STAGED_PHOTO,
    preserve: (ownerId, date, url) => {
      events.push("preserve");
      return lifecycle.preserve(ownerId, date, url);
    },
    abandon: () => {
      events.push("abandon");
      return lifecycle.abandonAll();
    },
  });

  assert.deepEqual(events, ["preserve", "persist", "abandon"]);
  assert.equal(readDraft(storage, DATE, OWNER)?.photoPath, STAGED_PHOTO);
});

test("a persisted staged image survives abandonment and explicit local discard", async () => {
  const lifecycle = createPhotoEditLifecycle("https://project.supabase.co");
  assert.equal(lifecycle.track(OWNER, DATE, STAGED_PHOTO), true);
  assert.equal(lifecycle.preserve(OWNER, DATE, STAGED_PHOTO), true);

  await lifecycle.abandonDate(DATE);
  await lifecycle.abandonAll();
  assert.equal(lifecycle.track(OWNER, DATE, STAGED_PHOTO), true);

  await lifecycle.discard(OWNER, DATE, STAGED_PHOTO);
  assert.equal(lifecycle.track(OWNER, DATE, STAGED_PHOTO), true);
});

test("a reflection patch refreshes the CAS baseline and rebases only still-local edits", async () => {
  const readonly = await import("../src/lib/checkin/readonly.ts");
  const base = checkIn("baseline note");
  const current = { ...base, note: "local edit during reflection" };
  const updated = {
    id: ROW_ID,
    user_id: OWNER,
    date: DATE,
    revision: 9,
    mood: 5,
    mood_emoji: "🔥",
    note: "another device edit",
    ai_reflection: "saved reflection",
  };

  const result = readonly.reconcileReflectionEntry({
    ownerId: OWNER,
    date: DATE,
    currentData: current,
    previousBaseline: base,
    updatedEntry: updated,
  });
  assert.equal(result.baseline.revision, 9);
  assert.equal(result.serverData.note, "another device edit");
  assert.equal(result.data.note, "local edit during reflection");
  assert.equal(result.data.mood, 5);
  assert.equal(result.data.aiReflection, "saved reflection");

  const clean = readonly.reconcileReflectionEntry({
    ownerId: OWNER,
    date: DATE,
    currentData: base,
    previousBaseline: base,
    updatedEntry: updated,
  });
  assert.equal(clean.data.note, "another device edit");
  assert.equal(clean.baseline.id, ROW_ID);
});

test("missing-RPC detection uses exact codes and validation codes remain client errors", async () => {
  const rpcErrors = await import("../src/lib/checkin/rpc-errors.ts");
  assert.equal(rpcErrors.isMissingConditionalSaveRpc({ code: "PGRST202" }), true);
  assert.equal(rpcErrors.isMissingConditionalSaveRpc({ code: "42883" }), true);
  assert.equal(
    rpcErrors.isMissingConditionalSaveRpc({
      code: "22023",
      message: "save_daily_entry_v2 rejected a scale identifier",
    }),
    false
  );
  assert.equal(
    rpcErrors.isMissingConditionalSaveRpc({ message: "save_daily_entry_v2 does not exist" }),
    false
  );
  assert.equal(rpcErrors.isConditionalSaveValidationError({ code: "22023" }), true);
  assert.equal(rpcErrors.isConditionalSaveValidationError({ code: "22001" }), false);
  assert.equal(rpcErrors.isConditionalSaveValidationError({ code: "23514" }), false);
  assert.equal(rpcErrors.isConditionalSaveValidationError({ code: "23505" }), false);
  assert.equal(rpcErrors.isConditionalSaveValidationError({ code: "PGRST202" }), false);
});

test("draft flush failures are distinguishable and never announce an autosave", async () => {
  const storage: DraftStorage = {
    getItem: () => null,
    setItem: () => {
      throw new Error("QuotaExceededError");
    },
    removeItem: () => {},
  };
  const writer = makeWriter(storage);
  assert.deepEqual(await writer.flushWithStatus(DATE, checkIn()), { status: "failed" });

  const form = readFileSync(join(ROOT, "src/components/checkin/CheckInForm.tsx"), "utf8");
  const screen = readFileSync(join(ROOT, "src/components/checkin/CheckInScreen.tsx"), "utf8");
  assert.match(form, /draftStorageError/);
  assert.match(form, /draftStorageSaved/);
  assert.match(screen, /flushWithStatus/);
  assert.match(form, /checkin\.error_draft_storage/);
  assert.match(form, /draftStorageSaved\s*\?[^:]*local_draft_auto_saved/);
});

test("a failed locked draft read blocks a new cloud save", () => {
  const screen = readFileSync(join(ROOT, "src/components/checkin/CheckInScreen.tsx"), "utf8");
  const start = screen.indexOf("const durableState = await readPendingSaveAndDraft");
  const end = screen.indexOf("let pendingRequest =", start);
  assert.ok(start >= 0 && end > start, "save preflight is present");
  const preflight = screen.slice(start, end);
  assert.match(
    preflight,
    /if \(\s*durableState\.draft\.status === "failed" \|\|\s*durableState\.draft\.status === "unavailable"\s*\)[\s\S]*?return;/
  );
});

test("the check-in persists the exact pending request before posting it", () => {
  const screen = readFileSync(join(ROOT, "src/components/checkin/CheckInScreen.tsx"), "utf8");
  assert.match(screen, /writePendingSave\(storage, durableRequest\)/);
  assert.match(screen, /saveEntry\(durableRequest\)/);
  assert.match(screen, /readPendingSaveAndDraft\(/);
  assert.match(screen, /releaseRejectedPendingSave\(storage, durableRequest, data\)/);
});
