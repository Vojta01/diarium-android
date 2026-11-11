"use client";

import { ProgressBar, ACCENT, FOCUS_RING } from "@/components/dashboard/primitives";
import type { GoalProgressData } from "@/lib/goal-progress";
import type { Goal } from "@/lib/goals";
import { useTranslation } from "@/lib/i18n";

import { CheckInSection } from "../CheckInSection";

/**
 * 🎯 Goals — read-only progress for the selected date's calendar period.
 *
 * The rows come from the same `fetchGoalProgress(date)` path as the Goals
 * screen and the dashboard, so the check-in cannot disagree with them. There is
 * no tick, no editor and no write here: goals are managed on the Goals
 * sub-screen, which the footer action opens.
 */
export function GoalsReadonlySection({
  rows,
  loading,
  error,
  onRetry,
  onOpenGoals,
}: {
  rows: readonly GoalProgressData<Goal>[];
  loading: boolean;
  /** i18n key for a failed goals read. */
  error: string | null;
  onRetry: () => void;
  onOpenGoals: () => void;
}) {
  const { t } = useTranslation();
  const met = rows.filter((row) => row.targetMet).length;

  return (
    <CheckInSection id="goals" title={t("checkin.goals_readonly_title")}>
      {loading ? (
        <p role="status" className="py-1 text-[12px] text-[color:var(--text-2)]">
          {t("common.loading")}
        </p>
      ) : error ? (
        <div role="alert" className="flex items-center justify-between gap-2">
          <span className="text-[12px] text-red-300">{t(error)}</span>
          <button
            type="button"
            onClick={onRetry}
            className={`min-h-11 shrink-0 rounded-lg border border-white/10 px-3 text-[12px] text-[color:var(--text-1)] hover:bg-white/10 ${FOCUS_RING}`}
          >
            {t("checkin.error_retry")}
          </button>
        </div>
      ) : rows.length === 0 ? (
        <p className="py-1 text-[12px] leading-[18px] text-[color:var(--text-2)]">
          {t("checkin.goals_empty")}
        </p>
      ) : (
        <>
          <p className="pb-2 text-[11px] leading-4 text-[color:var(--text-3)]">
            {t("checkin.goals_summary", { met, total: rows.length })}
          </p>
          <ul className="space-y-2">
            {rows.map((row) => {
              const period = t(`daylio_goals.${row.frequency}`);
              const valueLabel = `${row.count}/${row.goal.target_count} · ${period}`;
              return (
                <li
                  key={row.goal.id}
                  className="rounded-[14px] border border-white/10 bg-white/[0.05] px-3 py-2.5"
                >
                  <div className="flex items-center gap-2">
                    <span aria-hidden="true" className="text-[16px] leading-5">
                      🎯
                    </span>
                    <span className="min-w-0 flex-1 truncate text-[13px] font-medium leading-5 text-[color:var(--text-1)]">
                      {row.goal.name}
                    </span>
                    <span className="shrink-0 text-[12px] tabular-nums leading-5 text-[color:var(--text-2)]">
                      {valueLabel}
                    </span>
                    {row.targetMet ? (
                      <span
                        role="img"
                        aria-label={t("checkin.goals_met")}
                        className="shrink-0 text-[13px] font-semibold text-emerald-300"
                      >
                        ✓
                      </span>
                    ) : null}
                  </div>
                  <div className="mt-2">
                    <ProgressBar
                      fraction={row.fraction}
                      color={row.targetMet ? ACCENT.green : ACCENT.indigo}
                      label={t("checkin.goals_progress_aria", {
                        name: row.goal.name,
                        count: row.count,
                        target: row.goal.target_count,
                        period,
                      })}
                      valueNow={row.count}
                      valueMax={row.goal.target_count}
                    />
                  </div>
                </li>
              );
            })}
          </ul>
        </>
      )}

      <button
        type="button"
        onClick={onOpenGoals}
        className={`mt-3 min-h-11 w-full rounded-[12px] border border-white/10 bg-white/[0.05] px-4 text-[12px] font-medium text-[color:var(--text-1)] transition-colors hover:bg-white/[0.09] ${FOCUS_RING}`}
      >
        {t("checkin.goals_manage")}
      </button>
    </CheckInSection>
  );
}
