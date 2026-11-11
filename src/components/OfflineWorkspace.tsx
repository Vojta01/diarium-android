"use client";

import { CheckInScreen } from "@/components/checkin/CheckInScreen";
import { useTranslation } from "@/lib/i18n";

export function OfflineWorkspace({
  ownerId,
  onResumeOnline,
  onSignOut,
}: {
  /** Read only from the separate, previously provider-verified workspace binding. */
  ownerId: string;
  onResumeOnline: () => void;
  onSignOut: () => void;
}) {
  const { t } = useTranslation();

  return (
    <main className="mx-auto min-h-screen w-full max-w-[620px] px-4 py-6 text-white">
      <header className="mb-4">
        <h1 className="text-2xl font-semibold">Diarium</h1>
      </header>

      <section
        role="status"
        aria-live="polite"
        className="mb-5 rounded-2xl border border-amber-300/25 bg-amber-400/[0.08] p-4"
      >
        <h2 className="text-base font-semibold text-amber-100">
          {t("offline.title")}
        </h2>
        <p className="mt-2 text-sm leading-6 text-white/75">
          {t("offline.description")}
        </p>
        <p className="mt-2 text-sm leading-6 text-white/75">
          {t("offline.not_cached")}
        </p>
        <div className="mt-4 flex flex-wrap gap-3">
          <button
            type="button"
            onClick={onResumeOnline}
            className="min-h-11 rounded-xl bg-indigo-500 px-4 text-sm font-semibold text-white hover:bg-indigo-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300"
          >
            {t("offline.resume_online")}
          </button>
          <button
            type="button"
            onClick={onSignOut}
            className="min-h-11 rounded-xl border border-white/20 px-4 text-sm font-semibold text-white/80 hover:bg-white/[0.06] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300"
          >
            {t("offline.sign_out")}
          </button>
        </div>
      </section>

      <CheckInScreen
        key={`local-workspace-${ownerId}`}
        localOnly
        userId={ownerId}
        userName=""
        onSaveDone={() => {}}
        onOpenGoals={() => {}}
        onOpenOverview={() => {}}
      />
    </main>
  );
}
