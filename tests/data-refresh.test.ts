import assert from "node:assert/strict";
import test from "node:test";

import {
  DATA_CHANGED_EVENT,
  DATA_KINDS,
  matchesDataChange,
  subscribeDataChanged,
} from "../src/lib/data-refresh.ts";

test("a screen refreshes only for the data kinds it reads", () => {
  assert.equal(matchesDataChange(["entries"], ["entries"]), true);
  assert.equal(matchesDataChange(["entries"], ["goals"]), false);
  assert.equal(matchesDataChange(["entries", "goals"], ["goals", "templates"]), true);
  assert.equal(matchesDataChange([], ["entries"]), false);
});

test("every declared data kind is a non-empty unique string", () => {
  assert.equal(new Set(DATA_KINDS).size, DATA_KINDS.length);
  for (const kind of DATA_KINDS) assert.match(kind, /^[a-z]+$/);
});

test("subscribers receive matching events and stop after unsubscribe", () => {
  const target = new EventTarget();
  const testGlobal = globalThis as unknown as {
    window: EventTarget | undefined;
  };
  const previousWindow = testGlobal.window;
  // subscribeDataChanged only touches `window`; a bare EventTarget is enough.
  testGlobal.window = target;

  let calls = 0;
  const unsubscribe = subscribeDataChanged(["entries"], () => calls++);

  target.dispatchEvent(
    new CustomEvent(DATA_CHANGED_EVENT, { detail: { kinds: ["goals"] } })
  );
  assert.equal(calls, 0, "unrelated kind must not refresh");

  target.dispatchEvent(
    new CustomEvent(DATA_CHANGED_EVENT, { detail: { kinds: ["entries"] } })
  );
  assert.equal(calls, 1);

  target.dispatchEvent(new CustomEvent(DATA_CHANGED_EVENT, { detail: {} }));
  assert.equal(calls, 1, "malformed event must not refresh every screen");

  unsubscribe();
  target.dispatchEvent(
    new CustomEvent(DATA_CHANGED_EVENT, { detail: { kinds: ["entries"] } })
  );
  assert.equal(calls, 1, "unsubscribed listener must be removed");

  testGlobal.window = previousWindow;
});
