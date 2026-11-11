"use client";

import { useCallback, useEffect, useState } from "react";

import { type DashboardMetricKind } from "@/lib/dashboard";
import {
  DASHBOARD_ERROR_CODES,
  toDashboardError,
  type DashboardDataError,
} from "@/lib/dashboard-errors";
import {
  fetchDashboardData,
  type DashboardDataBundle,
} from "@/lib/dashboard-data";
import {
  DASHBOARD_FULL_SPAN_CLASS,
  DASHBOARD_GRID_CLASS,
  dashboardCardSpan,
  visibleDashboardCards,
} from "@/lib/dashboard-ui";
import { useTranslation } from "@/lib/i18n";
import { pragueDateKey } from "@/lib/date";
import type { SubScreen } from "@/lib/navigation";
import { useDataRefresh } from "@/lib/use-data-refresh";

import { DashboardMetricDialog } from "./dashboard/DashboardMetricDialog";
import { DashboardSkeleton } from "./dashboard/DashboardSkeleton";
import { DayDetailDialog } from "./dashboard/DayDetailDialog";
import { EntriesCard } from "./dashboard/EntriesCard";
import { GoalsCard } from "./dashboard/GoalsCard";
import { HeroStats } from "./dashboard/HeroStats";
import { MoodChartCard } from "./dashboard/MoodChartCard";
import { OnThisDayCard } from "./dashboard/OnThisDayCard";
import { ReflectionCard } from "./dashboard/ReflectionCard";
import { StreakCard } from "./dashboard/StreakCard";
import { TopAppsCard } from "./dashboard/TopAppsCard";
import { UsageCard } from "./dashboard/UsageCard";
import { WeekCard } from "./dashboard/WeekCard";
import { FOCUS_RING } from "./dashboard/primitives";

interface Props {
  onNavigateToCheckIn: (date: string) => void;
  onOpenSub: (sub: SubScreen) => void;
}

type LoadState =
  | { kind: "loading" }
  | { kind: "ready"; bundle: DashboardDataBundle }
  | { kind: "error"; error: DashboardDataError };

/**
 * The Overview: the Android beta5 dashboard, card for card.
 *
 * One load produces the 30-day core plus the independent goals/scales/habits
 * resources. A failed core read is a whole-screen error (signed-out and
 * transport failures keep their own messages); a failed optional resource stays
 * inside its card as an error with retry, never as an empty state. The Entries
 * card is the one exception to the error layout: its five destinations are
 * static, so it stays available even when the core read fails.
 */
