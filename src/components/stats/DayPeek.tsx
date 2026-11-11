"use client";

import { ANALYSIS_METRICS, metricValue } from "@/lib/analysis/metric-series";
import { formatLongDate, formatMetric } from "@/lib/analysis/format";
import { useTranslation } from "@/lib/i18n";
import type { DailyEntry } from "@/lib/stats";
import { answeredHabitEntries } from "@/lib/analysis/habit-catalog";
import type { HabitDef } from "@/lib/supabase/db";

import {
  ACCENT,
  Card,
  FOCUS_RING,
  Hint,
  MetricRow,
  SecondaryButton,
} from "@/components/dashboard/primitives";

/**
 * The shared read-only drill-down. It never edits anything; the only action is
 * "open day", which hands the date back to the existing check-in screen.
 */
export function DayPeek({
  date,
  entry,
  habits,
  onOpenDay,
  onClose,
}: {
  date: string;
  entry: DailyEntry | undefined;
  habits: readonly HabitDef[] | null;
  onOpenDay: (date: string) => void;
  onClose?: () => void;
}) {
  const { t, lang } = useTranslation();

  const answeredHabits = entry
    ? answeredHabitEntries(entry.habits, habits).map((habit) => ({
        ...habit,
        label:
          lang === "en"
            ? habits?.find((definition) => definition.key === habit.key)?.labelEn || habit.label
            : habit.label,
      }))
    : [];

  return (
    <div className="mt-4 scroll-mt-24">
      <Card
        cardId="day_peek"
        title={t("analysis.day.title")}
        accent={ACCENT.indigo}
        trailing={
          onClose ? (
            <button
              type="button"
              onClick={onClose}
              aria-label={t("analysis.day.close")}
              className={`flex min-h-11 min-w-11 items-center justify-center rounded-xl border border-white/10 text-[color:var(--text-2)] hover:bg-white/[0.06] hover:text-[color:var(--text-1)] ${FOCUS_RING}`}
            >
              <span aria-hidden="true">✕</span>
            </button>
          ) : null
        }
      >
        <p className="mt-1 text-[15px] font-semibold text-[color:var(--text-1)]">
          {formatLongDate(date, lang)}
        </p>

        {entry ? (
          <>
            <div className="mt-2">
              {ANALYSIS_METRICS.map((metric) => (
                <MetricRow
                  key={metric.key}
                  label={t(metric.labelKey)}
                  value={
                    metricValue(entry, metric.key) === null
                      ? t("analysis.timeline.not_recorded")
                      : formatMetric(metricValue(entry, metric.key), metric, lang)
                  }
                  muted={metricValue(entry, metric.key) === null}
                />
              ))}
            </div>

            <div className="mt-3">
              <p className="text-[11px] font-semibold uppercase tracking-[1.1px] text-[color:var(--text-2)]">
                {t("analysis.day.activities")}
              </p>
              {entry.activities.length > 0 ? (
                <ul className="mt-1.5 flex flex-wrap gap-1.5">
                  {entry.activities.map((activity) => (
                    <li
                      key={activity}
                      className="rounded-full border border-white/10 bg-white/[0.05] px-2.5 py-1 text-[12px] text-[color:var(--text-1)]"
                    >
                      {activity}
                    </li>
                  ))}
                </ul>
              ) : (
                <Hint>{t("analysis.day.no_activities")}</Hint>
              )}
            </div>

            <div className="mt-3">
              <p className="text-[11px] font-semibold uppercase tracking-[1.1px] text-[color:var(--text-2)]">
                {t("analysis.day.habits")}
              </p>
              {answeredHabits.length > 0 ? (
                <ul className="mt-1.5 flex flex-wrap gap-1.5">
                  {answeredHabits.map((habit) => (
                    <li
                      key={habit.key}
                      className="rounded-full border border-white/10 bg-white/[0.05] px-2.5 py-1 text-[12px] text-[color:var(--text-1)]"
                    >
                      <span aria-hidden="true">{habit.value ? "✓" : "✗"}</span>{" "}
                      {habit.label}
                    </li>
                  ))}
                </ul>
              ) : (
                <Hint>{t("analysis.day.not_answered")}</Hint>
              )}
            </div>
          </>
        ) : (
          <Hint>{t("analysis.day.no_entry")}</Hint>
        )}

        <div className="mt-3">
          <SecondaryButton onClick={() => onOpenDay(date)}>
            {t("analysis.day.open")}
          </SecondaryButton>
        </div>
      </Card>
    </div>
  );
}
