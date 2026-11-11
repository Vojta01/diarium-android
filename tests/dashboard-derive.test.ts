import assert from "node:assert/strict";
import test from "node:test";

import {
  attachOnThisDay,
  DASHBOARD_CARD_ORDER,
  DASHBOARD_LOAD_DAYS,
  DASHBOARD_WEEK_DAYS,
  deriveDashboard,
  longestStreak,
  monthWindow,
  previousWindow,
  rankTopApps,
  topAppsForCard,
  weekWindow,
} from "../src/lib/dashboard.ts";
import { entry } from "./dashboard-fixtures.ts";

const TODAY = "2026-11-07";

test("the card order is the Android beta5 hierarchy", () => {
  assert.deepEqual(DASHBOARD_CARD_ORDER, [
    "hero_stats",
    "streak",
    "week",
    "mood_chart",
    "on_this_day",
    "goals",
    "screen_time",
    "unlocks",
    "top_apps",
    "reflection",
    "entries",
  ]);
});

test("week and month windows are calendar days ending today, oldest first", () => {
  assert.equal(DASHBOARD_WEEK_DAYS, 7);
  assert.deepEqual(weekWindow(TODAY), [
    "2026-11-01",
    "2026-11-02",
    "2026-11-03",
    "2026-11-04",
    "2026-11-05",
    "2026-11-06",
    "2026-11-07",
  ]);
  assert.deepEqual(previousWindow(TODAY), [
    "2026-10-25",
    "2026-10-26",
    "2026-10-27",
    "2026-10-28",
    "2026-10-29",
    "2026-10-30",
    "2026-10-31",
  ]);

  const month = monthWindow(TODAY);
  assert.equal(month.length, DASHBOARD_LOAD_DAYS);
  assert.equal(month.length, 30);
  assert.equal(month[0], "2026-10-09");
  assert.equal(month[29], TODAY);
});

test("derive fills missing days, sums usage and averages only recorded moods", () => {
  const data = deriveDashboard(TODAY, [
    entry("2026-10-01", { mood: 5 }),
    entry("2026-10-02", { mood: 5 }),
    entry("2026-10-03", { mood: 5 }),
    entry("2026-10-30", { mood: 4 }),
    entry("2026-10-31", { mood: 3 }),
    entry("2026-11-01", { mood: 5 }),
    entry("2026-11-02", { mood: 5 }),
    entry("2026-11-03", {
      mood: 0,
      phone_screen_time: 3600,
      phone_unlocks: 100,
    }),
    entry("2026-11-04", {
      mood: 4,
      phone_screen_time: 1800,
      phone_unlocks: 50,
    }),
    entry("2026-11-05", { mood: 3 }),
    entry("2026-11-06", { mood: 4, ai_reflection: "včerejší reflexe" }),
    entry("2026-11-07", {
      mood: 4,
      note: "dnes",
      gratitude: ["kafe", "", "rodina"],
      moodEmoji: "🙂",
      phone_screen_time: 1800,
      phone_unlocks: 25,
      phone_top_apps: [
        { app: "Snooker", time_sec: 2220 },
        { app: "Hermes", time_sec: 480 },
        { app: "Foto", time_sec: 60 },
      ],
    }),
    entry("2025-11-07", { mood: 3, note: "před rokem" }),
  ]);

  assert.equal(data.week.length, 7);
  assert.equal(data.previousWeek.length, 7);
  assert.equal(data.month.length, 30);

  // 11-03 is a sync-only row: it has an entry and usage, but no mood.
  const syncOnly = data.week.find((day) => day.date === "2026-11-03");
  assert.ok(syncOnly);
  assert.equal(syncOnly.hasEntry, true);
  assert.equal(syncOnly.mood, 0);
  assert.equal(syncOnly.screenTimeSeconds, 3600);
  assert.equal(syncOnly.unlocks, 100);

  const missing = data.previousWeek.find((day) => day.date === "2026-10-25");
  assert.ok(missing);
  assert.equal(missing.hasEntry, false);
  assert.equal(missing.mood, 0);

  // The streak counts recorded days only: 11-07, 11-06, 11-05, 11-04, then
  // 11-03 breaks it even though a synced row exists.
  assert.equal(data.streak, 4);
  assert.equal(data.longestStreak, 4);

  // 5 + 5 + 4 + 3 + 4 + 4 over six answered days; 11-03 is not a zero.
  assert.equal(data.averageMood, 25 / 6);
  assert.equal(data.screenTimeMinutes, 120);
  assert.equal(data.unlocks, 175);

  assert.equal(data.todayEntry?.note, "dnes");
  assert.equal(data.lastGratitude?.date, "2026-11-07");
  assert.deepEqual(data.lastGratitude?.lines, ["kafe", "rodina"]);
  assert.equal(data.lastGratitude?.moodEmoji, "🙂");

  // The reflection card anchors on the newest recorded day, not on the newest
  // row in general.
  assert.equal(data.newestRecorded?.date, "2026-11-07");
  assert.equal(data.newestRecorded?.reflection, null);
  assert.equal(data.newestEntry?.date, "2026-11-07");
  assert.equal(data.reflection?.date, "2026-11-06");
  assert.equal(data.reflection?.text, "včerejší reflexe");

  // The lookbacks are outside the 30-day core and must arrive through the
  // separate memory read, never through the dashboard list.
  assert.equal(data.onThisDay, null);
  assert.equal(data.entryByDate["2025-11-07"], undefined);

  const withMemory = attachOnThisDay(data, TODAY, [
    entry("2025-11-07", { mood: 3, note: "před rokem" }),
  ]);
  assert.equal(withMemory.onThisDay?.date, "2025-11-07");
  assert.equal(withMemory.onThisDay?.horizon, "one_year");
  assert.equal(withMemory.entryByDate["2025-11-07"]?.note, "před rokem");
  // Attaching a memory must not change the 30-day-derived cards.
  assert.equal(withMemory.reflection?.date, "2026-11-06");
  assert.equal(withMemory.topApps, data.topApps);
});

