"use client";

import { useState } from "react";

import {
  ANALYSIS_METRICS,
  metricValue,
  type MetricDef,
  type MetricKey,
} from "@/lib/analysis/metric-series";
import type { PeriodComparison } from "@/lib/analysis/compare";
import {
  formatCi,
  formatCoverage,
  formatDayLabel,
  formatDiff,
  formatMetric,
} from "@/lib/analysis/format";
import type { StatsWindow } from "@/lib/analysis/stats-window";
import type { DiffEstimate, Verdict } from "@/lib/analysis/uncertainty";
import { useTranslation } from "@/lib/i18n";
import type { DailyEntry } from "@/lib/stats";

import {
  ACCENT,
  Card,
  FOCUS_RING,
  Hint,
  StatValue,
  withAlpha,
} from "@/components/dashboard/primitives";

function observedDays(
  entries: readonly DailyEntry[],
  window: StatsWindow,
  key: MetricKey
): string[] {
  return entries
    .filter(
      (entry) =>
        entry.date >= window.start &&
        entry.date <= window.end &&
        metricValue(entry, key) !== null
    )
    .map((entry) => entry.date);
}

function VerdictChip({
  verdict,
  nA,
  nB,
}: {
  verdict: Verdict;
  nA: number;
  nB: number;
}) {
  const { t } = useTranslation();
  const label =
    verdict === "insufficient"
      ? t("analysis.verdict.insufficient_n", { a: nA, b: nB })
      : t(`analysis.verdict.${verdict}`);
  const arrow =
    verdict === "higher" ? "↑" : verdict === "lower" ? "↓" : null;
  const tone =
    verdict === "higher"
      ? { border: withAlpha(ACCENT.cyan, 0.35), background: withAlpha(ACCENT.cyan, 0.1), color: ACCENT.cyan }
      : verdict === "lower"
        ? { border: withAlpha(ACCENT.amber, 0.35), background: withAlpha(ACCENT.amber, 0.1), color: ACCENT.amber }
        : { border: "rgba(255,255,255,0.1)", background: "rgba(255,255,255,0.04)", color: "var(--text-2)" };

  return (
    <span
      className="inline-flex shrink-0 items-center gap-1 whitespace-nowrap rounded-full border px-2 py-0.5 text-[10px] font-semibold leading-4"
      style={tone}
    >
      {arrow ? <span aria-hidden="true">{arrow}</span> : null}
      {label}
    </span>
  );
}

function Whisker({ estimate }: { estimate: DiffEstimate }) {
  if (
    estimate.ciLow === null ||
    estimate.ciHigh === null ||
    estimate.diff === null
  ) {
    return null;
  }
  const domain = Math.max(
    Math.abs(estimate.ciLow),
    Math.abs(estimate.ciHigh),
    Math.abs(estimate.diff),
    0.01
  );
  const x = (value: number) => 40 + (value / domain) * 36;
  return (
    <svg
      viewBox="0 0 80 16"
      className="h-4 w-20 shrink-0"
      aria-hidden="true"
      focusable="false"
    >
      <line x1="40" y1="2" x2="40" y2="14" stroke="rgba(255,255,255,0.18)" strokeWidth="1" />
      <line
        x1={x(estimate.ciLow)}
        y1="8"
        x2={x(estimate.ciHigh)}
        y2="8"
        stroke="var(--text-2)"
        strokeWidth="1.5"
        strokeLinecap="round"
      />
      <circle cx={x(estimate.diff)} cy="8" r="2.5" fill={ACCENT.indigoLight} />
    </svg>
  );
}

