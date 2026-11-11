import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
  CHECKIN_SECTION_ORDER,
  shouldRenderGoalsSection,
  visibleCheckInSections,
} from "../src/lib/checkin/model.ts";
import {
  GRATITUDE_SLOTS,
  gratitudeRowCount,
  setGratitudeSlot,
} from "../src/lib/checkin/gratitude.ts";
import {
  beginPhotoEdit,
  clearPhoto,
  photoAbandonIntent,
  photoSaveIntent,
  stagePhoto,
} from "../src/lib/checkin/photo-edit.ts";
import {
  definitionKey,
  isDuplicateDefinition,
  mutateCatalogue,
} from "../src/lib/checkin/catalogue.ts";
import { postSaveReflectionPhase } from "../src/lib/checkin/post-save.ts";
import { CheckInError } from "../src/lib/checkin/errors.ts";
import { isFutureDateKey } from "../src/lib/checkin/dates.ts";
import { formatScreenTimeDuration } from "../src/lib/dashboard-format.ts";

const STORED =
  "https://project.supabase.co/storage/v1/object/public/diary-photos/user-1/2026-11-07.jpg";
const STAGED =
  "https://project.supabase.co/storage/v1/object/public/diary-photos/user-1/2026-11-07-abc123.jpg";

function source(relative: string): string {
  return readFileSync(new URL(`../${relative}`, import.meta.url), "utf8");
}

test("screen time is omitted when its feature flag is off, order otherwise intact", () => {
  const core = visibleCheckInSections({ screenTime: false });
  const personal = visibleCheckInSections({ screenTime: true });

  assert.equal(core.includes("screenTime"), false);
  assert.equal(core.length, CHECKIN_SECTION_ORDER.length - 1);
  assert.deepEqual(personal, CHECKIN_SECTION_ORDER);
  // Omitting a section must never reorder the remaining twelve.
  assert.deepEqual(
    core,
    CHECKIN_SECTION_ORDER.filter((id) => id !== "screenTime")
  );
});

test("a failed goals read hides the read-only goals block", () => {
  assert.equal(shouldRenderGoalsSection(null), true);
  assert.equal(shouldRenderGoalsSection("checkin.goals_error"), false);
});

test("gratitude has exactly three editable slots and keeps legacy extras", () => {
  assert.equal(GRATITUDE_SLOTS, 3);
  assert.equal(gratitudeRowCount(), 3);

  const legacy = ["a", "b", "c", "legacy-4", "legacy-5"];
  const edited = setGratitudeSlot(legacy, 1, "B");
  assert.deepEqual(edited, ["a", "B", "c", "legacy-4", "legacy-5"]);
  assert.equal(edited.length, 5);

  // Editing a non-canonical index is a no-op, never a fourth slot.
  assert.deepEqual(setGratitudeSlot(["a"], 3, "d"), ["a"]);
  assert.deepEqual(setGratitudeSlot(["a"], -1, "d"), ["a"]);
  // Missing values are padded to the three-slot contract.
  assert.deepEqual(setGratitudeSlot([], 2, "c"), ["", "", "c"]);
});

test("abandoning a picked photo leaves the stored row and object intact", () => {
  const initial = beginPhotoEdit(STORED);
  assert.deepEqual(photoSaveIntent(initial), {});

  const picked = stagePhoto(initial, STAGED);
  assert.deepEqual(photoSaveIntent(picked), {
    photo_path: STAGED,
  });
  assert.deepEqual(photoAbandonIntent(picked), {
    retainedStaged: STAGED,
    retainedStored: STORED,
  });

  // The stored state is immutable: abandoning does not clear or replace it.
  assert.equal(initial.stored, STORED);
  assert.equal(initial.staged, null);
});

test("picking then saving replaces the entry reference without a blob-cleanup intent", () => {
  const picked = stagePhoto(beginPhotoEdit(STORED), STAGED);
  const intent = photoSaveIntent(picked);

  assert.equal(intent.photo_path, STAGED);
  assert.equal(intent.clear_photo, undefined);
  assert.equal("cleanup" in intent, false);
});

test("removing a photo saves a clear directive but retains the stored object", () => {
  const cleared = clearPhoto(beginPhotoEdit(STORED));
  assert.deepEqual(photoSaveIntent(cleared), {
    clear_photo: true,
  });

  // Clearing an already-empty day sends no update.
  assert.deepEqual(photoSaveIntent(clearPhoto(beginPhotoEdit(null))), {});
});

test("a staged photo wins over a pending clear", () => {
  const picked = stagePhoto(clearPhoto(beginPhotoEdit(STORED)), STAGED);
  const intent = photoSaveIntent(picked);
  assert.equal(intent.photo_path, STAGED);
  assert.equal(intent.clear_photo, undefined);
});