test("a missing today starts the streak from yesterday and never breaks it", () => {
  const data = deriveDashboard(TODAY, [
    entry("2026-11-06", { mood: 5 }),
    entry("2026-11-05", { mood: 4 }),
  ]);

  assert.equal(data.streak, 2);
  assert.equal(data.todayEntry, null);
  assert.equal(data.averageMood, 4.5);
  assert.equal(data.week[6].date, TODAY);
  assert.equal(data.week[6].mood, 0);
  assert.equal(data.week[6].hasEntry, false);
});

test("a sync-only today does not extend the streak", () => {
  const data = deriveDashboard(TODAY, [
    entry("2026-11-07", {
      mood: 0,
      phone_screen_time: 7200,
      phone_unlocks: 200,
    }),
    entry("2026-11-06", { mood: 5 }),
    entry("2026-11-05", { mood: 4 }),
  ]);

  // Today has a row but no mood, so the count starts at yesterday.
  assert.equal(data.streak, 2);
  assert.equal(data.todayEntry?.mood, 0);
  assert.equal(data.screenTimeMinutes, 120);
  assert.equal(data.unlocks, 200);
});

test("duplicate dates are last-wins, independent of input order", () => {
  const lastWins = deriveDashboard(TODAY, [
    entry("2026-11-06", { mood: 0 }),
    entry("2026-11-06", { mood: 5 }),
    entry("2026-11-07", { mood: 4 }),
  ]);
  assert.equal(lastWins.entryByDate["2026-11-06"].mood, 5);
  assert.equal(lastWins.streak, 2);

  const olderWinsWhenLast = deriveDashboard(TODAY, [
    entry("2026-11-07", { mood: 4 }),
    entry("2026-11-06", { mood: 5 }),
    entry("2026-11-06", { mood: 0 }),
  ]);
  assert.equal(olderWinsWhenLast.entryByDate["2026-11-06"].mood, 0);
  assert.equal(olderWinsWhenLast.streak, 1);
});

test("longestStreak ignores gaps and malformed dates", () => {
  assert.equal(
    longestStreak(
      new Set([
        "2026-01-01",
        "2026-01-02",
        "2026-01-03",
        "2026-01-05",
        "2026-01-06",
        "not-a-date",
      ])
    ),
    3
  );
});

