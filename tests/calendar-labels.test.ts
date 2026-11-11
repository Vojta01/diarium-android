import assert from "node:assert/strict";
import test from "node:test";

import { intlLocale, monthName, monthNames, weekdayNames } from "../src/lib/calendar-labels.ts";

test("Czech and English month names are localized, not hardcoded English", () => {
  assert.equal(monthName("cs", 0), "leden");
  assert.equal(monthName("en", 0), "January");
  assert.equal(monthNames("cs")[1], "únor");
  assert.equal(monthNames("en")[1], "February");
  assert.notDeepEqual(monthNames("cs"), monthNames("en"));
});

test("weekday labels start on Monday in both languages", () => {
  const cs = weekdayNames("cs");
  const en = weekdayNames("en");

  assert.equal(cs.length, 7);
  assert.equal(en.length, 7);
  assert.equal(en[0], "Mon");
  assert.equal(en[6], "Sun");
  assert.equal(cs[0], "po");
  assert.notDeepEqual(cs, en);
});

test("month list has twelve entries for every year", () => {
  assert.equal(monthNames("cs", 2026).length, 12);
  assert.equal(monthNames("en", 2024).length, 12);
});

test("maps app languages to Intl locales", () => {
  assert.equal(intlLocale("cs"), "cs-CZ");
  assert.equal(intlLocale("en"), "en-US");
});
