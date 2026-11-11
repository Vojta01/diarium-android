"use client";

import { useState, useEffect, useCallback } from "react";
import type { GoalProgressData } from "@/lib/goal-progress";
import type { Goal } from "@/lib/goals";
import { fetchGoalProgress } from "@/lib/goals-data";
import { useTranslation } from "@/lib/i18n";
import { useDataRefresh } from "@/lib/use-data-refresh";

interface GoalsWidgetProps {
  onAddGoal?: () => void;
}

export function GoalsWidget({ onAddGoal }: GoalsWidgetProps) {
  const { t } = useTranslation();
  const [progressList, setProgressList] = useState<GoalProgressData<Goal>[]>([]);
  const [loading, setLoading] = useState(true);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const loadGoals = useCallback(async () => {
    try {
      setProgressList(await fetchGoalProgress());
    } catch (e) {
      console.error("Failed to load goals:", e);
    }
    setLoading(false);
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async fetch resolves after the effect body
    loadGoals();
  }, [loadGoals]);

  useDataRefresh(["goals", "entries"], loadGoals);

  if (loading) {
    return (
      <div className="glass-card p-4">
        <div className="text-white/40 text-sm">{t("common.loading")}</div>
      </div>
    );
  }

  if (progressList.length === 0) {
    return (
      <div className="glass-card p-4">
        <h3 className="text-lg font-semibold text-white mb-2">🎯 {t("daylio_goals.title")}</h3>
        <p className="text-white/40 text-sm mb-3">{t("daylio_goals.no_goals")}</p>
        {onAddGoal && (
          <button
            onClick={onAddGoal}
            className="w-full py-2 px-4 rounded-xl bg-indigo-500/20 text-indigo-300 text-sm font-medium hover:bg-indigo-500/30 transition-colors"
          >
            + {t("daylio_goals.create_first")}
          </button>
        )}
      </div>
    );
  }

  return (
    <div className="glass-card p-4">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-lg font-semibold text-white">🎯 {t("daylio_goals.title")}</h3>
        {onAddGoal && (
          <button
            onClick={onAddGoal}
            className="text-indigo-400 text-sm font-medium hover:text-indigo-300 transition-colors"
          >
            + {t("daylio_goals.add_goal")}
          </button>
        )}
      </div>
      
      <div className="space-y-3">
        {progressList.map((progress) => {
          const goal = progress.goal;
          const current = progress.count;
          const target = goal.target_count;
          const percentage = progress.fraction * 100;
          const streak = progress.streak;
          const isMet = progress.targetMet;
          const isExpanded = expandedId === goal.id;

          return (
            <div
              key={goal.id}
              className="rounded-xl bg-white/5 border border-white/10 overflow-hidden transition-all duration-300"
            >
              <button
                onClick={() => setExpandedId(isExpanded ? null : goal.id)}
                className="w-full p-3 text-left hover:bg-white/5 transition-colors"
              >
                <div className="flex items-center justify-between mb-2">
                  <div className="flex items-center gap-2">
                    <span className="text-xl">🎯</span>
                    <span className="font-medium text-white">{goal.name}</span>
                  </div>
                  {isMet && <span className="text-green-400 text-sm">✓</span>}
                </div>
                
                {/* Progress bar */}
                <div className="w-full h-2 bg-white/10 rounded-full overflow-hidden mb-2">
                  <div
                    className="h-full rounded-full transition-all duration-500"
                    style={{
                      width: `${percentage}%`,
                      backgroundColor: isMet ? "#22c55e" : "#6366f1",
                    }}
                  />
                </div>
                
                <div className="flex items-center justify-between text-sm">
                  <span className="text-white/60">
                    {current}/{target} {t(`daylio_goals.${progress.frequency}`)}
                  </span>
                  {streak > 0 && (
                    <span className="text-orange-400 flex items-center gap-1">
                      🔥 {streak}
                    </span>
                  )}
                </div>
              </button>
              
              {/* Expanded details */}
              {isExpanded && (
                <div className="px-3 pb-3 pt-0 text-sm text-white/60 border-t border-white/10 mt-1 pt-2">
                  <div className="grid grid-cols-2 gap-2">
                    <div>
                      <span className="text-white/40">{t("daylio_goals.current_progress")}:</span>
                      <span className="ml-1 text-white">{current}/{target}</span>
                    </div>
                    <div>
                      <span className="text-white/40">{t("daylio_goals.streak")}:</span>
                      <span className="ml-1 text-white">{t("advanced_stats.days", { count: streak })}</span>
                    </div>
                    <div className="col-span-2">
                      <span className="text-white/40">{t("daylio_goals.frequency")}:</span>
                      <span className="ml-1 text-white">
                        {t(`daylio_goals.${progress.frequency}`)}
                      </span>
                    </div>
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
