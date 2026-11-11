import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import { cs } from "../src/lib/i18n/translations/cs.ts";
import { en } from "../src/lib/i18n/translations/en.ts";
import {
  PUSH_SUBSCRIPTIONS_PREFIX,
  canSubscribe,
  hasAndroidNotificationBridge,
  normalizePermission,
  pushGuidanceCopyKey,
  pushStatusCopyKey,
  pushSubscriptionsKey,
  pushSupportState,
} from "../src/lib/push.ts";

function lookup(dictionary: unknown, path: string): unknown {
  return path.split(".").reduce<unknown>((current, part) => {
    if (current == null || typeof current !== "object") return undefined;
    return (current as Record<string, unknown>)[part];
  }, dictionary);
}

const SUPPORTED = {
  hasServiceWorker: true,
  hasPushManager: true,
  hasNotification: true,
  permission: "default" as NotificationPermission,
};

test("push subscriptions are keyed per user", () => {
  assert.equal(pushSubscriptionsKey("user-a"), `${PUSH_SUBSCRIPTIONS_PREFIX}user-a`);
  assert.equal(pushSubscriptionsKey("user-b"), `${PUSH_SUBSCRIPTIONS_PREFIX}user-b`);
  assert.notEqual(pushSubscriptionsKey("user-a"), pushSubscriptionsKey("user-b"));
  assert.ok(!pushSubscriptionsKey("user-a").startsWith("diarium:push:subscriptions"));
});

test("a blank user id fails closed instead of sharing a bucket", () => {
  assert.throws(() => pushSubscriptionsKey(""));
  assert.throws(() => pushSubscriptionsKey("   "));
});

test("permission state maps to honest support states", () => {
  assert.equal(pushSupportState(SUPPORTED), "needs_permission");
  assert.equal(
    pushSupportState({ ...SUPPORTED, permission: "denied" }),
    "blocked"
  );
  assert.equal(
    pushSupportState({ ...SUPPORTED, permission: "granted" }),
    "permitted"
  );
  assert.equal(
    pushSupportState({ ...SUPPORTED, hasPushManager: false }),
    "unsupported"
  );
  assert.equal(
    pushSupportState({ ...SUPPORTED, hasServiceWorker: false }),
    "unsupported"
  );
  assert.equal(
    pushSupportState({ ...SUPPORTED, hasNotification: false }),
    "unsupported"
  );
});

test("only a browser that can actually subscribe is offered the action", () => {
  assert.equal(canSubscribe("needs_permission"), true);
  assert.equal(canSubscribe("permitted"), true);
  assert.equal(canSubscribe("blocked"), false);
  assert.equal(canSubscribe("unsupported"), false);
});

test("status and guidance keys exist in both languages and never promise scheduling", () => {
  const states = ["unsupported", "blocked", "needs_permission", "permitted"] as const;

  for (const state of states) {
    for (const key of [pushStatusCopyKey(state), pushGuidanceCopyKey(state)]) {
      assert.equal(typeof lookup(cs, key), "string", `cs.${key} missing`);
      assert.equal(typeof lookup(en, key), "string", `en.${key} missing`);
    }
  }

  for (const dictionary of [cs, en]) {
    const transport = lookup(dictionary, "settings.notifications_transport_hint");
    assert.equal(typeof transport, "string");
    assert.doesNotMatch(String(transport), /21:00|9 PM/);
  }
});

test("Android bridge detection only accepts a callable native method", () => {
  assert.equal(hasAndroidNotificationBridge(undefined), false);
  assert.equal(hasAndroidNotificationBridge({}), false);
  assert.equal(hasAndroidNotificationBridge({ openNotificationSettings: true }), false);
  assert.equal(
    hasAndroidNotificationBridge({ openNotificationSettings: () => {} }),
    true
  );
  assert.equal(normalizePermission("granted"), "granted");
  assert.equal(normalizePermission("nonsense"), null);
});

test("no global push prompt or floating dot is mounted outside Settings", () => {
  const layout = readFileSync(
    new URL("../src/app/layout.tsx", import.meta.url),
    "utf8"
  );
  const shell = readFileSync(
    new URL("../src/components/shell/AppShell.tsx", import.meta.url),
    "utf8"
  );
  const settings = readFileSync(
    new URL("../src/components/settings/SettingsView.tsx", import.meta.url),
    "utf8"
  );

  assert.doesNotMatch(layout, /PushNotificationManager/);
  assert.doesNotMatch(shell, /NotificationSettingsButton/);
  assert.match(settings, /NotificationsCard/);
});

test("an existing browser subscription is server-registered before the UI marks it active", () => {
  const card = readFileSync(
    new URL("../src/components/settings/NotificationsCard.tsx", import.meta.url),
    "utf8"
  );
  assert.match(card, /pushManager\.getSubscription\(\)/);
  assert.match(card, /method: "POST"[\s\S]*?JSON\.stringify\(\{ subscription \}\)/);
  assert.match(card, /if \(!response\.ok\) throw new Error\(`subscribe \$\{response\.status\}`\)/);
  assert.ok(card.indexOf("if (!response.ok) throw new Error(`subscribe ${response.status}`)")
    < card.indexOf("setSubscribed(true)"));
  assert.match(card, /setSubscribed\(false\);[\s\S]*?setFailed\(true\)/);
});

test("subscribe, delete, manual send and the cron use the per-user key", () => {
  const subscribe = readFileSync(
    new URL("../src/lib/push-subscription-handler.ts", import.meta.url),
    "utf8"
  );
  const send = readFileSync(
    new URL("../src/app/api/push/send/route.ts", import.meta.url),
    "utf8"
  );
  const cron = readFileSync(
    new URL("../src/app/api/cron/ai-report/route.ts", import.meta.url),
    "utf8"
  );

  for (const source of [subscribe, send, cron]) {
    assert.match(source, /pushSubscriptionsKey/);
    assert.doesNotMatch(source, /"diarium:push:subscriptions"/);
  }

  assert.match(subscribe, /redis\.srem\(pushSubscriptionsKey\(user\.id\)/);
  assert.match(subscribe, /redis\.sadd\(pushSubscriptionsKey\(user\.id\)/);
  assert.match(send, /userId is required/);
  assert.match(send, /pushSubscriptionsKey\(userId\)/);
  assert.match(send, /deliverStoredWebPushSubscriptions/);
  assert.match(cron, /pushSubscriptionsKey\(userId\)/);
  assert.match(cron, /deliverStoredWebPushSubscriptions/);
});