function Tile({
  row,
  def,
  expanded,
  onToggle,
}: {
  row: PeriodComparison;
  def: MetricDef;
  expanded: boolean;
  onToggle: () => void;
}) {
  const { t, lang } = useTranslation();
  const bothMissing = row.nCurrent === 0 && row.nPrevious === 0;
  const oneSideMissing = row.nCurrent === 0 || row.nPrevious === 0;
  const chip =
    !oneSideMissing && (row.nCurrent > 0 || row.nPrevious > 0) ? (
      <VerdictChip
        verdict={row.diff.verdict}
        nA={row.nCurrent}
        nB={row.nPrevious}
      />
    ) : null;
  const ci = formatCi(row.diff, def, lang);

  return (
    <div
      className="min-w-0 rounded-2xl border border-white/[0.09] bg-white/[0.03] p-2.5"
      data-metric={row.key}
    >
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={expanded}
        className={`flex min-h-11 w-full flex-col items-start gap-1 rounded-xl text-left ${FOCUS_RING}`}
      >
        <span className="flex w-full items-start justify-between gap-1">
          <span className="text-[11px] font-semibold uppercase leading-4 tracking-[0.8px] text-[color:var(--text-2)]">
            {t(def.labelKey)}
          </span>
          {chip}
        </span>

        {bothMissing ? (
          <>
            <StatValue
              value={formatMetric(row.meanCurrent, def, lang)}
              size="md"
            />
            <span className="text-[11px] leading-4 text-[color:var(--text-2)]">
              {t("analysis.compare.previous")}: {formatMetric(row.meanPrevious, def, lang)}
            </span>
            <span className="text-[10px] leading-4 text-[color:var(--text-3)]">
              {formatCoverage(row.nCurrent, row.current.days)} ·{" "}
              {formatCoverage(row.nPrevious, row.previous.days)} ·{" "}
              {t("analysis.compare.no_data")}
            </span>
          </>
        ) : (
          <>
            <StatValue
              value={formatMetric(row.meanCurrent, def, lang)}
              size="md"
            />
            <span className="text-[11px] leading-4 text-[color:var(--text-2)]">
              {t("analysis.compare.previous")}:{" "}
              {formatMetric(row.meanPrevious, def, lang)}
            </span>
            <span className="text-[10px] leading-4 text-[color:var(--text-3)]">
              {formatCoverage(row.nCurrent, row.current.days)} ·{" "}
              {formatCoverage(row.nPrevious, row.previous.days)}
            </span>
          </>
        )}
      </button>

      {oneSideMissing && !bothMissing ? (
        <p className="mt-1 text-[11px] leading-4 text-[color:var(--text-3)]">
          {row.nCurrent === 0
            ? t("analysis.compare.no_current")
            : t("analysis.compare.no_previous")}
        </p>
      ) : null}

      {!oneSideMissing && row.diff.diff !== null ? (
        <div className="mt-1.5 flex items-center justify-between gap-2">
          <span className="text-[12px] font-semibold tabular-nums text-[color:var(--text-1)]">
            {formatDiff(row.diff.diff, def, lang)}
          </span>
          <Whisker estimate={row.diff} />
        </div>
      ) : null}

      {!oneSideMissing && ci ? (
        <p className="mt-0.5 text-[11px] leading-4 text-[color:var(--text-3)]">{ci}</p>
      ) : null}
      {!oneSideMissing && row.diff.degenerate ? (
        <p className="mt-0.5 text-[11px] leading-4 text-[color:var(--text-3)]">
          {t("analysis.impact.no_variability")}
        </p>
      ) : null}
    </div>
  );
}

