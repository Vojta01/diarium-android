import assert from "node:assert/strict";
import test from "node:test";

import {
  escapeBelongsToDialog,
  resolveTabStop,
} from "../src/lib/modal-a11y.ts";

test("Tab wraps from the last focusable element to the first", () => {
  assert.equal(resolveTabStop(3, 2, false), 0);
  assert.equal(resolveTabStop(3, 1, false), null);
  assert.equal(resolveTabStop(3, 0, false), null);
});

test("Shift+Tab wraps from the first focusable element to the last", () => {
  assert.equal(resolveTabStop(3, 0, true), 2);
  assert.equal(resolveTabStop(3, 1, true), null);
  assert.equal(resolveTabStop(3, 2, true), null);
});

test("focus outside the dialog is pulled back inside", () => {
  assert.equal(resolveTabStop(2, -1, false), 0);
  assert.equal(resolveTabStop(2, -1, true), 1);
});

test("an empty dialog keeps focus on the panel", () => {
  assert.equal(resolveTabStop(0, -1, false), null);
  assert.equal(resolveTabStop(0, 0, true), null);
});

test("Escape belongs to the dialog when the target is inside it", () => {
  assert.equal(escapeBelongsToDialog(true, 0), true);
});

test("Escape belongs to the dialog while any modal is open", () => {
  // Focus may briefly sit on the backdrop or body; the sub-screen behind must
  // still not close while a real role=dialog[aria-modal] node is mounted.
  assert.equal(escapeBelongsToDialog(false, 1), true);
  assert.equal(escapeBelongsToDialog(false, 2), true);
});

test("Escape reaches the sub-screen only when no dialog is open", () => {
  assert.equal(escapeBelongsToDialog(false, 0), false);
});
