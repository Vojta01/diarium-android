import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
  HISTORY_DAYS_PER_WEEK,
  HISTORY_MOOD_COLORS,
  historyDayAriaLabel,
  historyDaysInMonth,
  historyDetail,
  historyDetailDate,
  historyEntryMap,
  historyIsFuture,
  historyIsSelectable,
  historyLeadingBlanks,
  historyMonthCountKey,
  historyMonthOf,
  historyMonthRange,
  historyMonthStats,
  historyMonthTitle,
  historyMonthWeeks,
  historyRovingDate,
  historyStepMonth,
  historySyncedNoteKey,
  historyTrailingBlanks,
  historyWeekdayHeaders,
  type HistoryTranslate,
} from "../src/lib/history.ts";
import { WEATHER_KEYS } from "../src/lib/dashboard-ui.ts";
import { SLEEP_OPTIONS, STRESS_OPTIONS } from "../src/lib/checkin/options.ts";
import { cs } from "../src/lib/i18n/translations/cs.ts";
import { en } from "../src/lib/i18n/translations/en.ts";
import { entry } from "./dashboard-fixtures.ts";

const TODAY = "2026-11-07";

function lookup(tree: Record<string, unknown>, key: string): unknown {
  return key.split(".").reduce<unknown>((node, part) => {
    if (node && typeof node === "object" && part in (node as object)) {
      return (node as Record<string, unknown>)[part];
    }
    return undefined;
  }, tree);
}

/** A real dictionary-backed `t`, so the aria tests read the shipped copy. */
function translator(lang: "cs" | "en"): HistoryTranslate {
  const tree = (lang === "cs" ? cs : en) as unknown as Record<string, unknown>;
  return (path, params) => {
    const value = lookup(tree, path);
    if (typeof value !== "string") return path;
    if (!params) return value;
    return value.replace(/\{(\w+)\}/g, (_, key: string) =>
      params[key] == null ? `{${key}}` : String(params[key])
    );
  };
}

// --- grid / date edge cases -------------------------------------------------

test("November 2026 is Monday-first, six weeks, with a padded trailing week", () => {
  const weeks = historyMonthWeeks(2026, 10);

  assert.equal(historyLeadingBlanks(2026, 10), 6, "1 Nov 2026 is a Sunday");
  assert.equal(historyTrailingBlanks(2026, 10), 6, "30 Nov is a lone Monday");
  assert.equal(weeks.length, 6);
  for (const week of weeks) assert.equal(week.length, HISTORY_DAYS_PER_WEEK);

  // The first row is Mon 26 Oct … Sun 1 Nov: six inert cells, then day 1.
  assert.deepEqual(weeks[0], [
    null,
    null,
    null,
    null,
    null,
    null,
    "2026-11-01",
  ]);
  // The last row must keep all seven columns so 30 Nov cannot stretch.
  assert.deepEqual(weeks.at(-1), [
    "2026-11-30",
    null,
    null,
    null,
    null,
    null,
    null,
  ]);

  const dates = weeks.flat().filter((date): date is string => date !== null);
  assert.equal(dates.length, 30);
  assert.equal(new Set(dates).size, 30);
  assert.equal(dates[0], "2026-11-01");
  assert.equal(dates.at(-1), "2026-11-30");
});

test("October 2026 pads one trailing cell; leap February keeps the 29th", () => {
  const october = historyMonthWeeks(2026, 9);
  assert.equal(historyLeadingBlanks(2026, 9), 3, "1 Oct 2026 is a Thursday");
  assert.equal(historyTrailingBlanks(2026, 9), 1);
  assert.equal(october.length, 5);
  assert.deepEqual(october.at(-1), [
    "2026-10-26",
    "2026-10-27",
    "2026-10-28",
    "2026-10-29",
    "2026-10-30",
    "2026-10-31",
    null,
  ]);

  const february = historyMonthWeeks(2024, 1);
  assert.equal(historyDaysInMonth(2024, 1), 29);
  assert.equal(historyLeadingBlanks(2024, 1), 3);
  assert.equal(historyTrailingBlanks(2024, 1), 3);
  assert.ok(february.flat().includes("2024-02-29"));
});

