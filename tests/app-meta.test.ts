import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import { ANDROID_VERSION, WEB_PACKAGE_NAME, WEB_VERSION } from "../src/lib/app-meta.ts";

test("About screen identity stays in sync with package.json", () => {
  const pkg = JSON.parse(
    readFileSync(new URL("../package.json", import.meta.url), "utf8")
  ) as { name: string; version: string };

  assert.equal(WEB_PACKAGE_NAME, pkg.name);
  assert.equal(WEB_VERSION, pkg.version);
});

test("the Android version is a single, honest build constant", () => {
  assert.match(ANDROID_VERSION, /^\d+\.\d+\.\d+(-beta\d+)?$/);
  assert.equal(ANDROID_VERSION, "2.0.0-beta5");
});
