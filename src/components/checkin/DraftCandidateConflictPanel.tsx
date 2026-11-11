"use client";

import { useState } from "react";

import { useTranslation } from "@/lib/i18n";
import type {
  DraftCandidateChoice,
  DraftConflictSnapshotRead,
} from "@/lib/checkin/draft";
import type { CheckInData } from "@/lib/types";

import { ConfirmDialog } from "./dialogs/ConfirmDialog";

type ReadySnapshot = Extract<DraftConflictSnapshotRead, { status: "ready" }>;

export function DraftCandidateConflictPanel({
  snapshot,
  error,
  onResolve,
}: {
  snapshot: ReadySnapshot;
  error: string | null;
  onResolve: (choice: DraftCandidateChoice) => void | Promise<void>;
}) {
  const { t } = useTranslation();
  const [pendingChoice, setPendingChoice] = useState<DraftCandidateChoice | null>(null);
  const [resolving, setResolving] = useState(false);
  const candidate = snapshot.candidates[0];
  if (!candidate) return null;

  const renderVersion = (title: string, data: CheckInData) => (
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
        <div>
          <dt className="text-white/50">{t("checkin.draft_candidate_photo")}</dt>
          <dd className="text-white/85">
            {data.photoPath ? t("checkin.draft_candidate_has_photo") : t("checkin.draft_candidate_no_photo")}
          </dd>
        </div>
        <div>
          <dt className="text-white/50">{t("checkin.draft_candidate_activities")}</dt>
          <dd className="break-words text-white/85">
            {data.activities.join(", ") || t("checkin.draft_conflict_no_value")}
          </dd>
        </div>
        <div>
          <dt className="text-white/50">{t("checkin.draft_candidate_scales")}</dt>
          <dd className="break-words text-white/85">
            {Object.entries(data.scaleValues).map(([id, value]) => `${id}: ${value}`).join(", ") ||
              t("checkin.draft_conflict_no_value")}
          </dd>
        </div>
      </dl>
    </article>
  );

  const localChoice = pendingChoice === "candidate";
  return (
    <section
      aria-labelledby="draft-candidate-conflict-title"
      className="glass-card space-y-4 border border-amber-300/25 p-5"
      data-screen="draft-candidate-conflict"
    >
      <div>
        <h2 id="draft-candidate-conflict-title" className="text-base font-semibold text-amber-100">
          {t("checkin.draft_candidate_title")}
        </h2>
        <p className="mt-2 text-[13px] leading-5 text-white/75">
          {t("checkin.draft_candidate_body", {
            date: snapshot.date,
            count: snapshot.candidates.length,
          })}
        </p>
      </div>

      {snapshot.current.status === "found" ? (
        <div className="grid gap-3 sm:grid-cols-2">
          {renderVersion(t("checkin.draft_candidate_recovered"), candidate.data)}
          {renderVersion(t("checkin.draft_candidate_current"), snapshot.current.data)}
        </div>
      ) : (
        <>
          {renderVersion(t("checkin.draft_candidate_recovered"), candidate.data)}
          <p role="status" className="rounded-xl border border-white/10 bg-black/10 p-4 text-sm text-white/75">
            {t("checkin.draft_candidate_current_missing")}
          </p>
        </>
      )}

      {error ? <p role="alert" className="text-sm text-red-200">{error}</p> : null}

      <div className="grid gap-3 sm:grid-cols-2">
        <button
          type="button"
          disabled={resolving}
          onClick={() => setPendingChoice("candidate")}
          className="min-h-11 rounded-xl bg-indigo-500 px-4 text-sm font-semibold text-white hover:bg-indigo-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300"
        >
          {t("checkin.draft_candidate_use")}
        </button>
        <button
          type="button"
          disabled={resolving}
          onClick={() => setPendingChoice("current")}
          className="min-h-11 rounded-xl border border-white/20 px-4 text-sm font-semibold text-white/85 hover:bg-white/[0.06] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300"
        >
          {t("checkin.draft_candidate_keep_current")}
        </button>
      </div>

      <ConfirmDialog
        open={pendingChoice !== null}
        title={t(localChoice ? "checkin.draft_candidate_use_title" : "checkin.draft_candidate_keep_title")}
        body={t(localChoice ? "checkin.draft_candidate_use_body" : "checkin.draft_candidate_keep_body")}
        confirmLabel={t(localChoice ? "checkin.draft_candidate_use_confirm" : "checkin.draft_candidate_keep_confirm")}
        cancelLabel={t("common.cancel")}
        onCancel={() => {
          if (!resolving) setPendingChoice(null);
        }}
        onConfirm={async () => {
          if (!pendingChoice || resolving) return;
          const choice = pendingChoice;
          setResolving(true);
          try {
            await onResolve(choice);
          } finally {
            setResolving(false);
            setPendingChoice(null);
          }
        }}
      />
    </section>
  );
}
