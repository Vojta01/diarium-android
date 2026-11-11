import assert from "node:assert/strict";
import test from "node:test";

import { lockBodyScroll } from "../src/lib/scroll-lock.ts";

test("locks body scrolling while a modal is open and restores it on close", () => {
  const body = { style: { overflow: "scroll" } };

  const release = lockBodyScroll(body);
  assert.equal(body.style.overflow, "hidden");

  release();
  assert.equal(body.style.overflow, "scroll");
});

test("stacked modals keep the page locked until the last one closes", () => {
  const body = { style: { overflow: "" } };

  const releaseOuter = lockBodyScroll(body);
  const releaseInner = lockBodyScroll(body);
  assert.equal(body.style.overflow, "hidden");

  releaseOuter();
  assert.equal(body.style.overflow, "hidden");

  releaseInner();
  assert.equal(body.style.overflow, "");
});

test("releasing a lock twice cannot unlock another modal", () => {
  const body = { style: { overflow: "auto" } };

  const releaseFirst = lockBodyScroll(body);
  const releaseSecond = lockBodyScroll(body);

  releaseFirst();
  releaseFirst();
  assert.equal(body.style.overflow, "hidden");

  releaseSecond();
  assert.equal(body.style.overflow, "auto");
});
