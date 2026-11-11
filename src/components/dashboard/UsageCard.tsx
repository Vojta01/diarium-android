"use client";

import { useState } from "react";

import {
  averagePerDay,
  metricTrend,
  type DashboardData,
  type DashboardDay,
} from "@/lib/dashboard";
import {
  formatCompactMinutes,
  formatMetricValue,
} from "@/lib/dashboard-format";
import {
  chartDays,
  chartRangeDays,
  dayLabelKey,
  formatDashboardDate,
  metricSeries,
  sumMetric,
  type ChartRange,
} from "@/lib/dashboard-ui";
import { useTranslation } from "@/lib/i18n";

import {
  ACCENT,
  Card,
  EmptyState,
  Hint,
  RangeToggle,
  SecondaryButton,
  StatValue,
  TrendLine,
  withAlpha,
} from "./primitives";

type UsageKind = "screen_time" | "unlocks";

function barLabel(value: number | null, kind: UsageKind): string {
  if (value === null) return "—";
  return kind === "screen_time" ? formatCompactMinutes(value) : String(value);
}

function Bars({
  days,
  kind,
  range,
}: {
  days: readonly DashboardDay[];
  kind: UsageKind;
  range: ChartRange;
}) {
  const { t, lang } = useTranslation();
  const values = metricSeries(days, kind);
  const max = Math.max(0, ...values.filter((v): v is number => v !== null));
  const accent = kind === "screen_time" ? ACCENT.indigo : ACCENT.cyan;
  const showEvery = range === "week" ? 1 : 5;

  return (
    <div className="mt-3">
      <div className="flex h-[96px] items-end gap-[2px]" aria-hidden="true">
        {days.map((day, index) => {
          const value = values[index];
          const height =
            value === null
              ? 6
              : max === 0
                ? 2
                : Math.max(2, (value / max) * 100);
          return (
            <div
              key={day.date}
              className="group relative flex h-full min-w-0 flex-1 items-end"
              title={`${formatDashboardDate(day.date, lang, "dayMonth")}: ${barLabel(value, kind)}`}
            >
              <div
                className="w-full rounded-t-[3px] transition-[height] duration-500 ease-out motion-reduce:transition-none"
                style={{
                  height: `${height}%`,
                  background:
                    value === null
                      ? "repeating-linear-gradient(45deg, rgba(255,255,255,0.22) 0 2px, transparent 2px 4px)"
                      : `linear-gradient(180deg, ${withAlpha(accent, 0.9)}, ${withAlpha(accent, 0.35)})`,
                }}
              />
            </div>
          );
        })}
      </div>
      <div className="mt-1 flex gap-[2px]" aria-hidden="true">
        {days.map((day, index) => {
          const key = dayLabelKey(day.date);
          const show = index % showEvery === 0 || index === days.length - 1;
          return (
            <span
              key={day.date}
              className="min-w-0 flex-1 text-center text-[9px] leading-3 text-[color:var(--text-3)]"
            >
              {show
                ? range === "week" && key
                  ? t(key)
                  : String(Number(day.date.slice(8, 10)))
                : ""}
            </span>
          );
        })}
      </div>
    </div>
  );
}

/**
 * 📱 / 🔓 One usage metric over a switchable 7/30 day window.
 *
 * The total is a sum of synced days only; a metric with no synced day is an
 * empty state with a dash, never a row of zero bars. A synced zero is a real
 * reading and keeps its bars.
 */
export function UsageCard({
  kind,
  data,
  onRefresh,
}: {
  kind: UsageKind;
  data: DashboardData;
  onRefresh: () => void;
}) {
  const { t, lang } = useTranslation();
  const [range, setRange] = useState<ChartRange>("week");
  const days = chartDays(data.month, range);
  const values = metricSeries(days, kind);
  const total = sumMetric(days, kind);
  const perDay = averagePerDay(days, kind);
  const trend = metricTrend(data.week, data.previousWeek, kind);
  const title =
    kind === "screen_time"
      ? t("dashboard.usage.screen_time_title")
      : t("dashboard.usage.unlocks_title");
  const emptyMessage =
    kind === "screen_time"
      ? t("dashboard.usage.empty_screen_time")
      : t("dashboard.usage.empty_unlocks");
  const metricName =
    kind === "screen_time"
      ? t("dashboard.metric.screen_time")
      : t("dashboard.metric.unlocks");
  const totalText = formatMetricValue(total, kind);
  const summary =
    total === null
      ? t("dashboard.usage.summary_empty", {
          metric: metricName,
          days: chartRangeDays(range),
        })
      : t("dashboard.usage.summary", {
          metric: metricName,
          days: chartRangeDays(range),
          total: totalText,
        });

  return (
    <Card
      cardId={kind === "screen_time" ? "screen_time" : "unlocks"}
      title={title}
      accent={kind === "screen_time" ? ACCENT.indigo : ACCENT.cyan}
      className="h-full"
      trailing={
        <RangeToggle
          value={range}
          onChange={setRange}
          label={t("dashboard.usage.range")}
        />
      }
    >
      {total === null ? (
        <>
          <EmptyState
            emoji={kind === "screen_time" ? "📱" : "🔓"}
            title={t("dashboard.usage.empty_title")}
            message={emptyMessage}
            action={
              <SecondaryButton onClick={onRefresh}>
                {t("dashboard.usage.refresh")}
              </SecondaryButton>
            }
          />
        </>
      ) : (
        <>
          <div className="mt-2">
            <StatValue value={totalText} size="md" />
            <Hint>
              {kind === "screen_time"
                ? t("dashboard.usage.total_screen_time", {
                    days: chartRangeDays(range),
                  })
                : t("dashboard.usage.total_unlocks", {
                    count: Math.round(total),
                  })}
            </Hint>
            {perDay !== null ? (
              <Hint>
                {t("dashboard.metric.per_day")}{" "}
                {formatMetricValue(perDay, kind)}
              </Hint>
            ) : null}
            {range === "week" ? <TrendLine trend={trend} tone="usage" /> : null}
          </div>
          <Bars days={days} kind={kind} range={range} />
          <Hint>
            {kind === "screen_time"
              ? t("dashboard.usage.legend_screen_time")
              : t("dashboard.usage.legend_unlocks")}
          </Hint>
        </>
      )}

      <p className="sr-only">{summary}</p>
      <table className="sr-only">
        <caption>
          {t("dashboard.usage.table_caption", { metric: metricName })}
        </caption>
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
              <td>{barLabel(values[index], kind)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}
