import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import { cs } from "../src/lib/i18n/translations/cs.ts";
import { en } from "../src/lib/i18n/translations/en.ts";
import {
  SUB_LABEL_KEYS,
  TAB_LABEL_KEYS,
} from "../src/lib/navigation.ts";

function lookup(dictionary: unknown, path: string): unknown {
  return path.split(".").reduce<unknown>((current, part) => {
    if (current == null || typeof current !== "object") return undefined;
    return (current as Record<string, unknown>)[part];
  }, dictionary);
}

const SHELL_KEYS = [
  ...Object.values(TAB_LABEL_KEYS),
  ...Object.values(SUB_LABEL_KEYS),
  "nav.primary",
  "nav.back",
  "today.subtitle",
  "history.subtitle",
  "history.loading",
  "history.view_mode",
  "stats.subtitle",
  "settings.subtitle",
  "settings.account",
  "settings.unknown_user",
  "settings.logout",
  "settings.language",
  "settings.data",
  "settings.export",
  "settings.notifications",
  "settings.notifications_hint",
  "settings.about_app",
  "about.app_name",
  "about.version_web",
  "about.version_android",
  "about.parity",
  "about.links",
  "about.data_ai_title",
  "about.data_ai",
  "about.license",
  "auth.links_hint",
  "error.title",
  "error.hint",
  "error.retry",
  "error.reload",
  "error.session_safe",
  "links.github_source",
  "links.android",
  "links.download_android",
  "links.apk_hint",
  "links.about",
];

test("every shell translation key exists in both cs and en", () => {
  for (const key of SHELL_KEYS) {
    const czech = lookup(cs, key);
    const english = lookup(en, key);

    assert.equal(typeof czech, "string", `cs.${key} is missing`);
    assert.equal(typeof english, "string", `en.${key} is missing`);
    assert.ok((czech as string).trim().length > 0, `cs.${key} is empty`);
    assert.ok((english as string).trim().length > 0, `en.${key} is empty`);
  }
});

test("bottom destinations use distinct Czech and English labels", () => {
  const czech = Object.values(TAB_LABEL_KEYS).map((key) => lookup(cs, key));
  const english = Object.values(TAB_LABEL_KEYS).map((key) => lookup(en, key));

  assert.equal(new Set(czech).size, 5);
  assert.equal(new Set(english).size, 5);
  assert.deepEqual(czech, ["Přehled", "Dnes", "Historie", "Statistiky", "Nastavení"]);
  assert.deepEqual(english, ["Overview", "Today", "History", "Statistics", "Settings"]);
});

test("AuthScreen derives the OAuth callback from the current origin", () => {
  const source = readFileSync(
    new URL("../src/components/AuthScreen.tsx", import.meta.url),
    "utf8"
  );

  assert.match(source, /resolveOAuthRedirectTo\(/);
  assert.doesNotMatch(source, /diarium-two\.vercel\.app/);
  assert.match(source, /window\.location\.origin/);
});

test("ErrorBoundary never clears the stored session", () => {
  const source = readFileSync(
    new URL("../src/components/ErrorBoundary.tsx", import.meta.url),
    "utf8"
  );

  assert.doesNotMatch(source, /localStorage\.clear/);
  assert.doesNotMatch(source, /\.auth\.signOut/);
  assert.match(source, /window\.location\.reload/);
});

test("deep links are parsed before verified-session bootstrap", () => {
  const source = readFileSync(new URL("../src/app/page.tsx", import.meta.url), "utf8");
  const parseIndex = source.indexOf("parseNavFromSearch(window.location.search)");
  const verificationIndex = source.indexOf("void persistVerifiedSession");

  assert.ok(parseIndex > 0, "page must parse the navigation deep link");
  assert.ok(verificationIndex > 0, "page must verify the stored session");
  assert.ok(
    parseIndex < verificationIndex,
    "deep links must be available before the verified session returns early"
  );
});