test("a month that starts on Monday and ends on Sunday needs no padding", () => {
  // February 2021: 1 Feb is a Monday, 28 Feb is a Sunday.
  assert.equal(historyLeadingBlanks(2021, 1), 0);
  assert.equal(historyTrailingBlanks(2021, 1), 0);
  const weeks = historyMonthWeeks(2021, 1);
  assert.equal(weeks.length, 4);
  assert.ok(weeks.every((week) => week.every((date) => date !== null)));
});

test("months roll over the year boundary in both directions", () => {
  assert.deepEqual(historyStepMonth(2026, 11, 1), { year: 2027, monthIndex: 0 });
  assert.deepEqual(historyStepMonth(2026, 0, -1), { year: 2025, monthIndex: 11 });
  assert.deepEqual(historyStepMonth(2026, 0, -13), { year: 2024, monthIndex: 11 });
  assert.deepEqual(historyStepMonth(2026, 11, 13), { year: 2028, monthIndex: 0 });
});

test("month read bounds stop at today and skip a future month", () => {
  assert.deepEqual(historyMonthRange(2026, 10, TODAY), {
    from: "2026-11-01",
    to: "2026-11-07",
  });
  assert.deepEqual(historyMonthRange(2026, 9, TODAY), {
    from: "2026-10-01",
    to: "2026-10-31",
  });
  assert.deepEqual(historyMonthRange(2024, 1, TODAY), {
    from: "2024-02-01",
    to: "2024-02-29",
  });
  assert.equal(historyMonthRange(2026, 11, TODAY), null, "December is future");
  assert.equal(historyMonthRange(2027, 0, "2026-12-31"), null);
});

test("future days are not selectable, today and the past are", () => {
  assert.equal(historyIsFuture("2026-11-08", TODAY), true);
  assert.equal(historyIsFuture(TODAY, TODAY), false);
  assert.equal(historyIsFuture("2026-11-06", TODAY), false);
  assert.equal(historyIsSelectable("2026-11-08", TODAY), false);
  assert.equal(historyIsSelectable(TODAY, TODAY), true);
  assert.equal(historyIsFuture("2027-01-01", "2026-12-31"), true);
});

test("parses a date key into a zero-based month and rejects malformed keys", () => {
  assert.deepEqual(historyMonthOf("2026-11-07"), { year: 2026, monthIndex: 10 });
  assert.deepEqual(historyMonthOf("2024-02-29"), { year: 2024, monthIndex: 1 });
  assert.equal(historyMonthOf("2026-13-01"), null);
  assert.equal(historyMonthOf("07.11.2026"), null);
  assert.equal(historyMonthOf(""), null);
});

test("localized month title and Monday-first weekday headers", () => {
  assert.equal(historyMonthTitle("cs", 2026, 10), "Listopad 2026");
  assert.equal(historyMonthTitle("en", 2026, 10), "November 2026");
  assert.equal(historyMonthTitle("cs", 2026, 0), "Leden 2026");
  assert.deepEqual(historyWeekdayHeaders("cs"), ["Po", "Út", "St", "Čt", "Pá", "So", "Ne"]);
  assert.deepEqual(historyWeekdayHeaders("en"), [
    "Mon",
    "Tue",
    "Wed",
    "Thu",
    "Fri",
    "Sat",
    "Sun",
  ]);
});

test("the calendar uses Android's moodColor palette, not the chip palette", () => {
  assert.equal(HISTORY_MOOD_COLORS[5], "#22d3ee", "5 is cyan");
  assert.equal(HISTORY_MOOD_COLORS[4], "#34d399");
  assert.equal(HISTORY_MOOD_COLORS[3], "#fbbf24");
  assert.equal(HISTORY_MOOD_COLORS[2], "#f97316");
  assert.equal(HISTORY_MOOD_COLORS[1], "#ef4444");
});

test("detail date keeps the Android d. MMMM yyyy shape without a weekday", () => {
  assert.equal(historyDetailDate("2026-11-07", "cs"), "7. listopadu 2026");
  assert.equal(historyDetailDate("2026-11-07", "en"), "November 7, 2026");
  assert.equal(historyDetailDate("not-a-date", "en"), "not-a-date");
});

