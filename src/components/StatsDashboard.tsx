"use client";

import { useState, useEffect, useCallback, useMemo, useRef } from "react";
import { YearInPixels } from "@/components/YearInPixels";
import { CalendarView } from "@/components/CalendarView";
import { ScreenTimeChart } from "@/components/ScreenTimeChart";
import { PeriodicSummary } from "@/components/PeriodicSummary";
import { AchievementsPanel } from "@/components/AchievementsPanel";
import { PhotoGallery } from "@/components/PhotoGallery";
import { ExportDialog } from "@/components/ExportDialog";
import { AnalysisSection } from "@/components/stats/AnalysisSection";
import { FOCUS_RING } from "@/components/dashboard/primitives";
import { fetchDailyEntries, type DailyEntry } from "@/lib/stats";
import { createStatsOverviewLoader, statsOverviewRange } from "@/lib/stats-dashboard-data";
import { pragueDateKey } from "@/lib/date";
import { DASHBOARD_ERROR_CODES, toDashboardError, type DashboardDataError } from "@/lib/dashboard-errors";
import { getFeatureFlags } from "@/lib/feature-flags";
import { useTranslation } from "@/lib/i18n";
import { readStoredSession } from "@/lib/auth-storage";
import { useDataRefresh } from "@/lib/use-data-refresh";

type StatsTab = "calendar" | "photos" | "screentime" | "ai" | "year";

