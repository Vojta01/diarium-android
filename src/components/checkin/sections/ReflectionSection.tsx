"use client";

import { Markdown } from "@/components/Markdown";
import { Spinner, FOCUS_RING } from "@/components/dashboard/primitives";
import type { CheckInError } from "@/lib/checkin/errors";
import {
  reflectionCanRequest,
  reflectionMode,
} from "@/lib/checkin/readonly";
import { useTranslation } from "@/lib/i18n";

import { CheckInSection } from "../CheckInSection";

/**
 * 🤖 AI reflection — the saved reflection for the selected day, last section.
 *
 * The text is the server's prose and is rendered verbatim (Markdown, like the
 * dashboard card). Generation is allowed only for a stored day with no
 * unsaved edits: the endpoint reads the saved row, and the follow-up write is a
 * partial `ai_reflection` patch. An unsaved form therefore warns instead of
 * offering an action that would silently ignore the edits on screen.
 */
export function ReflectionSection({
  reflection,
  loading,
  error,
  cached,
  canGenerate,
  dirty,
  onGenerate,
}: {
  reflection: string | null;
  loading: boolean;
  error: CheckInError | null;
  /** The server answered from the stored reflection instead of generating. */
  cached: boolean;
  /** True once the day is stored with a recorded mood. */
  canGenerate: boolean;
  /** The form has edits that are not saved yet. */
  dirty: boolean;
  onGenerate: () => void;
}) {
  const { t } = useTranslation();
  const hasReflection = Boolean(reflection?.trim());
  const mode = reflectionMode({ canGenerate, dirty, hasReflection });
  // A 403 is an account-level switch, not a transient failure: offering the
  // same button again would be a dead action. The error itself stays visible.
  const restricted = error?.code === "checkin.reflection_error_restricted";

  return (
    <CheckInSection id="reflection" title={t("checkin.reflection_title")}>
      {hasReflection ? (
        <div
          aria-live="polite"
          className="rounded-[14px] border border-indigo-400/45 bg-indigo-500/[0.12] px-3.5 py-3 text-[13px] leading-5 text-[color:var(--text-1)]"
        >
          <Markdown content={reflection ?? ""} />
        </div>
      ) : null}

      {hasReflection ? (
        <p className="mt-2 text-[11px] leading-4 text-[color:var(--text-3)]">
          {t("checkin.reflection_saved_hint")}
        </p>
      ) : canGenerate ? (
        <p className="mt-2 text-[11px] leading-4 text-[color:var(--text-3)]">
          {t("checkin.reflection_missing_hint")}
        </p>
      ) : null}

      {error ? (
        <div
          role="alert"
          className="mt-2 rounded-xl border border-red-400/25 bg-red-500/10 px-3 py-2 text-[12px] leading-[18px] text-red-200"
        >
          {t(error.code)}
        </div>
      ) : null}

      {cached && !loading ? (
        <p role="status" className="mt-2 text-[11px] leading-4 text-[color:var(--text-3)]">
          {t("checkin.reflection_cached")}
        </p>
      ) : null}

      {mode === "unsaved" ? (
        <p
          role="alert"
          className="mt-2 rounded-xl border border-amber-400/25 bg-amber-500/[0.08] px-3 py-2 text-[12px] leading-[18px] text-amber-100"
        >
          {t("checkin.reflection_unsaved")}
        </p>
      ) : null}

      {mode === "save-first" ? (
        <p className="mt-2 text-[12px] leading-[18px] text-[color:var(--text-2)]">
          {t("checkin.reflection_save_first")}
        </p>
      ) : null}

      {loading ? (
        <div
          role="status"
          aria-live="polite"
          className="mt-3 flex items-center gap-2 text-[12px] text-[color:var(--text-2)]"
        >
          <Spinner />
          {hasReflection
            ? t("checkin.reflection_regenerating")
            : t("checkin.reflection_loading")}
        </div>
      ) : !restricted && reflectionCanRequest(mode) ? (
        <button
          type="button"
          onClick={onGenerate}
          className={`mt-3 min-h-11 w-full rounded-[12px] border border-white/10 bg-white/[0.05] px-4 text-[12px] font-medium text-[color:var(--text-1)] transition-colors hover:bg-white/[0.09] ${FOCUS_RING}`}
        >
          {mode === "regenerate"
            ? t("checkin.reflection_regenerate")
            : t("checkin.reflection_generate")}
        </button>
      ) : null}
    </CheckInSection>
  );
}
