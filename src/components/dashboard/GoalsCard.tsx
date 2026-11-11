"use client";

import { useState } from "react";

import { GoalDialog } from "@/components/GoalDialog";
import type { GoalProgressData } from "@/lib/goal-progress";
import {
  GOAL_CARD_LIMIT,
  goalCardRows,
  goalPeriodKey,
} from "@/lib/dashboard-ui";
import { useTranslation } from "@/lib/i18n";

import {
  ACCENT,
  Card,
  CardError,
  Divider,
  EmptyState,
  Hint,
  ProgressBar,
  SecondaryButton,
} from "./primitives";

/** 🎯 Up to four goals, unmet first, with the create action in reach. */
export function GoalsCard({
  goals,
  error,
  totalCount,
  onRefresh,
}: {
  goals: readonly GoalProgressData[] | null;
  error: string | null;
  totalCount: number;
  onRefresh: () => void;
}) {
  const { t } = useTranslation();
  const [createOpen, setCreateOpen] = useState(false);

  const rows = goals ? goalCardRows(goals) : [];

  return (
    <Card
      cardId="goals"
      title={t("dashboard.goals.title")}
      accent={ACCENT.green}
      className="h-full"
    >
      {error ? (
        <CardError
          title={t("dashboard.goals.error_title")}
          message={error}
          retryLabel={t("dashboard.goals.retry")}
          onRetry={onRefresh}
        />
      ) : rows.length === 0 ? (
        <EmptyState
          emoji="🎯"
          title={t("dashboard.goals.empty")}
          message={t("dashboard.goals.empty_hint")}
          action={
            <SecondaryButton onClick={() => setCreateOpen(true)}>
              {t("dashboard.goals.create")}
            </SecondaryButton>
          }
        />
      ) : (
        <>
          <ul className="mt-2 flex flex-col gap-2.5">
            {rows.map((row) => {
              const target = row.goal.target_count;
              const caption = t("dashboard.goals.progress", {
                count: row.count,
                target,
                period: t(`dashboard.goals.${goalPeriodKey(row.frequency)}`),
              });
              return (
                <li key={row.goal.id}>
                  <div className="flex items-baseline justify-between gap-2">
                    <span className="min-w-0 truncate text-[13px] font-medium text-[color:var(--text-1)]">
                      {row.goal.name}
                    </span>
                    <span className="shrink-0 text-[12px] tabular-nums text-[color:var(--text-2)]">
                      {row.targetMet ? "✓" : `${row.count}/${target}`}
                    </span>
                  </div>
                  <div className="mt-1">
                    <ProgressBar
                      fraction={row.fraction}
                      color={row.targetMet ? ACCENT.green : ACCENT.indigo}
                      label={`${row.goal.name}: ${caption}`}
                      valueNow={row.count}
                      valueMax={Math.max(1, target)}
                    />
                  </div>
                  <div className="mt-0.5 flex items-center justify-between gap-2 text-[11px] leading-4 text-[color:var(--text-3)]">
                    <span>{caption}</span>
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
                </li>
              );
            })}
          </ul>
          {totalCount > GOAL_CARD_LIMIT ? (
            <Hint>
              {t("dashboard.goals.more", {
                count: totalCount - GOAL_CARD_LIMIT,
              })}
            </Hint>
          ) : null}
          <Divider />
          <SecondaryButton onClick={() => setCreateOpen(true)}>
            {t("dashboard.goals.create")}
          </SecondaryButton>
        </>
      )}

      <GoalDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onSaved={() => {
          setCreateOpen(false);
          onRefresh();
        }}
      />
    </Card>
  );
}
