"use client";

import { useId } from "react";

import { Modal } from "@/components/ui/Modal";
import { formatDashboardDate } from "@/lib/dashboard-ui";
import { useTranslation } from "@/lib/i18n";
import type { HabitDef } from "@/lib/supabase/db";
import type { Scale } from "@/lib/scales";
import type { DailyEntry } from "@/lib/stats";

import { DayDetail } from "./DayDetail";
import { FOCUS_RING } from "./primitives";

/**
 * The day-detail sheet. One modal primitive for the week discs, the mood chart
 * and the metric dialog, so Escape, the focus trap and focus restoration behave
 * identically wherever a day is opened from.
 */
export function DayDetailDialog({
  open,
  date,
  entry,
  habits,
  scales,
  scalesError,
  onRetry,
  onClose,
  onEdit,
}: {
  open: boolean;
  date: string;
  entry: DailyEntry | null;
  habits: readonly HabitDef[] | null;
  scales: readonly Scale[] | null;
  scalesError: string | null;
  onRetry: () => void;
  onClose: () => void;
  onEdit: (date: string) => void;
}) {
  const { t, lang } = useTranslation();
  const titleId = useId();

  if (!open) return null;

  return (
    <Modal
      onClose={onClose}
      labelledBy={titleId}
      align="center"
      panelClassName="relative glass-card my-4 max-h-[90vh] w-full max-w-[560px] overflow-y-auto p-5 sm:my-0"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[11px] font-semibold uppercase tracking-[1.2px] text-[color:var(--text-2)]">
            {t("dashboard.day_detail.title")}
          </p>
          <h2
            id={titleId}
            className="mt-0.5 text-[20px] font-semibold leading-6 tracking-[-0.2px] text-[color:var(--text-1)]"
          >
            {formatDashboardDate(date, lang, "long")}
          </h2>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label={t("dashboard.day_detail.close")}
          className={`-mr-1 -mt-1 flex size-11 shrink-0 items-center justify-center rounded-full text-[20px] leading-none text-[color:var(--text-2)] transition-colors hover:bg-white/[0.07] hover:text-[color:var(--text-1)] ${FOCUS_RING}`}
        >
          <span aria-hidden="true">✕</span>
        </button>
      </div>
      <div className="mt-3">
        <DayDetail
          date={date}
          entry={entry}
          habits={habits}
          scales={scales}
          scalesError={scalesError}
          onRetry={onRetry}
          onEdit={(nextDate) => {
            onEdit(nextDate);
            onClose();
          }}
        />
      </div>
    </Modal>
  );
}