// --- month counts / selection ----------------------------------------------

test("only a filled mood counts as a record; synced rows are counted apart", () => {
  const entries = [
    entry("2026-11-01", { mood: 5 }),
    entry("2026-11-02", { mood: 0, phone_screen_time: 3600 }),
    entry("2026-11-03", { mood: 3 }),
    entry("2026-10-31", { mood: 4 }),
  ];
  const stats = historyMonthStats(entries, 2026, 10);

  assert.deepEqual(stats, { recorded: 2, syncedOnly: 1, hasRows: true });

  const map = historyEntryMap(entries);
  assert.equal(map.get("2026-11-02")?.mood, 0);
  assert.equal(map.size, 4);

  assert.deepEqual(historyMonthStats([], 2026, 10), {
    recorded: 0,
    syncedOnly: 0,
    hasRows: false,
  });
});

test("the roving tab stop prefers the selected day, then today, then the first", () => {
  const weeks = historyMonthWeeks(2026, 10);

  assert.equal(
    historyRovingDate({
      selected: "2026-11-03",
      year: 2026,
      monthIndex: 10,
      todayKey: TODAY,
      weeks,
    }),
    "2026-11-03"
  );
  assert.equal(
    historyRovingDate({
      selected: null,
      year: 2026,
      monthIndex: 10,
      todayKey: TODAY,
      weeks,
    }),
    TODAY
  );
  assert.equal(
    historyRovingDate({
      selected: null,
      year: 2026,
      monthIndex: 9,
      todayKey: TODAY,
      weeks: historyMonthWeeks(2026, 9),
    }),
    "2026-10-01"
  );
  assert.equal(
    historyRovingDate({
      selected: null,
      year: 2026,
      monthIndex: 11,
      todayKey: TODAY,
      weeks: historyMonthWeeks(2026, 11),
    }),
    null
  );
});

test("count keys use the Czech three-form and the English two-form plural", () => {
  assert.equal(historyMonthCountKey(1, "cs"), "history.count_one");
  assert.equal(historyMonthCountKey(3, "cs"), "history.count_few");
  assert.equal(historyMonthCountKey(5, "cs"), "history.count_many");
  assert.equal(historyMonthCountKey(1, "en"), "history.count_one");
  assert.equal(historyMonthCountKey(3, "en"), "history.count_many");
  assert.equal(historySyncedNoteKey(2, "cs"), "history.synced_note_few");
  assert.equal(historySyncedNoteKey(2, "en"), "history.synced_note_many");
});

// --- day detail rendering ---------------------------------------------------

test("detail keeps null and zero apart and drops only empty values", () => {
  const day = entry("2026-11-03", {
    mood: 4,
    // 0 means "not answered", not a low sleep score.
    sleep_quality: 0,
    stress: 3,
    activities: [" Běh ", "", "   "],
    habits: { koureni: false, chuze: true },
    gratitude: [" ", "Rodina"],
    note: "  Dnes byl dobrý den.  ",
    weather: ["slunecno", ""],
    scale_values: { energie: 3, nalada: 0, stres: -1 },
    phone_screen_time: 0,
    phone_unlocks: 0,
    phone_top_apps: [{ app: "Telegram", time_sec: 600 }],
    photo_path: "https://example.test/photo.jpg",
    ai_reflection: "   ",
  });

  const view = historyDetail(day);

  assert.equal(view.recorded, true);
  assert.equal(view.mood, 4);
  assert.equal(view.sleepQuality, null, "0 is unanswered, not a value");
  assert.equal(view.stress, 3);
  assert.deepEqual(view.activities, ["Běh"]);
  assert.deepEqual(view.habits, [
    { key: "koureni", done: false },
    { key: "chuze", done: true },
  ]);
  assert.deepEqual(view.gratitude, ["Rodina"]);
  assert.equal(view.note, "  Dnes byl dobrý den.  ");
  assert.deepEqual(view.weather, ["slunecno"]);
  assert.deepEqual(view.scales, [{ id: "energie", value: 3 }]);
  assert.equal(view.phone?.screenTimeSeconds, 0, "a real 0-second reading survives");
  assert.equal(view.phone?.unlocks, 0);
  assert.equal(view.phone?.topApps.length, 1);
  assert.equal(view.photoSrc, "https://example.test/photo.jpg");
  assert.equal(view.reflection, null, "whitespace is not a reflection");
  assert.equal(view.sections.sleep, false);
  assert.equal(view.sections.stress, true);
  assert.equal(view.sections.photo, true);
  assert.equal(view.sections.reflection, false);
  assert.equal(view.sections.scales, true);
});