test("catalogue keys and duplicate detection match the API", () => {
  assert.equal(definitionKey("  Cold Shower "), "cold_shower");
  assert.equal(definitionKey("Běh   ráno"), "běh_ráno");

  const defs = [
    { key: "cold_shower", label: "Cold Shower" },
    { key: "run", label: "Run" },
  ];
  assert.equal(isDuplicateDefinition(defs, "cold shower"), true);
  assert.equal(isDuplicateDefinition(defs, "COLD_SHOWER"), true);
  assert.equal(isDuplicateDefinition(defs, "Run"), true);
  assert.equal(isDuplicateDefinition(defs, "Running"), false);
  // An edit may keep its own name.
  assert.equal(isDuplicateDefinition(defs, "Cold Shower", "cold_shower"), false);
});

test("a failed catalogue POST never applies a phantom definition", async () => {
  let applied = 0;
  const failure = await mutateCatalogue({
    request: async () => new Response("nope", { status: 500 }),
    apply: () => {
      applied += 1;
    },
    errorCode: "habits.error_save",
    networkCode: "habits.error_network",
  });

  assert.equal(failure, "habits.error_save");
  assert.equal(applied, 0);
});

test("a thrown catalogue request is a network failure, not a phantom add", async () => {
  let applied = 0;
  const failure = await mutateCatalogue({
    request: async () => {
      throw new Error("offline");
    },
    apply: () => {
      applied += 1;
    },
    errorCode: "activities.error_save",
    networkCode: "activities.error_network",
  });

  assert.equal(failure, "activities.error_network");
  assert.equal(applied, 0);
});

