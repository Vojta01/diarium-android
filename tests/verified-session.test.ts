import assert from "node:assert/strict";
import test from "node:test";

import {
  persistVerifiedSession,
  readVerifiedWorkspaceOwner,
  VERIFIED_WORKSPACE_KEY,
} from "../src/lib/auth/verified-session.ts";

const KEY = "sb-test-auth-token";
const TOKEN_WITH_FORGED_SUBJECT = "header.eyJzdWIiOiJhdHRhY2tlci1pZCJ9.unverified";
const SESSION = {
  access_token: TOKEN_WITH_FORGED_SUBJECT,
  refresh_token: "fake-refresh-token",
  expires_at: 1_800_000_000,
  token_type: "bearer",
};

function memoryStorage() {
  const values = new Map<string, string>();
  return {
    values,
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
  };
}

test("forged JWT claims never become a persisted account identity", async () => {
  const storage = memoryStorage();
  const auth = {
    getUser: async (token?: string) => {
      assert.equal(token, TOKEN_WITH_FORGED_SUBJECT);
      return { data: { user: null }, error: new Error("invalid token") };
    },
  };

  await assert.rejects(
    persistVerifiedSession(auth, storage, KEY, SESSION),
    /could not be verified/
  );
  assert.equal(storage.values.size, 0);
});

test("a provider verification failure does not persist a session or leak token details", async () => {
  const storage = memoryStorage();
  const auth = {
    getUser: async () => {
      throw new Error(`fetch failed for ${TOKEN_WITH_FORGED_SUBJECT}`);
    },
  };

  await assert.rejects(
    persistVerifiedSession(auth, storage, KEY, SESSION),
    (error: Error) => {
      assert.equal(error.message, "Supabase session could not be verified");
      assert.doesNotMatch(error.message, /unverified|fake-refresh/);
      return true;
    }
  );
  assert.equal(storage.values.size, 0);
});

test("a valid provider response supplies the persisted identity", async () => {
  const storage = memoryStorage();
  const providerUser = {
    id: "provider-verified-id",
    email: "verified@example.test",
    app_metadata: { provider: "google" },
    user_metadata: { name: "Verified User" },
  };
  const auth = {
    getUser: async (token?: string) => {
      assert.equal(token, TOKEN_WITH_FORGED_SUBJECT);
      return { data: { user: providerUser }, error: null };
    },
  };

  const user = await persistVerifiedSession(auth, storage, KEY, SESSION);
  const stored = JSON.parse(storage.values.get(KEY)!);
  assert.equal(user.id, providerUser.id);
  assert.equal(stored.user.id, providerUser.id);
  assert.notEqual(stored.user.id, "attacker-id");
  assert.equal(stored.access_token, SESSION.access_token);
  assert.equal(
    storage.values.get("diarium_verified_workspace_v1"),
    JSON.stringify({ version: 1, ownerId: providerUser.id }),
    "the separate local workspace binding must come from the provider-verified user"
  );

  const accountB = await persistVerifiedSession(
    {
      getUser: async () => ({ data: { user: { id: "provider-verified-account-b" } }, error: null }),
    },
    storage,
    KEY,
    SESSION
  );
  assert.equal(accountB.id, "provider-verified-account-b");
  assert.equal(readVerifiedWorkspaceOwner(storage), "provider-verified-account-b");
});

test("a network error is distinguishable from a rejected provider identity", async () => {
  const networkStorage = memoryStorage();
  const networkAuth = {
    getUser: async () => {
      throw new TypeError("Failed to fetch");
    },
  };
  await assert.rejects(
    persistVerifiedSession(networkAuth, networkStorage, KEY, SESSION),
    (error: unknown) =>
      error instanceof Error &&
      (error as Error & { kind?: string }).kind === "network"
  );
  assert.equal(networkStorage.values.has("diarium_verified_workspace_v1"), false);

  const rejectedStorage = memoryStorage();
  const rejectedAuth = {
    getUser: async () => ({
      data: { user: null },
      error: Object.assign(new Error("JWT expired"), {
        name: "AuthApiError",
        status: 401,
      }),
    }),
  };
  await assert.rejects(
    persistVerifiedSession(rejectedAuth, rejectedStorage, KEY, SESSION),
    (error: unknown) =>
      error instanceof Error &&
      (error as Error & { kind?: string }).kind === "rejected"
  );
  assert.equal(rejectedStorage.values.has("diarium_verified_workspace_v1"), false);
});

test("workspace lookup accepts only the explicit versioned binding", () => {
  const storage = memoryStorage();
  assert.equal(readVerifiedWorkspaceOwner(storage), null);

  storage.setItem(VERIFIED_WORKSPACE_KEY, JSON.stringify({ version: 1, ownerId: "account-a" }));
  assert.equal(readVerifiedWorkspaceOwner(storage), "account-a");
  assert.equal(readVerifiedWorkspaceOwner(storage, "account-a"), "account-a");
  assert.equal(readVerifiedWorkspaceOwner(storage, "account-b"), null);
  assert.equal(readVerifiedWorkspaceOwner(storage, null), null);

  storage.setItem(VERIFIED_WORKSPACE_KEY, JSON.stringify({ version: 2, ownerId: "account-b" }));
  assert.equal(readVerifiedWorkspaceOwner(storage), null);
  storage.setItem(VERIFIED_WORKSPACE_KEY, JSON.stringify({ version: 1, ownerId: "   " }));
  assert.equal(readVerifiedWorkspaceOwner(storage), null);
  storage.setItem(VERIFIED_WORKSPACE_KEY, "not-json");
  assert.equal(readVerifiedWorkspaceOwner(storage), null);
});