test("a synced-only day is listed but is not a record", () => {
  const day = entry("2026-09-07", {
    mood: 0,
    phone_screen_time: 4000,
    phone_unlocks: 12,
  });
  const view = historyDetail(day);

  assert.equal(view.recorded, false);
  assert.equal(view.mood, null);
  assert.equal(view.phone?.screenTimeSeconds, 4000);
  assert.equal(view.phone?.unlocks, 12);
  assert.equal(view.sections.recorded, false);
});

test("an unsafe photo and an empty day produce no fake content", () => {
  const unsafe = historyDetail(
    entry("2026-11-04", { photo_path: "javascript:alert(1)" })
  );
  assert.equal(unsafe.photoSrc, null);

  const blank = historyDetail(
    entry("2026-11-05", { note: "   ", gratitude: ["  "], weather: [] })
  );
  assert.equal(blank.photoSrc, null);
  assert.equal(blank.phone, null);
  assert.equal(blank.reflection, null);
  assert.equal(blank.sections.note, false);
  assert.equal(blank.sections.gratitude, false);
  assert.equal(blank.sections.weather, false);
});

// --- accessible labels / i18n ----------------------------------------------

test("accessible day labels carry the date, the mood and its label", () => {
  const csLabel = historyDayAriaLabel({
    date: "2026-11-03",
    entry: entry("2026-11-03", { mood: 4 }),
    lang: "cs",
    t: translator("cs"),
    todayKey: TODAY,
  });
  assert.match(csLabel, /3\. listopadu 2026/);
  assert.match(csLabel, /nálada 4 z 5/);
  assert.match(csLabel, /Dobře/);

  const enLabel = historyDayAriaLabel({
    date: "2026-11-03",
    entry: entry("2026-11-03", { mood: 4 }),
    lang: "en",
    t: translator("en"),
    todayKey: TODAY,
  });
  assert.match(enLabel, /November 3, 2026/);
  assert.match(enLabel, /mood 4 out of 5/);
  assert.match(enLabel, /Good/);
});

test("accessible labels distinguish empty, synced and future days", () => {
  const empty = historyDayAriaLabel({
    date: "2026-11-03",
    entry: null,
    lang: "cs",
    t: translator("cs"),
    todayKey: TODAY,
  });
  assert.match(empty, /bez záznamu/);

  const synced = historyDayAriaLabel({
    date: "2026-11-03",
    entry: entry("2026-11-03", { mood: 0 }),
    lang: "cs",
    t: translator("cs"),
    todayKey: TODAY,
  });
  assert.match(synced, /jen automatická data/);

  const future = historyDayAriaLabel({
    date: "2026-11-08",
    entry: null,
    lang: "cs",
    t: translator("cs"),
    todayKey: TODAY,
  });
  assert.match(future, /budoucí den/);
});

test("photo and scale indicators are announced, not only drawn", () => {
  const label = historyDayAriaLabel({
    date: "2026-11-03",
    entry: entry("2026-11-03", {
      mood: 5,
      photo_path: "https://example.test/p.jpg",
      scale_values: { energie: 2 },
    }),
    lang: "en",
    t: translator("en"),
    todayKey: TODAY,
  });
  assert.match(label, /photo/);
  assert.match(label, /scales/);
});

