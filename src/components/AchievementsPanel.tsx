"use client";

import { useCallback, useState, useEffect } from "react";
import { getAchievementProgress, ACHIEVEMENTS, type AchievementDef } from "@/lib/achievements";
import { useTranslation } from "@/lib/i18n";
import { useDataRefresh } from "@/lib/use-data-refresh";

export function AchievementsPanel() {
  const { t } = useTranslation();
  const [achievements, setAchievements] = useState<{ def: AchievementDef; unlocked: boolean; progress: number }[]>([]);
  const [loading, setLoading] = useState(true);
  const [showAll, setShowAll] = useState(false);

  const loadAchievements = useCallback(async () => {
    try {
      const data = await getAchievementProgress();
      setAchievements(data);
    } catch (e) {
      console.error("Failed to load achievements:", e);
    }
    setLoading(false);
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async fetch resolves after the effect body
    loadAchievements();
  }, [loadAchievements]);

  // Achievements are derived from entries, habits and goals, so refresh when any
  // of those mutations lands while this panel is mounted on a hidden tab.
  useDataRefresh(["entries", "habits", "goals"], loadAchievements);

  if (loading) {
    return (
      <div className="glass-card p-4">
        <div className="text-white/40 text-sm">{t("common.loading")}</div>
      </div>
    );
  }

  const unlocked = achievements.filter(a => a.unlocked);
  const locked = achievements.filter(a => !a.unlocked);
  const displayed = showAll ? achievements : [...unlocked, ...locked.slice(0, 4)];

  return (
    <div className="glass-card p-4">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-lg font-semibold text-white">🏆 {t("achievements.title")}</h3>
        <span className="text-sm text-white/40">
          {unlocked.length}/{ACHIEVEMENTS.length}
        </span>
      </div>

      <div className="grid grid-cols-2 gap-2">
        {displayed.map((ach) => (
          <div
            key={ach.def.key}
            className={`p-3 rounded-xl border transition-all ${
              ach.unlocked
                ? "bg-white/10 border-white/20"
                : "bg-white/5 border-white/5 opacity-50"
            }`}
          >
            <div className="flex items-center gap-2 mb-1">
              <span className="text-xl">
                {ach.unlocked ? ach.def.emoji : "🔒"}
              </span>
              {ach.unlocked && (
                <span className="text-xs text-green-400/80 ml-auto">
                  ✓
                </span>
              )}
            </div>
            <div className={`text-xs font-medium ${ach.unlocked ? "text-white" : "text-white/40"}`}>
              {ach.def.name}
            </div>
            <div className={`text-xs mt-0.5 ${ach.unlocked ? "text-white/50" : "text-white/30"}`}>
              {ach.def.description}
            </div>
            {/* Progress bar for progressive achievements */}
            {!ach.unlocked && ach.progress > 0 && ach.def.target > 1 && (
              <div className="mt-1.5 w-full h-1 bg-white/10 rounded-full overflow-hidden">
                <div
                  className="h-full rounded-full bg-white/30"
                  style={{ width: `${(ach.progress / ach.def.target) * 100}%` }}
                />
              </div>
            )}
          </div>
        ))}
      </div>

      {achievements.length > unlocked.length + 4 && (
        <button
          onClick={() => setShowAll(!showAll)}
          className="w-full mt-3 py-2 rounded-xl bg-white/5 text-white/50 text-sm hover:bg-white/10 hover:text-white/70 transition-colors"
        >
          {showAll ? t("common.close") : `+ ${achievements.filter(a => !a.unlocked).length - 4} ${t("achievements.locked").toLowerCase()}`}
        </button>
      )}
    </div>
  );
}
