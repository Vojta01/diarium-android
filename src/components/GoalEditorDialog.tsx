"use client";

import { useId, useState } from "react";
import { EmojiPicker } from "@/components/EmojiPicker";
import { Modal } from "@/components/ui/Modal";
import { useTranslation } from "@/lib/i18n";

/**
 * Goal as rendered on the "Dnes" page — stored in localStorage under `diarium_goals`.
 * (The DB-backed goals on the dashboard are a separate model, see `@/lib/goals`.)
 */
export interface DayGoal {
  id: string;
  emoji: string;
  name: string;
  completedDates: string[];
}

interface GoalEditorDialogProps {
  onClose: () => void;
  /** `id` present → edit that goal, absent → create a new one. */
  onSave: (goal: { id?: string; name: string; emoji: string }) => void;
  goal?: DayGoal | null;
}

/** Rendered only while open (the parent mounts it conditionally), so the fields
 *  below are initialised from props on every open — no effect-based reset. */
export function GoalEditorDialog({ onClose, onSave, goal }: GoalEditorDialogProps) {
  const { t } = useTranslation();
  const titleId = useId();
  const [name, setName] = useState(goal?.name ?? "");
  const [emoji, setEmoji] = useState(goal?.emoji || "🎯");
  const [error, setError] = useState<string | null>(null);

  const submit = () => {
    if (!name.trim()) {
      setError(t("goals.error_name"));
      return;
    }
    onSave({ id: goal?.id, name: name.trim(), emoji: emoji || "✅" });
    onClose();
  };

  return (
    <Modal
      onClose={onClose}
      labelledBy={titleId}
      panelClassName="w-full max-w-sm mx-4 mb-4 sm:mb-0 rounded-2xl border border-white/10 bg-gray-900/95 backdrop-blur-xl shadow-2xl"
    >
      <div className="px-5 pt-5 pb-2 flex items-center gap-2">
        <span className="text-xl leading-none">{emoji || "🎯"}</span>
        <h3 id={titleId} className="text-base font-semibold text-white">
          {goal ? t("goals.edit_title") : t("goals.new_title")}
        </h3>
      </div>

        <div className="px-5 pb-2 space-y-3">
          {error && <div className="p-2 rounded-lg bg-red-500/10 text-red-400 text-xs">{error}</div>}

          <div>
            <label className="block text-[11px] text-white/40 mb-1 uppercase tracking-wider">
              {t("goals.name_label")}
            </label>
            <input
              value={name}
              onChange={e => setName(e.target.value)}
              placeholder={t("goals.name_placeholder")}
              autoFocus
              onKeyDown={e => {
                if (e.key === "Enter") submit();
              }}
              className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white placeholder:text-white/15 focus:outline-none focus:border-indigo-400/50"
            />
          </div>

          <EmojiPicker value={emoji} onChange={setEmoji} label={t("emojiPicker.label")} />
        </div>

        <div className="px-5 pb-5 pt-1 flex gap-2">
          <button
            onClick={onClose}
            className="flex-1 py-2.5 rounded-xl bg-white/10 text-white/60 text-sm font-medium hover:bg-white/20 transition-colors"
          >
            {t("common.cancel")}
          </button>
          <button
            onClick={submit}
            className="flex-1 py-2.5 rounded-xl bg-indigo-500 text-white text-sm font-medium hover:bg-indigo-600 transition-colors"
          >
            {t("common.save")}
          </button>
        </div>
    </Modal>
  );
}
