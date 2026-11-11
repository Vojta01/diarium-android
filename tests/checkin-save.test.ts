import assert from "node:assert/strict";
import test from "node:test";

import {
  canSave,
  saveCheckIn,
  type InFlightRef,
  type SaveGate,
} from "../src/lib/checkin/save.ts";
import { loadCheckIn } from "../src/lib/checkin/load.ts";
import { emptyCheckIn, hasContent } from "../src/lib/checkin/model.ts";
import { CheckInError } from "../src/lib/checkin/errors.ts";
import type { CheckInPayload } from "../src/lib/types.ts";

const PAYLOAD: CheckInPayload = {
  date: "2026-11-07",
  mood: 4,
  weather: ["dest"],
};

function gate(overrides: Partial<SaveGate> = {}): SaveGate {
  return {
    loadFailed: false,
    photoUploading: false,
    hasContent: true,
    ...overrides,
  };
}

function inFlight(): InFlightRef {
  return { current: false };
}

test("a failed load blocks saving so an empty form cannot overwrite the day", async () => {
  const load = await loadCheckIn("2026-11-07", async () => {
    throw new CheckInError("load_failed", "checkin.error_load", 500);
  });

  assert.equal(load.status, "failed");
  assert.equal(canSave(gate({ loadFailed: true })), false);

  let posts = 0;
  const result = await saveCheckIn({
    gate: gate({ loadFailed: true }),
    inFlight: inFlight(),
    payload: PAYLOAD,
    post: async () => {
      posts += 1;
      return {};
    },
  });

  assert.deepEqual(result, { status: "blocked" });
  assert.equal(posts, 0);
});

test("a confirmed no-entry day is allowed to save", async () => {
  const load = await loadCheckIn("2026-11-07", async () => null);
  assert.equal(load.status, "empty");
  assert.equal(canSave(gate({ loadFailed: false })), true);

  let posts = 0;
  const result = await saveCheckIn({
    gate: gate(),
    inFlight: inFlight(),
    payload: PAYLOAD,
    post: async () => {
      posts += 1;
      return { id: "row-1" };
    },
  });

  assert.equal(result.status, "saved");
  assert.equal(posts, 1);
});

test("a photo upload blocks saving until the URL is ready", () => {
  assert.equal(canSave(gate({ photoUploading: true })), false);
});

test("an empty form is not saveable", () => {
  assert.equal(canSave(gate({ hasContent: false })), false);
});

test("an explicit scale clear is saveable even when it is the only result", () => {
  const data = emptyCheckIn();
  data.scaleValues = { "scale-id": 0 };
  assert.equal(hasContent(data), true);
  assert.equal(canSave(gate({ hasContent: hasContent(data) })), true);
});

test("two simultaneous saves produce one POST and one busy result", async () => {
  let release!: () => void;
  const blocked = new Promise<void>((resolve) => {
    release = resolve;
  });
  let posts = 0;
  const shared = inFlight();

  const first = saveCheckIn({
    gate: gate(),
    inFlight: shared,
    payload: PAYLOAD,
    post: async () => {
      posts += 1;
      await blocked;
      return { id: "row-1" };
    },
  });

  const second = await saveCheckIn({
    gate: gate(),
    inFlight: shared,
    payload: PAYLOAD,
    post: async () => {
      posts += 1;
      return { id: "row-2" };
    },
  });

  assert.deepEqual(second, { status: "busy" });
  assert.equal(posts, 1);

  release();
  const firstResult = await first;
  assert.equal(firstResult.status, "saved");
  assert.equal(posts, 1);
});

test("a failed save returns a typed error instead of throwing", async () => {
  const result = await saveCheckIn({
    gate: gate(),
    inFlight: inFlight(),
    payload: PAYLOAD,
    post: async () => {
      throw new CheckInError("save_failed", "checkin.error_save_server", 500);
    },
  });

  assert.equal(result.status, "failed");
  if (result.status === "failed") {
    assert.equal(result.error.code, "checkin.error_save_server");
    assert.equal(result.error.kind, "save_failed");
  }
});

test("a raw network failure maps to the retryable network code", async () => {
  const result = await saveCheckIn({
    gate: gate(),
    inFlight: inFlight(),
    payload: PAYLOAD,
    post: async () => {
      throw new TypeError("Failed to fetch");
    },
  });

  assert.equal(result.status, "failed");
  if (result.status === "failed") {
    assert.equal(result.error.code, "checkin.error_save_network");
  }
});

test("a saved empty form is still blocked before the post is called", async () => {
  const data = emptyCheckIn();
  assert.equal(
    canSave(gate({ hasContent: data.mood > 0 || data.note.length > 0 })),
    false
  );
});
