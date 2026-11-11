import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import { cs } from "../src/lib/i18n/translations/cs.ts";
import { en } from "../src/lib/i18n/translations/en.ts";
import { IMPLEMENTED_SUBS } from "../src/lib/navigation.ts";
import {
  JOURNAL_ROWS,
  REPORTS_ROW,
  SETTINGS_SECTIONS,
  SETTINGS_SUBS,
} from "../src/lib/settings.ts";

function lookup(dictionary: unknown, path: string): unknown {
  return path.split(".").reduce<unknown>((current, part) => {
    if (current == null || typeof current !== "object") return undefined;
    return (current as Record<string, unknown>)[part];
  }, dictionary);
}

const settingsSource = readFileSync(
  new URL("../src/components/settings/SettingsView.tsx", import.meta.url),
  "utf8"
);

test("Settings renders the exact Android order, Account last", () => {
  assert.deepEqual(
    SETTINGS_SECTIONS.map((section) => section.id),
    [
      "language",
      "journal",
      "notifications",
      "reports",
      "ai",
      "data",
      "about",
      "account",
    ]
  );

  const markers = [
    "<LanguageCard",
    "<JournalCard",
    "<NotificationsCard",
    "settings-data-title",
    "settings-about-title",
    "settings-account-title",
  ];
  const positions = markers.map((marker) => settingsSource.indexOf(marker));

  for (const position of positions) assert.ok(position >= 0, "marker missing");
  for (let index = 1; index < positions.length; index += 1) {
    assert.ok(
      positions[index] > positions[index - 1],
      `${markers[index]} must come after ${markers[index - 1]}`
    );
  }
});

test("every Settings section title exists in both languages", () => {
  for (const section of SETTINGS_SECTIONS) {
    assert.equal(typeof lookup(cs, section.titleKey), "string", `cs.${section.titleKey}`);
    assert.equal(typeof lookup(en, section.titleKey), "string", `en.${section.titleKey}`);
  }
});

test("Journal rows are goals, scales, templates, achievements", () => {
  assert.deepEqual(
    JOURNAL_ROWS.map((row) => row.sub),
    ["goals", "scales", "templates", "achievements"]
  );
  assert.deepEqual(
    JOURNAL_ROWS.map((row) => row.emoji),
    ["🎯", "📏", "📝", "🏆"]
  );
});

test("every Settings row opens an implemented sub-screen", () => {
  for (const sub of SETTINGS_SUBS) {
    assert.ok(
      IMPLEMENTED_SUBS.includes(sub),
      `${sub} is linked from Settings but not implemented`
    );
  }
  assert.equal(REPORTS_ROW.sub, "reports");

  for (const row of [...JOURNAL_ROWS, REPORTS_ROW]) {
    for (const key of [row.labelKey, row.hintKey]) {
      assert.equal(typeof lookup(cs, key), "string", `cs.${key}`);
      assert.equal(typeof lookup(en, key), "string", `en.${key}`);
    }
  }
});

test("Achievements moved out of Data and no card is blank", () => {
  const dataStart = settingsSource.indexOf("settings-data-title");
  const aboutStart = settingsSource.indexOf("settings-about-title");
  const dataCard = settingsSource.slice(dataStart, aboutStart);

  assert.match(dataCard, /settings\.export/);
  assert.doesNotMatch(dataCard, /achievements/);
});

test("Settings has no duplicate public links; About is the canonical home", () => {
  const about = readFileSync(
    new URL("../src/components/settings/AboutScreen.tsx", import.meta.url),
    "utf8"
  );

  assert.doesNotMatch(settingsSource, /ExternalLink/);
  assert.doesNotMatch(settingsSource, /github\.com|raw\.githubusercontent/);

  for (const constant of ["GITHUB_WEB_URL", "GITHUB_ANDROID_URL", "ANDROID_APK_URL"]) {
    assert.equal(
      about.split(`href={${constant}}`).length - 1,
      1,
      `${constant} must be rendered exactly once in About`
    );
  }
  assert.match(about, /WEB_VERSION/);
  assert.match(about, /ANDROID_VERSION/);
  assert.match(about, /WEB_PACKAGE_NAME/);
});

test("About platform copy only claims verified web-only features in both languages", () => {
  const czechParity = String(lookup(cs, "about.parity"));
  const englishParity = String(lookup(en, "about.parity"));
  const czechWebOnly = String(lookup(cs, "about.web_only_body"));
  const englishWebOnly = String(lookup(en, "about.web_only_body"));

  assert.match(czechParity, /PDF export/);
  assert.match(englishParity, /PDF export/);
  assert.match(czechWebOnly, /PDF export je dostupný jen na webu/);
  assert.match(englishWebOnly, /PDF export is available only on the web/);
  assert.match(czechWebOnly, /O aplikaci/);
  assert.match(englishWebOnly, /About screen/);
  assert.match(czechParity, /Rok v pixelech/);
  assert.match(englishParity, /Year in Pixels/);
  assert.doesNotMatch(czechParity, /Google Photos/i);
  assert.doesNotMatch(englishParity, /Google Photos/i);
  assert.doesNotMatch(czechWebOnly, /Google Photos|roční přehled|Rok v pixelech/i);
  assert.doesNotMatch(englishWebOnly, /Google Photos|yearly overview|Year in Pixels/i);
});

test("About lists honest native-only capabilities without fake controls", () => {
  const native = readFileSync(
    new URL("../src/components/settings/NativeOnlyCard.tsx", import.meta.url),
    "utf8"
  );

  assert.match(native, /about\.native_profile_title/);
  assert.match(native, /about\.native_onboarding_title/);
  assert.match(native, /about\.native_byok_title/);
  assert.match(native, /about\.native_scheduling_title/);
  assert.match(native, /about\.native_screen_time_title/);
  assert.doesNotMatch(native, /onClick|onOpenOnboarding|<button/);

  assert.match(
    String(lookup(cs, "about.native_onboarding_body")),
    /Čtyři kroky/
  );
  assert.match(
    String(lookup(en, "about.native_onboarding_body")),
    /Four steps/
  );
  assert.doesNotMatch(settingsSource, /ai_enabled|aiEnabled/);
});