test("every literal history translation key exists in Czech and English", () => {
  const files = [
    readFileSync(
      new URL("../src/components/history/HistoryView.tsx", import.meta.url),
      "utf8"
    ),
    readFileSync(
      new URL("../src/components/history/CalendarCard.tsx", import.meta.url),
      "utf8"
    ),
    readFileSync(
      new URL("../src/components/history/DayDetailCard.tsx", import.meta.url),
      "utf8"
    ),
  ];

  const keys = new Set<string>();
  for (const source of files) {
    for (const match of source.matchAll(/\bt\(\s*"([a-zA-Z0-9_.]+)"/g)) {
      keys.add(match[1]);
    }
  }

  const missing: string[] = [];
  for (const key of keys) {
    for (const [lang, tree] of [
      ["cs", cs],
      ["en", en],
    ] as const) {
      const value = lookup(tree as unknown as Record<string, unknown>, key);
      if (typeof value !== "string" || value.trim() === "") {
        missing.push(`${lang}:${key}`);
      }
    }
  }
  assert.deepEqual(missing, []);
});

test("dynamic history key families cover every rendered option", () => {
  const families: string[] = [
    ...[1, 2, 3, 4, 5].map((mood) => `mood.mood_${mood}`),
    ...WEATHER_KEYS.map((key) => `dashboard.weather.${key}`),
    ...SLEEP_OPTIONS.map((option) => option.labelKey),
    ...STRESS_OPTIONS.map((option) => option.labelKey),
    "gallery.count_one",
    "gallery.count_few",
    "gallery.count_many",
  ];

  for (const [lang, tree] of [
    ["cs", cs],
    ["en", en],
  ] as const) {
    for (const key of families) {
      const value = lookup(tree as unknown as Record<string, unknown>, key);
      assert.equal(typeof value, "string", `${lang}:${key} is missing`);
      assert.notEqual((value as string).trim(), "", `${lang}:${key} is empty`);
    }
  }
});

// --- component contracts ----------------------------------------------------

test("calendar renders a real grid with inert future days and day labels", () => {
  const source = readFileSync(
    new URL("../src/components/history/CalendarCard.tsx", import.meta.url),
    "utf8"
  );

  assert.match(source, /role="grid"/);
  assert.match(source, /role="row"/);
  assert.match(source, /role="gridcell"/);
  assert.match(source, /aria-label=\{t\("history\.calendar_aria"/);
  assert.match(source, /data-testid=\{`calendar-day-\$\{date\}`\}/);
  assert.match(source, /disabled=\{!selectable\}/);
  assert.match(source, /aria-current=\{isToday \? "date" : undefined\}/);
  assert.match(source, /tabIndex=\{date === roving \? 0 : -1\}/);
  assert.match(source, /onKeyDown=/);
  assert.match(source, /onToday/);
});

test("day detail renders the Android section order and one edit action", () => {
  const source = readFileSync(
    new URL("../src/components/history/DayDetailCard.tsx", import.meta.url),
    "utf8"
  );

  // The extracted <ScalesSection> is declared above the card, so its title
  // literal appears earlier in the file. The rendered order is the order of
  // calls inside the card body, starting at the detail model.
  const bodyStart = source.indexOf("const view = historyDetail(entry);");
  assert.notEqual(bodyStart, -1, "the card must render the normalized detail");
  const body = source.slice(bodyStart);

  const order = [
    't("dashboard.day_detail.sleep")',
    't("dashboard.day_detail.stress")',
    't("dashboard.day_detail.weather")',
    't("dashboard.day_detail.activities")',
    't("dashboard.day_detail.habits")',
    't("dashboard.day_detail.gratitude")',
    't("dashboard.day_detail.note")',
    "<ScalesSection",
    't("dashboard.day_detail.screen_time")',
    't("dashboard.day_detail.photo")',
    't("dashboard.day_detail.reflection")',
  ];

  let previous = -1;
  for (const key of order) {
    const index = body.indexOf(key);
    assert.notEqual(index, -1, `${key} must be rendered`);
    assert.ok(index > previous, `${key} is out of order`);
    previous = index;
  }

  assert.match(source, /historyDetail\(entry\)/);
  assert.match(source, /onEdit\(date\)/);
  assert.match(source, /dashboard\.day_detail\.unrecorded_hint/);
  assert.match(source, /dashboard\.day_detail\.open_checkin/);
});
