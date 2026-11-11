import assert from "node:assert/strict";
import test from "node:test";

import { restoreFocus } from "../src/lib/focus.ts";

function fakeOpener(connected: boolean) {
  let calls = 0;
  return {
    isConnected: connected,
    focus: () => {
      calls += 1;
    },
    calls: () => calls,
  };
}

test("restores focus to a still-connected opener", () => {
  const opener = fakeOpener(true);

  assert.equal(restoreFocus(opener), true);
  assert.equal(opener.calls(), 1);
});

test("never focuses an opener that was unmounted while the sub-screen was open", () => {
  const opener = fakeOpener(false);

  assert.equal(restoreFocus(opener), false);
  assert.equal(opener.calls(), 0);
});

test("does nothing when no opener was captured", () => {
  assert.equal(restoreFocus(null), false);
  assert.equal(restoreFocus(undefined), false);
});
