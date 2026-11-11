import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import { buildEntryRow } from "../src/lib/entry-row.ts";
import {
  aiFailedError,
  aiNetworkError,
  aiPersistError,
} from "../src/lib/checkin/errors.ts";
import {
  buildReflectionRequest,
  checkInGoalRows,
  reflectionCanRequest,
  reflectionMode,
  reflectionPatch,
  screenTimeSnapshot,
} from "../src/lib/checkin/readonly.ts";
import { CHECKIN_SECTION_ORDER } from "../src/lib/checkin/model.ts";

const DATE = "2026-11-07";
const USER = "user-1";

const WEEKLY_GOAL = {
  id: "goal-1",
  activity_key: "chuze",
  name: "Chůze",
  target_count: 3,
  frequency: "weekly",
  is_active: true,
};

const ENTRIES = [
  { date: "2026-10-26", activities: ["Chůze"] },
  { date: "2026-10-28", activities: ["Chůze"] },
  { date: "2026-11-02", activities: ["Chůze"] },
  { date: "2026-11-04", activities: ["Chůze"] },
  { date: "2026-11-09", activities: ["Chůze"] },
];

test("goal progress uses the selected date's calendar period", () => {
  const rows = checkInGoalRows([WEEKLY_GOAL], ENTRIES, DATE);
  const current = rows[0].periods.at(-1);

  // 2026-11-07 is a Saturday: the week is Mon 11-02 .. Sun 11-08. The 11-09
  // row belongs to the next week and must not be counted.
  assert.equal(rows[0].count, 2);
  assert.equal(current?.start, "2026-11-02");
  assert.equal(current?.end, "2026-11-08");
  assert.equal(rows[0].targetMet, false);

  // The same rows read on the next Monday see only that new week.
  const nextWeek = checkInGoalRows([WEEKLY_GOAL], ENTRIES, "2026-11-09");
  assert.equal(nextWeek[0].count, 1);
  assert.equal(nextWeek[0].periods.at(-1)?.start, "2026-11-09");

  // A daily goal counts only the selected day, not "today".
  const daily = checkInGoalRows(
    [{ ...WEEKLY_GOAL, frequency: "daily", target_count: 1 }],
    ENTRIES,
    "2026-11-04"
  );
  assert.equal(daily[0].count, 1);
  assert.equal(daily[0].targetMet, true);
  assert.equal(
    checkInGoalRows(
      [{ ...WEEKLY_GOAL, frequency: "daily", target_count: 1 }],
      ENTRIES,
      "2026-11-05"
    )[0].count,
    0
  );
});

test("screen time keeps null (not synced) distinct from a real zero", () => {
  const empty = screenTimeSnapshot({
    phoneScreenTime: null,
    phoneUnlocks: null,
    phoneTopApps: [],
  });
  assert.equal(empty.hasAny, false);
  assert.equal(empty.screenTime, null);
  assert.equal(empty.unlocks, null);

  const zero = screenTimeSnapshot({
    phoneScreenTime: 0,
    phoneUnlocks: 0,
    phoneTopApps: [],
  });
  assert.equal(zero.hasAny, true);
  assert.equal(zero.screenTime, 0);
  assert.equal(zero.unlocks, 0);

  const partial = screenTimeSnapshot({
    phoneScreenTime: null,
    phoneUnlocks: 42,
    phoneTopApps: [{ app: "Instagram", time_sec: 1200 }],
  });
  assert.equal(partial.hasAny, true);
  assert.equal(partial.screenTime, null);
  assert.equal(partial.unlocks, 42);
});

test("the top-apps snapshot keeps order and is capped at five", () => {
  const apps = Array.from({ length: 6 }, (_, index) => ({
    app: `App ${index + 1}`,
    time_sec: (index + 1) * 60,
  }));
  const snapshot = screenTimeSnapshot({
    phoneScreenTime: 600,
    phoneUnlocks: 3,
    phoneTopApps: apps,
  });

  assert.deepEqual(
    snapshot.topApps.map((app) => app.app),
    ["App 1", "App 2", "App 3", "App 4", "App 5"]
  );
});

test("the reflection request carries identifiers only, never form fields", () => {
  const request = buildReflectionRequest({
    userId: USER,
    date: DATE,
    lang: "cs",
    userName: " Vojta ",
  });
  assert.deepEqual(request, {
    user_id: USER,
    date: DATE,
    lang: "cs",
    userName: "Vojta",
  });

  for (const key of [
    "mood",
    "mood_emoji",
    "sleep_quality",
    "stress",
    "activities",
    "habits",
    "gratitude",
    "note",
    "weather",
    "scale_values",
    "phone_screen_time",
    "ai_reflection",
  ]) {
    assert.equal(key in request, false, `${key} must not be sent`);
  }

  assert.deepEqual(
    buildReflectionRequest({ userId: USER, date: DATE, lang: "en" }),
    { user_id: USER, date: DATE, lang: "en" }
  );
});

