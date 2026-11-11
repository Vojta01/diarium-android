"use client";

import { useRef, type RefObject } from "react";

import { useTranslation } from "@/lib/i18n";
import { formatCheckInDate, relativeDateKey } from "@/lib/checkin/dates";

/**
 * Screen header and date navigation (Android `DateNav`).
 *
 * Deliberately not sticky and without a save button: the only save action lives
 * inline at the end of the form. The date input is a native control layered
 * over the picker button, so it opens the platform picker and stays keyboard
 * reachable.
 */
export function CheckInHeader({
  date,
  today,
  lang,
  disabled,
  error,
  errorRef,
  draftRestored,
  lateNightHint,
  onShift,
  onPickDate,
}: {
  date: string;
  today: string;
  lang: string;
  disabled: boolean;
  /** i18n key for the global error banner, or null. */
  error: string | null;
  errorRef?: RefObject<HTMLDivElement | null>;
  draftRestored: boolean;
  lateNightHint: boolean;
  onShift: (days: number) => void;
  onPickDate: (date: string) => void;
}) {
  const { t } = useTranslation();
  const dateInputRef = useRef<HTMLInputElement>(null);
  const relative = relativeDateKey(date, today);
  const relativeLabel = relative
    ? t(`checkin.date_${relative}`)
    : formatCheckInDate(date, lang);
  const isToday = date === today;

  return (
    <header className="space-y-3">
      <div>
        <h1 className="text-[24px] font-bold leading-8 text-[color:var(--text-1)]">
          {t("checkin.header_title")}
        </h1>
        <p className="mt-1 text-[13px] leading-5 text-[color:var(--text-2)]">
          {t("checkin.header_subtitle")}
        </p>
      </div>

      {error ? (
        <div
          ref={errorRef}
          role="alert"
          tabIndex={-1}
          className="rounded-xl border border-red-500/30 bg-red-500/10 px-4 py-3 text-sm text-red-200 outline-none"
        >
          {t(error)}
        </div>
      ) : null}

      {lateNightHint ? (
        <p
          role="status"
          className="rounded-xl border border-indigo-400/20 bg-indigo-500/[0.08] px-3 py-2 text-[12px] leading-[18px] text-indigo-100"
        >
          {t("checkin.late_night_hint")}
        </p>
      ) : null}

      <div className="checkin-section flex items-center gap-2 px-2 py-1.5">
        <button
          type="button"
          onClick={() => onShift(-1)}
          disabled={disabled}
          aria-label={t("checkin.date_prev")}
          className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-white/[0.05] text-[15px] text-[color:var(--text-2)] transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80 disabled:opacity-40"
        >
          ◀
        </button>

        <div className="min-w-0 flex-1 text-center">
          <p className="truncate text-[15px] font-semibold text-[color:var(--text-1)]">
            {relativeLabel}
          </p>
          <p className="text-[11px] tabular-nums text-[color:var(--text-3)]">
            {date}
          </p>
          <div className="relative mt-0.5 inline-flex">
            <span
              aria-hidden="true"
              className="inline-flex min-h-11 min-w-11 items-center justify-center gap-1 rounded-lg px-2 text-[11px] text-indigo-300"
            >
              📅 {t("checkin.date_pick")}
            </span>
            <input
              ref={dateInputRef}
              type="date"
              value={date}
              max={today}
              aria-label={t("checkin.date_pick")}
              onChange={(event) => {
                if (event.target.value) onPickDate(event.target.value);
              }}
              className="absolute inset-0 h-full w-full cursor-pointer opacity-0"
            />
          </div>
        </div>

        <button
          type="button"
          onClick={() => onShift(1)}
          disabled={disabled || isToday}
          aria-label={t("checkin.date_next")}
          className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-white/[0.05] text-[15px] text-[color:var(--text-2)] transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80 disabled:opacity-40"
        >
          ▶
        </button>
      </div>

      {draftRestored ? (
        <p
          role="status"
          className="rounded-xl border border-amber-400/20 bg-amber-500/[0.08] px-3 py-2 text-[12px] text-amber-100"
        >
          {t("checkin.draft_restored")}
        </p>
      ) : null}
    </header>
  );
}
