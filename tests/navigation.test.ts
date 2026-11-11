import assert from "node:assert/strict";
import test from "node:test";

import {
  DEFAULT_NAV,
  MAIN_TABS,
  SUB_SCREENS,
  closeSub,
  isMainTab,
  isValidIsoDate,
  navScrollKey,
  navToSearch,
  navAfterCheckinSave,
  normalizeNav,
  openCheckin,
  openSub,
  parentTabForSub,
  parseNavFromSearch,
  selectTab,
  shellPanelId,
} from "../src/lib/navigation.ts";

test("exposes exactly the five Android bottom destinations in order", () => {
  assert.deepEqual([...MAIN_TABS], ["overview", "today", "history", "stats", "settings"]);
  assert.equal(MAIN_TABS.length, 5);
});

test("sub-screens never become a sixth tab", () => {
  for (const sub of SUB_SCREENS) {
    assert.ok(!(MAIN_TABS as readonly string[]).includes(sub));
    assert.ok(isMainTab(parentTabForSub(sub)));
  }
});

test("defaults to the overview destination for empty or unknown searches", () => {
  assert.deepEqual(parseNavFromSearch(""), DEFAULT_NAV);
  assert.deepEqual(parseNavFromSearch("?"), DEFAULT_NAV);
  assert.deepEqual(parseNavFromSearch("?view=nope"), DEFAULT_NAV);
  assert.deepEqual(parseNavFromSearch("?view=overview"), DEFAULT_NAV);
});

test("parses a stats deep link", () => {
  assert.deepEqual(parseNavFromSearch("?view=stats"), {
    tab: "stats",
    sub: null,
    checkinDate: null,
  });
});

test("restores ?edit= for a stored session", () => {
  assert.deepEqual(parseNavFromSearch("?edit=2026-11-06"), {
    tab: "today",
    sub: null,
    checkinDate: "2026-11-06",
  });
  assert.deepEqual(parseNavFromSearch("?view=today&edit=2026-11-06"), {
    tab: "today",
    sub: null,
    checkinDate: "2026-11-06",
  });
});

test("ignores malformed or impossible edit dates", () => {
  assert.deepEqual(parseNavFromSearch("?edit=06.11.2026"), DEFAULT_NAV);
  assert.deepEqual(parseNavFromSearch("?edit=2026-02-31"), DEFAULT_NAV);
  assert.deepEqual(parseNavFromSearch("?edit="), DEFAULT_NAV);
  assert.equal(isValidIsoDate("2026-11-06"), true);
  assert.equal(isValidIsoDate("2026-13-01"), false);
});

test("parses a sub-screen deep link and opens its parent tab", () => {
  assert.deepEqual(parseNavFromSearch("?sub=achievements"), {
    tab: "overview",
    sub: "achievements",
    checkinDate: null,
  });
  assert.deepEqual(parseNavFromSearch("?view=stats&sub=reports"), {
    tab: "stats",
    sub: "reports",
    checkinDate: null,
  });
  assert.deepEqual(parseNavFromSearch("?view=settings&sub=achievements"), {
    tab: "settings",
    sub: "achievements",
    checkinDate: null,
  });
  assert.deepEqual(parseNavFromSearch("?sub=unknown"), DEFAULT_NAV);
});

test("routes the About sub-screen through Settings", () => {
  assert.ok((SUB_SCREENS as readonly string[]).includes("about"));
  assert.equal(parentTabForSub("about"), "settings");
  assert.deepEqual(parseNavFromSearch("?sub=about"), {
    tab: "settings",
    sub: "about",
    checkinDate: null,
  });
  assert.equal(
    navToSearch({ tab: "settings", sub: "about", checkinDate: null }),
    "?view=settings&sub=about"
  );
});

test("every bottom tab controls a distinct shell panel id", () => {
  const ids = MAIN_TABS.map((tab) => shellPanelId(tab));

  assert.deepEqual(ids, [
    "shell-panel-overview",
    "shell-panel-today",
    "shell-panel-history",
    "shell-panel-stats",
    "shell-panel-settings",
  ]);
  assert.equal(new Set(ids).size, MAIN_TABS.length);
});

test("serializes the default destination to a clean URL", () => {
  assert.equal(navToSearch(DEFAULT_NAV), "");
  assert.equal(navToSearch({ tab: "stats", sub: null, checkinDate: null }), "?view=stats");
  assert.equal(
    navToSearch({ tab: "today", sub: null, checkinDate: "2026-11-06" }),
    "?view=today&edit=2026-11-06"
  );
  assert.equal(
    navToSearch({ tab: "today", sub: "export", checkinDate: null }),
    "?view=today&sub=export"
  );
});