test("top apps prefer the newest rankable day and rank by seconds", () => {
  const data = deriveDashboard(TODAY, [
    entry("2026-11-05", {
      mood: 4,
      phone_top_apps: [
        { app: "Snooker", time_sec: 2220 },
        { app: "Hermes WebUI", time_sec: 480 },
        { app: "Foto", time_sec: 60 },
      ],
    }),
    // A morning sync with two apps must not replace a complete snapshot.
    entry("2026-11-06", {
      mood: 4,
      phone_top_apps: [
        { app: "Chrome", time_sec: 900 },
        { app: "Gmail", time_sec: 300 },
      ],
    }),
    entry("2026-11-07", {
      mood: 4,
      phone_top_apps: [{ app: "Mapy", time_sec: 120 }],
    }),
  ]);

  assert.equal(data.topApps?.date, "2026-11-07");
  assert.equal(data.rankedTopApps?.date, "2026-11-05");
  assert.deepEqual(
    rankTopApps(data.rankedTopApps?.apps ?? []).map((app) => app.app),
    ["Snooker", "Hermes WebUI", "Foto"]
  );

  const ranked = rankTopApps([
    { app: "B", time_sec: 120 },
    { app: "A", time_sec: 120 },
    { app: "Sub-minute", time_sec: 59 },
    { app: "Leader", time_sec: 600 },
    { app: "C", time_sec: 60 },
    { app: "D", time_sec: 60 },
    { app: "E", time_sec: 60 },
  ]);
  assert.deepEqual(
    ranked.map((app) => app.app),
    ["Leader", "A", "B", "C", "D"]
  );
});

test("reflection and newest-recorded do not get hidden by a newer synced row", () => {
  const data = deriveDashboard(TODAY, [
    entry("2026-11-06", {
      mood: 4,
      ai_reflection: "text z 6. 11.",
    }),
    entry("2026-11-07", {
      mood: 0,
      phone_screen_time: 1800,
      phone_unlocks: 40,
    }),
  ]);

  assert.equal(data.newestEntry?.date, "2026-11-07");
  assert.equal(data.newestEntry?.reflection, null);
  assert.equal(data.newestRecorded?.date, "2026-11-06");
  assert.equal(data.newestRecorded?.reflection, "text z 6. 11.");
  assert.equal(data.reflection?.date, "2026-11-06");
});

test("a blank reflection counts as none", () => {
  const data = deriveDashboard(TODAY, [
    entry("2026-11-07", { mood: 4, ai_reflection: "   " }),
  ]);
  assert.equal(data.reflection, null);
  assert.equal(data.newestRecorded?.reflection, null);
});

test("no entries yields honest nulls, not zeroes", () => {
  const data = deriveDashboard(TODAY, []);
  assert.equal(data.todayEntry, null);
  assert.equal(data.streak, 0);
  assert.equal(data.longestStreak, 0);
  assert.equal(data.averageMood, null);
  assert.equal(data.screenTimeMinutes, null);
  assert.equal(data.unlocks, null);
  assert.equal(data.topApps, null);
  assert.equal(data.rankedTopApps, null);
  assert.equal(data.reflection, null);
  assert.equal(data.newestRecorded, null);
  assert.equal(data.newestEntry, null);
  assert.equal(data.lastGratitude, null);
  assert.equal(data.onThisDay, null);
  assert.equal(data.week.length, 7);
  assert.equal(data.month.length, 30);
});

test("31+ days old usage, reflection and gratitude never reach the core", () => {
  const data = deriveDashboard(TODAY, [
    entry("2026-10-08", {
      mood: 5,
      note: "starý den",
      gratitude: ["stará vděčnost"],
      ai_reflection: "stará reflexe",
      phone_top_apps: [
        { app: "Old A", time_sec: 600 },
        { app: "Old B", time_sec: 500 },
        { app: "Old C", time_sec: 400 },
      ],
    }),
    entry("2026-11-07", { mood: 4 }),
  ]);

  assert.equal(data.entryByDate["2026-10-08"], undefined);
  assert.equal(data.topApps, null);
  assert.equal(data.rankedTopApps, null);
  assert.equal(data.reflection, null);
  assert.equal(data.lastGratitude, null);
  assert.equal(data.newestEntry?.date, TODAY);
});

