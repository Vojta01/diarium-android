"use client";

import { useSyncExternalStore } from "react";

import { useTranslation } from "@/lib/i18n";
import { getAndroidBridge, hasAndroidNotificationBridge } from "@/lib/android-bridge";

const subscribeToNothing = () => () => {};
const getServerSnapshot = () => false;

function bridgeAvailable(): boolean {
  return hasAndroidNotificationBridge(getAndroidBridge());
}

/**
 * Opens the NATIVE notification settings inside the Android app.
 *
 * The Diarium Android wrapper schedules reminders locally; the browser cannot.
 * The control therefore exists only inside that wrapper and is rendered as a
 * normal 44 px row — never as the old 21 px bell that returned null in every
 * browser and left the Settings card with no action at all.
 */
export function NotificationSettingsButton() {
  const { t } = useTranslation();
  const available = useSyncExternalStore(
    subscribeToNothing,
    bridgeAvailable,
    getServerSnapshot
  );

  if (!available) return null;

  return (
    <button
      type="button"
      onClick={() => getAndroidBridge()?.openNotificationSettings?.()}
      className="flex min-h-11 w-full flex-col items-start justify-center rounded-xl border border-white/10 px-3 py-2 text-left transition-colors hover:bg-white/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/70"
    >
      <span className="text-sm font-medium text-[color:var(--text-1)]">
        {t("settings.notifications_native_button")}
      </span>
      <span className="text-xs leading-5 text-[color:var(--text-2)]">
        {t("settings.notifications_native_hint")}
      </span>
    </button>
  );
}