test("a successful catalogue POST applies exactly once with the server body", async () => {
  let applied = 0;
  let received: unknown = null;
  const failure = await mutateCatalogue({
    request: async () =>
      new Response(JSON.stringify({ ok: true, key: "run" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    apply: (body) => {
      applied += 1;
      received = body;
    },
    errorCode: "habits.error_save",
    networkCode: "habits.error_network",
  });

  assert.equal(failure, null);
  assert.equal(applied, 1);
  assert.deepEqual(received, { ok: true, key: "run" });
});

test("post-save reflection has one phase and never shows stale text", () => {
  assert.equal(
    postSaveReflectionPhase({ loading: true, error: null, reflection: "old" }),
    "loading"
  );
  assert.equal(
    postSaveReflectionPhase({
      loading: false,
      error: new CheckInError(
        "ai_failed",
        "checkin.reflection_error_server",
        500
      ),
      reflection: "old",
    }),
    "error"
  );
  assert.equal(
    postSaveReflectionPhase({ loading: false, error: null, reflection: "text" }),
    "reflection"
  );
  assert.equal(
    postSaveReflectionPhase({ loading: false, error: null, reflection: "   " }),
    "error"
  );
  assert.equal(
    postSaveReflectionPhase({ loading: false, error: null, reflection: null }),
    "error"
  );
});

test("the server rejects future Prague dates but accepts today and the past", () => {
  const today = "2026-11-07";
  assert.equal(isFutureDateKey("2026-11-08", today), true);
  assert.equal(isFutureDateKey("2027-01-01", today), true);
  assert.equal(isFutureDateKey(today, today), false);
  assert.equal(isFutureDateKey("2026-11-06", today), false);
  assert.equal(isFutureDateKey("not-a-date", today), false);
  assert.equal(isFutureDateKey("2026-02-30", today), false);
});

test("screen time reads as hours and minutes, not a chart label", () => {
  assert.equal(formatScreenTimeDuration(13_140), "3 h 39 min");
  assert.equal(formatScreenTimeDuration(2_700), "45 min");
  assert.equal(formatScreenTimeDuration(10_800), "3 h");
  assert.equal(formatScreenTimeDuration(0), "0 min");
  assert.equal(formatScreenTimeDuration(null), "—");
  assert.equal(formatScreenTimeDuration(undefined), "—");
});

test("the form locks every field during save or guarded navigation", () => {
  const form = source("src/components/checkin/CheckInForm.tsx");
  assert.match(form, /disabled=\{props\.saving \|\| props\.navigationPending\}/);
  assert.match(form, /inert=\{props\.saving \|\| props\.navigationPending\}/);
  assert.match(form, /visibleCheckInSections\(getFeatureFlags\(\)\)/);
  assert.match(form, /shouldRenderGoalsSection\(props\.goalsError\)/);
});

test("draft persistence gates shell tab and popstate navigation", () => {
  const shell = source("src/components/shell/AppShell.tsx");
  const selectStart = shell.indexOf("const handleSelectTab = useCallback");
  const selectEnd = shell.indexOf("const handleNavigateToCheckIn", selectStart);
  const selectTabHandler = shell.slice(selectStart, selectEnd);
  assert.match(selectTabHandler, /replacesCheckinSession/);
  assert.match(selectTabHandler, /leavesActiveCheckin/);
  assert.match(selectTabHandler, /if \(guard && !\(await guard\(\)\)\) return;/);

  const popStateStart = shell.indexOf("const onPopState = async");
  const popStateEnd = shell.indexOf("window.addEventListener(\"popstate\"", popStateStart);
  const popStateHandler = shell.slice(popStateStart, popStateEnd);
  assert.match(popStateHandler, /changesTodaySession/);
  assert.match(popStateHandler, /await guard\(\)/);
  assert.match(popStateHandler, /writeHistory\(current, "replace"\)/);
});

test("photo uploads block shell navigation and draft-storage retry", () => {
  const screen = source("src/components/checkin/CheckInScreen.tsx");
  assert.match(screen, /const photoUploadingRef = useRef\(false\)/);
  assert.match(screen, /if \(navigationInFlight\.current \|\| photoUploadingRef\.current\) return false;/);
  assert.match(screen, /if \(navigationInFlight\.current \|\| photoUploadingRef\.current\) return;/);
  assert.match(screen, /photoUploadingRef\.current = uploading;/);

  const form = source("src/components/checkin/CheckInForm.tsx");
  assert.match(
    form,
    /onClick=\{props\.onRetryDraftStorage\}\s+disabled=\{props\.photoUploading \|\| props\.navigationPending\}/
  );
});

test("habit add never writes a false answer and uses a server-first mutation", () => {
  const screen = source("src/components/checkin/CheckInScreen.tsx");
  const start = screen.indexOf("const addHabit = async");
  const end = screen.indexOf("const updateHabit = async", start);
  assert.ok(start >= 0 && end > start, "addHabit block exists");

  const addHabit = screen.slice(start, end);
  assert.doesNotMatch(addHabit, /updateData\(/);
  assert.match(addHabit, /mutateCatalogue\(/);
  assert.match(addHabit, /isDuplicateDefinition\(/);
  assert.match(addHabit, /return "habits\.error_duplicate"/);
});

test("activity mutations use the server-first helper and rollback on failure", () => {
  const screen = source("src/components/checkin/CheckInScreen.tsx");
  const blocks = [
    ["const addActivity = async", "const removeActivity = async"],
    ["const removeActivity = async", "const restoreActivity = async"],
    ["const restoreActivity = async", "// ── Habit mutations ──"],
  ] as const;

  for (const [startMarker, endMarker] of blocks) {
    const start = screen.indexOf(startMarker);
    const end = screen.indexOf(endMarker, start);
    assert.ok(start >= 0 && end > start, `${startMarker} block exists`);
    assert.match(screen.slice(start, end), /mutateCatalogue\(/);
  }
});

test("activity remove and restore failures name the action they failed", () => {
  const screen = source("src/components/checkin/CheckInScreen.tsx");
  const removeStart = screen.indexOf("const removeActivity = async");
  const restoreStart = screen.indexOf("const restoreActivity = async", removeStart);
  const habitsStart = screen.indexOf("// ── Habit mutations ──", restoreStart);

  assert.match(
    screen.slice(removeStart, restoreStart),
    /errorCode: "activities\.error_remove"/
  );
  assert.match(
    screen.slice(restoreStart, habitsStart),
    /errorCode: "activities\.error_restore"/
  );
});

test("the post-save dialog never starts its own request and closes to Overview", () => {
  const dialog = source(
    "src/components/checkin/dialogs/PostSaveReflectionDialog.tsx"
  );
  assert.doesNotMatch(dialog, /fetch\(/);
  assert.match(dialog, /<Modal/);
  assert.match(dialog, /postSaveReflectionPhase/);
  assert.match(dialog, /checkin\.post_save_close/);

  const screen = source("src/components/checkin/CheckInScreen.tsx");
  assert.match(screen, /<PostSaveReflectionDialog/);
  assert.match(screen, /onOpenOverview\(\)/);
  assert.match(screen, /setPostSaveOpen\(true\)/);
});

test("the save-entry route rejects future dates server-side", () => {
  const route = source("src/app/api/save-entry/route.ts");
  assert.match(route, /isFutureDateKey\(date\)/);
  assert.match(route, /Future date not allowed/);
  assert.match(route, /status: 400/);
});

test("Today is 620px at 1280 while Overview keeps its wide layout", () => {
  const shell = source("src/components/shell/AppShell.tsx");
  assert.match(shell, /max-w-\[620px\]/);
  assert.doesNotMatch(shell, /max-w-\[760px\]/);
  assert.match(shell, /lg:max-w-\[1040px\]/);
  assert.match(shell, /onOpenOverview=/);
});
