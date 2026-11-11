import assert from "node:assert/strict";
import test from "node:test";

import { cs } from "../src/lib/i18n/translations/cs.ts";
import { en } from "../src/lib/i18n/translations/en.ts";

test("stored sleep-quality values 1 through 5 have Czech and English labels", () => {
  for (const [lang, translations] of [
    ["cs", cs],
    ["en", en],
  ] as const) {
    for (const value of [1, 2, 3, 4, 5]) {
      const label = translations.sleep[`sleep_${value}` as keyof typeof translations.sleep];
      assert.equal(
        typeof label,
        "string",
        `${lang} must translate sleep.sleep_${value}`
      );
      assert.ok(label.trim(), `${lang} sleep.sleep_${value} must not be empty`);
    }
  }
});
