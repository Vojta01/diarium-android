/**
 * Web-push identity and support helpers.
 *
 * Subscriptions are stored **per user** in Redis. The previous build kept one
 * global set (`diarium:push:subscriptions`), so every push — including the
 * AI-report cron, which is triggered for a single user — went to every
 * subscriber's device. That is a cross-account leak, not just a UX defect:
 * these helpers are the single source of the key shape so subscribe, delete,
 * manual send and the cron cannot drift back to a shared set.
 *
 * Pure (no DOM, no Redis import) so the key/copy rules are testable in Node.
 */

export const PUSH_SUBSCRIPTIONS_PREFIX = "diarium:push:subs:";

/**
 * Redis set key holding one user's web-push subscriptions.
 * Throws on a blank id: a missing user must fail closed, never write to a
 * shared "anonymous" bucket.
 */
export function pushSubscriptionsKey(userId: string): string {
  const id = userId.trim();
  if (!id) throw new Error("pushSubscriptionsKey requires a user id");
  return `${PUSH_SUBSCRIPTIONS_PREFIX}${id}`;
}

/** What the browser exposes, captured once per check. */
export interface PushEnvironment {
  hasServiceWorker: boolean;
  hasPushManager: boolean;
  hasNotification: boolean;
  permission: NotificationPermission | null;
}

export type PushSupportState =
  | "unsupported"
  | "blocked"
  | "needs_permission"
  | "permitted";

/**
 * Browser capability/permission state. `unsupported` and `blocked` must be
 * explained honestly; `needs_permission` is the only state that may ask the
 * user, and only from the Settings card — never automatically on page load.
 */
export function pushSupportState(environment: PushEnvironment): PushSupportState {
  if (
    !environment.hasServiceWorker ||
    !environment.hasPushManager ||
    !environment.hasNotification
  ) {
    return "unsupported";
  }
  if (environment.permission === "denied") return "blocked";
  if (environment.permission === "granted") return "permitted";
  return "needs_permission";
}

/** Whether the card may offer an Enable action in this state. */
export function canSubscribe(state: PushSupportState): boolean {
  return state === "needs_permission" || state === "permitted";
}

/** i18n key for the one-line status shown in the Settings card. */
export function pushStatusCopyKey(state: PushSupportState): string {
  switch (state) {
    case "unsupported":
      return "settings.notifications_status_unsupported";
    case "blocked":
      return "settings.notifications_status_denied";
    case "permitted":
      return "settings.notifications_status_on";
    case "needs_permission":
      return "settings.notifications_status_off";
  }
}

/** Guidance key shown under the status (never promises a schedule). */
export function pushGuidanceCopyKey(state: PushSupportState): string {
  switch (state) {
    case "unsupported":
      return "settings.notifications_unsupported_hint";
    case "blocked":
      return "settings.notifications_denied_hint";
    case "permitted":
    case "needs_permission":
      return "settings.notifications_transport_hint";
  }
}

/** True when the page runs inside the Diarium Android WebView wrapper. */
export { hasAndroidNotificationBridge } from "./android-bridge.ts";

/** A `Notification` permission value from an unknown runtime value. */
export function normalizePermission(value: unknown): NotificationPermission | null {
  return value === "default" || value === "denied" || value === "granted"
    ? value
    : null;
}
