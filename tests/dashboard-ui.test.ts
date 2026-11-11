import assert from "node:assert/strict";
import test from "node:test";

import {
  DASHBOARD_CARD_ORDER,
  deriveDashboard,
  type DashboardCardId,
  type DashboardData,
  type DashboardDay,
} from "../src/lib/dashboard.ts";
import type { GoalProgressData } from "../src/lib/goal-progress.ts";
import type { GoalDefinition } from "../src/lib/goal-progress.ts";
import {
  chartDays,
  chartRangeDays,
  DASHBOARD_FULL_SPAN_CLASS,
  DASHBOARD_GRID_CLASS,
  dashboardCardSpan,
  dayDetailSections,
  dayForm,
  dayLabelKey,
  formatDashboardDate,
  goalCardRows,
  goalPeriodKey,
  isDashboardCardVisible,
  latestAverage,
  memoryGratitudeLines,
  memoryPhotoSrc,
  metricBestWorst,
  moodMovingAverage,
  movingAverageOf,
  reflectionView,
  scaleValueText,
  sumMetric,
  topAppsCardState,
  visibleDashboardCards,
  weatherKey,
  type DashboardCardSources,
} from "../src/lib/dashboard-ui.ts";
import { entry } from "./dashboard-fixtures.ts";

const TODAY = "2026-11-07";

function day(date: string, mood = 0): DashboardDay {
  return {
    date,
    mood,
    hasEntry: true,
    screenTimeSeconds: null,
    unlocks: null,
  };
}

function goalProgress(
  overrides: Partial<GoalProgressData<GoalDefinition>> = {}
): GoalProgressData<GoalDefinition> {
  return {
    goal: {
      id: "g1",
      activity_key: "chuze",
      name: "Walk",
      target_count: 3,
      frequency: "weekly",
      is_active: true,
    },
    frequency: "weekly",
    count: 1,
    streak: 0,
    periods: [],
    targetMet: false,
    remaining: 2,
    fraction: 1 / 3,
    previousMet: null,
    history: [],
    metPeriods: 0,
    knownPeriods: 0,
    ...overrides,
  };
}

function sources(
  data: DashboardData,
  goals: GoalProgressData[] | null = [],
  goalsError: string | null = null
): DashboardCardSources {
  return { data, goals, goalsError };
}

function emptyData(overrides: Partial<DashboardData> = {}): DashboardData {
  return {
    ...deriveDashboard(TODAY, []),
    ...overrides,
  };
}

