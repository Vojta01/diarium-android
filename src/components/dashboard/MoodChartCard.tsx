"use client";

import { useId, useState } from "react";

import {
  type DashboardData,
  type DashboardDay,
} from "@/lib/dashboard";
import { formatAverageMood } from "@/lib/dashboard-format";
import {
  chartDays,
  chartRangeDays,
  dayLabelKey,
  formatDashboardDate,
  moodMovingAverage,
  type ChartRange,
} from "@/lib/dashboard-ui";
import { useTranslation } from "@/lib/i18n";
import { CHART_MOOD_COLORS, isRecordedMood } from "@/lib/mood";
import { MOOD_EMOJIS } from "@/lib/stats";
import { segmentRuns } from "@/lib/analysis/geometry";

import {
  ACCENT,
  Card,
  EmptyState,
  FOCUS_RING,
  Hint,
  RangeToggle,
} from "./primitives";

const W = 320;
const H = 150;
const PAD = { top: 14, right: 10, bottom: 20, left: 22 };

function xAt(index: number, count: number): number {
  const inner = W - PAD.left - PAD.right;
  if (count <= 1) return PAD.left + inner / 2;
  return PAD.left + (index / (count - 1)) * inner;
}

function yAt(value: number): number {
  const inner = H - PAD.top - PAD.bottom;
  return PAD.top + ((5 - value) / 4) * inner;
}

interface Point {
  index: number;
  value: number;
  x: number;
  y: number;
}

function moodPoints(days: readonly DashboardDay[]): Point[] {
  const points: Point[] = [];
  days.forEach((day, index) => {
    if (!isRecordedMood(day.mood)) return;
    points.push({
      index,
      value: day.mood,
      x: xAt(index, days.length),
      y: yAt(day.mood),
    });
  });
  return points;
}

/** Split into runs so a missing day is a gap, not a straight lie. */
function segments(points: readonly Point[]): Point[][] {
  return segmentRuns(points);
}

