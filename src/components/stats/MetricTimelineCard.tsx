"use client";

import { useMemo, type PointerEvent as ReactPointerEvent } from "react";

import {
  bandRect,
  segmentRuns,
  xAt,
  yAt,
  type PlotPoint,
} from "@/lib/analysis/geometry";
import {
  ANALYSIS_METRICS,
  baselineBand,
  buildSeries,
  type MetricDef,
  type MetricKey,
} from "@/lib/analysis/metric-series";
import { formatDayLabel, formatMetric } from "@/lib/analysis/format";
import { dayAxis, type StatsWindow } from "@/lib/analysis/stats-window";
import { useTranslation } from "@/lib/i18n";
import { CHART_MOOD_COLORS } from "@/lib/mood";
import type { DailyEntry } from "@/lib/stats";

import {
  ACCENT,
  Card,
  FOCUS_RING,
  Hint,
  withAlpha,
} from "@/components/dashboard/primitives";

const W = 320;
const H = 44;
const PAD = { top: 6, right: 10, bottom: 6, left: 22 };

const STYLES: Record<MetricKey, { color: string; dash?: string }> = {
  mood: { color: ACCENT.indigoLight },
  sleep: { color: ACCENT.cyan, dash: "4 2" },
  stress: { color: ACCENT.amber, dash: "1 2" },
  screen_time: { color: ACCENT.violet },
  unlocks: { color: ACCENT.green, dash: "6 3" },
};

function summaryFor(
  def: MetricDef,
  series: ReturnType<typeof buildSeries>,
  days: number,
  lang: "cs" | "en",
  t: (path: string, params?: Record<string, string | number>) => string
): string {
  const values = series.values.filter(
    (value): value is number => value !== null
  );
  if (values.length === 0) {
    return t("analysis.timeline.summary_empty", {
      metric: t(def.labelKey),
      days,
    });
  }
  const mean = values.reduce((sum, value) => sum + value, 0) / values.length;
  return t("analysis.timeline.summary", {
    metric: t(def.labelKey),
    days,
    n: values.length,
    mean: formatMetric(mean, def, lang),
  });
}

