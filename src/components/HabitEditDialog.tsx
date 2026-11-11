"use client";

import { useId, useState } from "react";
import { EmojiPicker } from "@/components/EmojiPicker";
import { Modal } from "@/components/ui/Modal";
import { useTranslation } from "@/lib/i18n";
import type { HabitDef } from "@/lib/supabase/db";

interface HabitEditDialogProps {
  onClose: () => void;
  /**
   * Returns an i18n key when the save is rejected (duplicate name), or null on
   * success. The dialog stays open with the error so the typed name is kept.
   */
  onSave: (habit: {
    name: string;
    icon: string;
    isNegative: boolean;
  }) => string | null | Promise<string | null>;
  /** Present in edit mode; removes the habit after confirmation. */
  onDelete?: () => string | null | Promise<string | null>;
  habit?: HabitDef | null;
}

/** Mounted only while open, so the fields start from props on every open. */
export function HabitEditDialog({
  onClose,
  onSave,
  onDelete,
  habit,
}: HabitEditDialogProps) {
  const { t } = useTranslation();
  const titleId = useId();
  const [name, setName] = useState(habit?.label ?? "");
  const [icon, setIcon] = useState(habit?.icon || "✅");
  const [isNegative, setIsNegative] = useState(habit?.is_negative ?? false);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const submit = async () => {
    if (saving || deleting) return;
    if (!name.trim()) {
      setError(t("habits.error_name"));
      return;
    }
    setSaving(true);
    const failure = await onSave({
      name: name.trim(),
      icon: icon || "✅",
      isNegative,
    });
    setSaving(false);
    if (failure) {
      setError(t(failure));
      return;
    }
    onClose();
  };

  const remove = async () => {
    if (!onDelete || saving || deleting) return;
    setDeleting(true);
    setError(null);
    let failure: string | null;
    try {
      failure = await onDelete();
    } catch {
      failure = "habits.error_save";
    }
    setDeleting(false);
    if (failure) {
      setError(t(failure));
      return;
    }
    onClose();
  };

  return (
    <Modal
      onClose={onClose}
      labelledBy={titleId}
      panelClassName="mx-4 mb-4 w-full max-w-sm rounded-2xl border border-white/10 bg-gray-900/95 shadow-2xl backdrop-blur-xl sm:mb-0"
    >
      <div className="flex items-center gap-2 px-5 pb-2 pt-5">
        <span aria-hidden="true" className="text-xl leading-none">
          {icon || "✅"}
        </span>
        <h3 id={titleId} className="text-base font-semibold text-white">
          {habit ? t("habits.edit_title") : t("habits.new_title")}
        </h3>
      </div>

      <div className="space-y-3 px-5 pb-2">
        {error ? (
          <p role="alert" className="rounded-lg bg-red-500/10 p-2 text-xs text-red-300">
            {error}
          </p>
        ) : null}

        <div>
          <label
            htmlFor={`${titleId}-name`}
            className="mb-1 block text-[11px] uppercase tracking-wider text-[color:var(--text-3)]"
          >
            {t("habits.name_label")}
          </label>
          <input
            id={`${titleId}-name`}
            value={name}
            maxLength={40}
            disabled={saving || deleting}
            onChange={(event) => {
              setName(event.target.value);
              setError(null);
            }}
            placeholder={t("habits.name_placeholder")}
            autoFocus
            onKeyDown={(event) => {
              if (event.key === "Enter") void submit();
            }}
            className="min-h-11 w-full rounded-lg border border-white/10 bg-white/5 px-3 text-sm text-white placeholder:text-white/25 focus:border-indigo-400/60 focus:outline-none"
          />
        </div>

        <EmojiPicker value={icon} onChange={setIcon} label={t("emojiPicker.label")} />

        <button
          type="button"
          aria-pressed={isNegative}
          disabled={saving || deleting}
          onClick={() => setIsNegative(!isNegative)}
          className={`flex min-h-11 w-full items-center gap-3 rounded-xl border p-3 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80 ${
            isNegative ? "border-red-400/30 bg-red-500/10" : "border-white/10 bg-white/5"
          }`}
        >
          <span
            aria-hidden="true"
            className={`relative h-7 w-12 shrink-0 rounded-full transition-colors ${
              isNegative ? "bg-red-500/60" : "bg-white/10"
            }`}
          >
            <span
              className={`absolute top-0.5 h-6 w-6 rounded-full bg-white shadow transition-transform ${
                isNegative ? "translate-x-[22px]" : "translate-x-[2px]"
              }`}
            />
          </span>
          <span className="min-w-0">
            <span className="block text-sm text-[color:var(--text-1)]">
              {t("habits.negative_label")}
            </span>
            <span className="block text-[11px] leading-snug text-[color:var(--text-3)]">
              {t("habits.negative_hint")}
            </span>
          </span>
        </button>

        {habit ? (
          <p className="text-[11px] leading-snug text-[color:var(--text-3)]">
            {t("habits.key_note")}
          </p>
        ) : null}

        {habit && onDelete ? (
          confirmDelete ? (
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => setConfirmDelete(false)}
                disabled={deleting}
                className="min-h-11 flex-1 rounded-xl border border-white/10 bg-white/5 text-sm text-[color:var(--text-2)] disabled:opacity-50"
              >
                {t("common.cancel")}
              </button>
              <button
                type="button"
                onClick={() => void remove()}
                disabled={deleting}
                className="min-h-11 flex-1 rounded-xl bg-red-500/80 text-sm font-medium text-white hover:bg-red-500 disabled:opacity-50"
              >
                {t("common.remove")}
              </button>
            </div>
          ) : (
            <button
              type="button"
              onClick={() => setConfirmDelete(true)}
              className="min-h-11 w-full rounded-xl border border-red-500/20 bg-red-500/5 text-sm text-red-300 hover:bg-red-500/10"
            >
              {t("habits.remove_btn")}
            </button>
          )
        ) : null}
      </div>

      <div className="flex gap-2 px-5 pb-5 pt-1">
        <button
          type="button"
          onClick={onClose}
          disabled={saving || deleting}
          className="min-h-11 flex-1 rounded-xl bg-white/10 text-sm font-medium text-[color:var(--text-2)] transition-colors hover:bg-white/20 disabled:opacity-50"
        >
          {t("common.cancel")}
        </button>
        <button
          type="button"
          onClick={() => void submit()}
          disabled={saving || deleting}
          className="min-h-11 flex-1 rounded-xl bg-indigo-500 text-sm font-medium text-white transition-colors hover:bg-indigo-600 disabled:opacity-50"
        >
          {t("common.save")}
        </button>
      </div>
    </Modal>
  );
}