function averageMood(days: readonly DashboardDay[]): number | null {
  const values = days
    .map((day) => (isRecordedMood(day.mood) ? day.mood : null))
    .filter((value) => value !== null);
  if (values.length === 0) return null;
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function MoodChart({
  days,
  onOpenDay,
}: {
  days: readonly DashboardDay[];
  onOpenDay: (date: string) => void;
}) {
  const { t, lang } = useTranslation();
  const rawId = useId();
  const gradientId = `mood-gradient-${rawId.replace(/[^a-zA-Z0-9_-]/g, "")}`;
  const points = moodPoints(days);
  const average = moodMovingAverage(days);
  const averagePoints = days
    .map((_, index) => {
      const value = average[index];
      return value === null
        ? null
        : { index, value, x: xAt(index, days.length), y: yAt(value) };
    })
    .filter(
      (point): point is Point =>
        point !== null && Number.isFinite(point.value)
    );

  const mean = averageMood(days);
  const summary =
    mean === null
      ? t("dashboard.mood_chart.summary_empty", { days: days.length })
      : t("dashboard.mood_chart.summary", {
          days: days.length,
          average: `${formatAverageMood(mean)} / 5`,
        });

  const runs = segments(points);
  const areaPath =
    runs.length === 1 && runs[0].length > 1
      ? `M ${runs[0][0].x} ${yAt(1)} L ${runs[0]
          .map((point) => `${point.x} ${point.y}`)
          .join(" L ")} L ${runs[0][runs[0].length - 1].x} ${yAt(1)} Z`
      : null;
  const avgPath =
    averagePoints.length > 1
      ? `M ${averagePoints.map((point) => `${point.x} ${point.y}`).join(" L ")}`
      : null;

  const showEvery = days.length > 14 ? 5 : 1;
  const labelIndexes = new Set<number>();
  days.forEach((_, index) => {
    if (index % showEvery === 0 || index === days.length - 1) {
      labelIndexes.add(index);
    }
  });

  return (
    <figure className="mt-2">
      <div className="relative">
        <svg
          viewBox={`0 0 ${W} ${H}`}
          className="block h-auto w-full overflow-visible"
          role="img"
          aria-label={summary}
        >
          <defs>
            <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={ACCENT.indigo} stopOpacity="0.32" />
              <stop offset="100%" stopColor={ACCENT.indigo} stopOpacity="0" />
            </linearGradient>
          </defs>
          {[5, 3, 1].map((value) => (
            <g key={value}>
              <line
                x1={PAD.left}
                x2={W - PAD.right}
                y1={yAt(value)}
                y2={yAt(value)}
                stroke="rgba(255,255,255,0.06)"
                strokeDasharray="3 4"
              />
              <text
                x={0}
                y={yAt(value) + 3}
                fill="rgba(255,255,255,0.35)"
                fontSize="9"
              >
                {value}
              </text>
            </g>
          ))}

          {areaPath ? <path d={areaPath} fill={`url(#${gradientId})`} /> : null}

          {runs.map((run, index) =>
            run.length > 1 ? (
              <path
                key={`line-${index}`}
                d={`M ${run.map((point) => `${point.x} ${point.y}`).join(" L ")}`}
                fill="none"
                stroke={ACCENT.indigoLight}
                strokeWidth="2.5"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            ) : null
          )}

          {avgPath ? (
            <path
              d={avgPath}
              fill="none"
              stroke={ACCENT.violet}
              strokeWidth="1.4"
              strokeDasharray="4 4"
              opacity="0.75"
            />
          ) : null}

          {points.map((point) => (
            <circle
              key={point.index}
              cx={point.x}
              cy={point.y}
              r="3.2"
              fill={CHART_MOOD_COLORS[point.value]}
              stroke="#0b0b12"
              strokeWidth="1.2"
            />
          ))}

          {days.map((day, index) => {
            const key = dayLabelKey(day.date);
            return labelIndexes.has(index) ? (
              <text
                key={day.date}
                x={xAt(index, days.length)}
                y={H - 4}
                textAnchor="middle"
                fill="rgba(255,255,255,0.38)"
                fontSize="8.5"
              >
                {days.length <= 7 && key
                  ? t(key)
                  : String(Number(day.date.slice(8, 10)))}
              </text>
            ) : null;
          })}
        </svg>

        {/* One 44px target per day, positioned over the plot. The visible marks
            are decorative; the button carries the date and mood for AT. Only the
            week view has targets wide enough to stay 44px; the month chart is a
            summary and its table is the accessible equivalent. */}
        {days.length <= 7 ? (
          <div className="absolute inset-x-[6.5%] inset-y-0 flex">
            {days.map((day) => {
              const mood = isRecordedMood(day.mood) ? day.mood : null;
              const label = formatDashboardDate(day.date, lang, "numeric");
              const moodLabel = mood
                ? t(`mood.mood_${mood}`)
                : t("dashboard.week.missing_mood");
              return (
                <button
                  key={day.date}
                  type="button"
                  onClick={() => onOpenDay(day.date)}
                  aria-label={t("dashboard.week.mood_of_day", {
                    date: label,
                    mood: moodLabel,
                  })}
                  className={`h-full min-h-11 min-w-0 flex-1 rounded-lg ${FOCUS_RING}`}
                />
              );
            })}
          </div>
        ) : null}
      </div>

      <figcaption className="mt-1 flex items-center justify-between gap-3">
        <span className="flex items-center gap-1.5 text-[11px] text-[color:var(--text-2)]">
          <span
            aria-hidden="true"
            className="inline-block h-0.5 w-4 rounded-full"
            style={{ backgroundColor: ACCENT.violet }}
          />
          {t("dashboard.mood_chart.average_label")}
        </span>
        {mean !== null ? (
          <span className="text-[11px] text-[color:var(--text-2)]">
            {t("dashboard.week.average_value", {
              value: formatAverageMood(mean),
            })}
          </span>
        ) : null}
      </figcaption>

      <table className="sr-only">
        <caption>{t("dashboard.mood_chart.table_caption")}</caption>
        <thead>
          <tr>
            <th scope="col">{t("dashboard.mood_chart.column_date")}</th>
            <th scope="col">{t("dashboard.mood_chart.column_mood")}</th>
          </tr>
        </thead>
        <tbody>
          {days.map((day) => (
            <tr key={day.date}>
              <th scope="row">{formatDashboardDate(day.date, lang, "numeric")}</th>
              <td>
                {isRecordedMood(day.mood)
                  ? `${MOOD_EMOJIS[day.mood]} ${t(`mood.mood_${day.mood}`)}`
                  : t("dashboard.week.missing_mood")}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  );
}

/** 📈 The 30-day mood trend with a switchable 7-day window. */
export function MoodChartCard({
  data,
  onOpenDay,
}: {
  data: DashboardData;
  onOpenDay: (date: string) => void;
}) {
  const { t } = useTranslation();
  const [range, setRange] = useState<ChartRange>("week");
  const days = chartDays(data.month, range);
  const hasMood = days.some((day) => isRecordedMood(day.mood));

  return (
    <Card
      cardId="mood_chart"
      title={t("dashboard.mood_chart.title")}
      accent={ACCENT.indigo}
      trailing={
        <RangeToggle
          value={range}
          onChange={setRange}
          label={t("dashboard.mood_chart.range")}
        />
      }
    >
      <Hint>
        {range === "week"
          ? t("dashboard.mood_chart.hint")
          : t("dashboard.mood_chart.month_hint")}{" "}
        {t("dashboard.week.last_days", { days: chartRangeDays(range) })}
      </Hint>
      {hasMood ? (
        <MoodChart days={days} onOpenDay={onOpenDay} />
      ) : (
        <EmptyState
          emoji="📈"
          title={t("dashboard.week.no_mood")}
          message={t("dashboard.mood_chart.summary_empty", {
            days: chartRangeDays(range),
          })}
        />
      )}
    </Card>
  );
}