test("a run longer than the window is capped at the 30 loaded days", () => {
  // Build 45 consecutive recorded days ending today without relying on the
  // fixture's date arithmetic.
  const run = [];
  for (let back = 44; back >= 0; back -= 1) {
    const date = new Date("2026-11-07T12:00:00Z");
    date.setUTCDate(date.getUTCDate() - back);
    run.push(entry(date.toISOString().slice(0, 10), { mood: 4 }));
  }

  const data = deriveDashboard(TODAY, run);
  assert.equal(data.streak, DASHBOARD_LOAD_DAYS);
  assert.equal(data.longestStreak, DASHBOARD_LOAD_DAYS);
  assert.equal(Object.keys(data.entryByDate).length, DASHBOARD_LOAD_DAYS);
});

test("only yesterday is a one-day streak and a real week entry", () => {
  const data = deriveDashboard(TODAY, [entry("2026-11-06", { mood: 5 })]);

  assert.equal(data.streak, 1);
  assert.equal(data.longestStreak, 1);
  assert.equal(data.todayEntry, null);
  assert.equal(data.week[5].date, "2026-11-06");
  assert.equal(data.week[5].mood, 5);
  assert.equal(data.week[6].hasEntry, false);
});

test("a memory outside the three lanes is ignored and cannot leak old cards", () => {
  const data = deriveDashboard(TODAY, [entry(TODAY, { mood: 4 })]);
  const attached = attachOnThisDay(data, TODAY, [
    entry("2026-08-07", {
      mood: 5,
      note: "mimo dráhy",
      ai_reflection: "stará reflexe",
      phone_top_apps: [
        { app: "Old A", time_sec: 600 },
        { app: "Old B", time_sec: 500 },
        { app: "Old C", time_sec: 400 },
      ],
    }),
  ]);

  assert.equal(attached.onThisDay, null);
  assert.equal(attached.entryByDate["2026-08-07"], undefined);
  assert.equal(attached.reflection, null);
  assert.equal(attached.topApps, null);
});

test("a memory's old reflection and top apps stay out of the core cards", () => {
  const data = deriveDashboard(TODAY, [entry(TODAY, { mood: 4 })]);
  const attached = attachOnThisDay(data, TODAY, [
    entry("2025-11-07", {
      mood: 3,
      note: "před rokem",
      ai_reflection: "stará reflexe",
      phone_top_apps: [
        { app: "Old A", time_sec: 600 },
        { app: "Old B", time_sec: 500 },
        { app: "Old C", time_sec: 400 },
      ],
    }),
  ]);

  assert.equal(attached.onThisDay?.date, "2025-11-07");
  assert.equal(attached.entryByDate["2025-11-07"]?.note, "před rokem");
  assert.equal(attached.reflection, null);
  assert.equal(attached.topApps, null);
  assert.equal(attached.rankedTopApps, null);
});

test("the card falls back to the newest snapshot when no day is rankable", () => {
  const data = deriveDashboard(TODAY, [
    entry("2026-11-05", {
      mood: 4,
      phone_top_apps: [
        { app: "Snooker", time_sec: 2220 },
        { app: "Hermes", time_sec: 480 },
        { app: "Foto", time_sec: 60 },
      ],
    }),
    entry("2026-11-07", {
      mood: 4,
      phone_top_apps: [
        { app: "Chrome", time_sec: 900 },
        { app: "Gmail", time_sec: 300 },
      ],
    }),
  ]);

  assert.equal(data.rankedTopApps?.date, "2026-11-05");
  assert.equal(data.topApps?.date, "2026-11-07");
  assert.equal(topAppsForCard(data)?.date, "2026-11-05");

  const noRankable = deriveDashboard(TODAY, [
    entry("2026-11-07", {
      mood: 4,
      phone_top_apps: [{ app: "Mapy", time_sec: 120 }],
    }),
  ]);
  assert.equal(noRankable.rankedTopApps, null);
  assert.equal(topAppsForCard(noRankable)?.date, "2026-11-07");
});
