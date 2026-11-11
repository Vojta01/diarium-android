"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";

import { comparePeriods } from "@/lib/analysis/compare";
import { analysisWindows, dayAxis } from "@/lib/analysis/stats-window";
import type { AnalysisRange } from "@/lib/analysis/stats-window";
import {
  DashboardDataError,
  isDashboardAuthError,
} from "@/lib/dashboard-errors";
import { useTranslation } from "@/lib/i18n";
import type { DailyEntry } from "@/lib/stats";
import { getHabitsOrThrow, type HabitDef } from "@/lib/supabase/db";
import { useDataRefresh } from "@/lib/use-data-refresh";

import {
  CardError,
  EmptyState,
  FOCUS_RING,
} from "@/components/dashboard/primitives";

import { DayPeek } from "./DayPeek";
import { ImpactCard } from "./ImpactCard";
import { MetricTimelineCard } from "./MetricTimelineCard";
import { PeriodComparisonCard } from "./PeriodComparisonCard";

type LoadState = "loading" | "ready" | "error";

/**
 * The dashboard owns the one bounded read shared by analysis and the other
 * stats tabs. This section owns analysis view state and the habit catalogue.
 */
export function AnalysisSection({
  onOpenDay,
  today,
  entries,
  entriesLoading,
  entriesError,
  onRetryEntries,
}: {
  onOpenDay?: (date: string) => void;
  today: string;
  entries: DailyEntry[];
  entriesLoading: boolean;
  entriesError: DashboardDataError | null;
  onRetryEntries: () => void;
}) {
  const { t } = useTranslation();
  const headingId = useId();
  const peekRef = useRef<HTMLDivElement>(null);

  const [range, setRange] = useState<AnalysisRange>(30);
  const [showBaseline, setShowBaseline] = useState(true);
  const [habits, setHabits] = useState<HabitDef[] | null>(null);
  const [selectedDate, setSelectedDate] = useState<string | null>(null);

  const loadHabits = useCallback(() => {
    getHabitsOrThrow().then(setHabits).catch(() => setHabits(null));
  }, []);

  const retry = useCallback(() => {
    onRetryEntries();
    loadHabits();
  }, [onRetryEntries, loadHabits]);

  useEffect(() => {
    loadHabits();
  }, [loadHabits]);

  useDataRefresh(["habits"], loadHabits);

  const state: LoadState = entriesLoading
    ? "loading"
    : entriesError
      ? "error"
      : "ready";
  const error = entriesError;

  const windows = useMemo(() => analysisWindows(today, range), [today, range]);
  const axis = dayAxis(windows.current);
  const comparisons = useMemo(
    () => comparePeriods(entries, windows),
    [entries, windows]
  );

  const entryByDate = useMemo(
    () => new Map(entries.map((entry) => [entry.date, entry])),
    [entries]
  );

  const selectFromChip = useCallback((date: string) => {
    setSelectedDate(date);
    window.requestAnimationFrame(() => {
      peekRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
    });
  }, []);

  const ranges: AnalysisRange[] = [7, 30, 90];

  return (
    <section aria-labelledby={headingId} className="mx-auto mt-8 max-w-2xl pb-2">
      <div className="flex flex-col gap-2">
        <div>
          <h2
            id={headingId}
            className="text-[18px] font-bold tracking-[-0.3px] text-[color:var(--text-1)]"
          >
            {t("analysis.title")}
          </h2>
          <p className="mt-0.5 text-[12px] leading-[18px] text-[color:var(--text-2)]">
            {t("analysis.subtitle")}
          </p>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2">
          <div
            role="group"
            aria-label={t("analysis.range_label")}
            className="flex items-center gap-0.5 rounded-xl border border-white/10 bg-white/[0.04] p-0.5"
          >
            {ranges.map((value) => (
              <button
                key={value}
                type="button"
                aria-pressed={range === value}
                onClick={() => {
                  setRange(value);
                  setSelectedDate(null);
                }}
                className={`min-h-11 min-w-11 rounded-[10px] px-2.5 text-[11px] font-semibold transition-colors ${FOCUS_RING} ${
                  range === value
                    ? "bg-indigo-500/30 text-[color:var(--text-1)] shadow-[inset_0_1px_0_rgba(255,255,255,0.1)]"
                    : "text-[color:var(--text-2)] hover:text-[color:var(--text-1)]"
                }`}
              >
                {t(`analysis.range_${value}`)}
              </button>
            ))}
          </div>

          <button
            type="button"
            aria-pressed={showBaseline}
            onClick={() => setShowBaseline((value) => !value)}
            className={`min-h-11 rounded-xl border px-3 text-[11px] font-semibold ${FOCUS_RING} ${
              showBaseline
                ? "border-cyan-400/40 bg-cyan-500/10 text-[color:var(--text-1)]"
                : "border-white/10 bg-white/[0.03] text-[color:var(--text-2)]"
            }`}
          >
            {showBaseline
              ? t("analysis.baseline_on")
              : t("analysis.baseline_off")}
          </button>
        </div>

        {range === 7 ? (
          <p className="text-[11px] leading-4 text-[color:var(--text-3)]">
            {t("analysis.range_hint_7")}
          </p>
        ) : null}
      </div>

      {state === "loading" ? (
        <div
          aria-busy="true"
          aria-label={t("analysis.loading")}
          className="mt-4 space-y-3"
        >
          <span className="sr-only">{t("analysis.loading")}</span>
          {[0, 1, 2].map((index) => (
            <div
              key={index}
              className="glass-card h-28 animate-pulse motion-reduce:animate-none"
            />
          ))}
        </div>
      ) : state === "error" && error ? (
        <div className="glass-card mt-4">
          <CardError
            title={t("analysis.error_title")}
            message={`${t(error.code)}${
              isDashboardAuthError(error)
                ? ` ${t("analysis.error_signin")}`
                : ""
            }`}
            retryLabel={t("analysis.retry")}
            onRetry={retry}
          />
        </div>
      ) : entries.length === 0 ? (
        <div className="glass-card mt-4">
          <EmptyState
            emoji="📊"
            title={t("analysis.empty_title")}
            message={t("analysis.empty_hint")}
          />
        </div>
      ) : (
        <>
          <PeriodComparisonCard
            rows={comparisons}
            entries={entries}
            selectedDate={selectedDate}
            onSelectDate={selectFromChip}
          />

          <MetricTimelineCard
            entries={entries}
            axis={axis}
            baselineWindow={windows.baseline}
            showBaseline={showBaseline}
            selectedDate={selectedDate}
            onSelectDate={setSelectedDate}
          />

          {selectedDate ? (
            <div ref={peekRef}>
              <DayPeek
                date={selectedDate}
                entry={entryByDate.get(selectedDate)}
                habits={habits}
                onOpenDay={(date) => onOpenDay?.(date)}
                onClose={() => setSelectedDate(null)}
              />
            </div>
          ) : null}

          <ImpactCard
            entries={entries}
            axis={axis}
            habits={habits}
            selectedDate={selectedDate}
            onSelectDate={selectFromChip}
          />
        </>
      )}
    </section>
  );
}
