"use client";

import { useId, useState, useEffect } from "react";
import { getActivities, type ActivityDef } from "@/lib/supabase/db";
import { createGoal, updateGoal, type Goal } from "@/lib/goals";
import { notifyDataChanged } from "@/lib/data-refresh";
import { useTranslation } from "@/lib/i18n";
import { Modal } from "@/components/ui/Modal";

interface GoalDialogProps {
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
  goal?: Goal | null; // null = create, Goal = edit
}

export function GoalDialog({ open, onClose, onSaved, goal }: GoalDialogProps) {
  const { t } = useTranslation();
  const titleId = useId();
  const [activities, setActivities] = useState<ActivityDef[]>([]);
  const [activityKey, setActivityKey] = useState("");
  const [name, setName] = useState("");
  const [targetCount, setTargetCount] = useState(3);
  const [frequency, setFrequency] = useState<"daily" | "weekly" | "monthly">("weekly");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (open) {
      loadActivities();
      if (goal) {
        setActivityKey(goal.activity_key);
        setName(goal.name);
        setTargetCount(goal.target_count);
        setFrequency(goal.frequency);
      } else {
        setActivityKey("");
        setName("");
        setTargetCount(3);
        setFrequency("weekly");
      }
      setError(null);
    }
  }, [open, goal]);

  async function loadActivities() {
    try {
      const data = await getActivities();
      // Filter out categories that don't make sense as goals:
      // počasí (weather) — can't set a goal for rain
      // skryté (hidden) — user explicitly hid these
      const goalCategories = new Set(["počasí", "skryté"]);
      const filtered = data.filter(a => !goalCategories.has(a.category));
      // Deduplicate by label (case-insensitive)
      const seen = new Set<string>();
      setActivities(filtered.filter(a => {
        const lower = a.label.toLowerCase();
        if (seen.has(lower)) return false;
        seen.add(lower);
        return true;
      }));
    } catch {}
  }

  async function handleSave() {
    if (!activityKey) {
      setError(t("daylio_goals.error_activity"));
      return;
    }
    if (!name.trim()) {
      setError(t("daylio_goals.error_name"));
      return;
    }

    setSaving(true);
    setError(null);

    try {
      if (goal) {
        await updateGoal(goal.id, {
          activity_key: activityKey,
          name: name.trim(),
          target_count: targetCount,
          frequency,
        });
      } else {
        await createGoal({
          activity_key: activityKey,
          name: name.trim(),
          target_count: targetCount,
          frequency,
          is_active: true,
        });
      }
      notifyDataChanged("goals");
      onSaved();
      onClose();
    } catch (e: any) {
      setError(e.message || t("daylio_goals.save_error"));
    }
    setSaving(false);
  }

  if (!open) return null;

  const selectedActivity = activities.find(a => a.key === activityKey);

  return (
    <Modal
      onClose={onClose}
      labelledBy={titleId}
      panelClassName="relative glass-card p-6 w-full max-w-md mx-4 mb-4 sm:mb-0 max-h-[90vh] overflow-y-auto"
    >
      <h2 id={titleId} className="text-xl font-bold text-white mb-4">
        {goal ? t("daylio_goals.edit_goal") : t("daylio_goals.add_goal")}
      </h2>

        {/* Error */}
        {error && (
          <div className="mb-3 p-2 rounded-lg bg-red-500/10 text-red-400 text-sm">{error}</div>
        )}

        {/* Activity selector */}
        <label className="block mb-3">
          <span className="text-sm text-white/60 mb-1 block">{t("daylio_goals.activity")}</span>
          <select
            value={activityKey}
            onChange={(e) => setActivityKey(e.target.value)}
            className="w-full rounded-xl bg-white/10 border border-white/10 text-white px-3 py-2 text-sm focus:outline-none focus:border-indigo-400"
          >
            <option value="">-- {t("daylio_goals.activity")} --</option>
            {activities.map((a) => (
              <option key={a.key} value={a.key}>
                {a.icon} {a.label}
              </option>
            ))}
          </select>
          {selectedActivity && (
            <div className="flex items-center gap-2 mt-1 text-sm text-white/40">
              <span>{selectedActivity.icon}</span>
              <span>{selectedActivity.label}</span>
              <span className="px-1.5 py-0.5 rounded text-xs" style={{ backgroundColor: `${selectedActivity.color}20`, color: selectedActivity.color }}>
                {t(`categoryGroups.${selectedActivity.category}` as any)}
              </span>
            </div>
          )}
        </label>

        {/* Name */}
        <label className="block mb-3">
          <span className="text-sm text-white/60 mb-1 block">{t("daylio_goals.goal_name")}</span>
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t("daylio_goals.name_placeholder")}
            className="w-full rounded-xl bg-white/10 border border-white/10 text-white px-3 py-2 text-sm focus:outline-none focus:border-indigo-400 placeholder:text-white/30"
          />
        </label>

        {/* Target count */}
        <label className="block mb-3">
          <span className="text-sm text-white/60 mb-1 block">{t("daylio_goals.target_count")}</span>
          <div className="flex items-center gap-2">
            <button
              onClick={() => setTargetCount(Math.max(1, targetCount - 1))}
              className="w-8 h-8 rounded-lg bg-white/10 text-white hover:bg-white/20"
            >
              −
            </button>
            <input
              type="number"
              min={1}
              max={99}
              value={targetCount}
              onChange={(e) => setTargetCount(Math.max(1, Math.min(99, parseInt(e.target.value) || 1)))}
              className="w-16 text-center rounded-xl bg-white/10 border border-white/10 text-white px-3 py-2 text-sm focus:outline-none focus:border-indigo-400"
            />
            <button
              onClick={() => setTargetCount(Math.min(99, targetCount + 1))}
              className="w-8 h-8 rounded-lg bg-white/10 text-white hover:bg-white/20"
            >
              +
            </button>
          </div>
        </label>

        {/* Frequency */}
        <label className="block mb-4">
          <span className="text-sm text-white/60 mb-1 block">{t("daylio_goals.frequency")}</span>
          <div className="flex gap-2">
            {(["daily", "weekly", "monthly"] as const).map((freq) => (
              <button
                key={freq}
                onClick={() => setFrequency(freq)}
                className={`flex-1 py-2 rounded-xl text-sm font-medium transition-all ${
                  frequency === freq
                    ? "bg-indigo-500 text-white"
                    : "bg-white/10 text-white/60 hover:bg-white/20"
                }`}
              >
                {t(`daylio_goals.${freq}`)}
              </button>
            ))}
          </div>
        </label>

        {/* Buttons */}
        <div className="flex gap-3">
          <button
            onClick={onClose}
            className="flex-1 py-2.5 rounded-xl bg-white/10 text-white/60 font-medium hover:bg-white/20 transition-colors"
          >
            {t("common.cancel")}
          </button>
          <button
            onClick={handleSave}
            disabled={saving}
            className="flex-1 py-2.5 rounded-xl bg-indigo-500 text-white font-medium hover:bg-indigo-600 transition-colors disabled:opacity-50"
          >
            {saving ? t("common.saving") : t("common.save")}
          </button>
        </div>
    </Modal>
  );
}
