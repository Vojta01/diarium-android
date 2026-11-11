import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import test from "node:test";
import { join } from "node:path";

import { buildCheckInPayload } from "../src/lib/checkin/payload.ts";
import { emptyCheckIn } from "../src/lib/checkin/model.ts";
import {
  resolveDraftConflict,
  type DraftConflict,
} from "../src/lib/checkin/draft-recovery.ts";
import {
  draftKey,
  readDraft,
  writeDraft,
  type DraftStorage,
} from "../src/lib/checkin/draft.ts";
import { TestDraftLocks, testDraftLockResolver } from "./draft-lock-test-utils.ts";

const ROOT = join(import.meta.dirname, "..");
const HELPER_PATH = join(ROOT, "src/lib/checkin/save-v2.ts");
const OWNER = "11111111-1111-4111-8111-111111111111";
const DATE = "2026-11-06";
const ROW_ID = "22222222-2222-4222-8222-222222222222";
const SCALE_A = "33333333-3333-4333-8333-333333333333";
const SCALE_B = "44444444-4444-4444-8444-444444444444";
const REQUEST_ID = "55555555-5555-4555-8555-555555555555";

function memoryStorage(): DraftStorage {
  const values = new Map<string, string>();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
  };
}

function formData(note: string) {
  const data = emptyCheckIn();
  data.mood = 4;
  data.note = note;
  return data;
}

async function saveV2Module() {
  assert.equal(existsSync(HELPER_PATH), true, "conditional-save helper is implemented");
  return import("../src/lib/checkin/save-v2.ts");
}

test("check-in payload expresses scale clears using the v2 explicit clear intent", () => {
  const data = {
    ...emptyCheckIn(),
    mood: 4,
    scaleValues: { [SCALE_A]: 4, [SCALE_B]: 0 },
  };

  const payload = buildCheckInPayload(data, DATE);

  assert.deepEqual(payload.scale_values, { [SCALE_A]: 4, [SCALE_B]: 0 });
  assert.deepEqual(payload.clear_scale_ids, [SCALE_B]);
});

test("absent-row RPC arguments send explicit SQL NULL token fields", async () => {
  const { buildSaveV2RpcArgs } = await saveV2Module();
  const args = buildSaveV2RpcArgs(
    { user_id: OWNER, date: DATE, note: "draft" },
    { exists: false, id: null, revision: null },
    REQUEST_ID
  );

  assert.deepEqual(args, {
    p_payload: { user_id: OWNER, date: DATE, note: "draft" },
    p_expected_exists: false,
    p_expected_id: null,
    p_expected_revision: null,
    p_request_id: REQUEST_ID,
  });
});

test("server rows produce explicit absent or positive exists baselines", async () => {
  const casHelper = await saveV2Module();
  assert.equal(typeof casHelper.baselineFromEntry, "function");
  assert.deepEqual(casHelper.baselineFromEntry(null), {
    exists: false,
    id: null,
    revision: null,
  });
  assert.deepEqual(
    casHelper.baselineFromEntry({ id: ROW_ID, revision: 9 }),
    { exists: true, id: ROW_ID, revision: 9 }
  );
  assert.throws(
    () => casHelper.baselineFromEntry({ id: ROW_ID }),
    /CAS revision/
  );
});

test("an ambiguous retry reuses its immutable ID only for the identical save intent", async () => {
  const { createOrReuseSaveIntent } = await saveV2Module();
  const baseline = { exists: true, id: ROW_ID, revision: 7 } as const;
  const payload = { user_id: OWNER, date: DATE, note: "edited offline" };
  let generated = 0;
  const makeId = () => {
    generated += 1;
    return REQUEST_ID;
  };

  const first = createOrReuseSaveIntent(payload, baseline, null, makeId);
  const retry = createOrReuseSaveIntent(payload, baseline, first, makeId);
  const changed = createOrReuseSaveIntent(
    { ...payload, note: "new edit" },
    baseline,
    retry,
    makeId
  );

  assert.equal(first.requestId, REQUEST_ID);
  assert.equal(retry.requestId, REQUEST_ID);
  assert.notEqual(changed.fingerprint, first.fingerprint);
  assert.equal(generated, 2);
});

