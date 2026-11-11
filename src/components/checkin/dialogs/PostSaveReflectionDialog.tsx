"use client";

import { useId } from "react";

import { Markdown } from "@/components/Markdown";
import { Modal } from "@/components/ui/Modal";
import { Spinner, FOCUS_RING } from "@/components/dashboard/primitives";
import type { CheckInError } from "@/lib/checkin/errors";
import { postSaveReflectionPhase } from "@/lib/checkin/post-save";
import { useTranslation } from "@/lib/i18n";

/**
 * Shown once after a successful save. It never blocks or rolls back the save:
 * the row is already written, so an AI failure only changes this dialog from a
 * reflection to an error with a retry action.
 *
 * The dialog reads the same reflection state as the inline section and calls
 * the same guarded `requestReflection`; it never starts a second request on its
 * own. Closing it always lands on Overview.
 */
export function PostSaveReflectionDialog({
  reflection,
  loading,
  error,
  cached,
  canGenerate,
  onGenerate,
  onClose,
}: {
  reflection: string | null;
  loading: boolean;
  error: CheckInError | null;
  cached: boolean;
  /** The saved day exists, so a retry can be offered. */
  canGenerate: boolean;
  onGenerate: () => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const titleId = useId();
  const phase = postSaveReflectionPhase({ loading, error, reflection });
  const restricted = error?.code === "checkin.reflection_error_restricted";
  const canRetry = phase === "error" && canGenerate && !restricted;

  return (
    <Modal
      onClose={onClose}
      labelledBy={titleId}
      align="center"
      panelClassName="mx-4 w-full max-w-md rounded-2xl border border-white/10 bg-gray-900/95 shadow-2xl backdrop-blur-xl"
    >
      <div className="px-5 pb-2 pt-5">
        <p className="text-[11px] font-medium uppercase tracking-[0.8px] text-emerald-300/85">
          {t("checkin.post_save_title")}
        </p>
        <h3 id={titleId} className="mt-1 text-base font-semibold text-white">
          {t("checkin.post_save_reflection_title")}
        </h3>
      </div>

      <div className="space-y-3 px-5 pb-3">
        {phase === "loading" ? (
          <div
            role="status"
            aria-live="polite"
            className="flex items-center gap-2 rounded-xl border border-white/10 bg-white/[0.04] px-3 py-3 text-[12px] text-[color:var(--text-2)]"
          >
            <Spinner />
            {t("checkin.post_save_reflection_loading")}
          </div>
        ) : phase === "reflection" ? (
          <div
            aria-live="polite"
            className="max-h-[45dvh] overflow-y-auto rounded-[14px] border border-indigo-400/45 bg-indigo-500/[0.12] px-3.5 py-3 text-[13px] leading-5 text-[color:var(--text-1)]"
          >
            <Markdown content={reflection ?? ""} />
          </div>
        ) : (
          <div
            role="alert"
            className="rounded-xl border border-red-400/25 bg-red-500/10 px-3 py-2 text-[12px] leading-[18px] text-red-200"
          >
            {error
              ? t(error.code)
              : t("checkin.post_save_reflection_missing")}
          </div>
        )}

        {phase === "reflection" ? (
          <p className="text-[11px] leading-4 text-[color:var(--text-3)]">
            {t("checkin.post_save_reflection_hint")}
          </p>
        ) : null}

        {cached && !loading ? (
          <p role="status" className="text-[11px] leading-4 text-[color:var(--text-3)]">
            {t("checkin.reflection_cached")}
          </p>
        ) : null}
      </div>

      <div className="flex gap-2 px-5 pb-5 pt-1">
        {canRetry ? (
          <button
            type="button"
            onClick={onGenerate}
            className={`min-h-11 flex-1 rounded-xl bg-indigo-500 px-3 text-sm font-medium text-white transition-colors hover:bg-indigo-400 ${FOCUS_RING}`}
          >
            {t("checkin.reflection_generate")}
          </button>
        ) : null}
        <button
          type="button"
          onClick={onClose}
          className={`min-h-11 flex-1 rounded-xl border border-white/10 bg-white/[0.06] px-3 text-sm font-medium text-[color:var(--text-1)] transition-colors hover:bg-white/[0.1] ${FOCUS_RING}`}
        >
          {t("checkin.post_save_close")}
        </button>
      </div>
    </Modal>
  );
}
