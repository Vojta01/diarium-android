"use client";

import { useCallback, useEffect, useMemo, useState } from "react";

import { PhotoGallery } from "@/components/PhotoGallery";
import { CardError, EmptyState } from "@/components/dashboard/primitives";
import { CalendarCard, HistoryMonthNav } from "@/components/history/CalendarCard";
import { DayDetailCard } from "@/components/history/DayDetailCard";
import {
  DASHBOARD_ERROR_CODES,
  toDashboardError,
  type DashboardDataError,
} from "@/lib/dashboard-errors";
import { pragueDateKey } from "@/lib/date";
import {
  historyEntryMap,
  historyMonthCountKey,
  historyMonthOf,
  historyMonthRange,
  historyMonthStats,
  historyMonthTitle,
  historyStepMonth,
  historySyncedNoteKey,
  type HistoryMonth,
  type HistoryMonthStats,
} from "@/lib/history";
import { useTranslation } from "@/lib/i18n";
import { getScalesOrThrow, type Scale } from "@/lib/scales";
import { fetchDailyEntries, type DailyEntry } from "@/lib/stats";
import { getHabitsOrThrow, type HabitDef } from "@/lib/supabase/db";
import { useDataRefresh } from "@/lib/use-data-refresh";

type HistoryLoad =
  | { status: "loading" }
  | {
      status: "ready";
      year: number;
      monthIndex: number;
      entries: DailyEntry[];
    }
  | { status: "error"; error: DashboardDataError };

/**
 * Historie — a month at a glance, and one day in full.
 *
 * The calendar answers "what did this month look like"; selecting a day opens
 * its read-only detail below the grid. One read per visible month, capped at
 * today, so opening History cannot fetch the account's whole archive and a
 * future month costs no request. A failed read stays an error with retry —
 * never an empty month that would read as "nothing written".
 *
 * The photo gallery is a web-only secondary mode (Android has no gallery);
 * the calendar and day detail are the parity screen.
 */
