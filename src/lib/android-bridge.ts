/**
 * The only declaration of the Android WebView bridge.
 *
 * Two components talk to the wrapper (notification settings and screen-time
 * backfill). Keeping the shape in one module prevents the "subsequent property
 * declarations must have the same type" conflict a second `declare global`
 * block causes, and keeps the optional methods honest: a browser has no bridge.
 */
export interface AndroidBridge {
  openNotificationSettings?: () => void;
  forceBackfill?: () => void;
}

declare global {
  interface Window {
    AndroidBridge?: AndroidBridge;
  }
}

export function getAndroidBridge(): AndroidBridge | undefined {
  return typeof window === "undefined" ? undefined : window.AndroidBridge;
}

/** True when the object really exposes the native notification settings call. */
export function hasAndroidNotificationBridge(bridge: unknown): bridge is AndroidBridge {
  return Boolean(
    bridge &&
      typeof bridge === "object" &&
      typeof (bridge as AndroidBridge).openNotificationSettings === "function"
  );
}