test("the card order is the Android beta5 order, entries last", () => {
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

test("B1 renders every fixed card and never the Entries card", () => {
  const visible = visibleDashboardCards(sources(emptyData()));
  assert.deepEqual(visible, [
    "hero_stats",
    "streak",
    "week",
    "mood_chart",
    "goals",
    "screen_time",
    "unlocks",
  ]);
  assert.equal(visible.includes("entries"), false);
});

test("conditional cards appear only when their data exists", () => {
  const data = emptyData({
    onThisDay: {
      date: "2025-11-07",
      horizon: "one_year",
      entry: entry("2025-11-07", { mood: 4, note: "A year ago" }),
    },
    topApps: { date: "2026-11-06", apps: [{ app: "A", time_sec: 600 }] },
    reflection: { date: "2026-11-06", text: "Keep going." },
    newestRecorded: {
      date: "2026-11-06",
      entry: entry("2026-11-06", { mood: 4 }),
      reflection: "Keep going.",
    },
  });
  const visible = visibleDashboardCards(sources(data));
  for (const id of ["on_this_day", "top_apps", "reflection"] as const) {
    assert.ok(visible.includes(id), `${id} should be visible`);
  }
  // Still in the canonical order.
  const indices = visible.map((id) => DASHBOARD_CARD_ORDER.indexOf(id));
  assert.deepEqual(indices, [...indices].sort((a, b) => a - b));
});

test("a failed goals read still renders the goals card (error, not empty)", () => {
  const visible = visibleDashboardCards(
    sources(emptyData(), null, "errors.goals_load")
  );
  assert.ok(visible.includes("goals"));
  assert.equal(
    isDashboardCardVisible("goals", sources(emptyData(), null, "errors.goals_load")),
    true
  );
});

test("Entries can be switched on for B2 without touching the order", () => {
  const visible = visibleDashboardCards(sources(emptyData()), { entries: true });
  assert.equal(visible.at(-1), "entries");
});

test("full-width cards span two columns; an absent partner widens its pair", () => {
  const all: DashboardCardId[] = [
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
  ];
  assert.equal(dashboardCardSpan("hero_stats", all), 2);
  assert.equal(dashboardCardSpan("mood_chart", all), 2);
  assert.equal(dashboardCardSpan("streak", all), 1);
  assert.equal(dashboardCardSpan("week", all), 1);

  const noMemory = all.filter((id) => id !== "on_this_day");
  assert.equal(dashboardCardSpan("goals", noMemory), 2);
  assert.equal(dashboardCardSpan("screen_time", all), 1);
  assert.equal(dashboardCardSpan("unlocks", all), 1);

  const noReflection = all.filter((id) => id !== "reflection");
  assert.equal(dashboardCardSpan("top_apps", noReflection), 2);
});

test("the overview grid splits only at lg, never at md", () => {
  assert.match(DASHBOARD_GRID_CLASS, /\bgrid-cols-1\b/);
  assert.match(DASHBOARD_GRID_CLASS, /\blg:grid-cols-2\b/);
  assert.doesNotMatch(DASHBOARD_GRID_CLASS, /\bmd:/);
  assert.equal(DASHBOARD_FULL_SPAN_CLASS, "lg:col-span-2");
  assert.doesNotMatch(DASHBOARD_FULL_SPAN_CLASS, /\bmd:/);
});

test("memory gratitude trims blanks and caps at three lines", () => {
  assert.deepEqual(
    memoryGratitudeLines(
      entry("2025-11-07", {
        gratitude: ["  rodina  ", "", "   ", "kafe", "klid", "moře"],
      })
    ),
    ["rodina", "kafe", "klid"]
  );
  assert.deepEqual(memoryGratitudeLines(entry("2025-11-07")), []);
});

test("memory photos accept http(s) and inline images only", () => {
  assert.equal(
    memoryPhotoSrc(entry("d", { photo_path: " https://x.test/y.jpg " })),
    "https://x.test/y.jpg"
  );
  assert.equal(
    memoryPhotoSrc(entry("d", { photo_path: "data:image/jpeg;base64,AAAA" })),
    "data:image/jpeg;base64,AAAA"
  );
  assert.equal(
    memoryPhotoSrc(entry("d", { photo_path: "javascript:alert(1)" })),
    null
  );
  assert.equal(
    memoryPhotoSrc(entry("d", { photo_path: "file:///etc/passwd" })),
    null
  );
  assert.equal(memoryPhotoSrc(entry("d")), null);
});

test("goal rows are unmet-first, stable, and capped at four", () => {
  const goals = [
    goalProgress({ goal: { ...goalProgress().goal, id: "met-1" }, targetMet: true }),
    goalProgress({ goal: { ...goalProgress().goal, id: "unmet-1" }, targetMet: false }),
    goalProgress({ goal: { ...goalProgress().goal, id: "met-2" }, targetMet: true }),
    goalProgress({ goal: { ...goalProgress().goal, id: "unmet-2" }, targetMet: false }),
    goalProgress({ goal: { ...goalProgress().goal, id: "unmet-3" }, targetMet: false }),
  ];
  const rows = goalCardRows(goals);
  assert.equal(rows.length, 4);
  assert.deepEqual(
    rows.map((row) => row.goal.id),
    ["unmet-1", "unmet-2", "unmet-3", "met-1"]
  );
  // The input is not mutated.
  assert.equal(goals[0].goal.id, "met-1");
});

test("a week range takes the last seven days of the month window", () => {
  const month = Array.from({ length: 30 }, (_, index) =>
    day(`2026-10-${String(index + 1).padStart(2, "0")}`, 3)
  );
  assert.equal(chartRangeDays("week"), 7);
  assert.equal(chartRangeDays("month"), 30);
  assert.equal(chartDays(month, "week").length, 7);
  assert.equal(chartDays(month, "week")[0].date, "2026-10-24");
  assert.equal(chartDays(month, "month").length, 30);
  assert.deepEqual(chartDays(month, "month"), month);
});

test("usage totals ignore days the phone never synced", () => {
  const days: DashboardDay[] = [
    { ...day("2026-11-01"), screenTimeSeconds: 3600, unlocks: 10 },
    { ...day("2026-11-02"), screenTimeSeconds: null, unlocks: null },
    { ...day("2026-11-03"), screenTimeSeconds: 1800, unlocks: 5 },
  ];
  assert.equal(sumMetric(days, "screen_time"), 90);
  assert.equal(sumMetric(days, "unlocks"), 15);
  assert.equal(sumMetric([days[1]], "screen_time"), null);
});

test("top apps prefer a rankable older snapshot over a thin newest one", () => {
  const data = emptyData({
    topApps: {
      date: "2026-11-07",
      apps: [
        { app: "A", time_sec: 120 },
        { app: "B", time_sec: 60 },
      ],
    },
    rankedTopApps: {
      date: "2026-11-06",
      apps: [
        { app: "A", time_sec: 120 },
        { app: "B", time_sec: 60 },
        { app: "C", time_sec: 600 },
      ],
    },
  });
  const state = topAppsCardState(data);
  assert.equal(state.kind, "ranked");
  if (state.kind === "ranked") {
    assert.equal(state.date, "2026-11-06");
    assert.deepEqual(state.apps.map((app) => app.app), ["C", "A", "B"]);
  }
});

test("a snapshot shorter than three rankable apps is not enough data", () => {
  const state = topAppsCardState(
    emptyData({
      topApps: {
        date: "2026-11-07",
        apps: [
          { app: "A", time_sec: 120 },
          { app: "B", time_sec: 0 },
        ],
      },
    })
  );
  assert.equal(state.kind, "not_enough");
});

test("no snapshot at all hides the top-apps card", () => {
  assert.deepEqual(topAppsCardState(emptyData()), { kind: "hidden" });
});

test("reflection view keeps the last text visible while the anchor is missing", () => {
  const view = reflectionView({
    newestRecorded: {
      date: "2026-11-06",
      entry: entry("2026-11-06", { mood: 4 }),
      reflection: null,
    },
    newestEntry: null,
    reflection: { date: "2026-11-04", text: "Older text" },
  });
  assert.ok(view);
  assert.equal(view?.text, "Older text");
  assert.equal(view?.date, "2026-11-04");
  assert.equal(view?.missingDate, "2026-11-06");
  assert.equal(view?.canGenerate, true);
});

test("reflection view anchors on the recorded day's own text", () => {
  const view = reflectionView({
    newestRecorded: {
      date: "2026-11-06",
      entry: entry("2026-11-06", { mood: 4 }),
      reflection: "Fresh text",
    },
    newestEntry: null,
    reflection: { date: "2026-11-04", text: "Older text" },
  });
  assert.equal(view?.text, "Fresh text");
  assert.equal(view?.date, "2026-11-06");
  assert.equal(view?.missingDate, null);
  assert.equal(view?.canGenerate, false);
});

test("reflection view is null when nothing exists to show", () => {
  assert.equal(
    reflectionView({
      newestRecorded: null,
      newestEntry: null,
      reflection: null,
    }),
    null
  );
});

test("day forms follow Czech plural rules and English two-form rules", () => {
  assert.equal(dayForm(1, "cs"), "one");
  assert.equal(dayForm(3, "cs"), "few");
  assert.equal(dayForm(5, "cs"), "many");
  assert.equal(dayForm(1, "en"), "one");
  assert.equal(dayForm(3, "en"), "many");
});

test("goal period keys map daily, weekly and monthly", () => {
  assert.equal(goalPeriodKey("daily"), "period_daily");
  assert.equal(goalPeriodKey("weekly"), "period_weekly");
  assert.equal(goalPeriodKey("monthly"), "period_monthly");
  assert.equal(goalPeriodKey("weird"), "period_weekly");
});

test("moving average skips unanswered days instead of pulling it to zero", () => {
  assert.deepEqual(movingAverageOf([null, null, 4, null, 2], 7), [
    null,
    null,
    4,
    4,
    3,
  ]);
  assert.deepEqual(movingAverageOf([1, 2, 3, 4], 2), [1, 1.5, 2.5, 3.5]);
});

test("mood moving average aligns to the mood series", () => {
  const days = [day("2026-11-01", 0), day("2026-11-02", 2), day("2026-11-03", 4)];
  assert.deepEqual(moodMovingAverage(days, 7), [null, 2, 3]);
  assert.equal(latestAverage([null, 2, null, 3]), 3);
  assert.equal(latestAverage([null, null]), null);
});

test("best and worst days come from the current week's values only", () => {
  const days = [
    { ...day("2026-11-01"), unlocks: 5 },
    { ...day("2026-11-02"), unlocks: 20 },
    { ...day("2026-11-03"), unlocks: null },
    { ...day("2026-11-04"), unlocks: 12 },
  ];
  const { best, worst } = metricBestWorst(days, "unlocks");
  assert.equal(best?.day.date, "2026-11-02");
  assert.equal(worst?.day.date, "2026-11-01");
});

test("dates format in the UI language from UTC noon", () => {
  assert.equal(formatDashboardDate("2026-11-07", "cs", "numeric"), "7. 11. 2026");
  assert.equal(formatDashboardDate("2026-11-07", "en", "numeric"), "Nov 7, 2026");
  assert.equal(formatDashboardDate("2026-11-07", "cs", "dayMonth"), "7. 11.");
  assert.equal(formatDashboardDate("2026-11-07", "en", "dayMonth"), "Nov 7");
  assert.equal(formatDashboardDate("not-a-date", "en"), "not-a-date");
});

test("weekday keys match the existing day_labels dictionary", () => {
  // 2026-11-07 is a Saturday.
  assert.equal(dayLabelKey("2026-11-07"), "dashboard.day_labels.sat");
  assert.equal(dayLabelKey("2026-11-08"), "dashboard.day_labels.sun");
  assert.equal(dayLabelKey("nope"), null);
});

test("weather keys accept picker keys, Czech labels and English labels", () => {
  assert.equal(weatherKey("slunecno"), "slunecno");
  assert.equal(weatherKey("Slunečno"), "slunecno");
  assert.equal(weatherKey("Rain"), "dest");
  assert.equal(weatherKey("Déšť"), "dest");
  assert.equal(weatherKey("meteor shower"), null);
});

test("day detail sections are omitted when the field is empty", () => {
  const empty = dayDetailSections(entry("2026-11-07"));
  assert.equal(empty.recorded, false);
  assert.equal(empty.sleep, false);
  assert.equal(empty.habits, false);
  assert.equal(empty.photo, false);

  const full = dayDetailSections(
    entry("2026-11-07", {
      mood: 4,
      sleep_quality: 3,
      stress: 2,
      weather: ["dest"],
      activities: ["Chůze"],
      habits: { cteni: true },
      gratitude: ["Family"],
      note: "A good day",
      scale_values: { energy: 4, empty: 0 },
      phone_screen_time: 3600,
      phone_unlocks: 10,
      phone_top_apps: [{ app: "A", time_sec: 600 }],
      photo_path: "https://example.com/p.jpg",
      ai_reflection: "Keep going.",
    })
  );
  for (const [key, value] of Object.entries(full)) {
    assert.equal(value, true, `${key} should be present`);
  }
});

test("scale values use the real maximum and fall back to five", () => {
  assert.equal(scaleValueText(3, 5), "3 / 5");
  assert.equal(scaleValueText(3, 10), "3 / 10");
  assert.equal(scaleValueText(3, null), "3 / 5");
  assert.equal(scaleValueText(3, 0), "3 / 5");
});