test("a concurrent v2 update after local conflict selection returns conflict and retains the draft", async () => {
  const storage = memoryStorage();
  const resolveLocks = testDraftLockResolver(new TestDraftLocks());
  const originalLocal = formData("offline local note");
  const draftWrite = await writeDraft(storage, DATE, OWNER, originalLocal, null, resolveLocks);
  assert.equal(draftWrite.status, "written");
  if (draftWrite.status !== "written") return;
  const originalRaw = storage.getItem(draftKey(DATE, OWNER));
  const v1Data = formData("cloud V1 note");
  const v1 = {
    id: ROW_ID,
    user_id: OWNER,
    date: DATE,
    revision: 1,
    note: "cloud V1 note",
  };
  const conflict: DraftConflict<typeof v1> = {
    ownerId: OWNER,
    date: DATE,
    draftRevision: draftWrite.revision,
    localDraft: originalLocal,
    cloudEntry: v1,
    cloudData: v1Data,
  };
  const selected = await resolveDraftConflict({
    storage,
    conflict,
    choice: "local",
    confirmed: true,
    activeOwnerId: OWNER,
    activeDate: DATE,
    resolveLocks,
  });
  assert.equal(selected.status, "local");
  if (selected.status !== "local") return;

  // Another device commits V2 after the user selected local against V1.
  let current = { ...v1, revision: 2, note: "cloud V2 note" };
  const eventOrder: string[] = [];
  const submittedArgs: Record<string, unknown>[] = [];
  const { performConditionalSave } = await saveV2Module();
  const result = await performConditionalSave({
    payload: {
      user_id: OWNER,
      date: DATE,
      mood: selected.data.mood,
      note: selected.data.note,
    },
    baseline: { exists: true, id: v1.id, revision: v1.revision },
    requestId: REQUEST_ID,
    rpc: async (args) => {
      eventOrder.push("rpc");
      submittedArgs.push(args as unknown as Record<string, unknown>);
      // This is the atomic v2 server operation: compare tokens at the write
      // boundary, not a preceding GET, and never apply the stale payload.
      if (
        args.p_expected_exists !== true ||
        args.p_expected_id !== current.id ||
        args.p_expected_revision !== current.revision
      ) {
        return {
          data: {
            status: "conflict",
            reason: "stale_revision",
            current_id: current.id,
            current_revision: current.revision,
          },
          error: null,
        };
      }
      current = { ...current, ...args.p_payload, revision: current.revision + 1 };
      return { data: { status: "saved", entry: current, replayed: false }, error: null };
    },
    readLatest: async () => {
      eventOrder.push("read-latest");
      return current;
    },
  });

  assert.equal(result.status, "conflict");
  if (result.status !== "conflict") return;
  assert.equal(result.latest?.note, "cloud V2 note");
  assert.deepEqual(eventOrder, ["rpc", "read-latest"]);
  const sentArgs = submittedArgs[0]!;
  assert.equal(sentArgs.p_expected_exists, true);
  assert.equal(sentArgs.p_expected_id, ROW_ID);
  assert.equal(sentArgs.p_expected_revision, 1);
  assert.equal(current.note, "cloud V2 note", "the stale local payload was not applied");
  assert.equal(storage.getItem(draftKey(DATE, OWNER)), originalRaw);
  assert.equal(readDraft(storage, DATE, OWNER)?.note, "offline local note");
});

test("a CAS conflict rebases unchanged scale and photo fields to the newest cloud row", async () => {
  const recovery = await import("../src/lib/checkin/draft-recovery.ts");
  assert.equal(typeof recovery.rebaseCheckInData, "function");

  const base = formData("cloud V1 note");
  base.mood = 3;
  base.activities = ["base activity"];
  base.habits = { hydration: true };
  base.scaleValues = { [SCALE_A]: 2 };
  base.photoPath = "https://example.test/v1.jpg";
  const local = { ...base, scaleValues: { [SCALE_A]: 0 }, note: "local edit" };
  const remote = formData("cloud V2 note");
  remote.mood = 5;
  remote.activities = ["remote activity"];
  remote.habits = { hydration: false, sleep: true };
  remote.scaleValues = { [SCALE_A]: 7, [SCALE_B]: 4 };
  remote.photoPath = "https://example.test/v2.jpg";

  const rebased = recovery.rebaseCheckInData(local, base, remote);

  assert.equal(rebased.note, "local edit");
  assert.equal(rebased.mood, 5, "unchanged local fields take the newest cloud value");
  assert.deepEqual(rebased.activities, ["remote activity"]);
  assert.deepEqual(rebased.habits, { hydration: false, sleep: true });
  assert.deepEqual(rebased.scaleValues, { [SCALE_A]: 0, [SCALE_B]: 4 });
  assert.equal(rebased.photoPath, "https://example.test/v2.jpg");

  const localPhotoClear = { ...local, photoPath: null };
  const cleared = recovery.rebaseCheckInData(localPhotoClear, base, remote);
  assert.equal(cleared.photoPath, null, "an intentional local photo clear stays explicit");
});

