import assert from "node:assert/strict";
import test from "node:test";

import { cs } from "../src/lib/i18n/translations/cs.ts";
import { en } from "../src/lib/i18n/translations/en.ts";

type Flat = Map<string, string>;

function flatten(value: unknown, prefix = "", out: Flat = new Map()): Flat {
  assert.equal(typeof value, "object");
  assert.notEqual(value, null);

  for (const [key, child] of Object.entries(value as Record<string, unknown>)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof child === "string") {
      out.set(path, child);
    } else {
      // Translation dictionaries must stay string-only. Arrays used to hold
      // month/day names and silently broke because t() only returns strings.
      assert.equal(Array.isArray(child), false, `${path} must not be an array`);
      flatten(child, path, out);
    }
  }
  return out;
}

test("Czech and English dictionaries expose exactly the same deep keys", () => {
  const czech = flatten(cs);
  const english = flatten(en);

  const missingInEnglish = [...czech.keys()].filter((key) => !english.has(key));
  const missingInCzech = [...english.keys()].filter((key) => !czech.has(key));

  assert.deepEqual(missingInEnglish, [], "English must not fall back to Czech");
  assert.deepEqual(missingInCzech, [], "Czech must not silently borrow English keys");
});

test("every English value is a real string, never a raw key or empty", () => {
  const english = flatten(en);

  for (const [key, value] of english) {
    assert.equal(typeof value, "string", `${key} must be a string`);
    assert.notEqual(value.trim(), "", `${key} must not be empty`);
    assert.notEqual(value, key, `${key} must not render its own key`);
  }
});

test("English has no Czech diacritics outside the Czech language label", () => {
  const english = flatten(en);
  for (const [key, value] of english) {
    if (key === "language.cs") continue;
    assert.doesNotMatch(value, /[áčďéěíňóřšťúůýž]/i, `${key} contains Czech text: ${value}`);
  }
});