/** M1: this period against the equally long period immediately before it. */
export function PeriodComparisonCard({
  rows,
  entries,
  selectedDate,
  onSelectDate,
}: {
  rows: readonly PeriodComparison[];
  entries: readonly DailyEntry[];
  selectedDate: string | null;
  onSelectDate: (date: string) => void;
}) {
  const { t, lang } = useTranslation();
  const [expanded, setExpanded] = useState<MetricKey | null>(null);

  const definitions = new Map(ANALYSIS_METRICS.map((metric) => [metric.key, metric]));
  const currentRecorded = entries.filter(
    (entry) =>
      rows[0] &&
      entry.date >= rows[0].current.start &&
      entry.date <= rows[0].current.end
  ).length;
  const previousRecorded = entries.filter(
    (entry) =>
      rows[0] &&
      entry.date >= rows[0].previous.start &&
      entry.date <= rows[0].previous.end
  ).length;

  const openRow = expanded ? rows.find((row) => row.key === expanded) : null;
  const openDef = expanded ? definitions.get(expanded) : null;
  const currentDays = openRow ? observedDays(entries, openRow.current, openRow.key) : [];
  const previousDays = openRow
    ? observedDays(entries, openRow.previous, openRow.key)
    : [];

  return (
    <div className="mt-4">
      <Card cardId="period_comparison" title={t("analysis.compare.title")} accent={ACCENT.indigo}>
        <Hint>
          {t("analysis.compare.recorded", {
            n: currentRecorded,
            days: rows[0]?.current.days ?? 0,
          })}
          {" · "}
          {t("analysis.compare.previous_recorded", {
            n: previousRecorded,
            days: rows[0]?.previous.days ?? 0,
          })}
        </Hint>

        <p className="mt-1 text-[11px] leading-4 text-[color:var(--text-3)]">
          {t("analysis.compare.hint")}
        </p>

        <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-3">
          {rows.map((row) => {
            const def = definitions.get(row.key);
            if (!def) return null;
            return (
              <Tile
                key={row.key}
                row={row}
                def={def}
                expanded={expanded === row.key}
                onToggle={() => {
                  if (expanded === row.key) {
                    setExpanded(null);
                  } else {
                    setExpanded(row.key);
                  }
                }}
              />
            );
          })}
        </div>

        <p className="mt-2 text-[10px] leading-4 text-[color:var(--text-3)]">
          {t("analysis.compare.screen_hint")}
        </p>

        {openRow && openDef ? (
          <div className="mt-3 rounded-2xl border border-white/10 bg-white/[0.03] p-3">
            <div className="flex items-start justify-between gap-2">
              <p className="text-[12px] font-semibold text-[color:var(--text-1)]">
                {t("analysis.compare.expand")} · {t(openDef.labelKey)}
              </p>
              <button
                type="button"
                onClick={() => setExpanded(null)}
                className={`min-h-11 min-w-11 rounded-xl border border-white/10 px-2 text-[11px] text-[color:var(--text-2)] hover:text-[color:var(--text-1)] ${FOCUS_RING}`}
              >
                {t("analysis.compare.collapse")}
              </button>
            </div>

            {[
              {
                label: t("analysis.compare.current_days"),
                days: currentDays,
              },
              {
                label: t("analysis.compare.previous_days"),
                days: previousDays,
              },
            ].map((group) => (
              <div key={group.label} className="mt-2">
                <p className="text-[10px] font-semibold uppercase tracking-[0.9px] text-[color:var(--text-2)]">
                  {group.label} · {group.days.length}
                </p>
                {group.days.length === 0 ? (
                  <p className="mt-1 text-[11px] text-[color:var(--text-3)]">
                    {t("analysis.compare.no_data")}
                  </p>
                ) : (
                  <div className="mt-1.5 flex flex-wrap gap-1">
                    {group.days.map((date) => (
                      <button
                        key={date}
                        type="button"
                        onClick={() => onSelectDate(date)}
                        aria-pressed={selectedDate === date}
                        className={`min-h-11 rounded-lg border px-2 text-[11px] tabular-nums ${FOCUS_RING} ${
                          selectedDate === date
                            ? "border-indigo-400/60 bg-indigo-500/20 text-[color:var(--text-1)]"
                            : "border-white/10 bg-white/[0.04] text-[color:var(--text-2)] hover:text-[color:var(--text-1)]"
                        }`}
                      >
                        {formatDayLabel(date, lang)}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        ) : null}
      </Card>
    </div>
  );
}
