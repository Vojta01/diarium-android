"use client";

import { useId } from "react";

import { Modal } from "@/components/ui/Modal";
import {
  metricTrend,
  type DashboardData,
  type DashboardMetricKind,
} from "@/lib/dashboard";
import {
  EM_DASH,
  formatAverageMood,
  formatMetricValue,
  formatPercentChange,
} from "@/lib/dashboard-format";
import {
  formatDashboardDate,
  metricBestWorst,
  metricSeries,
} from "@/lib/dashboard-ui";
import { useTranslation } from "@/lib/i18n";
import { CHART_MOOD_COLORS, isRecordedMood } from "@/lib/mood";

import { ACCENT, FOCUS_RING, MetricRow, withAlpha } from "./primitives";

const WINDOW_DAYS = 14;

function metricLabelKey(kind: DashboardMetricKind): string {
  switch (kind) {
    case "screen_time":
      return "dashboard.metric.screen_time";
    case "unlocks":
      return "dashboard.metric.unlocks";
    case "mood":
      return "dashboard.metric.mood";
  }
}

function valueText(value: number | null, kind: DashboardMetricKind): string {
  if (value === null) return EM_DASH;
  return kind === "mood"
    ? `${formatAverageMood(value)} / 5`
    : formatMetricValue(value, kind);
}

function averageOf(values: readonly (number | null)[]): number | null {
  const present = values.filter((value): value is number => value !== null);
  if (present.length === 0) return null;
  return present.reduce((sum, value) => sum + value, 0) / present.length;
}

/**
 * The hero cards' 14-day breakdown: this week vs last week, the average, the
 * highest and lowest day, and one bar per day. The chart is presentational;
 * its hidden table carries the same values for assistive technology.
 */
