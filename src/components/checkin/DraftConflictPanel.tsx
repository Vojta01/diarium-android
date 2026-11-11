"use client";

import { useState } from "react";

import { useTranslation } from "@/lib/i18n";
import type { DraftConflict, DraftConflictChoice } from "@/lib/checkin/draft-recovery";
import type { Entry } from "@/lib/supabase/db";

import { ConfirmDialog } from "./dialogs/ConfirmDialog";

export function DraftConflictPanel({
  conflict,
  error,
  onResolve,
}: {
  conflict: DraftConflict<Entry>;
  error: string | null;
  onResolve: (choice: DraftConflictChoice) => void;
}) {
  const { t } = useTranslation();
  const [pendingChoice, setPendingChoice] = useState<DraftConflictChoice | null>(null);

  const message = conflict.ambiguous
    ? pendingChoice === "cloud"
      ? {
          title: t("checkin.draft_ambiguous_continue_title"),
          body: t("checkin.draft_ambiguous_continue_body"),
          confirm: t("checkin.draft_ambiguous_continue_confirm"),
        }
      : {
          title: t("checkin.draft_retry_pending_confirm_title"),
          body: t("checkin.draft_retry_pending_confirm_body"),
          confirm: t("checkin.draft_retry_pending_confirm"),
        }
    : pendingChoice === "cloud" && !conflict.cloudEntry
      ? {
          title: t("checkin.draft_discard_local_no_cloud_title"),
          body: t("checkin.draft_discard_local_no_cloud_body"),
          confirm: t("checkin.draft_discard_local_no_cloud_confirm"),
        }
      : pendingChoice === "local"
        ? {
            title: t("checkin.draft_use_local_confirm_title"),
            body: t("checkin.draft_use_local_confirm_body"),
            confirm: t("checkin.draft_use_local_confirm"),
          }
        : {
            title: t("checkin.draft_keep_cloud_confirm_title"),
            body: t("checkin.draft_keep_cloud_confirm_body"),
            confirm: t("checkin.draft_keep_cloud_confirm"),
          };

  const renderVersion = (
    title: string,
    data: DraftConflict<Entry>["cloudData"]
  ) => (
    <article className="rounded-xl border border-white/10 bg-black/10 p-4">
      <h3 className="text-sm font-semibold text-white">{title}</h3>
      <dl className="mt-2 space-y-2 text-[13px] leading-5">
        <div>
          <dt className="text-white/50">{t("checkin.draft_conflict_mood")}</dt>
          <dd className="text-white/85">
            {data.mood > 0 ? `${data.mood}/5` : t("checkin.draft_conflict_no_mood")}
          </dd>
        </div>
        <div>
          <dt className="text-white/50">{t("checkin.draft_conflict_sleep")}</dt>
          <dd className="text-white/85">
            {data.sleepQuality > 0 ? `${data.sleepQuality}/5` : t("checkin.draft_conflict_no_value")}
          </dd>
        </div>
        <div>
          <dt className="text-white/50">{t("checkin.draft_conflict_stress")}</dt>
          <dd className="text-white/85">
            {data.stress > 0 ? `${data.stress}/5` : t("checkin.draft_conflict_no_value")}
          </dd>
        </div>
        <div>
          <dt className="text-white/50">{t("checkin.draft_conflict_gratitude")}</dt>
          <dd className="whitespace-pre-wrap break-words text-white/85">
            {data.gratitude.filter((value) => value.trim()).join("\n") ||
              t("checkin.draft_conflict_no_gratitude")}
          </dd>
        </div>
        <div>
          <dt className="text-white/50">{t("checkin.draft_conflict_weather")}</dt>
          <dd className="text-white/85">
            {data.weather.join(", ") || t("checkin.draft_conflict_no_weather")}
          </dd>
        </div>
        <div>
          <dt className="text-white/50">{t("checkin.draft_conflict_note")}</dt>
          <dd className="whitespace-pre-wrap break-words text-white/85">
            {data.note.trim() || t("checkin.draft_conflict_no_note")}
          </dd>
        </div>
      </dl>
    </article>
  );

  return (
    <section
      aria-labelledby="draft-conflict-title"
      className="glass-card space-y-4 border border-amber-300/25 p-5"
      data-screen="draft-conflict"
    >
      <div>
        <h2 id="draft-conflict-title" className="text-base font-semibold text-amber-100">
          {t(conflict.ambiguous ? "checkin.draft_ambiguous_title" : "checkin.draft_conflict_title")}
        </h2>
        <p className="mt-2 text-[13px] leading-5 text-white/75">
          {conflict.ambiguous
            ? t("checkin.draft_ambiguous_body")
            : t("checkin.draft_conflict_body", { date: conflict.date })}
        </p>
      </div>

      {!conflict.cloudEntry ? (
        <p role="status" className="text-sm text-white/60">
          {t("checkin.draft_conflict_cloud_missing")}
        </p>
      ) : null}

      <div className="grid gap-3 sm:grid-cols-2">
        {renderVersion(t("checkin.draft_local_title"), conflict.localDraft)}
        {renderVersion(t("checkin.draft_cloud_title"), conflict.cloudData)}
      </div>

      {error ? (
        <p role="alert" className="text-sm text-red-200">{error}</p>
      ) : null}

      <div className="grid gap-3 sm:grid-cols-2">
        <button
          type="button"
          onClick={() => setPendingChoice("local")}
          className="min-h-11 rounded-xl bg-indigo-500 px-4 text-sm font-semibold text-white hover:bg-indigo-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300"
        >
          {t(conflict.ambiguous ? "checkin.draft_retry_pending" : "checkin.draft_use_local")}
        </button>
        <button
          type="button"
          onClick={() => setPendingChoice("cloud")}
          className="min-h-11 rounded-xl border border-white/20 px-4 text-sm font-semibold text-white/85 hover:bg-white/[0.06] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300"
        >
          {t(
            conflict.ambiguous
              ? "checkin.draft_ambiguous_continue"
              : !conflict.cloudEntry
                ? "checkin.draft_discard_local_no_cloud"
                : "checkin.draft_keep_cloud"
          )}
        </button>
      </div>

      <ConfirmDialog
        open={pendingChoice !== null}
        title={message.title}
        body={message.body}
        confirmLabel={message.confirm}
        cancelLabel={t("common.cancel")}
        onCancel={() => setPendingChoice(null)}
        onConfirm={() => {
          if (pendingChoice) onResolve(pendingChoice);
          setPendingChoice(null);
        }}
      />
    </section>
  );
}
