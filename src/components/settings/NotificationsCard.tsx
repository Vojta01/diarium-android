"use client";

import { useCallback, useEffect, useState } from "react";

import { NotificationSettingsButton } from "@/components/NotificationSettingsButton";
import { VAPID_PUBLIC_KEY } from "@/lib/vapid";
import { useTranslation } from "@/lib/i18n";
import { getAccessToken } from "@/lib/supabase/db";
import {
  canSubscribe,
  normalizePermission,
  pushGuidanceCopyKey,
  pushStatusCopyKey,
  pushSupportState,
  type PushSupportState,
} from "@/lib/push";

type CardState = "checking" | "ready" | "working" | "error";

function authHeaders(): Record<string, string> {
  const token = getAccessToken();
  return {
    "Content-Type": "application/json",
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

function urlBase64ToUint8Array(base64String: string): Uint8Array {
  const padding = "=".repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, "+").replace(/_/g, "/");
  const rawData = atob(base64);
  const output = new Uint8Array(rawData.length);
  for (let i = 0; i < rawData.length; i += 1) output[i] = rawData.charCodeAt(i);
  return output;
}

function currentSupport(): PushSupportState {
  if (typeof window === "undefined" || typeof navigator === "undefined") {
    return "unsupported";
  }
  return pushSupportState({
    hasServiceWorker: "serviceWorker" in navigator,
    hasPushManager: typeof window.PushManager !== "undefined",
    hasNotification: typeof window.Notification !== "undefined",
    permission: normalizePermission(
      typeof window.Notification !== "undefined"
        ? window.Notification.permission
        : null
    ),
  });
}

/**
 * The only push UI in the app.
 *
 * It never asks for permission on mount and never auto-subscribes: opening
 * Settings must not trigger a browser prompt. The status it shows is the real
 * `Notification.permission` plus whether this browser currently holds a push
 * subscription. Copy states plainly that web push is transport only — the web
 * has no scheduler, Android does.
 */
export function NotificationsCard() {
  const { t } = useTranslation();
  const [support, setSupport] = useState<PushSupportState>(() => currentSupport());
  const [state, setState] = useState<CardState>("checking");
  const [subscribed, setSubscribed] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const next = currentSupport();

    // Probe in an async callback: a synchronous setState inside the effect
    // body would cascade, and this only synchronises with a browser API.
    if (next === "unsupported" || next === "blocked") {
      void Promise.resolve().then(() => {
        if (cancelled) return;
        setSupport(next);
        setSubscribed(false);
        setState("ready");
      });
      return () => {
        cancelled = true;
      };
    }

    void (async () => {
      try {
        const registration = await navigator.serviceWorker.ready;
        const subscription = await registration.pushManager.getSubscription();
        if (!subscription) {
          if (cancelled) return;
          setSupport(next);
          setSubscribed(false);
          setState("ready");
          return;
        }

        // Re-register this browser's current subscription under the signed-in
        // user's scoped Redis key. A local browser subscription alone is not
        // proof that the server has a valid, deliverable registration.
        const response = await fetch("/api/push/subscribe", {
          method: "POST",
          headers: authHeaders(),
          body: JSON.stringify({ subscription }),
        });
        if (!response.ok) throw new Error(`subscribe ${response.status}`);
        if (cancelled) return;
        setSupport(next);
        setSubscribed(true);
        setFailed(false);
        setState("ready");
      } catch {
        if (cancelled) return;
        setSupport(next);
        setSubscribed(false);
        setFailed(true);
        setState("ready");
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  const enable = useCallback(async () => {
    setFailed(false);
    setState("working");

    try {
      const permission = await Notification.requestPermission();
      setSupport(pushSupportState({
        hasServiceWorker: "serviceWorker" in navigator,
        hasPushManager: typeof window.PushManager !== "undefined",
        hasNotification: true,
        permission: normalizePermission(permission),
      }));
      if (permission !== "granted") {
        setSubscribed(false);
        setState("ready");
        return;
      }

      const registration = await navigator.serviceWorker.ready;
      let subscription = await registration.pushManager.getSubscription();
      if (!subscription) {
        if (!VAPID_PUBLIC_KEY) throw new Error("missing VAPID key");
        subscription = await registration.pushManager.subscribe({
          userVisibleOnly: true,
          applicationServerKey: urlBase64ToUint8Array(
            VAPID_PUBLIC_KEY
          ) as unknown as BufferSource,
        });
      }

      const response = await fetch("/api/push/subscribe", {
        method: "POST",
        headers: authHeaders(),
        body: JSON.stringify({ subscription }),
      });
      if (!response.ok) throw new Error(`subscribe ${response.status}`);

      setSubscribed(true);
      setState("ready");
    } catch {
      setFailed(true);
      setState("ready");
    }
  }, []);

  const disable = useCallback(async () => {
    setFailed(false);
    setState("working");

    try {
      const registration = await navigator.serviceWorker.ready;
      const subscription = await registration.pushManager.getSubscription();

      if (subscription) {
        // Delete on the server first: if that fails, the user is still
        // subscribed and the card must say so instead of lying.
        const response = await fetch("/api/push/subscribe", {
          method: "DELETE",
          headers: authHeaders(),
          body: JSON.stringify({ subscription }),
        });
        if (!response.ok) throw new Error(`unsubscribe ${response.status}`);
        await subscription.unsubscribe();
      }

      setSubscribed(false);
      setState("ready");
    } catch {
      setFailed(true);
      setState("ready");
    }
  }, []);

  const busy = state === "working";
  const statusKey =
    state === "checking"
      ? "settings.notifications_status_checking"
      : support === "permitted" && !subscribed
        ? "settings.notifications_status_off"
        : pushStatusCopyKey(support);

  return (
    <div className="space-y-3">
      <p className="text-sm leading-6 text-[color:var(--text-2)]">
        {t("settings.notifications_hint")}
      </p>
      <p
        className="text-sm leading-6 text-[color:var(--text-2)]"
        role="status"
        aria-live="polite"
      >
        {t(statusKey)}
      </p>
      <p className="text-xs leading-5 text-[color:var(--text-2)]">
        {t(pushGuidanceCopyKey(support))}
      </p>

      {failed ? (
        <p role="alert" className="text-sm text-red-300">
          {t("settings.notifications_error")}
        </p>
      ) : null}

      {support === "unsupported" || support === "blocked" ? null : (
        <div className="flex flex-wrap gap-2">
          {!subscribed && canSubscribe(support) ? (
            <button
              type="button"
              onClick={() => void enable()}
              disabled={busy}
              aria-busy={busy}
              className="min-h-11 rounded-xl bg-indigo-500 px-4 text-sm font-medium text-white transition-colors hover:bg-indigo-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300 disabled:opacity-50"
            >
              {busy ? t("settings.notifications_busy") : t("settings.notifications_enable")}
            </button>
          ) : null}
          {subscribed ? (
            <button
              type="button"
              onClick={() => void disable()}
              disabled={busy}
              aria-busy={busy}
              className="min-h-11 rounded-xl border border-white/15 px-4 text-sm font-medium text-[color:var(--text-1)] transition-colors hover:bg-white/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300 disabled:opacity-50"
            >
              {busy ? t("settings.notifications_busy") : t("settings.notifications_disable")}
            </button>
          ) : null}
        </div>
      )}

      <NotificationSettingsButton />

      <p className="text-xs leading-5 text-[color:var(--text-2)]">
        {t("settings.notifications_transport_hint")}
      </p>
    </div>
  );
}
