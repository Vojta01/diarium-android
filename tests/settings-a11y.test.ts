import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

function source(path: string): string {
  return readFileSync(new URL(path, import.meta.url), "utf8");
}

test("Settings header uses the settings subtitle, never the email", () => {
  const shell = source("../src/components/shell/AppShell.tsx");
  const settings = source("../src/components/settings/SettingsView.tsx");

  assert.match(shell, /t\("settings\.subtitle"\)/);
  assert.doesNotMatch(shell, /user\.email \|\|/);
  assert.equal(settings.split("{email ||").length - 1, 1);
  assert.match(settings, /t\("settings\.account_provider_google"\)/);
  assert.match(settings, /t\("settings\.account_signout_hint"\)/);
});

test("Settings header has no action cluster", () => {
  const shell = source("../src/components/shell/AppShell.tsx");

  assert.match(
    shell,
    /trailing=\{nav\.tab === "overview" \? headerActions : undefined\}/
  );
});

test("language choices are labelled, pressed and 44 px", () => {
  const switcher = source("../src/components/LanguageSwitcher.tsx");

  assert.match(switcher, /role="group"/);
  assert.match(switcher, /aria-pressed=\{active\}/);
  assert.match(switcher, /min-h-11/);
  assert.match(switcher, /Čeština/);
  assert.match(switcher, /English/);
  assert.match(switcher, /focus-visible:ring-2/);
  assert.doesNotMatch(switcher, /text-white\/25/);
});

test("every Settings card is named by its heading", () => {
  const settings = source("../src/components/settings/SettingsView.tsx");
  const journal = source("../src/components/settings/JournalCard.tsx");
  const reports = source("../src/components/settings/ReportsCard.tsx");
  const ai = source("../src/components/settings/AiPrivacyCard.tsx");
  const language = source("../src/components/settings/LanguageCard.tsx");

  for (const card of [settings, journal, reports, ai, language]) {
    assert.match(card, /aria-labelledby=/);
    assert.match(card, /<SectionHeader[^>]*id=/);
  }
});

test("rows are 44 px navigation buttons with hints", () => {
  const row = source("../src/components/settings/SettingsRow.tsx");

  assert.match(row, /type="button"/);
  assert.match(row, /min-h-11/);
  assert.match(row, /w-full/);
});

test("web is dark-only and allows pinch zoom", () => {
  const css = source("../src/app/globals.css");
  const layout = source("../src/app/layout.tsx");
  const settings = source("../src/components/settings/SettingsView.tsx");

  assert.match(css, /color-scheme:\s*dark/);
  assert.doesNotMatch(layout, /userScalable:\s*false/);
  assert.doesNotMatch(layout, /maximumScale:\s*1\b/);
  assert.doesNotMatch(settings, /theme|Theme|tmavý|light/i);
  assert.doesNotMatch(layout, /diarium_theme/);
});

test("no destructive account-deletion UI is shipped", () => {
  const settings = source("../src/components/settings/SettingsView.tsx");
  const about = source("../src/components/settings/AboutScreen.tsx");

  for (const text of [settings, about]) {
    assert.doesNotMatch(text, /deleteAccount|account\/delete|smazat účet/i);
    assert.doesNotMatch(text, /onDelete|Delete account/i);
  }
});
