"use client";

import { useState, useEffect } from "react";
import { Markdown } from "@/components/Markdown";
import { getAccessToken } from "@/lib/supabase/db";
import { useTranslation } from "@/lib/i18n";

interface Report {
  content: string;
  period_start: string;
  period_end: string;
  created_at: string;
}

interface Props {
  userId: string;
}

export function PeriodicSummary({ userId }: Props) {
  const { t, lang } = useTranslation();
  const [type, setType] = useState<"weekly" | "monthly" | "yearly">("weekly");
  const [analysis, setAnalysis] = useState<string | null>(null);
  const [reportMeta, setReportMeta] = useState<Report | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadingReport, setLoadingReport] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Auto-fetch existing report on mount and when type changes
  useEffect(() => {
    async function fetchExisting() {
      setLoadingReport(true);
      setAnalysis(null);
      setReportMeta(null);
      setLoadError(null);
      setError(null);
      try {
        const tok = getAccessToken();
        const resp = await fetch(`/api/ai/reports?type=${type}&user_id=${userId}`, {
          headers: { ...(tok ? { "Authorization": `Bearer ${tok}` } : {}) },
        });
        const json = await resp.json();
        if (!resp.ok || json.error) {
          setLoadError(json.error || t("periodicSummary.error_connection"));
          return;
        }
        if (json.report) {
          setAnalysis(json.report.content);
          setReportMeta(json.report);
        }
      } catch (e) {
        setLoadError(
          e instanceof Error && e.message
            ? e.message
            : t("periodicSummary.error_connection")
        );
      } finally {
        setLoadingReport(false);
      }
    }
    fetchExisting();
  }, [type, userId, t]);

  const fetchSummary = async () => {
    setLoading(true);
    setError(null);
    try {
      const tok = getAccessToken();
      const resp = await fetch("/api/ai/periodic", {
        method: "POST",
        headers: { "Content-Type": "application/json", ...(tok ? { "Authorization": `Bearer ${tok}` } : {}) },
        body: JSON.stringify({ type, user_id: userId, lang, force: true }),
      });
      const json = await resp.json();
      if (json.error) {
        setError(json.error);
      } else if (json.analysis) {
        setAnalysis(json.analysis);
        setReportMeta(null); // Live generated, no DB meta
      }
    } catch (e) {
      setError(
        e instanceof Error && e.message
          ? e.message
          : t("periodicSummary.error_connection")
      );
    }
    setLoading(false);
  };

  const periodLabel = type === "weekly" ? t("periodicSummary.weekly") : type === "monthly" ? t("periodicSummary.monthly") : t("periodicSummary.yearly");

  return (
    <div className="glass-card">
      <h2 className="text-lg font-semibold text-white mb-4">{t("periodicSummary.title")}</h2>

      <div className="flex gap-2 mb-4">
        {(["weekly", "monthly", "yearly"] as const).map((periodType) => (
          <button
            key={periodType}
            onClick={() => setType(periodType)}
            className={`flex-1 py-2 rounded-xl text-sm font-medium transition-all ${
              type === periodType
                ? "bg-indigo-500/20 text-white border border-indigo-400/30"
                : "text-white/30 hover:text-white/50"
            }`}
          >
            {periodType === "weekly" ? t("periodicSummary.tab_weekly") : periodType === "monthly" ? t("periodicSummary.tab_monthly") : t("periodicSummary.tab_yearly")}
          </button>
        ))}
      </div>

      {/* Existing report (from cron) */}
      {loadingReport && (
        <div className="p-4 text-white/30 text-sm text-center">{t("periodicSummary.loading")}</div>
      )}

      {!loadingReport && loadError ? (
        <div
          role="alert"
          className="p-4 bg-red-500/10 border border-red-500/20 rounded-xl text-red-400 text-sm mb-4"
        >
          {t("periodicSummary.error_prefix", { error: loadError })}
        </div>
      ) : null}

      {analysis && (
        <div className="p-4 bg-indigo-500/5 border border-indigo-400/10 rounded-xl mb-4">
          {reportMeta && (
            <div className="text-xs text-white/30 mb-2">
              {t("periodicSummary.generated_at", { date: new Date(reportMeta.created_at).toLocaleDateString(lang === "cs" ? "cs-CZ" : "en-US"), start: reportMeta.period_start, end: reportMeta.period_end })}
            </div>
          )}
          {!reportMeta && (
            <div className="text-xs text-white/30 mb-2">{t("periodicSummary.just_generated")}</div>
          )}
          <Markdown content={analysis} />
        </div>
      )}

      {!loadingReport && !analysis && !loadError && (
        <div className="p-4 text-white/20 text-sm text-center mb-2">
          {t("periodicSummary.no_report", { period: periodLabel.toLowerCase() })}
        </div>
      )}

      {/* Manual generate button */}
      <button
        onClick={fetchSummary}
        disabled={loading}
        className="w-full py-2.5 rounded-xl bg-white/5 border border-white/10 hover:bg-white/10 disabled:opacity-50 text-white/50 hover:text-white/70 text-sm transition-colors"
      >
        {loading ? t("periodicSummary.generating") : t("periodicSummary.generate", { period: periodLabel.toLowerCase() })}
      </button>

      {error && (
        <div className="mt-4 p-4 bg-red-500/10 border border-red-500/20 rounded-xl text-red-400 text-sm">
          {error === "Not enough data yet"
            ? t("periodicSummary.not_enough_data")
            : t("periodicSummary.error_prefix", { error })}
        </div>
      )}
    </div>
  );
}
