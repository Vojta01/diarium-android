"use client";

import { useMemo, useState } from "react";

import { formatCi, formatDayLabel, formatDiff, formatInterval, formatMagnitude, formatMetric } from "@/lib/analysis/format";
import {
  activityImpacts,
  habitImpacts,
  IMPACT_MIN_N,
  rankImpacts,
  type ImpactResult,
} from "@/lib/analysis/impact";
import type { MetricDef } from "@/lib/analysis/metric-series";
import type { DiffEstimate, Verdict } from "@/lib/analysis/uncertainty";
import { useTranslation } from "@/lib/i18n";
import type { DailyEntry } from "@/lib/stats";
import type { HabitDef } from "@/lib/supabase/db";

import {
  ACCENT,
  Card,
  EmptyState,
  FOCUS_RING,
  Hint,
  withAlpha,
} from "@/components/dashboard/primitives";

const MOOD_DEF: MetricDef = {
  key: "mood",
  render: "line",
  unit: "points",
  min: 1,
  max: 5,
  labelKey: "analysis.metric.mood",
};

type Filter = "all" | "activities" | "habits";

function VerdictChip({
  verdict,
  nWith,
  nWithout,
}: {
  verdict: Verdict;
  nWith: number;
  nWithout: number;
}) {
  const { t } = useTranslation();
  const label =
    verdict === "insufficient"
      ? t("analysis.verdict.insufficient_n", { a: nWith, b: nWithout })
      : t(`analysis.verdict.${verdict}`);
  const arrow =
    verdict === "higher" ? "↑" : verdict === "lower" ? "↓" : null;
  const color =
    verdict === "higher"
      ? ACCENT.cyan
      : verdict === "lower"
        ? ACCENT.amber
        : "var(--text-2)";
  return (
    <span
      className="inline-flex shrink-0 items-center gap-1 whitespace-nowrap rounded-full border px-2 py-0.5 text-[10px] font-semibold leading-4"
      style={{
        color,
        borderColor:
          verdict === "higher"
            ? withAlpha(ACCENT.cyan, 0.35)
            : verdict === "lower"
              ? withAlpha(ACCENT.amber, 0.35)
              : "rgba(255,255,255,0.1)",
        background:
          verdict === "higher"
            ? withAlpha(ACCENT.cyan, 0.1)
            : verdict === "lower"
              ? withAlpha(ACCENT.amber, 0.1)
              : "rgba(255,255,255,0.04)",
      }}
    >
      {arrow ? <span aria-hidden="true">{arrow}</span> : null}
      {label}
    </span>
  );
}

function DivergingBar({ diff, maxAbs }: { diff: number | null; maxAbs: number }) {
  if (diff === null || maxAbs <= 0) return null;
  const fraction = Math.min(1, Math.abs(diff) / maxAbs);
  const width = `${fraction * 50}%`;
  return (
    <div
      aria-hidden="true"
      className="relative h-2 w-full overflow-hidden rounded-full bg-white/[0.06]"
    >
      <span className="absolute inset-y-0 left-1/2 w-px bg-white/20" />
      <span
        className="absolute inset-y-0 rounded-full"
        style={{
          width,
          left: diff >= 0 ? "50%" : undefined,
          right: diff < 0 ? "50%" : undefined,
          backgroundColor: diff >= 0 ? ACCENT.indigoLight : ACCENT.amber,
        }}
      />
    </div>
  );
}

function detailCopy(
  row: ImpactResult,
  t: (path: string, params?: Record<string, string | number>) => string,
  lang: "cs" | "en"
): string {
  const estimate: DiffEstimate = row.diff;
  if (estimate.verdict === "insufficient") {
    const parts = [
      t("analysis.impact.needs_split", {
        min: IMPACT_MIN_N,
        with: row.nWith,
        without: row.nWithout,
      }),
    ];
    if (row.nWithout === 0) parts.push(t("analysis.impact.never_without"));
    if (row.nWith === 0) parts.push(t("analysis.impact.never_with"));
    return parts.join(" · ");
  }

  if (estimate.degenerate) return t("analysis.impact.detail_degenerate");

  const interval = formatInterval(estimate, MOOD_DEF, lang);
  if (estimate.verdict === "not_clear") {
    return interval
      ? t("analysis.impact.detail_not_clear", { ci: interval })
      : t("analysis.impact.detail_degenerate");
  }

  const direction = estimate.verdict === "higher" ? "higher" : "lower";
  const value = formatMagnitude(estimate.diff ?? 0, MOOD_DEF, lang);
  return interval
    ? t(`analysis.impact.detail_${direction}`, { value, ci: interval })
    : t(`analysis.impact.detail_${direction}`, {
        value,
        ci: formatCi(estimate, MOOD_DEF, lang) ?? "—",
      });
}

/**
 * M3: activity/habit with-vs-without mood comparison.
 *
 * Every visible row states its sample sizes and its 95 % CI on the difference.
 * The list is descriptive, not causal; the comparison count is disclosed.
 */
