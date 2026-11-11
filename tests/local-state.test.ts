import assert from "node:assert/strict";
import test from "node:test";

import { clearUserLocalState } from "../src/lib/local-state.ts";

function fakeStorage(initial: Record<string, string>) {
  const map = new Map(Object.entries(initial));
  return {
    get length() {
      return map.size;
    },
    key(index: number) {
      return [...map.keys()][index] ?? null;
    },
    removeItem(key: string) {
      map.delete(key);
    },
    has(key: string) {
      return map.has(key);
    },
    snapshot() {
      return Object.fromEntries(map);
    },
  };
}

test("logout removes auth tokens, local goals and the Google token, but keeps drafts", () => {
  const storage = fakeStorage({
    "sb-abc-auth-token": "{}",
    "supabase.auth.token": "{}",
    "diarium_draft_2026-11-07": "legacy draft",
    "diarium_draft_2026-11-07_account-a": "account draft",
    "diarium_goals": "[]",
    "diarium_verified_workspace_v1": JSON.stringify({
      version: 1,
      ownerId: "verified-account-a",
    }),
    "diarium_google_token": "ya29.secret",
    "diarium_google_token_expires_at": "1760000000000",
    "diarium-lang": "cs",
    unrelated: "keep",
  });

  clearUserLocalState(storage);

  assert.deepEqual(storage.snapshot(), {
    "diarium_draft_2026-11-07": "legacy draft",
    "diarium_draft_2026-11-07_account-a": "account draft",
    "diarium-lang": "cs",
    unrelated: "keep",
  });
});

test("logout is safe on an empty storage", () => {
  const storage = fakeStorage({});
  assert.doesNotThrow(() => clearUserLocalState(storage));
  assert.deepEqual(storage.snapshot(), {});
});