export function HistoryView({
  onNavigateToDate,
}: {
  onNavigateToDate: (date: string) => void;
}) {
  const { t, lang } = useTranslation();
  const today = pragueDateKey();
  const todayMonth = historyMonthOf(today);

  const [month, setMonth] = useState<HistoryMonth>(
    () => todayMonth ?? { year: Number(today.slice(0, 4)), monthIndex: 0 }
  );
  const [selected, setSelected] = useState<string | null>(null);
  const [mode, setMode] = useState<"calendar" | "photos">("calendar");
  const [load, setLoad] = useState<HistoryLoad>({ status: "loading" });
  const [reload, setReload] = useState(0);

  // Scale names/maxima are keyed by uuid in `scale_values`, so the detail
  // needs the user's scales; habits turn stored keys into readable labels.
  const [scales, setScales] = useState<Scale[] | null>(null);
  const [scalesError, setScalesError] = useState(false);
  const [habits, setHabits] = useState<HabitDef[] | null>(null);

  const loadScales = useCallback(async () => {
    try {
      const next = await getScalesOrThrow();
      setScales(next);
      setScalesError(false);
    } catch {
      // A failed read is not an empty list: the detail shows retry instead of
      // pretending the user has no scales.
      setScales(null);
      setScalesError(true);
    }
  }, []);

  const loadHabits = useCallback(async () => {
    try {
      setHabits(await getHabitsOrThrow());
    } catch {
      // Habit labels fall back to humanized keys; the day itself still renders.
      setHabits(null);
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- the scales read resolves after the effect body
    void loadScales();
    void loadHabits();
  }, [loadHabits, loadScales]);

  useEffect(() => {
    let active = true;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- a new month must show its loading state before the async read resolves
    setLoad({ status: "loading" });

    const range = historyMonthRange(month.year, month.monthIndex, today);
    if (!range) {
      // A month that has not started yet is empty and costs no request.
      setLoad({
        status: "ready",
        year: month.year,
        monthIndex: month.monthIndex,
        entries: [],
      });
      return () => {
        active = false;
      };
    }

    fetchDailyEntries(range)
      .then((entries) => {
        if (active) {
          setLoad({
            status: "ready",
            year: month.year,
            monthIndex: month.monthIndex,
            entries,
          });
        }
      })
      .catch((error: unknown) => {
        if (!active) return;
        setLoad({
          status: "error",
          error: toDashboardError(error, DASHBOARD_ERROR_CODES.entries),
        });
      });

    return () => {
      active = false;
    };
  }, [month.monthIndex, month.year, reload, today]);

  useDataRefresh(["entries", "scales", "habits"], () => {
    setReload((value) => value + 1);
    void loadScales();
    void loadHabits();
  });

  // Only the month the grid is drawing may paint its entries. A month that is
  // still loading must not wear the previous month's data (Android's `monthData`).
  const monthData =
    load.status === "ready" &&
    load.year === month.year &&
    load.monthIndex === month.monthIndex
      ? load.entries
      : null;
  const entries = useMemo(() => monthData ?? [], [monthData]);
  const isLoading =
    load.status === "loading" ||
    (load.status === "ready" && monthData === null);
  const entryMap = useMemo(() => historyEntryMap(entries), [entries]);
  const stats = historyMonthStats(entries, month.year, month.monthIndex);
  const title = historyMonthTitle(lang, month.year, month.monthIndex);
  const selectedEntry = selected ? entryMap.get(selected) ?? null : null;

  const goToMonth = (delta: number) => {
    // A different month must not keep showing the previous month's error or
    // data for one paint; the effect re-reads it.
    setLoad({ status: "loading" });
    setMonth((current) => historyStepMonth(current.year, current.monthIndex, delta));
    setSelected(null);
  };

  const goToToday = () => {
    if (todayMonth) {
      const changes =
        month.year !== todayMonth.year ||
        month.monthIndex !== todayMonth.monthIndex;
      if (changes) setLoad({ status: "loading" });
      setMonth(changes ? todayMonth : month);
    }
    setSelected(today);
  };

  return (
    <div className="space-y-4">
      <div
        role="group"
        aria-label={t("history.view_mode")}
        className="flex gap-1 rounded-2xl border border-white/10 bg-white/[0.04] p-1"
      >
        {(["calendar", "photos"] as const).map((item) => {
          const active = mode === item;
          return (
            <button
              key={item}
              type="button"
              aria-pressed={active}
              onClick={() => setMode(item)}
              className={`min-h-11 flex-1 rounded-xl px-3 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/70 ${
                active
                  ? "bg-indigo-500/20 text-[color:var(--text-1)] ring-1 ring-indigo-400/30"
                  : "text-[color:var(--text-3)] hover:text-[color:var(--text-2)]"
              }`}
            >
              {t(
                item === "calendar"
                  ? "history.mode_calendar"
                  : "history.mode_photos"
              )}
            </button>
          );
        })}
      </div>

      {mode === "photos" ? (
        <>
          <section className="glass-card">
            <HistoryMonthNav
              title={title}
              onPrevious={() => goToMonth(-1)}
              onNext={() => goToMonth(1)}
              onToday={goToToday}
            />
          </section>
          {isLoading ? (
            <div className="glass-card animate-pulse-slow text-sm text-[color:var(--text-2)]">
              {t("history.loading")}
            </div>
          ) : load.status === "error" ? (
            <section className="glass-card">
              <CardError
                title={t("history.error_title")}
                message={
                  load.error.code === "errors.signed_out"
                    ? t("errors.signed_out")
                    : t("history.load_error")
                }
                retryLabel={t("history.retry")}
                onRetry={() => setReload((value) => value + 1)}
              />
            </section>
          ) : (
            <PhotoGallery entries={entries} onNavigateToDate={onNavigateToDate} />
          )}
        </>
      ) : (
        <>
          <CalendarCard
            year={month.year}
            monthIndex={month.monthIndex}
            todayKey={today}
            entries={entries}
            loading={isLoading}
            selected={selected}
            onPrevious={() => goToMonth(-1)}
            onNext={() => goToMonth(1)}
            onToday={goToToday}
            onSelect={setSelected}
          />

          {load.status === "error" ? (
            <section className="glass-card">
              <CardError
                title={t("history.error_title")}
                message={
                  load.error.code === "errors.signed_out"
                    ? t("errors.signed_out")
                    : t("history.load_error")
                }
                retryLabel={t("history.retry")}
                onRetry={() => setReload((value) => value + 1)}
              />
            </section>
          ) : monthData !== null ? (
            selected ? (
              <DayDetailCard
                date={selected}
                entry={selectedEntry}
                habits={habits}
                scales={scales}
                scalesError={scalesError}
                onRetryScales={() => void loadScales()}
                onEdit={onNavigateToDate}
              />
            ) : (
              <MonthHint
                title={title}
                stats={stats}
                lang={lang}
              />
            )
          ) : null}
        </>
      )}
    </div>
  );
}

/** What the month holds, before a day is picked — or why it holds nothing. */
function MonthHint({
  title,
  stats,
  lang,
}: {
  title: string;
  stats: HistoryMonthStats;
  lang: "cs" | "en";
}) {
  const { t } = useTranslation();

  if (!stats.hasRows) {
    return (
      <section className="glass-card">
        <EmptyState
          emoji="📝"
          title={t("history.empty_title")}
          message={t("history.month_empty", { month: title })}
        />
      </section>
    );
  }

  if (stats.recorded === 0) {
    return (
      <section className="glass-card">
        <EmptyState
          emoji="📱"
          title={t("history.synced_only_title")}
          message={t("history.synced_only_body", { month: title })}
        />
      </section>
    );
  }

  return (
    <section className="glass-card">
      <p className="text-[13px] leading-5 text-[color:var(--text-1)]">
        {t(historyMonthCountKey(stats.recorded, lang), {
          count: stats.recorded,
        })}
      </p>
      {stats.syncedOnly > 0 ? (
        <p className="mt-1 text-[12px] leading-[18px] text-[color:var(--text-2)]">
          {t(historySyncedNoteKey(stats.syncedOnly, lang), {
            count: stats.syncedOnly,
          })}
        </p>
      ) : null}
      <p className="mt-1 text-[12px] leading-[18px] text-[color:var(--text-2)]">
        {t("history.select_day")}
      </p>
    </section>
  );
}