export function ImpactCard({
  entries,
  axis,
  habits,
  selectedDate,
  onSelectDate,
}: {
  entries: readonly DailyEntry[];
  axis: readonly string[];
  habits: readonly HabitDef[] | null;
  selectedDate: string | null;
  onSelectDate: (date: string) => void;
}) {
  const { t, lang } = useTranslation();
  const [filter, setFilter] = useState<Filter>("all");
  const [showAll, setShowAll] = useState(false);
  const [expanded, setExpanded] = useState<string | null>(null);

  const activities = useMemo(() => activityImpacts(entries, axis), [entries, axis]);
  const habitRows = useMemo(
    () => habitImpacts(entries, axis, habits),
    [entries, axis, habits]
  );

  const results = useMemo(() => {
    const combined =
      filter === "activities"
        ? activities
        : filter === "habits"
          ? habitRows
          : [...activities, ...habitRows];
    return rankImpacts(combined);
  }, [filter, activities, habitRows]);

  const visible = showAll ? results : results.slice(0, 8);
  const maxAbs = visible.reduce(
    (max, row) =>
      row.diff.diff === null ? max : Math.max(max, Math.abs(row.diff.diff)),
    0
  );

  const filters: { key: Filter; label: string }[] = [
    { key: "all", label: t("analysis.impact.filter_all") },
    { key: "activities", label: t("analysis.impact.filter_activities") },
    { key: "habits", label: t("analysis.impact.filter_habits") },
  ];

  return (
    <div className="mt-4">
      <Card cardId="impact" title={t("analysis.impact.title")} accent={ACCENT.violet}>
        <Hint>{t("analysis.impact.hint")}</Hint>

        <div
          role="group"
          aria-label={t("analysis.impact.filter_label")}
          className="mt-2 flex flex-wrap gap-1"
        >
          {filters.map((item) => (
            <button
              key={item.key}
              type="button"
              aria-pressed={filter === item.key}
              onClick={() => {
                setFilter(item.key);
                setShowAll(false);
                setExpanded(null);
              }}
              className={`min-h-11 rounded-xl border px-3 text-[11px] font-semibold ${FOCUS_RING} ${
                filter === item.key
                  ? "border-indigo-400/50 bg-indigo-500/20 text-[color:var(--text-1)]"
                  : "border-white/10 bg-white/[0.03] text-[color:var(--text-2)] hover:text-[color:var(--text-1)]"
              }`}
            >
              {item.label}
            </button>
          ))}
        </div>

        {results.length === 0 ? (
          <div className="mt-3">
            <EmptyState
              emoji="🧩"
              title={t("analysis.impact.empty_title")}
              message={t("analysis.impact.empty_hint")}
            />
          </div>
        ) : (
          <div className="mt-2">
            {visible.map((row) => {
              const isOpen = expanded === `${row.kind}:${row.key}`;
              const ci = formatCi(row.diff, MOOD_DEF, lang);
              return (
                <div
                  key={`${row.kind}:${row.key}`}
                  className="border-t border-white/[0.07] py-2 first:border-t-0"
                >
                  <button
                    type="button"
                    onClick={() =>
                      setExpanded(isOpen ? null : `${row.kind}:${row.key}`)
                    }
                    aria-expanded={isOpen}
                    className={`flex min-h-11 w-full flex-col gap-1 rounded-xl text-left ${FOCUS_RING}`}
                  >
                    <span className="flex w-full items-start justify-between gap-2">
                      <span className="min-w-0 text-[13px] font-semibold text-[color:var(--text-1)]">
                        {row.kind === "habit" && lang === "en" && row.labelEn
                          ? row.labelEn
                          : row.label}
                        <span className="ml-1.5 text-[10px] font-medium uppercase tracking-[0.6px] text-[color:var(--text-3)]">
                          {row.kind === "activity"
                            ? t("analysis.impact.filter_activities")
                            : t("analysis.impact.filter_habits")}
                        </span>
                      </span>
                      <VerdictChip
                        verdict={row.diff.verdict}
                        nWith={row.nWith}
                        nWithout={row.nWithout}
                      />
                    </span>

                    <span className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 text-[11px] leading-4 text-[color:var(--text-2)]">
                      <span className="tabular-nums">
                        {row.nWith} {t("analysis.impact.with")} / {row.nWithout}{" "}
                        {t("analysis.impact.without")}
                      </span>
                      <span className="tabular-nums text-[color:var(--text-1)]">
                        {formatMetric(row.meanWith, MOOD_DEF, lang)} →{" "}
                        {formatMetric(row.meanWithout, MOOD_DEF, lang)}
                      </span>
                      {row.diff.diff !== null ? (
                        <span className="font-semibold tabular-nums text-[color:var(--text-1)]">
                          {formatDiff(row.diff.diff, MOOD_DEF, lang)}
                        </span>
                      ) : null}
                      {ci ? <span className="text-[10px]">{ci}</span> : null}
                    </span>

                    <DivergingBar diff={row.diff.diff} maxAbs={maxAbs} />
                  </button>

                  <p className="mt-1 text-[11px] leading-4 text-[color:var(--text-2)]">
                    {detailCopy(row, t, lang)}
                  </p>

                  {isOpen ? (
                    <div className="mt-2 rounded-2xl border border-white/10 bg-white/[0.03] p-2.5">
                      {[
                        {
                          label: t("analysis.impact.days_with"),
                          days: row.sourceDaysWith,
                        },
                        {
                          label: t("analysis.impact.days_without"),
                          days: row.sourceDaysWithout,
                        },
                      ].map((group) => (
                        <div key={group.label} className="mt-1 first:mt-0">
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
                </div>
              );
            })}

            {results.length > 8 ? (
              <button
                type="button"
                onClick={() => setShowAll((value) => !value)}
                className={`mt-1 min-h-11 w-full rounded-xl border border-white/10 bg-white/[0.03] text-[12px] font-medium text-[color:var(--text-2)] hover:text-[color:var(--text-1)] ${FOCUS_RING}`}
              >
                {showAll
                  ? t("analysis.impact.show_less")
                  : t("analysis.impact.show_all", { count: results.length })}
              </button>
            ) : null}

            <p className="mt-2 text-[10px] leading-4 text-[color:var(--text-3)]">
              {t("analysis.impact.association_note")}{" "}
              {t("analysis.impact.comparisons_note", { count: results.length })}
            </p>
          </div>
        )}
      </Card>
    </div>
  );
}