test("a saved SQL receipt without an entry resolves from its unchanged current row", async () => {
  const { performConditionalSave } = await saveV2Module();
  const entry = {
    id: ROW_ID,
    user_id: OWNER,
    date: DATE,
    revision: 8,
    note: "already committed",
  };
  const eventOrder: string[] = [];

  const result = await performConditionalSave({
    payload: { user_id: OWNER, date: DATE, note: "already committed" },
    baseline: { exists: true, id: ROW_ID, revision: 7 },
    requestId: REQUEST_ID,
    // save_daily_entry_v2 stores an immutable receipt without the `entry` field.
    rpc: async () => ({
      data: { status: "saved", entry_id: ROW_ID, revision: 8, replayed: true },
      error: null,
    }),
    readLatest: async () => {
      eventOrder.push("read-latest");
      return entry;
    },
  });

  assert.deepEqual(result, { status: "saved", entry, replayed: true });
  assert.deepEqual(eventOrder, ["read-latest"]);
});

test("a saved replay receipt conflicts when its row has since been superseded", async () => {
  const { performConditionalSave } = await saveV2Module();
  const latest = {
    id: ROW_ID,
    user_id: OWNER,
    date: DATE,
    revision: 9,
    note: "newer write",
  };
  let rpcCalls = 0;
  let latestReads = 0;

  const result = await performConditionalSave({
    payload: { user_id: OWNER, date: DATE, note: "already committed" },
    baseline: { exists: true, id: ROW_ID, revision: 7 },
    requestId: REQUEST_ID,
    rpc: async () => {
      rpcCalls += 1;
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

  assert.deepEqual(result, {
    status: "conflict",
    latest,
    reason: "replay_superseded",
  });
  assert.equal(rpcCalls, 1, "receipt resolution never resubmits the stale payload");
  assert.equal(latestReads, 1);
});

test("the v2 route uses the authenticated client and has no legacy upsert fallback", () => {
  const route = readFileSync(join(ROOT, "src/app/api/save-entry/route.ts"), "utf8");
  assert.match(route, /save_daily_entry_v2/);
  assert.match(route, /NEXT_PUBLIC_SUPABASE_ANON_KEY/);
  assert.match(route, /BEARER_SCHEME = 'Bearer'/);
  assert.match(route, /Authorization: `\$\{BEARER_SCHEME\} \$\{token\}`/);

  assert.match(route, /const user = await verifyAuth\(req\)/);
  assert.match(route, /if \(user && user_id !== user.id\)/);
  assert.match(route, /\.eq\('user_id', user.id\)/);
  assert.match(
    route,
    /isMissingRpc\(error\)[\s\S]+status: missingRpc \? 503 : validationFailure \? 400 : 500/
  );
  assert.match(route, /isConditionalSaveValidationError\(error\)/);

  const casStart = route.indexOf("const outcome = await performConditionalSave");
  const casEnd = route.indexOf("// Keep the separate partial AI-reflection contract", casStart);
  assert.ok(casStart >= 0 && casEnd > casStart, "v2 path and legacy path are distinct");
  const casBranch = route.slice(casStart, casEnd);
  assert.doesNotMatch(casBranch, /SERVICE_KEY|\.upsert\(/);
  assert.doesNotMatch(casBranch, /\.from\('scale_entries'\)/);
  assert.match(casBranch, /performConditionalSave|buildSaveV2RpcArgs/);
  assert.match(route, /clear_scale_ids/);
});

test("missing-cloud conflicts label local draft disposal truthfully", () => {
  const panel = readFileSync(join(ROOT, "src/components/checkin/DraftConflictPanel.tsx"), "utf8");
  const english = readFileSync(join(ROOT, "src/lib/i18n/translations/en.ts"), "utf8");

  assert.match(panel, /pendingChoice === "cloud" && !conflict\.cloudEntry[\s\S]+draft_discard_local_no_cloud/);
  assert.match(english, /draft_discard_local_no_cloud_body:[^\n]*removes this device's local draft/);
});

test("the check-in request binds a loaded exists/id/revision token and request ID", () => {
  const screen = readFileSync(
    join(ROOT, "src/components/checkin/CheckInScreen.tsx"),
    "utf8"
  );
  const db = readFileSync(join(ROOT, "src/lib/supabase/db.ts"), "utf8");
  assert.match(screen, /baseline/);
  assert.match(screen, /requestId/);
  assert.match(screen, /writePendingSave\(storage, durableRequest\)[\s\S]*saveEntry\(durableRequest\)/);
  assert.match(db, /return postConditionalEntryBody\(payloadOrRequest\.body\)/);
  assert.match(db, /expected_exists/);
  assert.match(db, /request_id/);
});
