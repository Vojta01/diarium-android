"use client";

import { useCallback, useEffect, useState } from "react";

import { GoalDialog } from "@/components/GoalDialog";
import {
  ActionButton,
  CardError,
  EmptyState,
  ProgressBar,
  ACCENT,
  FOCUS_RING,
} from "@/components/dashboard/primitives";
import { DASHBOARD_ERROR_CODES, toDashboardError, type DashboardErrorCode } from "@/lib/dashboard-errors";
import { dayForm, formatDashboardDate, goalPeriodKey } from "@/lib/dashboard-ui";
import { pragueDateKey } from "@/lib/date";
import { fetchGoalProgress } from "@/lib/goals-data";
import type { Goal } from "@/lib/goals";
import type { GoalProgressData } from "@/lib/goal-progress";
import { useTranslation } from "@/lib/i18n";
import { useDataRefresh } from "@/lib/use-data-refresh";

type LoadState =
  | { kind: "loading" }
  | { kind: "ready"; rows: GoalProgressData<Goal>[] }
  | { kind: "error"; code: DashboardErrorCode };

/**
 * The full Goals destination behind the Overview's Entries card.
 *
 * It is the same calendar-period model the dashboard card uses, only uncapped:
 * every active goal, its current period, streak and recent calendar periods.
 * Create/edit reuse the existing `GoalDialog`; this screen adds no CRUD of its
 * own and never turns a failed read into "no goals".
 */