function Row({
  def,
  series,
  band,
  baselineN,
  showBaseline,
  selectedIndex,
  onPick,
}: {
  def: MetricDef;
  series: ReturnType<typeof buildSeries>;
  band: { p25: number; p75: number; n: number } | null;
  baselineN: number;
  showBaseline: boolean;
  selectedIndex: number;
  onPick: (index: number) => void;
}) {
  const { t, lang } = useTranslation();
  const count = series.values.length;
  const observed = series.values.filter(
    (value): value is number => value !== null
  );
  const style = STYLES[def.key];
  const maxValue =
    def.max ??
    Math.max(
      1,
      ...observed,
      band?.p75 ?? 0,
      ...(band ? [band.p25] : [])
    ) * 1.1;
  const baselineY = yAt(0, def.min, maxValue, H, PAD.top, PAD.bottom);
  const step = count > 1 ? (W - PAD.left - PAD.right) / (count - 1) : W - PAD.left - PAD.right;

  const points: PlotPoint[] = [];
  series.values.forEach((value, index) => {
    if (value === null) return;
    points.push({
      index,
      value,
      x: xAt(index, count, W, PAD.left, PAD.right),
      y: yAt(value, def.min, maxValue, H, PAD.top, PAD.bottom),
    });
  });

  const bandGeometry = band
    ? bandRect(band.p25, band.p75, def.min, maxValue, H, PAD.top, PAD.bottom)
    : null;

  const pick = (event: ReactPointerEvent<SVGSVGElement>) => {
    if (event.type === "pointermove" && event.buttons !== 1) return;
    const rect = event.currentTarget.getBoundingClientRect();
    if (rect.width <= 0) return;
    const localX = ((event.clientX - rect.left) / rect.width) * W;
    const index =
      count <= 1
        ? 0
        : Math.round((localX - PAD.left) / Math.max(1e-9, step));
    onPick(Math.max(0, Math.min(count - 1, index)));
  };

  const selectedX =
    selectedIndex >= 0 && selectedIndex < count
      ? xAt(selectedIndex, count, W, PAD.left, PAD.right)
      : null;

  return (
    <div className="mt-2.5" data-metric-row={def.key}>
      <div className="flex items-center justify-between gap-2">
        <span className="flex min-w-0 items-center gap-1.5 text-[11px] font-semibold text-[color:var(--text-2)]">
          <span
            aria-hidden="true"
            className="inline-block h-0.5 w-4 shrink-0 rounded-full"
            style={{
              backgroundColor: style.color,
              opacity: 0.9,
            }}
          />
          <span className="truncate">{t(def.labelKey)}</span>
        </span>
        {showBaseline ? (
          <span className="shrink-0 text-[10px] text-[color:var(--text-3)]">
            {band
              ? t("analysis.timeline.baseline_band", { n: band.n })
              : t("analysis.timeline.baseline_needs", { n: baselineN })}
          </span>
        ) : null}
      </div>

      <svg
        viewBox={`0 0 ${W} ${H}`}
        className="h-auto w-full touch-pan-y select-none"
        role="img"
        aria-label={summaryFor(def, series, count, lang, t)}
        style={{ touchAction: "pan-y" }}
        onPointerDown={pick}
        onPointerMove={pick}
      >
        <title>{t(def.labelKey)}</title>

        {selectedX !== null ? (
          <>
            <rect
              x={Math.max(PAD.left, selectedX - Math.max(1, step / 2))}
              y={0}
              width={
                Math.min(
                  W - PAD.right,
                  selectedX + Math.max(1, step / 2)
                ) -
                Math.max(PAD.left, selectedX - Math.max(1, step / 2))
              }
              height={H}
              fill="rgba(255,255,255,0.06)"
            />
            <line
              x1={selectedX}
              x2={selectedX}
              y1={0}
              y2={H}
              stroke="rgba(255,255,255,0.55)"
              strokeWidth="1.5"
            />
          </>
        ) : null}

        {showBaseline && bandGeometry && band ? (
          <rect
            x={PAD.left}
            y={bandGeometry.y}
            width={W - PAD.left - PAD.right}
            height={bandGeometry.height}
            fill={withAlpha(style.color, 0.16)}
            rx={1}
          />
        ) : null}

        {def.render === "line"
          ? segmentRuns(points).map((run, runIndex) => (
              <g key={`run-${runIndex}`}>
                {run.length > 1 ? (
                  <path
                    d={run
                      .map(
                        (point, index) =>
                          `${index === 0 ? "M" : "L"}${point.x.toFixed(2)} ${point.y.toFixed(2)}`
                      )
                      .join(" ")}
                    fill="none"
                    stroke={style.color}
                    strokeWidth="1.6"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeDasharray={style.dash}
                  />
                ) : null}
                {run.map((point) => (
                  <circle
                    key={point.index}
                    cx={point.x}
                    cy={point.y}
                    r="2.6"
                    fill={
                      def.key === "mood"
                        ? CHART_MOOD_COLORS[point.value]
                        : style.color
                    }
                    stroke="rgba(7,7,11,0.85)"
                    strokeWidth="1"
                  />
                ))}
              </g>
            ))
          : points.map((point) => {
              const height = Math.max(0, baselineY - point.y);
              const zero = point.value === 0;
              return (
                <rect
                  key={point.index}
                  x={point.x - Math.max(1.1, step * 0.28)}
                  y={zero ? baselineY - 2 : point.y}
                  width={Math.max(2.2, step * 0.56)}
                  height={zero ? 2 : height}
                  rx={1}
                  fill={style.color}
                  opacity={zero ? 0.55 : 0.75}
                >
                  <title>
                    {formatDayLabel(series.dates[point.index], lang)}:{" "}
                    {formatMetric(point.value, def, lang)}
                  </title>
                </rect>
              );
            })}
      </svg>
    </div>
  );
}

/**
 * M2: every metric on one shared day axis.
 *
 * Each row keeps its own y-scale. Ratings are lines with gaps for missing
 * days; screen time and unlocks are bars from a true zero. The range input is
 * the keyboard/AT path to the same selected day the pointer picks.
 */