test("search round-trips through parseNavFromSearch", () => {
  const states = [
    DEFAULT_NAV,
    { tab: "today", sub: null, checkinDate: "2026-11-06" },
    { tab: "history", sub: null, checkinDate: null },
    { tab: "settings", sub: null, checkinDate: null },
    { tab: "overview", sub: "achievements", checkinDate: null },
    { tab: "settings", sub: "about", checkinDate: null },
  ] as const;

  for (const state of states) {
    assert.deepEqual(parseNavFromSearch(navToSearch(state)), state);
  }
});

test("a completed save drops ?edit= but keeps the saved day on screen", () => {
  const editing = {
    tab: "today",
    sub: null,
    checkinDate: "2026-11-06",
  } as const;

  const urlState = navAfterCheckinSave(editing);

  // Address bar: no stale edit deep link.
  assert.equal(navToSearch(urlState), "?view=today");
  // Rendered state keeps the date, so the mounted screen (and its confirmation
  // card) stays on the saved day instead of remounting as today.
  assert.equal(editing.checkinDate, "2026-11-06");
  assert.deepEqual(urlState, { tab: "today", sub: null, checkinDate: null });
});

test("a save that finishes after a tab switch never pulls the user back", () => {
  const onHistory = { tab: "history", sub: null, checkinDate: null } as const;
  assert.equal(navAfterCheckinSave(onHistory), onHistory);

  const onSettingsSub = {
    tab: "settings",
    sub: "about",
    checkinDate: null,
  } as const;
  assert.equal(navAfterCheckinSave(onSettingsSub), onSettingsSub);

  // Saving today has no ?edit= to drop and must not rewrite history either.
  assert.equal(navAfterCheckinSave(DEFAULT_NAV), DEFAULT_NAV);
  const today = { tab: "today", sub: null, checkinDate: null } as const;
  assert.equal(navAfterCheckinSave(today), today);
});

test("tapping Today after saving a past day starts a fresh today session", () => {
  const savedPast = { tab: "today", sub: null, checkinDate: "2026-11-06" } as const;

  // The rendered state keeps the date after save; only the bottom-nav tap
  // resets it to a plain Today destination.
  assert.deepEqual(selectTab(savedPast, "today"), {
    tab: "today",
    sub: null,
    checkinDate: null,
  });
});

test("selectTab closes an open sub-screen and resets stale edit dates", () => {
  const editing = { tab: "today", sub: null, checkinDate: "2026-11-06" } as const;

  assert.deepEqual(selectTab(editing, "stats"), {
    tab: "stats",
    sub: null,
    checkinDate: null,
  });
  // Tapping the Today destination always means today, even if the screen was
  // opened with ?edit= for a past day.
  assert.deepEqual(selectTab(openSub(editing, "goals"), "today"), {
    tab: "today",
    sub: null,
    checkinDate: null,
  });
  assert.equal(selectTab(DEFAULT_NAV, "overview"), DEFAULT_NAV);
});

test("normalizeNav drops sub-screens the shell does not implement", () => {
  const stale = { tab: "today", sub: "export", checkinDate: "2026-11-06" } as const;

  assert.deepEqual(normalizeNav(stale, ["achievements", "about"]), {
    tab: "today",
    sub: null,
    checkinDate: "2026-11-06",
  });
  assert.deepEqual(
    normalizeNav({ tab: "overview", sub: "achievements", checkinDate: null }, ["achievements"]),
    { tab: "overview", sub: "achievements", checkinDate: null }
  );
});

test("openCheckin switches to Today and validates the date", () => {
  assert.deepEqual(openCheckin(DEFAULT_NAV, "2026-11-05"), {
    tab: "today",
    sub: null,
    checkinDate: "2026-11-05",
  });
  assert.deepEqual(openCheckin(DEFAULT_NAV, "not-a-date"), {
    tab: "today",
    sub: null,
    checkinDate: null,
  });
});

test("closeSub returns to the parent tab without touching the date", () => {
  const state = { tab: "today", sub: "goals", checkinDate: "2026-11-05" } as const;
  assert.deepEqual(closeSub(state), {
    tab: "today",
    sub: null,
    checkinDate: "2026-11-05",
  });
  assert.equal(closeSub(DEFAULT_NAV), DEFAULT_NAV);
});

test("scroll keys are stable per destination", () => {
  assert.equal(navScrollKey(DEFAULT_NAV), "tab:overview");
  assert.equal(navScrollKey({ tab: "stats", sub: null, checkinDate: null }), "tab:stats");
  assert.equal(
    navScrollKey({ tab: "overview", sub: "achievements", checkinDate: null }),
    "sub:achievements"
  );
});
