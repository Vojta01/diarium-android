import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { join } from "node:path";
import {
  createDraftWriter,
  DRAFT_PREFIX,
  draftKey,
  readDraft,
  readDraftWithStatus,
  type DraftStorage,
} from "../src/lib/checkin/draft.ts";
import { TestDraftLocks, testDraftLockResolver } from "./draft-lock-test-utils.ts";
import { emptyCheckIn } from "../src/lib/checkin/model.ts";
import { CheckInError } from "../src/lib/checkin/errors.ts";
import {
  recoverOnlineLoad,
  resolveDraftConflict,
} from "../src/lib/checkin/draft-recovery.ts";

const ROOT = join(import.meta.dirname, "..");
const screen = readFileSync(
  join(ROOT, "src/components/checkin/CheckInScreen.tsx"),
  "utf8"
);
const checkInForm = readFileSync(
  join(ROOT, "src/components/checkin/CheckInForm.tsx"),
  "utf8"
);
const noteSection = readFileSync(
  join(ROOT, "src/components/checkin/sections/NoteSection.tsx"),
  "utf8"
);

const DATE = "2026-11-06";
const OWNER = "verified-account-a";
const resolveLocks = testDraftLockResolver(new TestDraftLocks());

function makeWriter(storage: DraftStorage, owner: string, date: string) {
  const writer = createDraftWriter(() => storage, owner, resolveLocks);
  writer.observe(date, readDraftWithStatus(storage, date, owner));
  return writer;
}

function memoryStorage(): DraftStorage & { dump(): Map<string, string> } {
  const map = new Map<string, string>();
  return {
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => map.set(key, value),
    removeItem: (key) => map.delete(key),
    dump: () => map,
  };
}

function draft(note: string) {
  const data = emptyCheckIn();
  data.mood = 2;
  data.note = note;
  return data;
}

test("a reconnect with a cloud entry preserves the owner/date draft as a conflict", async () => {
  const storage = memoryStorage();
  const writer = makeWriter(storage, OWNER, DATE);
  const localDraft = draft("edited while offline");
  await writer.flush(DATE, localDraft);
  const originalRaw = storage.getItem(draftKey(DATE, OWNER));
  const cloudData = draft("saved in cloud");
  cloudData.mood = 5;

  const result = await recoverOnlineLoad(
    { status: "entry", entry: { id: "cloud-entry" } },
    storage,
    DATE,
    OWNER,
    () => cloudData,
    resolveLocks
  );

  assert.equal(result.status, "conflict");
  if (result.status !== "conflict") return;
  assert.equal(result.conflict.localDraft.note, "edited while offline");
  assert.equal(result.conflict.cloudData.note, "saved in cloud");
  assert.equal(storage.getItem(draftKey(DATE, OWNER)), originalRaw);
  assert.equal(readDraft(storage, DATE, OWNER)?.note, "edited while offline");
});

test("a local draft is retained until the user confirms keeping the cloud entry", async () => {
  const storage = memoryStorage();
  const writer = makeWriter(storage, OWNER, DATE);
  await writer.flush(DATE, draft("edited while offline"));
  const originalRaw = storage.getItem(draftKey(DATE, OWNER));
  storage.setItem(draftKey("2026-11-05", OWNER), "another date");
  storage.setItem(draftKey(DATE, "other-owner"), "another owner");
  storage.setItem(`${DRAFT_PREFIX}${DATE}`, "ownerless legacy");
  const cloudData = draft("saved in cloud");
  const recovered = await recoverOnlineLoad(
    { status: "entry", entry: { id: "cloud-entry" } },
    storage,
    DATE,
    OWNER,
    () => cloudData,
    resolveLocks
  );
  assert.equal(recovered.status, "conflict");
  if (recovered.status !== "conflict") return;

  const notConfirmed = await resolveDraftConflict({
    storage,
    conflict: recovered.conflict,
    choice: "cloud",
    confirmed: false,
    activeOwnerId: OWNER,
    activeDate: DATE,
    resolveLocks,
  });
  assert.equal(notConfirmed.status, "confirmation_required");
  assert.equal(storage.getItem(draftKey(DATE, OWNER)), originalRaw);

  const confirmed = await resolveDraftConflict({
    storage,
    conflict: recovered.conflict,
    choice: "cloud",
    confirmed: true,
    activeOwnerId: OWNER,
    activeDate: DATE,
    resolveLocks,
  });
  assert.equal(confirmed.status, "cloud");
  assert.equal(confirmed.data?.note, "saved in cloud");
  assert.equal(storage.getItem(draftKey(DATE, OWNER)), null);
  assert.equal(storage.getItem(draftKey("2026-11-05", OWNER)), "another date");
  assert.equal(storage.getItem(draftKey(DATE, "other-owner")), "another owner");
  assert.equal(storage.getItem(`${DRAFT_PREFIX}${DATE}`), "ownerless legacy");
});

