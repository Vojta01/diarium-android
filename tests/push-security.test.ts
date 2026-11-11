import assert from "node:assert/strict";
import test from "node:test";

import { isValidWebPushSubscription } from "../src/lib/push-security.ts";
import { deliverStoredWebPushSubscriptions } from "../src/lib/push-delivery.ts";
import { createPushSubscriptionHandlers } from "../src/lib/push-subscription-handler.ts";
import { registerFcmToken, type FcmTokenStore } from "../src/lib/fcm-token-store.ts";
import { pushSubscriptionsKey } from "../src/lib/push.ts";

const p256dh = `B${"A".repeat(86)}`;
const auth = "A".repeat(22);

function subscription(endpoint: string, keys = { p256dh, auth }) {
  return { endpoint, expirationTime: null, keys };
}

const browserFixtures = [
  subscription("https://fcm.googleapis.com/fcm/send/browser-token_1"),
  subscription("https://updates.push.services.mozilla.com/wpush/v2/browser-token_2"),
  subscription("https://web.push.apple.com/Q_browser-token-3"),
  subscription("https://wns2-bl2p.notify.windows.com/w/?token=browser-token-4"),
];

test("documented Chrome, Firefox, Safari and Edge browser subscriptions are accepted", () => {
  for (const fixture of browserFixtures) {
    assert.equal(isValidWebPushSubscription(fixture), true, fixture.endpoint);
  }
});

test("SSRF, redirect-target, credential, port and malformed key shapes are rejected", () => {
  const badEndpoints = [
    "http://fcm.googleapis.com/fcm/send/token",
    "https://127.0.0.1/fcm/send/token",
    "https://[::1]/fcm/send/token",
    "https://localhost/fcm/send/token",
    "https://fcm.googleapis.com.evil.test/fcm/send/token",
    "https://dummy@evil.test/fcm/send/token",
    "https://fcm.googleapis.com:443/fcm/send/token",
    "https://fcm.googleapis.com:8443/fcm/send/token",
    "https://wns2-bl2p.notify.windows.com:443/w/?token=browser-token",
    "https://updates.push.services.mozilla.com:444/wpush/v2/token",
    "https://example.com/redirect?to=http://127.0.0.1:8080/admin",
    "https://fcm.googleapis.com.evil.test/fcm/send/token?redirect=http://169.254.169.254/",
    "https://fcm.googleapis.com/fcm/send/token#fragment",
  ];
  for (const endpoint of badEndpoints) {
    assert.equal(isValidWebPushSubscription(subscription(endpoint)), false, endpoint);
  }
  assert.equal(isValidWebPushSubscription({ endpoint: browserFixtures[0].endpoint }), false);
  assert.equal(
    isValidWebPushSubscription(subscription(browserFixtures[0].endpoint, { p256dh: "short", auth })),
    false
  );
  assert.equal(
    isValidWebPushSubscription(subscription(browserFixtures[0].endpoint, { p256dh, auth: "!" })),
    false
  );
});

test("legacy Redis values are validated again before send and invalid values are pruned", async () => {
  const invalid = JSON.stringify(subscription("http://127.0.0.1/metadata"));
  const valid = JSON.stringify(browserFixtures[0]);
  const sent: string[] = [];
  const removed: unknown[] = [];

  const result = await deliverStoredWebPushSubscriptions({
    key: pushSubscriptionsKey("user-a"),
    stored: [invalid, valid, "not-json"],
    redis: { srem: async (_key, member) => { removed.push(member); } },
    payload: "{}",
    sendNotification: async (sub) => { sent.push(sub.endpoint); },
  });

  assert.deepEqual(sent, [browserFixtures[0].endpoint]);
  assert.deepEqual(removed, [invalid, "not-json"]);
  assert.deepEqual(result, { sent: 1, errors: 0 });
});

