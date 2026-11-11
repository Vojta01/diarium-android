import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
  externalLinkNameLabel,
  visibleLinkText,
} from "../src/lib/a11y.ts";
import { cs } from "../src/lib/i18n/translations/cs.ts";
import { en } from "../src/lib/i18n/translations/en.ts";

function accessibleName(
  dictionary: typeof en | typeof cs,
  children: Parameters<typeof externalLinkNameLabel>[0],
  destinationLabel: string
): string {
  return dictionary.links.opens_in_new_tab.replace(
    "{label}",
    externalLinkNameLabel(children, destinationLabel)
  );
}

test("accessible name keeps the visible text and appends the new-tab hint", () => {
  const name = accessibleName(en, "GitHub", "GitHub repository");

  assert.ok(name.includes("GitHub"), `"${name}" must contain the visible text`);
  assert.match(name, /opens in a new tab/);
  assert.ok(
    !name.includes("GitHub repository"),
    "the destination-only label must not replace visible children"
  );
});

test("Czech accessible name keeps the visible text and appends the hint", () => {
  const name = accessibleName(cs, "Stáhnout APK", "Android aplikace");

  assert.ok(name.includes("Stáhnout APK"));
  assert.match(name, /nové kartě/);
});

test("falls back to the destination label when there is no visible text", () => {
  assert.equal(externalLinkNameLabel(undefined, "Android app"), "Android app");
  assert.equal(externalLinkNameLabel(null, "Download APK"), "Download APK");
  assert.equal(externalLinkNameLabel([], "GitHub"), "GitHub");
});

test("flattens nested string children and normalizes whitespace", () => {
  assert.equal(visibleLinkText(["Git", "Hub"]), "GitHub");
  assert.equal(visibleLinkText(["Download", " ", "APK"]), "Download APK");
  assert.equal(
    externalLinkNameLabel(["  Open\n  dashboard  "], "fallback"),
    "Open dashboard"
  );
  assert.equal(externalLinkNameLabel(["", "Download APK"], "fallback"), "Download APK");
});

test("ExternalLink keeps the 44px target and the safe link attributes", () => {
  const component = readFileSync(
    new URL("../src/components/ExternalLink.tsx", import.meta.url),
    "utf8"
  );

  assert.match(component, /externalLinkNameLabel\(children, label\)/);
  assert.match(component, /min-h-11/);
  assert.match(component, /rel="noopener noreferrer"/);
  assert.match(component, /referrerPolicy="no-referrer"/);
  assert.match(component, /target="_blank"/);
});