test("confirmed local choice keeps its draft and preserves cloud-only fields", async () => {
  const storage = memoryStorage();
  const writer = makeWriter(storage, OWNER, DATE);
  const localDraft = draft("edited while offline");
  localDraft.sleepQuality = 4;
  localDraft.stress = 2;
  localDraft.gratitude = ["offline gratitude", "", ""];
  localDraft.weather = ["dest"];
  localDraft.activities = [];
  localDraft.habits = {};
  localDraft.scaleValues = {};
  await writer.flush(DATE, localDraft);
  const originalRaw = storage.getItem(draftKey(DATE, OWNER));
  const cloudData = draft("saved in cloud");
  cloudData.sleepQuality = 1;
  cloudData.stress = 5;
  cloudData.gratitude = ["cloud gratitude", "", ""];
  cloudData.weather = ["sunny"];
  cloudData.activities = ["cloud activity"];
  cloudData.habits = { hydrated: true };
  cloudData.scaleValues = { "scale-id": 4 };
  cloudData.aiReflection = "cloud reflection";
  const recovered = await recoverOnlineLoad(
    { status: "entry", entry: { id: "cloud-entry" } },
    storage,
    DATE,
    OWNER,
    () => cloudData,
    resolveLocks
  );
  assert.equal(recovered.status, "conflict");
  if (recovered.status !== "conflict") return;

  const resolution = await resolveDraftConflict({
    storage,
    conflict: recovered.conflict,
    choice: "local",
    confirmed: true,
    activeOwnerId: OWNER,
    activeDate: DATE,
    resolveLocks,
  });

  assert.equal(resolution.status, "local");
  if (resolution.status !== "local") return;
  assert.equal(resolution.data.note, "edited while offline");
  assert.equal(resolution.data.sleepQuality, 4);
  assert.equal(resolution.data.stress, 2);
  assert.deepEqual(resolution.data.gratitude, ["offline gratitude", "", ""]);
  assert.deepEqual(resolution.data.weather, ["dest"]);
  assert.deepEqual(resolution.data.activities, ["cloud activity"]);
  assert.deepEqual(resolution.data.habits, { hydrated: true });
  assert.deepEqual(resolution.data.scaleValues, { "scale-id": 4 });
  assert.equal(resolution.data.aiReflection, "cloud reflection");
  assert.equal(storage.getItem(draftKey(DATE, OWNER)), originalRaw);
});

test("a failed network load and retry leave the local draft intact", async () => {
  const storage = memoryStorage();
  const writer = makeWriter(storage, OWNER, DATE);
  await writer.flush(DATE, draft("survives reconnect retry"));
  const originalRaw = storage.getItem(draftKey(DATE, OWNER));

  const failed = await recoverOnlineLoad(
    {
      status: "failed",
      error: new CheckInError("load_failed", "checkin.error_load", 0),
    },
    storage,
    DATE,
    OWNER,
    () => {
      throw new Error("a failed result must not map a cloud entry");
    }
  );
  assert.equal(failed.status, "failed");
  assert.equal(storage.getItem(draftKey(DATE, OWNER)), originalRaw);

  const retry = await recoverOnlineLoad(
    { status: "entry", entry: { id: "cloud-entry" } },
    storage,
    DATE,
    OWNER,
    () => draft("server copy"),
    resolveLocks
  );
  assert.equal(retry.status, "conflict");
  assert.equal(storage.getItem(draftKey(DATE, OWNER)), originalRaw);
});

test("account switches and ownerless legacy data never inherit another owner's draft", async () => {
  const storage = memoryStorage();
  const ownerlessKey = `${DRAFT_PREFIX}${DATE}`;
  const ownerlessValue = JSON.stringify({ mood: 3, note: "legacy" });
  storage.setItem(ownerlessKey, ownerlessValue);
  const writer = makeWriter(storage, OWNER, DATE);
  await writer.flush(DATE, draft("account A only"));
  const accountAValue = storage.getItem(draftKey(DATE, OWNER));

  const accountBLoad = await recoverOnlineLoad(
    { status: "entry", entry: { id: "account-b-cloud-entry" } },
    storage,
    DATE,
    "verified-account-b",
    () => draft("account B cloud"),
    resolveLocks
  );
  assert.equal(accountBLoad.status, "entry");
  assert.equal(storage.getItem(draftKey(DATE, OWNER)), accountAValue);
  assert.equal(storage.getItem(ownerlessKey), ownerlessValue);
});