test("push subscription handlers authorize before storage and isolate idempotent users", async () => {
  const sets = new Map<string, Set<string>>();
  let authCalls = 0;
  const handlers = createPushSubscriptionHandlers({
    authenticate: async (request) => {
      authCalls += 1;
      const bearer = request.headers.get("authorization");
      return bearer === "Bearer user-a" ? { id: "user-a" }
        : bearer === "Bearer user-b" ? { id: "user-b" }
          : null;
    },
    getRedis: () => ({
      sadd: async (key, member) => {
        const set = sets.get(key) ?? new Set<string>();
        set.add(member);
        sets.set(key, set);
      },
      srem: async (key, member) => { sets.get(key)?.delete(String(member)); },
    }),
    registerFcmToken: async () => "created",
    deleteFcmToken: async () => {},
  });

  const unauthenticated = await handlers.POST(new Request("https://diarium.test/api/push/subscribe", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ subscription: browserFixtures[0] }),
  }));
  assert.equal(unauthenticated.status, 401);
  assert.equal(sets.size, 0);

  const rejected = await handlers.POST(new Request("https://diarium.test/api/push/subscribe", {
    method: "POST",
    headers: { authorization: "Bearer user-a", "content-type": "application/json" },
    body: JSON.stringify({ subscription: subscription("https://127.0.0.1/metadata") }),
  }));
  assert.equal(rejected.status, 400);
  assert.equal(sets.size, 0, "malicious endpoints must be rejected before storage");

  const badKeys = await handlers.POST(new Request("https://diarium.test/api/push/subscribe", {
    method: "POST",
    headers: { authorization: "Bearer user-a", "content-type": "application/json" },
    body: JSON.stringify({
      subscription: subscription(browserFixtures[0].endpoint, { p256dh: "short", auth }),
    }),
  }));
  assert.equal(badKeys.status, 400);
  assert.equal(sets.size, 0, "malformed encryption keys must be rejected before storage");

  for (const user of ["user-a", "user-b", "user-a"]) {
    const response = await handlers.POST(new Request("https://diarium.test/api/push/subscribe", {
      method: "POST",
      headers: { authorization: `Bearer ${user}`, "content-type": "application/json" },
      body: JSON.stringify({ subscription: browserFixtures[0] }),
    }));
    assert.equal(response.status, 200);
  }
  assert.equal(authCalls, 6);
  assert.equal(sets.get(pushSubscriptionsKey("user-a"))?.size, 1);
  assert.equal(sets.get(pushSubscriptionsKey("user-b"))?.size, 1);
  assert.notEqual(pushSubscriptionsKey("user-a"), pushSubscriptionsKey("user-b"));
});

test("a global FCM token conflict cannot transfer ownership and cross-user delete is inert", async () => {
  const owners = new Map<string, string>([["device-token", "user-a"]]);
  const store: FcmTokenStore = {
    insert: async ({ user_id, token }) => {
      if (owners.has(token)) return { code: "23505" };
      owners.set(token, user_id);
      return { code: null };
    },
    getOwner: async (token) => owners.get(token) ?? null,
    deleteOwned: async (userId, token) => {
      if (owners.get(token) === userId) owners.delete(token);
    },
  };

  assert.equal(await registerFcmToken(store, "user-b", "device-token"), "conflict");
  assert.equal(owners.get("device-token"), "user-a");
  await store.deleteOwned("user-b", "device-token");
  assert.equal(owners.get("device-token"), "user-a");
  assert.equal(await registerFcmToken(store, "user-a", "device-token"), "existing");
});

test("authenticated cross-user FCM deletion and token claiming are refused", async () => {
  const owners = new Map<string, string>([["device-token", "user-a"]]);
  const store: FcmTokenStore = {
    insert: async ({ user_id, token }) => {
      if (owners.has(token)) return { code: "23505" };
      owners.set(token, user_id);
      return { code: null };
    },
    getOwner: async (token) => owners.get(token) ?? null,
    deleteOwned: async (userId, token) => {
      if (owners.get(token) === userId) owners.delete(token);
    },
  };
  const handlers = createPushSubscriptionHandlers({
    authenticate: async () => ({ id: "user-b" }),
    getRedis: () => null,
    registerFcmToken: (userId, token) => registerFcmToken(store, userId, token),
    deleteFcmToken: (userId, token) => store.deleteOwned(userId, token),
  });

  const post = await handlers.POST(new Request("https://diarium.test/api/push/subscribe", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ platform: "android", token: "device-token" }),
  }));
  assert.equal(post.status, 409);

  const del = await handlers.DELETE(new Request("https://diarium.test/api/push/subscribe", {
    method: "DELETE",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ platform: "android", token: "device-token" }),
  }));
  assert.equal(del.status, 200);
  assert.equal(owners.get("device-token"), "user-a");
});

test("subscribe route delegates its authenticated identity to the handler", async () => {
  const { readFileSync } = await import("node:fs");
  const route = readFileSync(new URL("../src/app/api/push/subscribe/route.ts", import.meta.url), "utf8");
  assert.match(route, /authenticate:\s*verifyAuth/);
  assert.match(route, /createPushSubscriptionHandlers/);
  assert.match(route, /export const POST = handlers\.POST/);
  assert.match(route, /export const DELETE = handlers\.DELETE/);
  assert.match(route, /\.eq\("user_id", userId\)[\s\S]*?\.eq\("token", token\)/);
  assert.doesNotMatch(route, /onConflict:\s*"token"/);
});