test("the reflection patch is a partial ai_reflection write only", () => {
  assert.deepEqual(reflectionPatch(DATE, "  Shrnutí.  "), {
    date: DATE,
    ai_reflection: "Shrnutí.",
  });
  assert.deepEqual(Object.keys(reflectionPatch(DATE, "X") ?? {}).sort(), [
    "ai_reflection",
    "date",
  ]);
  assert.equal(reflectionPatch(DATE, "   "), null);

  // The row the save route receives carries no other field either.
  assert.deepEqual(
    buildEntryRow({ user_id: USER, date: DATE, ai_reflection: "Shrnutí." }),
    { user_id: USER, date: DATE, ai_reflection: "Shrnutí." }
  );
});

test("an unsaved or unstored form can never request a reflection", () => {
  assert.equal(
    reflectionMode({ canGenerate: false, dirty: true, hasReflection: false }),
    "save-first"
  );
  assert.equal(
    reflectionMode({ canGenerate: false, dirty: false, hasReflection: true }),
    "save-first"
  );
  assert.equal(
    reflectionMode({ canGenerate: true, dirty: true, hasReflection: false }),
    "unsaved"
  );
  assert.equal(
    reflectionMode({ canGenerate: true, dirty: true, hasReflection: true }),
    "unsaved"
  );
  assert.equal(
    reflectionMode({ canGenerate: true, dirty: false, hasReflection: false }),
    "generate"
  );
  assert.equal(
    reflectionMode({ canGenerate: true, dirty: false, hasReflection: true }),
    "regenerate"
  );

  assert.equal(reflectionCanRequest("save-first"), false);
  assert.equal(reflectionCanRequest("unsaved"), false);
  assert.equal(reflectionCanRequest("generate"), true);
  assert.equal(reflectionCanRequest("regenerate"), true);
});

test("AI failures map to the visible cooldown, off and error states", () => {
  assert.equal(aiFailedError(401).code, "checkin.reflection_error_auth");
  assert.equal(aiFailedError(403).code, "checkin.reflection_error_restricted");
  assert.equal(aiFailedError(429).code, "checkin.reflection_error_cooldown");
  assert.equal(aiFailedError(500).code, "checkin.reflection_error_server");
  assert.equal(aiNetworkError().code, "checkin.reflection_error_network");
  assert.equal(aiPersistError().code, "checkin.reflection_error_save");
});

const SECTION_FILES = [
  "src/components/checkin/sections/GoalsReadonlySection.tsx",
  "src/components/checkin/sections/ScreenTimeSection.tsx",
  "src/components/checkin/sections/ReflectionSection.tsx",
];

function source(relative: string): string {
  return readFileSync(new URL(`../${relative}`, import.meta.url), "utf8");
}

test("the read-only sections never write form state, storage or the goals API", () => {
  const forbidden = [
    "localStorage",
    "sessionStorage",
    "<input",
    "<textarea",
    "<select",
    'type="checkbox"',
    "setData",
    "onSave",
    "buildCheckInPayload",
    "saveEntry",
    "updateGoal",
    "createGoal",
    "deleteGoal",
    "fetch(",
    "useEffect",
  ];

  for (const file of SECTION_FILES) {
    const text = source(file);
    for (const token of forbidden) {
      assert.equal(
        text.includes(token),
        false,
        `${file} must not contain ${token}`
      );
    }
  }
});

test("the form keeps exactly one save action and maps every section", () => {
  const form = source("src/components/checkin/CheckInForm.tsx");

  assert.equal((form.match(/props\.onSave/g) ?? []).length, 1);
  assert.equal((form.match(/type="submit"/g) ?? []).length, 0);

  for (const id of CHECKIN_SECTION_ORDER) {
    assert.match(
      form,
      new RegExp(`\\n    ${id}:`),
      `${id} must be rendered by the order map`
    );
  }
});

test("the screen's reflection flow sends the builder body and a partial patch", () => {
  const screen = source("src/components/checkin/CheckInScreen.tsx");

  assert.match(screen, /buildReflectionRequest\(/);
  assert.match(screen, /body: JSON\.stringify\(requestBody\)/);
  assert.match(screen, /reflectionPatch\(/);
  const requestStart = screen.indexOf("const requestBody = buildReflectionRequest");
  const requestEnd = screen.indexOf("fetch(\"/api/ai/reflect\"", requestStart);
  assert.ok(requestStart >= 0 && requestEnd > requestStart);
  assert.doesNotMatch(screen.slice(requestStart, requestEnd), /\.\.\.(?:entryData|data)/);
  // The auto-request is skipped when the saved row already has a reflection.
  assert.match(screen, /if \(!savedData\.aiReflection\)/);
});

test("the passive stored view keeps screen time and reflection, not goals", () => {
  const stored = source("src/components/checkin/StoredDayView.tsx");
  const detail = source("src/components/dashboard/DayDetail.tsx");

  // Android's history detail renders the stored row (including screen time and
  // the AI reflection) and has no goals block; the web wrapper delegates to the
  // same shared DayDetail.
  assert.match(stored, /<DayDetail/);
  assert.match(detail, /dashboard\.day_detail\.screen_time/);
  assert.match(detail, /dashboard\.day_detail\.reflection/);
  assert.equal(detail.includes("dashboard.day_detail.goals"), false);
});
