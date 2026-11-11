"use client";

import { useEffect, useRef, useState } from "react";

import type { DashboardData } from "@/lib/dashboard";
import { formatDashboardDate, reflectionView } from "@/lib/dashboard-ui";
import { useTranslation } from "@/lib/i18n";
import { readStoredSession } from "@/lib/auth-storage";
import { getAccessToken } from "@/lib/supabase/db";

import { ACCENT, ActionButton, Card, Hint, Lead } from "./primitives";

type ReflectionErrorKey =
  | "dashboard.reflection.error_generic"
  | "dashboard.reflection.error_connect"
  | "dashboard.reflection.error_cooldown"
  | "dashboard.reflection.error_unavailable"
  | "dashboard.reflection.error_signed_out";

function errorKeyForStatus(status: number): ReflectionErrorKey {
  if (status === 401) return "dashboard.reflection.error_signed_out";
  if (status === 403) return "dashboard.reflection.error_unavailable";
  if (status === 429) return "dashboard.reflection.error_cooldown";
  if (status >= 500) return "dashboard.reflection.error_generic";
  return "dashboard.reflection.error_generic";
}

/**
 * 🤖 The newest AI reflection, with a generate action for the newest recorded
 * day that still has none.
 *
 * Generation goes through the same `/api/ai/reflect` route and server prompt as
 * the check-in screen, saves through `/api/save-entry`, then asks the dashboard
 * to refetch so the card and its data agree. A cooldown, an expired session and
 * a network failure each get their own visible message.
 */
export function ReflectionCard({
  data,
  onRefresh,
}: {
  data: DashboardData;
  onRefresh: () => void;
}) {
  const { t, lang } = useTranslation();
  const view = reflectionView(data);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ReflectionErrorKey | null>(null);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  if (!view) return null;

  const missingLabel = view.missingDate
    ? formatDashboardDate(view.missingDate, lang, "numeric")
    : null;

  async function generate() {
    if (!view?.missingDate || busy) return;
    const targetDate = view.missingDate;
    setBusy(true);
    setError(null);

    const session = readStoredSession();
    const token = getAccessToken();
    if (!session?.user?.id || !token) {
      setError("dashboard.reflection.error_signed_out");
      setBusy(false);
      return;
    }

    try {
      const response = await fetch("/api/ai/reflect", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({
          user_id: session.user.id,
          date: targetDate,
          userName: session.user.email ?? "",
        }),
      });

      if (!response.ok) {
        if (mounted.current) setError(errorKeyForStatus(response.status));
        return;
      }

      const json = (await response.json()) as { reflection?: string };
      const reflection = json.reflection?.trim();
      if (!reflection) {
        if (mounted.current) setError("dashboard.reflection.error_generic");
        return;
      }

      // Persist exactly as the check-in screen does, so a refresh keeps it.
      const save = await fetch("/api/save-entry", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({
          user_id: session.user.id,
          date: targetDate,
          ai_reflection: reflection,
        }),
      });
      if (!save.ok && mounted.current) {
        setError("dashboard.reflection.error_generic");
      }

      if (mounted.current) onRefresh();
    } catch {
      if (mounted.current) setError("dashboard.reflection.error_connect");
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  return (
    <Card
      cardId="reflection"
      title={t("dashboard.reflection.title")}
      accent={ACCENT.violet}
      className="h-full"
    >
      <div className="mt-1">
        {view.date ? (
          <>
            <Lead>{t("dashboard.reflection.latest")}</Lead>
            <Hint>
              {t("dashboard.reflection.from_date", {
                date: formatDashboardDate(view.date, lang, "numeric"),
              })}
            </Hint>
          </>
        ) : (
          <Hint>{t("dashboard.reflection.none")}</Hint>
        )}
      </div>

      {view.text ? (
        <div className="mt-2 rounded-xl border border-white/[0.07] bg-black/20 px-3 py-2.5">
          <p className="whitespace-pre-line text-[13px] leading-5 text-[color:var(--text-1)]">
            {view.text}
          </p>
        </div>
      ) : null}

      {view.canGenerate && missingLabel ? (
        <div className="mt-3">
          {!view.text ? (
            <Hint>{t("dashboard.reflection.none")}</Hint>
          ) : (
            <Hint>
              {t("dashboard.reflection.missing_for_date", {
                date: missingLabel,
              })}
            </Hint>
          )}
          <div className="mt-2">
            <ActionButton onClick={generate} disabled={busy} busy={busy}>
              {busy
                ? t("dashboard.reflection.generating")
                : t("dashboard.reflection.generate", { date: missingLabel })}
            </ActionButton>
          </div>
        </div>
      ) : null}

      {error ? (
        <p
          role="alert"
          className="mt-2 rounded-lg border border-red-400/25 bg-red-500/10 px-2.5 py-2 text-[12px] leading-[18px] text-red-200"
        >
          {t(error)}
        </p>
      ) : null}
    </Card>
  );
}