test("a stale account cannot confirm a cloud choice that deletes another owner's draft", async () => {
  const storage = memoryStorage();
  const writer = makeWriter(storage, OWNER, DATE);
  await writer.flush(DATE, draft("account A only"));
  const originalRaw = storage.getItem(draftKey(DATE, OWNER));
  const recovered = await recoverOnlineLoad(
    { status: "entry", entry: { id: "cloud-entry" } },
    storage,
    DATE,
    OWNER,
    () => draft("server copy"),
    resolveLocks
  );
  assert.equal(recovered.status, "conflict");
  if (recovered.status !== "conflict") return;

  const result = await resolveDraftConflict({
    storage,
    conflict: recovered.conflict,
    choice: "cloud",
    confirmed: true,
    activeOwnerId: "verified-account-b",
    activeDate: DATE,
    resolveLocks,
  });
  assert.equal(result.status, "stale");
  assert.equal(storage.getItem(draftKey(DATE, OWNER)), originalRaw);
});

test("a failed local draft deletion keeps the conflict available for retry", async () => {
  const storage = memoryStorage();
  const writer = makeWriter(storage, OWNER, DATE);
  await writer.flush(DATE, draft("keep until removal is confirmed"));
  const originalRaw = storage.getItem(draftKey(DATE, OWNER));
  const recovered = await recoverOnlineLoad(
    { status: "entry", entry: { id: "cloud-entry" } },
    storage,
    DATE,
    OWNER,
    () => draft("cloud copy"),
    resolveLocks
  );
  assert.equal(recovered.status, "conflict");
  if (recovered.status !== "conflict") return;

  const failingStorage: DraftStorage = {
    getItem: (key) => storage.getItem(key),
    setItem: (key, value) => storage.setItem(key, value),
    removeItem: () => {
      throw new Error("storage unavailable");
    },
  };
  const result = await resolveDraftConflict({
    storage: failingStorage,
    conflict: recovered.conflict,
    choice: "cloud",
    confirmed: true,
    activeOwnerId: OWNER,
    activeDate: DATE,
    resolveLocks,
  });

  assert.equal(result.status, "storage_failed");
  assert.equal(storage.getItem(draftKey(DATE, OWNER)), originalRaw);
});

test("failed or ambiguous save paths cannot clear a draft before a saved response", () => {
  const saveStart = screen.indexOf("const handleSave = async");
  const saveEnd = screen.indexOf("async function requestReflection", saveStart);
  assert.ok(saveStart >= 0 && saveEnd > saveStart, "save handler exists");
  const saveHandler = screen.slice(saveStart, saveEnd);
  const failedBranch = saveHandler.indexOf('if (result.status === "failed")');
  const savedBranch = saveHandler.indexOf('if (result.status !== "saved")');
  const clearDraft = saveHandler.indexOf("draftWriter.markSaved(");
  assert.ok(failedBranch >= 0 && savedBranch > failedBranch);
  assert.ok(clearDraft > savedBranch, "only a saved result clears its local draft");
  assert.match(saveHandler.slice(failedBranch, savedBranch), /return;/);
});

test("loading a cloud row never marks a local draft saved before conflict recovery", () => {
  const recoveryStart = screen.indexOf("const recovery = await recoverOnlineLoad(");
  const conflictStart = screen.indexOf('if (recovery.status === "conflict")', recoveryStart);
  assert.ok(recoveryStart >= 0 && conflictStart > recoveryStart, "load uses conflict recovery");

  const loadBranch = screen.slice(recoveryStart, screen.indexOf("setDraftConflict(null)", conflictStart));
  assert.doesNotMatch(
    loadBranch,
    /draftWriter\.markSaved\(/,
    "loading a cloud row is not proof the local draft was saved"
  );
  assert.match(screen, /setDraftConflict\(recovery\.conflict/);
});

test("reconnect conflict offers confirmed local/cloud recovery and warns before replace", () => {
  assert.match(screen, /<DraftConflictPanel/);
  assert.match(screen, /resolveDraftConflict\(/);
  assert.match(checkInForm, /replacingCloudEntry/);
  assert.match(checkInForm, /draft_local_selected_notice/);
});

test("local recovery suppresses stale effect cleanup until the selected snapshot is current", () => {
  const localChoiceStart = screen.indexOf('if (result.status === "local")');
  const localChoiceEnd = screen.indexOf("const shiftDay", localChoiceStart);
  assert.ok(localChoiceStart >= 0 && localChoiceEnd > localChoiceStart);
  const localChoice = screen.slice(localChoiceStart, localChoiceEnd);
  assert.match(localChoice, /draftWriter\.markUnedited\(\)/);
  assert.doesNotMatch(localChoice, /draftWriter\.markEdited\(\)/);
  assert.match(screen, /if \(draftRecoveryNotice\) draftWriter\.markEdited\(\)/);
});

test("local-only check-in does not expose cloud-backed note templates", () => {
  assert.match(checkInForm, /localOnly=\{props\.localOnly \?\? false\}/);
  assert.match(noteSection, /localOnly/);
  assert.match(noteSection, /offline\.templates_unavailable/);
  assert.match(noteSection, /!localOnly \? \(/);
});