export function GoalsScreen() {
  const { t, lang } = useTranslation();
  const today = pragueDateKey();
  const [state, setState] = useState<LoadState>({ kind: "loading" });
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<Goal | null>(null);

  const load = useCallback(async () => {
    setState({ kind: "loading" });
    try {
      const rows = await fetchGoalProgress(today);
      setState({ kind: "ready", rows });
    } catch (error) {
      setState({
        kind: "error",
        code: toDashboardError(error, DASHBOARD_ERROR_CODES.goals).code,
      });
    }
  }, [today]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async fetch resolves after the effect body
    void load();
  }, [load]);

  useDataRefresh(["entries", "goals", "habits"], () => {
    void load();
  });

  const openCreate = () => {
    setEditing(null);
    setDialogOpen(true);
  };

  const openEdit = (goal: Goal) => {
    setEditing(goal);
    setDialogOpen(true);
  };

  return (
    <div className="space-y-3" data-screen="goals">
      <div className="flex items-start justify-between gap-3">
        <p className="min-w-0 text-[13px] leading-[19px] text-[color:var(--text-2)]">
          {t("screens.goals.subtitle")}
        </p>
        <button
          type="button"
          onClick={openCreate}
          className={`min-h-11 shrink-0 rounded-[14px] border border-indigo-500/50 bg-indigo-500/[0.18] px-4 text-[13px] font-semibold text-[color:var(--text-1)] transition-colors hover:bg-indigo-500/25 ${FOCUS_RING}`}
        >
          {t("screens.goals.add")}
        </button>
      </div>

      {state.kind === "loading" ? (
        <div className="glass-card p-4" role="status" aria-live="polite">
          <p className="text-[13px] text-[color:var(--text-2)]">
            {t("screens.goals.loading")}
          </p>
        </div>
      ) : null}

      {state.kind === "error" ? (
        <div className="glass-card p-4">
          <CardError
            title={t("screens.goals.error_title")}
            message={t(state.code)}
            retryLabel={t("screens.goals.retry")}
            onRetry={() => void load()}
          />
        </div>
      ) : null}

      {state.kind === "ready" && state.rows.length === 0 ? (
        <div className="glass-card p-4">
          <EmptyState
            emoji="🎯"
            title={t("screens.goals.empty_title")}
            message={t("screens.goals.empty_hint")}
            action={<ActionButton onClick={openCreate}>{t("screens.goals.add")}</ActionButton>}
          />
        </div>
      ) : null}

      {state.kind === "ready" && state.rows.length > 0 ? (
        <ul className="space-y-3">
          {state.rows.map((row) => {
            const target = Math.max(1, row.goal.target_count);
            const period = t(`dashboard.goals.${goalPeriodKey(row.frequency)}`);
            const caption = t("dashboard.goals.progress", {
              count: row.count,
              target,
              period,
            });
            const form = dayForm(row.streak, lang);

            return (
              <li key={row.goal.id} className="glass-card p-4" data-goal={row.goal.id}>
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <h2 className="truncate text-[16px] font-semibold leading-6 text-[color:var(--text-1)]">
                      {row.goal.name}
                    </h2>
                    <p className="mt-0.5 text-[12px] leading-[18px] text-[color:var(--text-2)]">
                      {caption}
                      {row.streak > 0 ? (
                        <span className="ml-2 text-[color:var(--text-3)]">
                          🔥 {row.streak} {t(`dashboard.streak.unit_${form}`)}
                        </span>
                      ) : null}
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => openEdit(row.goal)}
                    aria-label={t("screens.goals.edit", { name: row.goal.name })}
                    className={`flex min-h-11 min-w-11 shrink-0 items-center justify-center rounded-xl border border-white/10 bg-white/[0.05] text-[15px] text-[color:var(--text-1)] transition-colors hover:bg-white/[0.09] ${FOCUS_RING}`}
                  >
                    <span aria-hidden="true">✏️</span>
                  </button>
                </div>

                <div className="mt-3">
                  <ProgressBar
                    fraction={row.fraction}
                    color={row.targetMet ? ACCENT.green : ACCENT.indigo}
                    label={`${row.goal.name}: ${caption}`}
                    valueNow={row.count}
                    valueMax={target}
                  />
                </div>
                <div className="mt-1 flex items-center justify-between gap-2 text-[11px] leading-4 text-[color:var(--text-3)]">
                  <span>
                    {row.targetMet ? "✓" : `${row.count}/${target}`}
                  </span>
                  {row.previousMet !== null ? (
                    <span>
                      {t(
                        row.previousMet
                          ? "dashboard.goals.previous_met"
                          : "dashboard.goals.previous_missed"
                      )}
                    </span>
                  ) : null}
                </div>

                <div className="mt-3">
                  <p className="text-[11px] font-semibold uppercase leading-4 tracking-[1.1px] text-[color:var(--text-2)]">
                    {t("screens.goals.history")}
                  </p>
                  <ol className="mt-1.5 flex flex-wrap gap-1.5">
                    {row.periods.map((item) => {
                      const range = t("screens.goals.period_range", {
                        start: formatDashboardDate(item.start, lang, "dayMonth"),
                        end: formatDashboardDate(item.end, lang, "dayMonth"),
                      });
                      const outcome =
                        item.met === null
                          ? t("screens.goals.period_no_data")
                          : item.met
                            ? t("screens.goals.period_met")
                            : t("screens.goals.period_missed");
                      return (
                        <li key={`${row.goal.id}-${item.start}`}>
                          <span
                            aria-label={`${range}: ${outcome}`}
                            className={`flex size-6 items-center justify-center rounded-md border text-[11px] font-semibold ${
                              item.met === true
                                ? "border-emerald-400/30 bg-emerald-500/15 text-emerald-300"
                                : item.met === false
                                  ? "border-red-400/25 bg-red-500/10 text-red-300"
                                  : "border-white/10 bg-white/[0.03] text-[color:var(--text-3)]"
                            }`}
                          >
                            {item.met === true ? "✓" : item.met === false ? "✗" : "·"}
                          </span>
                        </li>
                      );
                    })}
                  </ol>
                </div>
              </li>
            );
          })}
        </ul>
      ) : null}

      <GoalDialog
        open={dialogOpen}
        goal={editing}
        onClose={() => setDialogOpen(false)}
        onSaved={() => {
          setDialogOpen(false);
          void load();
        }}
      />
    </div>
  );
}