export function Dashboard({ onNavigateToCheckIn, onOpenSub }: Props) {
  const { t } = useTranslation();
  const today = pragueDateKey();
  const [state, setState] = useState<LoadState>({ kind: "loading" });
  const [refreshing, setRefreshing] = useState(false);
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const [metric, setMetric] = useState<DashboardMetricKind | null>(null);
  const [dayDate, setDayDate] = useState<string | null>(null);

  const load = useCallback(
    async (mode: "initial" | "refresh") => {
      if (mode === "initial") setState({ kind: "loading" });
      else setRefreshing(true);

      try {
        const bundle = await fetchDashboardData(today);
        setState({ kind: "ready", bundle });
        setRefreshError(null);
      } catch (error) {
        const dashboardError = toDashboardError(
          error,
          DASHBOARD_ERROR_CODES.entries
        );
        if (mode === "initial") {
          setState({ kind: "error", error: dashboardError });
        } else {
          setRefreshError(dashboardError.code);
        }
      } finally {
        if (mode === "refresh") setRefreshing(false);
      }
    },
    [today]
  );

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async fetch resolves after the effect body
    void load("initial");
  }, [load]);

  // The overview stays mounted while the user edits on other tabs, so a save
  // must refetch the derived data instead of trusting the mount snapshot.
  useDataRefresh(["entries", "goals", "habits", "scales"], () => {
    void load("refresh");
  });

  const openDay = useCallback((date: string) => {
    setMetric(null);
    setDayDate(date);
  }, []);

  const openMetric = useCallback((kind: DashboardMetricKind) => {
    setDayDate(null);
    setMetric(kind);
  }, []);

  if (state.kind === "loading") {
    return (
      <div className="mx-auto w-full max-w-[1040px]">
        <DashboardSkeleton />
      </div>
    );
  }

  if (state.kind === "error") {
    return (
      <div className="mx-auto w-full max-w-[1040px]">
        <div className="mx-auto flex min-h-[50vh] w-full max-w-[440px] flex-col items-center justify-center py-10 text-center">
          <span aria-hidden="true" className="text-[34px] leading-none">
            {state.error.kind === "signed_out" ? "🔒" : "⚠️"}
          </span>
          <h2 className="mt-3 text-[20px] font-semibold text-[color:var(--text-1)]">
            {t(
              state.error.kind === "signed_out"
                ? "dashboard.signed_out_title"
                : "dashboard.load_failed_title"
            )}
          </h2>
          <p
            role="alert"
            className="mt-1.5 text-[13px] leading-5 text-[color:var(--text-2)]"
          >
            {state.error.kind === "signed_out"
              ? t("dashboard.signed_out_message")
              : t(state.error.code)}
          </p>
          <button
            type="button"
            onClick={() => void load("initial")}
            className={`mt-4 min-h-11 rounded-[14px] border border-indigo-500/50 bg-indigo-500/[0.18] px-5 text-[14px] font-semibold text-[color:var(--text-1)] hover:bg-indigo-500/25 ${FOCUS_RING}`}
          >
            {t("dashboard.retry")}
          </button>
        </div>
        <div className="mt-2">
          <EntriesCard onOpenSub={onOpenSub} />
        </div>
      </div>
    );
  }

  const bundle = state.bundle;
  const { data } = bundle;
  const visible = visibleDashboardCards(
    {
      data,
      goals: bundle.goals,
      goalsError: bundle.goalsError,
    },
    { entries: true }
  );
  const goalsError = bundle.goalsError ? t(bundle.goalsError) : null;
  const scalesError = bundle.scalesError ? t(bundle.scalesError) : null;
  const refresh = () => {
    void load("refresh");
  };

  return (
    <div className="mx-auto w-full max-w-[1040px]" aria-busy={refreshing || undefined}>
      {refreshing ? (
        <p
          aria-live="polite"
          className="mb-3 text-center text-[12px] text-[color:var(--text-2)]"
        >
          {t("dashboard.refreshing")}
        </p>
      ) : null}
      {refreshError ? (
        <div
          role="alert"
          className="mb-3 flex flex-wrap items-center justify-between gap-2 rounded-xl border border-red-400/25 bg-red-500/10 px-3 py-2"
        >
          <span className="text-[12px] text-red-200">
            {t("dashboard.refresh_failed")} {t(refreshError)}
          </span>
          <button
            type="button"
            onClick={refresh}
            className={`min-h-11 rounded-lg border border-white/10 px-3 text-[12px] text-[color:var(--text-1)] ${FOCUS_RING}`}
          >
            {t("dashboard.retry")}
          </button>
        </div>
      ) : null}

      <div className={DASHBOARD_GRID_CLASS}>
        {visible.map((id) => {
          const span = dashboardCardSpan(id, visible);
          return (
            <div
              key={id}
              className={`min-w-0 ${span === 2 ? DASHBOARD_FULL_SPAN_CLASS : ""}`}
            >
              {id === "hero_stats" ? (
                <HeroStats data={data} onOpenMetric={openMetric} />
              ) : null}
              {id === "streak" ? <StreakCard data={data} /> : null}
              {id === "week" ? <WeekCard data={data} onOpenDay={openDay} /> : null}
              {id === "mood_chart" ? (
                <MoodChartCard data={data} onOpenDay={openDay} />
              ) : null}
              {id === "on_this_day" && data.onThisDay ? (
                <OnThisDayCard memory={data.onThisDay} onOpenDay={openDay} />
              ) : null}
              {id === "goals" ? (
                <GoalsCard
                  goals={bundle.goals}
                  error={goalsError}
                  totalCount={bundle.goals?.length ?? 0}
                  onRefresh={refresh}
                />
              ) : null}
              {id === "screen_time" ? (
                <UsageCard kind="screen_time" data={data} onRefresh={refresh} />
              ) : null}
              {id === "unlocks" ? (
                <UsageCard kind="unlocks" data={data} onRefresh={refresh} />
              ) : null}
              {id === "top_apps" ? (
                <TopAppsCard data={data} onRefresh={refresh} />
              ) : null}
              {id === "reflection" ? (
                <ReflectionCard data={data} onRefresh={refresh} />
              ) : null}
              {id === "entries" ? (
                <EntriesCard onOpenSub={onOpenSub} />
              ) : null}
            </div>
          );
        })}
      </div>

      <DashboardMetricDialog
        open={metric !== null}
        kind={metric ?? "screen_time"}
        data={data}
        onClose={() => setMetric(null)}
      />

      <DayDetailDialog
        open={dayDate !== null}
        date={dayDate ?? today}
        entry={dayDate ? (data.entryByDate[dayDate] ?? null) : null}
        habits={bundle.habits}
        scales={bundle.scales}
        scalesError={scalesError}
        onRetry={refresh}
        onClose={() => setDayDate(null)}
        onEdit={(date) => {
          setDayDate(null);
          onNavigateToCheckIn(date);
        }}
      />
    </div>
  );
}
