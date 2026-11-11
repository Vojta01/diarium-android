"use client";

import { useId, useState } from "react";

import { Modal } from "@/components/ui/Modal";
import type { ActivityDef } from "@/lib/supabase/db";
import { useTranslation } from "@/lib/i18n";
import {
  ACTIVITY_CATEGORY_ORDER,
  categoryLabel,
  definitionLabel,
  groupActivities,
} from "@/lib/checkin/options";

import { CheckInSection } from "../CheckInSection";
import { Chip } from "../primitives/Chip";

export interface NewActivityInput {
  name: string;
  icon: string;
  category: string;
}

/**
 * Aktivity — catalogue chips grouped by category, with the add/manage dialogs.
 *
 * Weather definitions are filtered out here (and by `groupActivities`): weather
 * has its own multi-select and must not appear twice. Chips store the activity
 * *label* because that is the historical row contract.
 */
export function ActivitiesSection({
  activities,
  hidden,
  selected,
  error,
  onToggle,
  onAdd,
  onRemove,
  onRestore,
}: {
  activities: readonly ActivityDef[];
  hidden: readonly ActivityDef[];
  selected: readonly string[];
  /** i18n key for a failed add/remove, shown inline. */
  error: string | null;
  onToggle: (label: string) => void;
  onAdd: (input: NewActivityInput) => Promise<string | null>;
  onRemove: (key: string) => string | null | Promise<string | null>;
  onRestore: (key: string) => string | null | Promise<string | null>;
}) {
  const { t, lang } = useTranslation();
  const titleId = useId();
  const [showAdd, setShowAdd] = useState(false);
  const [showManage, setShowManage] = useState(false);
  const [name, setName] = useState("");
  const [icon, setIcon] = useState("📌");
  const [category, setCategory] = useState<string>("vlastní");
  const [localError, setLocalError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const groups = groupActivities(activities, (key) => categoryLabel(key, t));

  const openAdd = () => {
    setName("");
    setIcon("📌");
    setCategory("vlastní");
    setLocalError(null);
    setShowAdd(true);
  };

  const submit = async () => {
    const trimmed = name.trim();
    if (!trimmed) {
      setLocalError(t("activities.error_empty"));
      return;
    }
    if (trimmed.length > 40) {
      setLocalError(t("activities.error_too_long"));
      return;
    }
    const key = trimmed.toLowerCase().replace(/\s+/g, "_");
    const duplicate = activities.some(
      (def) =>
        def.key === key || def.label.trim().toLowerCase() === trimmed.toLowerCase()
    );
    if (duplicate) {
      setLocalError(t("activities.error_duplicate"));
      return;
    }

    setSubmitting(true);
    const failure = await onAdd({
      name: trimmed,
      icon: icon.trim() || "📌",
      category,
    });
    setSubmitting(false);
    if (failure) {
      setLocalError(t(failure));
      return;
    }
    setShowAdd(false);
  };

  return (
    <CheckInSection
      id="activities"
      title={t("checkin.activities_title")}
      trailing={
        <>
          <button
            type="button"
            onClick={openAdd}
            aria-label={t("checkin.add_activity")}
            title={t("checkin.add_activity")}
            className="flex h-11 w-11 items-center justify-center rounded-lg text-[16px] text-[color:var(--text-2)] transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80"
          >
            ➕
          </button>
          <button
            type="button"
            onClick={() => setShowManage(true)}
            aria-label={t("checkin.manage")}
            title={t("checkin.manage")}
            className="flex h-11 w-11 items-center justify-center rounded-lg text-[16px] text-[color:var(--text-2)] transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80"
          >
            ⚙️
          </button>
        </>
      }
    >
      {groups.length === 0 ? (
        <p className="py-1 text-[12px] italic text-[color:var(--text-3)]">
          {t("activities.no_activities_yet")}
        </p>
      ) : (
        <div className="space-y-2.5">
          {groups.map((group) => (
            <div key={group.key}>
              <p className="mb-1.5 text-[10px] font-semibold uppercase tracking-[1px] text-[color:var(--text-3)]">
                {group.title}
              </p>
              <div className="flex flex-wrap gap-1.5">
                {group.items.map((def) => (
                  <Chip
                    key={def.key}
                    pressed={selected.includes(def.label)}
                    onClick={() => onToggle(def.label)}
                    icon={def.icon}
                  >
                    {definitionLabel(def, lang)}
                  </Chip>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

      {error ? (
        <p role="alert" className="mt-2 text-[12px] text-red-300">
          {t(error)}
        </p>
      ) : null}

      {showAdd ? (
        <Modal
          onClose={() => setShowAdd(false)}
          labelledBy={titleId}
          align="center"
          panelClassName="mx-4 w-full max-w-sm rounded-2xl border border-white/10 bg-gray-900/95 shadow-2xl backdrop-blur-xl"
        >
          <div className="px-5 pb-2 pt-5">
            <h3 id={titleId} className="text-base font-semibold text-white">
              {t("activities.add_activity_modal_title")}
            </h3>
          </div>
          <div className="space-y-3 px-5 pb-2">
            <div>
              <label
                htmlFor={`${titleId}-name`}
                className="mb-1 block text-[11px] uppercase tracking-wider text-[color:var(--text-3)]"
              >
                {t("activities.name_label")}
              </label>
              <input
                id={`${titleId}-name`}
                value={name}
                maxLength={40}
                onChange={(event) => {
                  setName(event.target.value);
                  setLocalError(null);
                }}
                onKeyDown={(event) => {
                  if (event.key === "Enter") void submit();
                }}
                placeholder={t("activities.name_placeholder")}
                className="min-h-11 w-full rounded-lg border border-white/10 bg-white/5 px-3 text-sm text-white placeholder:text-white/25 focus:border-indigo-400/60 focus:outline-none"
                autoFocus
              />
            </div>
            <div>
              <label
                htmlFor={`${titleId}-icon`}
                className="mb-1 block text-[11px] uppercase tracking-wider text-[color:var(--text-3)]"
              >
                {t("activities.emoji_label")}
              </label>
              <input
                id={`${titleId}-icon`}
                value={icon}
                maxLength={8}
                onChange={(event) => setIcon(event.target.value)}
                placeholder={t("activities.emoji_placeholder")}
                className="min-h-11 w-full rounded-lg border border-white/10 bg-white/5 px-3 text-sm text-white placeholder:text-white/25 focus:border-indigo-400/60 focus:outline-none"
              />
            </div>
            <fieldset>
              <legend className="mb-1.5 text-[11px] uppercase tracking-wider text-[color:var(--text-3)]">
                {t("activities.category_label")}
              </legend>
              <div className="grid grid-cols-3 gap-1.5">
                {ACTIVITY_CATEGORY_ORDER.map((key) => (
                  <button
                    key={key}
                    type="button"
                    aria-pressed={category === key}
                    onClick={() => setCategory(key)}
                    className={`min-h-11 rounded-lg px-2 text-[11px] font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80 ${
                      category === key
                        ? "bg-indigo-500/30 text-indigo-100 ring-1 ring-indigo-400/50"
                        : "bg-white/5 text-[color:var(--text-2)] hover:bg-white/10"
                    }`}
                  >
                    {categoryLabel(key, t)}
                  </button>
                ))}
              </div>
            </fieldset>
            {localError ? (
              <p role="alert" className="rounded-lg bg-red-500/10 px-3 py-2 text-xs text-red-300">
                {localError}
              </p>
            ) : null}
          </div>
          <div className="flex gap-2 px-5 pb-5 pt-2">
            <button
              type="button"
              onClick={() => setShowAdd(false)}
              className="min-h-11 flex-1 rounded-xl border border-white/10 bg-white/5 text-sm font-medium text-[color:var(--text-2)] transition-colors hover:bg-white/10"
            >
              {t("addActivity.cancel")}
            </button>
            <button
              type="button"
              onClick={() => void submit()}
              disabled={submitting}
              className="min-h-11 flex-1 rounded-xl bg-indigo-500 text-sm font-medium text-white transition-colors hover:bg-indigo-400 disabled:opacity-50"
            >
              {t("addActivity.add")}
            </button>
          </div>
        </Modal>
      ) : null}

      {showManage ? (
        <Modal
          onClose={() => setShowManage(false)}
          labelledBy={`${titleId}-manage`}
          align="center"
          panelClassName="mx-4 w-full max-w-md rounded-2xl border border-white/10 bg-gray-900/95 shadow-2xl backdrop-blur-xl"
        >
          <div className="flex items-center justify-between px-5 pb-2 pt-5">
            <h3 id={`${titleId}-manage`} className="text-base font-semibold text-white">
              {t("activities.your_activities")}
            </h3>
            <button
              type="button"
              onClick={() => setShowManage(false)}
              aria-label={t("common.close")}
              className="flex h-11 w-11 items-center justify-center rounded-lg text-[color:var(--text-3)] hover:bg-white/10 hover:text-white"
            >
              ✕
            </button>
          </div>
          <div className="max-h-[60dvh] space-y-1 overflow-y-auto px-5 pb-3">
            {groups.map((group) => (
              <div key={group.key}>
                <p className="px-1 py-1 text-[10px] uppercase tracking-wider text-[color:var(--text-3)]">
                  {group.title}
                </p>
                {group.items.map((def) => (
                  <div
                    key={def.key}
                    className="flex min-h-11 items-center justify-between gap-2 rounded-lg px-2 text-xs hover:bg-white/5"
                  >
                    <span className="flex min-w-0 items-center gap-1.5">
                      <span aria-hidden="true">{def.icon}</span>
                      <span className="truncate text-[color:var(--text-1)]">
                        {definitionLabel(def, lang)}
                      </span>
                    </span>
                    <button
                      type="button"
                      onClick={() => onRemove(def.key)}
                      aria-label={t("activities.remove_tooltip_custom")}
                      className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg text-red-300/70 hover:bg-red-500/10 hover:text-red-300"
                    >
                      ✕
                    </button>
                  </div>
                ))}
              </div>
            ))}
            {hidden.length > 0 ? (
              <div className="mt-2 border-t border-white/5 pt-2">
                <p className="mb-1.5 px-1 text-[10px] uppercase tracking-wider text-[color:var(--text-3)]">
                  {t("activities.hidden_activities", { count: hidden.length })}
                </p>
                <div className="flex flex-wrap gap-1.5">
                  {hidden.map((def) => (
                    <button
                      key={def.key}
                      type="button"
                      onClick={() => onRestore(def.key)}
                      className="flex min-h-11 items-center gap-1 rounded-lg border border-white/5 bg-white/5 px-2 text-xs text-[color:var(--text-2)] transition-colors hover:border-emerald-500/20 hover:text-emerald-300"
                    >
                      <span aria-hidden="true">{def.icon}</span>
                      <span>{definitionLabel(def, lang)}</span>
                      <span aria-hidden="true">↩</span>
                    </button>
                  ))}
                </div>
              </div>
            ) : null}
          </div>
        </Modal>
      ) : null}
    </CheckInSection>
  );
}
