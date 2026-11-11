import assert from "node:assert/strict";
import test from "node:test";

import { DASHBOARD_CARD_ORDER } from "../src/lib/dashboard.ts";
import {
  DASHBOARD_ENTRY_ROWS,
  DASHBOARD_ENTRY_SUBS,
} from "../src/lib/entries.ts";
import { cs } from "../src/lib/i18n/translations/cs.ts";
import { en } from "../src/lib/i18n/translations/en.ts";
import {
  IMPLEMENTED_SUBS,
  SUB_SCREENS,
  navToSearch,
  normalizeNav,
  parseNavFromSearch,
} from "../src/lib/navigation.ts";

function lookup(dictionary: unknown, path: string): unknown {
  return path.split(".").reduce<unknown>((current, part) => {
    if (current == null || typeof current !== "object") return undefined;
    return (current as Record<string, unknown>)[part];
  }, dictionary);
}

test("Entries is the final dashboard card, after every beta5 card", () => {
  assert.equal(DASHBOARD_CARD_ORDER.at(-1), "entries");
  assert.equal(DASHBOARD_CARD_ORDER.filter((id) => id === "entries").length, 1);
  assert.deepEqual([...DASHBOARD_CARD_ORDER], [
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

test("the Entries card has the five real destinations in beta5 order", () => {
  assert.deepEqual([...DASHBOARD_ENTRY_SUBS], [
    "goals",
    "scales",
    "templates",
    "achievements",
    "reports",
  ]);
  assert.equal(new Set(DASHBOARD_ENTRY_SUBS).size, 5);
  assert.equal(DASHBOARD_ENTRY_ROWS.length, 5);

  for (const row of DASHBOARD_ENTRY_ROWS) {
    assert.ok(
      (SUB_SCREENS as readonly string[]).includes(row.sub),
      `${row.sub} must be a known sub-screen`
    );
    assert.ok(
      IMPLEMENTED_SUBS.includes(row.sub),
      `${row.sub} must have a real implementation`
    );
    assert.notEqual(row.sub, "notifications");
  }
});

test("no Notifications row is shipped without a Notifications screen", () => {
  assert.equal(
    (SUB_SCREENS as readonly string[]).includes("notifications"),
    false
  );
  assert.equal(
    DASHBOARD_ENTRY_ROWS.some((row) => /notification/i.test(row.sub)),
    false
  );
  assert.equal(
    DASHBOARD_ENTRY_ROWS.some((row) => /notification/i.test(row.labelKey)),
    false
  );
});

test("every Entries row has both translations and a unique label", () => {
  const labels = new Set<string>();
  for (const row of DASHBOARD_ENTRY_ROWS) {
    for (const key of [row.labelKey, row.hintKey]) {
      assert.equal(typeof lookup(cs, key), "string", `cs.${key} is missing`);
      assert.equal(typeof lookup(en, key), "string", `en.${key} is missing`);
    }
    labels.add(row.labelKey);
  }
  assert.equal(labels.size, DASHBOARD_ENTRY_ROWS.length);
});

test("every Entries row is a working deep link and survives normalization", () => {
  for (const sub of DASHBOARD_ENTRY_SUBS) {
    const parsed = parseNavFromSearch(`?sub=${sub}`);
    assert.equal(parsed.sub, sub);
    // Reports is parented by Stats, so its deep link carries the parent view.
    assert.equal(
      navToSearch(parsed),
      sub === "reports" ? "?view=stats&sub=reports" : `?sub=${sub}`
    );

    const normalized = normalizeNav(parsed, IMPLEMENTED_SUBS);
    assert.equal(normalized.sub, sub, `${sub} must not be dropped`);
  }
});

test("implemented sub-screens are the five Entries rows plus About", () => {
  assert.deepEqual([...IMPLEMENTED_SUBS], [
    "goals",
    "scales",
    "templates",
    "achievements",
    "reports",
    "about",
  ]);
  // Export is a dialog in Settings, not a standalone sub-screen in this build.
  assert.equal(IMPLEMENTED_SUBS.includes("export"), false);
  assert.deepEqual(
    normalizeNav({ tab: "overview", sub: "export", checkinDate: null }, IMPLEMENTED_SUBS),
    { tab: "overview", sub: null, checkinDate: null }
  );
});