export function MetricTimelineCard({
  entries,
  axis,
  baselineWindow,
  showBaseline,
  selectedDate,
  onSelectDate,
}: {
  entries: readonly DailyEntry[];
  axis: readonly string[];
  baselineWindow: StatsWindow;
  showBaseline: boolean;
  selectedDate: string | null;
  onSelectDate: (date: string) => void;
}) {
  const { t, lang } = useTranslation();
  const baselineAxis = useMemo(() => dayAxis(baselineWindow), [baselineWindow]);

  const rows = useMemo(
    () =>
      ANALYSIS_METRICS.map((def) => {
        const series = buildSeries(entries, axis, def.key);
        const baselineSeries = buildSeries(entries, baselineAxis, def.key);
        const baselineValues = baselineSeries.values;
        return {
          def,
          series,
          band: baselineBand(baselineValues),
          baselineN: baselineValues.filter((value) => value !== null).length,
        };
      }),
    [entries, axis, baselineAxis]
  );

  const visible = rows.filter(
    (row) =>
      row.series.values.some((value) => value !== null) || row.baselineN > 0
  );
  const hidden = rows.filter((row) => !visible.includes(row));

  const selectedIndex = selectedDate ? axis.indexOf(selectedDate) : -1;
  const sliderIndex = selectedIndex >= 0 ? selectedIndex : axis.length - 1;

  const valuesFor = (date: string) => {
    const index = axis.indexOf(date);
    if (index < 0) return t("analysis.timeline.not_recorded");
    return visible
      .map((row) => {
        const value = row.series.values[index];
        return `${t(row.def.labelKey)}: ${
          value === null
            ? t("analysis.timeline.not_recorded")
            : formatMetric(value, row.def, lang)
        }`;
      })
      .join(" · ");
  };

  const sliderDate = axis[sliderIndex];
  const sliderValueText = `${formatDayLabel(sliderDate, lang)} — ${valuesFor(sliderDate)}`;
  const readout =
    selectedIndex >= 0 && selectedDate !== null
      ? t("analysis.timeline.selected_readout", {
          date: formatDayLabel(selectedDate, lang),
          values: valuesFor(selectedDate),
        })
      : null;

  return (
    <div className="mt-4">
      <Card cardId="metric_timeline" title={t("analysis.timeline.title")} accent={ACCENT.cyan}>
        <Hint>{t("analysis.timeline.hint")}</Hint>

        <div className="mt-2">
          {visible.map((row) => (
            <Row
              key={row.def.key}
              def={row.def}
              series={row.series}
              band={row.band}
              baselineN={row.baselineN}
              showBaseline={showBaseline}
              selectedIndex={selectedIndex}
              onPick={(index) => onSelectDate(axis[index])}
            />
          ))}
        </div>

        <div className="mt-1 flex justify-between text-[9px] tabular-nums text-[color:var(--text-3)]">
          <span>{formatDayLabel(axis[0], lang)}</span>
          <span>{formatDayLabel(axis[Math.floor(axis.length / 2)], lang)}</span>
          <span>{formatDayLabel(axis[axis.length - 1], lang)}</span>
        </div>

        {hidden.length > 0 ? (
          <p className="mt-2 text-[10px] leading-4 text-[color:var(--text-3)]">
            {t("analysis.timeline.hidden_metrics", {
              metrics: hidden.map((row) => t(row.def.labelKey)).join(", "),
            })}
          </p>
        ) : null}

        <div className="mt-3 rounded-2xl border border-white/10 bg-white/[0.03] p-3">
          <label
            htmlFor="analysis-day-slider"
            className="text-[11px] font-semibold text-[color:var(--text-2)]"
          >
            {t("analysis.timeline.selected_day")}
          </label>
          <input
            id="analysis-day-slider"
            type="range"
            min={0}
            max={Math.max(0, axis.length - 1)}
            step={1}
            value={sliderIndex}
            onChange={(event) => onSelectDate(axis[Number(event.target.value)])}
            aria-valuetext={sliderValueText}
            className={`mt-2 h-11 w-full accent-indigo-400 ${FOCUS_RING}`}
          />
          <p className="min-h-4 text-[11px] leading-4 text-[color:var(--text-2)]">
            {readout ?? sliderValueText}
          </p>
        </div>

        <div className="sr-only">
          <table>
            <caption>{t("analysis.timeline.table_caption")}</caption>
            <thead>
              <tr>
                <th scope="col">{t("analysis.timeline.column_date")}</th>
                {visible.map((row) => (
                  <th key={row.def.key} scope="col">
                    {t(row.def.labelKey)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {axis.map((date) => (
                <tr key={date}>
                  <th scope="row">{formatDayLabel(date, lang)}</th>
                  {visible.map((row) => {
                    const value = row.series.values[axis.indexOf(date)];
                    return (
                      <td key={row.def.key}>
                        {value === null
                          ? t("analysis.timeline.not_recorded")
                          : value === 0
                            ? t("analysis.timeline.zero")
                            : formatMetric(value, row.def, lang)}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}
