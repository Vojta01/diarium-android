"use client";

import { useState } from "react";
import { useTranslation } from "@/lib/i18n";

/**
 * Refresh button for the screen-time chart — only visible inside the native
 * Android app (which can re-read UsageStats and re-push the last 7 days).
 * In a plain browser there is nothing to re-sync, so it stays hidden.
 */
export function ScreenRefreshButton() {
  const { t } = useTranslation();
  const [syncing, setSyncing] = useState(false);

  const bridge = typeof window !== "undefined" ? window.AndroidBridge : undefined;
  if (!bridge?.forceBackfill) return null;

  const onClick = () => {
    setSyncing(true);
    bridge.forceBackfill!();
    setTimeout(() => setSyncing(false), 3000);
    setTimeout(() => window.location.reload(), 5000);
  };

  return (
    <button
      onClick={onClick}
      disabled={syncing}
      className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium
        bg-white/5 border border-white/10 text-white/70 hover:bg-white/10
        hover:text-white transition-all disabled:opacity-60"
      title={t("screenTime.refresh_hint")}
    >
      <span className={syncing ? "inline-block animate-spin" : "inline-block"}>
        {syncing ? "⏳" : "🔄"}
      </span>
      {syncing ? t("screenTime.refreshing") : t("screenTime.refresh")}
    </button>
  );
}