export function DashboardMetricDialog({
  open,
  kind,
  data,
  onClose,
}: {
  open: boolean;
  kind: DashboardMetricKind;
  data: DashboardData;
  onClose: () => void;
}) {
  const { t, lang } = useTranslation();
  const titleId = useId();
  if (!open) return null;

  const days = data.month.slice(-WINDOW_DAYS);
  const values = metricSeries(days, kind);
  const trend = metricTrend(data.week, data.previousWeek, kind);
  const { best, worst } = metricBestWorst(days, kind);
  const average = averageOf(values);

  const weekValue =
    kind === "mood"
      ? averageOf(metricSeries(data.week, kind))
      : metricSeries(data.week, kind).reduce<number | null>(
          (sum, value) =>
            value === null ? sum : (sum ?? 0) + value,
          null
        );
  const previousValue =
    kind === "mood"
      ? averageOf(metricSeries(data.previousWeek, kind))
      : metricSeries(data.previousWeek, kind).reduce<number | null>(
          (sum, value) =>
            value === null ? sum : (sum ?? 0) + value,
          null
        );

  const max = Math.max(
    0,
    ...values.filter((value): value is number => value !== null)
  );
  const accent =
    kind === "screen_time"
      ? ACCENT.indigo
      : kind === "unlocks"
        ? ACCENT.cyan
        : ACCENT.indigoLight;
  const metricName = t(metricLabelKey(kind));
  const summary =
    average === null
      ? t("dashboard.usage.summary_empty", {
          metric: metricName,
          days: WINDOW_DAYS,
        })
      : t("dashboard.usage.summary", {
          metric: metricName,
          days: WINDOW_DAYS,
          total: valueText(average, kind),
        });

  return (
    <Modal
      onClose={onClose}
      labelledBy={titleId}
      align="center"
      panelClassName="relative glass-card my-4 max-h-[90vh] w-full max-w-[560px] overflow-y-auto p-5 sm:my-0"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[11px] font-semibold uppercase tracking-[1.2px] text-[color:var(--text-2)]">
            {t("dashboard.metric.last_14")}
          </p>
          <h2
            id={titleId}
            className="mt-0.5 text-[22px] font-semibold leading-7 tracking-[-0.3px] text-[color:var(--text-1)]"
          >
            {metricName}
          </h2>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label={t("dashboard.metric.close")}
          className={`-mr-1 -mt-1 flex size-11 shrink-0 items-center justify-center rounded-full text-[20px] leading-none text-[color:var(--text-2)] transition-colors hover:bg-white/[0.07] hover:text-[color:var(--text-1)] ${FOCUS_RING}`}
        >
          <span aria-hidden="true">✕</span>
        </button>
      </div>

      <div className="mt-4 rounded-xl border border-white/[0.07] bg-white/[0.03] px-3 py-1.5">
        <MetricRow
          label={t("dashboard.metric.this_week")}
          value={valueText(weekValue, kind)}
        />
        <MetricRow
          label={t("dashboard.metric.last_week")}
          value={valueText(previousValue, kind)}
        />
        <MetricRow
          label={t("dashboard.metric.change")}
          value={formatPercentChange(trend)}
        />
        <MetricRow
          label={t("dashboard.metric.per_day")}
          value={valueText(average, kind)}
        />
      </div>

      <div className="mt-3 grid grid-cols-2 gap-3">
        <div className="rounded-xl border border-white/[0.07] bg-white/[0.03] px-3 py-2">
          <p className="text-[10px] font-semibold uppercase tracking-[1px] text-[color:var(--text-3)]">
            {t("dashboard.metric.best_day")}
          </p>
          <p className="mt-0.5 text-[13px] font-medium text-[color:var(--text-1)]">
            {best
              ? `${formatDashboardDate(best.day.date, lang, "dayMonth")} · ${valueText(best.value, kind)}`
              : EM_DASH}
          </p>
        </div>
        <div className="rounded-xl border border-white/[0.07] bg-white/[0.03] px-3 py-2">
          <p className="text-[10px] font-semibold uppercase tracking-[1px] text-[color:var(--text-3)]">
            {t("dashboard.metric.worst_day")}
          </p>
          <p className="mt-0.5 text-[13px] font-medium text-[color:var(--text-1)]">
            {worst
              ? `${formatDashboardDate(worst.day.date, lang, "dayMonth")} · ${valueText(worst.value, kind)}`
              : EM_DASH}
          </p>
        </div>
      </div>

      <section className="mt-4">
        <h3 className="text-[11px] font-semibold uppercase tracking-[1.1px] text-[color:var(--text-2)]">
          {t("dashboard.metric.by_day")}
        </h3>
        <div
          className="mt-2 flex h-[112px] items-end gap-[3px]"
          role="img"
          aria-label={summary}
        >
          {days.map((day, index) => {
            const value = values[index];
            const height =
              value === null
                ? 6
                : kind === "mood"
                  ? Math.max(6, ((value - 1) / 4) * 100)
                  : max === 0
                    ? 3
                    : Math.max(3, (value / max) * 100);
            const color =
              kind === "mood"
                ? value !== null
                  ? CHART_MOOD_COLORS[value]
                  : "repeating-linear-gradient(45deg, rgba(255,255,255,0.22) 0 2px, transparent 2px 4px)"
                : value !== null
                  ? `linear-gradient(180deg, ${withAlpha(accent, 0.9)}, ${withAlpha(accent, 0.35)})`
                  : "repeating-linear-gradient(45deg, rgba(255,255,255,0.22) 0 2px, transparent 2px 4px)";
            return (
              <div
                key={day.date}
                className="flex h-full min-w-0 flex-1 items-end rounded-t-[4px]"
              >
                <span
                  aria-hidden="true"
                  className="block w-full rounded-t-[3px]"
                  style={{ height: `${height}%`, background: color }}
                />
              </div>
            );
          })}
        </div>
        <div className="mt-1 flex gap-[3px]" aria-hidden="true">
          {days.map((day, index) => (
            <span
              key={day.date}
              className="min-w-0 flex-1 text-center text-[9px] leading-3 text-[color:var(--text-3)]"
            >
              {index % 2 === 0 ? String(Number(day.date.slice(8, 10))) : ""}
            </span>
          ))}
        </div>
      </section>

      <table className="sr-only">
        <caption>{t("dashboard.usage.table_caption", { metric: metricName })}</caption>
        <thead>
          <tr>
            <th scope="col">{t("dashboard.usage.column_day")}</th>
            <th scope="col">{t("dashboard.usage.column_value")}</th>
          </tr>
        </thead>
        <tbody>
          {days.map((day, index) => (
            <tr key={day.date}>
              <th scope="row">{formatDashboardDate(day.date, lang, "numeric")}</th>
              <td>
                {kind === "mood"
                  ? isRecordedMood(day.mood)
                    ? `${day.mood} / 5`
                    : EM_DASH
                  : valueText(values[index], kind)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </Modal>
  );
}