export function StatsDashboard({ onNavigateToDate }: { onNavigateToDate?: (date: string) => void }) {
  const { t } = useTranslation();
  const [entries, setEntries] = useState<DailyEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [entriesError, setEntriesError] = useState<DashboardDataError | null>(null);
  const [tab, setTab] = useState<StatsTab>("calendar");
  const [showExport, setShowExport] = useState(false);
  const [userId] = useState(() => readStoredSession()?.user?.id || "");
  const flags = getFeatureFlags();
  const [today, setToday] = useState(() => pragueDateKey());
  const overviewRange = useMemo(() => statsOverviewRange(today), [today]);
  const loadOverview = useMemo(
    () => createStatsOverviewLoader(fetchDailyEntries),
    []
  );
  const fullHistoryRequested = useRef(false);
  const fullHistoryRequest = useRef<Promise<void> | null>(null);
  const loadSequence = useRef(0);

  const loadFullHistory = useCallback((forceFresh = false) => {
    if (!forceFresh) {
      if (fullHistoryRequest.current) return fullHistoryRequest.current;
      if (fullHistoryRequested.current) return null;
    }

    fullHistoryRequested.current = true;
    const requestId = ++loadSequence.current;
    const request: Promise<void> = fetchDailyEntries()
      .then((data) => {
        if (requestId !== loadSequence.current) return;
        setEntries(data);
        setEntriesError(null);
        setLoading(false);
      })
      .catch((failure) => {
        if (requestId !== loadSequence.current) return;
        setEntriesError(toDashboardError(failure, DASHBOARD_ERROR_CODES.entries));
        setLoading(false);
      })
      .finally(() => {
        if (fullHistoryRequest.current === request) fullHistoryRequest.current = null;
      });
    fullHistoryRequest.current = request;
    return request;
  }, []);

  const load = useCallback((forceFresh = false) => {
    if (fullHistoryRequested.current) {
      void loadFullHistory(forceFresh);
      return;
    }

    const requestId = ++loadSequence.current;
    const currentToday = pragueDateKey();
    setToday(currentToday);
    loadOverview(currentToday, forceFresh)
      .then((data) => {
        if (requestId !== loadSequence.current || fullHistoryRequested.current) return;
        setEntries(data);
        setEntriesError(null);
        setLoading(false);
      })
      .catch((failure) => {
        if (requestId !== loadSequence.current || fullHistoryRequested.current) return;
        setEntriesError(toDashboardError(failure, DASHBOARD_ERROR_CODES.entries));
        setLoading(false);
      });
  }, [loadFullHistory, loadOverview]);

  const retryLoad = useCallback(() => {
    setLoading(true);
    setEntriesError(null);
    load(true);
  }, [load]);

  const selectTab = useCallback((next: StatsTab) => {
    setTab(next);
    if (next === "photos" || next === "screentime") void loadFullHistory();
  }, [loadFullHistory]);

  const ensureCalendarHistory = useCallback((year: number, month: number) => {
    const monthKey = `${year}-${String(month).padStart(2, "0")}`;
    if (monthKey <= overviewRange.from.slice(0, 7)) void loadFullHistory();
  }, [loadFullHistory, overviewRange.from]);

  useEffect(() => {
    load();
  }, [load]);

  const refreshEntries = useCallback(() => load(true), [load]);
  useDataRefresh(["entries"], refreshEntries);


  const tabs: { key: StatsTab; label: string }[] = [
    { key: "calendar", label: t("statsDashboard.tab_calendar") },
    { key: "photos", label: t("statsDashboard.tab_photos") },
  ];
  if (flags.screenTime) {
    tabs.push({ key: "screentime", label: t("statsDashboard.tab_screentime") });
  }
  tabs.push(
    { key: "ai", label: t("statsDashboard.tab_ai") },
    { key: "year", label: t("statsDashboard.tab_pixels") }
  );

  return (
    <div>
      {/* Tab bar: 3 columns at 390 px and a single row from sm up. This is the
          fix for six labels squeezed into a max-w-md flex row. */}
      <div className="mx-auto mb-6 grid max-w-md grid-cols-3 gap-1 sm:grid-cols-5">
        {tabs.map((item) => (
          <button
            key={item.key}
            type="button"
            aria-pressed={tab === item.key}
            onClick={() => selectTab(item.key)}
            className={`flex min-h-11 items-center justify-center rounded-xl px-1.5 py-2 text-center text-[11px] font-medium leading-tight transition-all sm:text-sm ${FOCUS_RING} ${
              tab === item.key
                ? "border border-indigo-400/30 bg-indigo-500/20 text-white"
                : "text-white/40 hover:text-white/70"
            }`}
          >
            {item.label}
          </button>
        ))}
      </div>

      <div className="mx-auto max-w-2xl pb-2">
        {loading && entries.length === 0 ? (
          <div aria-busy="true" className="flex min-h-40 items-center justify-center">
            <div className="text-white/40 text-lg">{t("statsDashboard.loading")}</div>
          </div>
        ) : (
          <>
            {tab === "calendar" && (
              <CalendarView
                entries={entries}
                onNavigateToDate={onNavigateToDate}
                onViewMonth={ensureCalendarHistory}
              />
            )}
            {tab === "photos" && <PhotoGallery entries={entries} onNavigateToDate={onNavigateToDate} />}
            {tab === "screentime" && flags.screenTime && <ScreenTimeChart entries={entries} />}
            {tab === "ai" && <PeriodicSummary userId={userId} />}
            {tab === "year" && <YearInPixels />}
          </>
        )}
      </div>

      <AnalysisSection
        onOpenDay={onNavigateToDate}
        today={today}
        entries={entries}
        entriesLoading={loading}
        entriesError={entriesError}
        onRetryEntries={retryLoad}
      />

      <div className="mx-auto mt-6 max-w-2xl pb-2">
        <AchievementsPanel />
        <div className="flex justify-center pt-4">
          <button
            onClick={() => setShowExport(true)}
            className={`px-4 py-2 rounded-xl bg-white/5 border border-white/10 text-white/50 hover:text-white hover:bg-white/10 transition-colors text-sm ${FOCUS_RING}`}
          >
            📥 {t("export.export_button")}
          </button>
        </div>
      </div>

      <ExportDialog open={showExport} onClose={() => setShowExport(false)} />
    </div>
  );
}
