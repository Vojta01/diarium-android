"use client";

import { useState } from "react";

import { TemplatePicker } from "@/components/TemplatePicker";
import { useTranslation } from "@/lib/i18n";
import { templateChange } from "@/lib/checkin/note";

import { CheckInSection } from "../CheckInSection";
import { ConfirmDialog } from "../dialogs/ConfirmDialog";

/**
 * Rychlá poznámka — textarea plus templates. Selecting a template replaces the
 * note (never appends), and a non-empty note asks first so a written paragraph
 * cannot disappear behind a tap.
 */
export function NoteSection({
  value,
  onChange,
  localOnly = false,
}: {
  value: string;
  onChange: (value: string) => void;
  localOnly?: boolean;
}) {
  const { t } = useTranslation();
  const [pickerOpen, setPickerOpen] = useState(false);
  const [pending, setPending] = useState<string | null>(null);

  const applyTemplate = (content: string) => {
    const change = templateChange(value, content);
    if (change.needsConfirm) {
      setPending(change.next);
      return;
    }
    onChange(change.next);
  };

  return (
    <CheckInSection
      id="note"
      title={t("checkin.note_title")}
      trailing={
        !localOnly ? (
          <button
            type="button"
            onClick={() => setPickerOpen(true)}
            className="inline-flex min-h-11 items-center gap-1 rounded-lg px-2 text-[12px] text-indigo-300/80 transition-colors hover:text-indigo-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80"
          >
            <span aria-hidden="true">📄</span>
            {t("checkin.note_template")}
          </button>
        ) : (
          <span className="max-w-[15rem] text-right text-[11px] leading-4 text-white/50">
            {t("offline.templates_unavailable")}
          </span>
        )
      }
    >
      <label htmlFor="checkin-note" className="sr-only">
        {t("checkin.note_title")}
      </label>
      <textarea
        id="checkin-note"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={t("checkin.note_placeholder")}
        rows={4}
        maxLength={10000}
        className="w-full resize-none rounded-xl border border-white/10 bg-white/5 px-3.5 py-3 text-sm leading-5 text-[color:var(--text-1)] placeholder:text-white/25 focus:border-indigo-400/60 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/40"
      />

      {!localOnly ? (
        <TemplatePicker
          open={pickerOpen}
          onClose={() => setPickerOpen(false)}
          onSelect={applyTemplate}
          currentNote={value}
        />
      ) : null}

      {!localOnly ? (
        <ConfirmDialog
          open={pending !== null}
          title={t("checkin.note_replace_title")}
          body={t("checkin.note_replace_body")}
          confirmLabel={t("checkin.note_replace_confirm")}
          cancelLabel={t("common.cancel")}
          onCancel={() => setPending(null)}
          onConfirm={() => {
            if (pending !== null) onChange(pending);
            setPending(null);
          }}
        />
      ) : null}
    </CheckInSection>
  );
}
