"use client";

import { PeriodicSummary } from "@/components/PeriodicSummary";
import { useTranslation } from "@/lib/i18n";

/**
 * The AI Reports destination behind the Overview's Entries card.
 *
 * It wraps the existing `PeriodicSummary` capability unchanged: the same
 * `/api/ai/reports` read and `/api/ai/periodic` generation, no new prompt or
 * storage. The period tabs and their empty/error states stay inside it.
 */
export function ReportsScreen({ userId }: { userId: string }) {
  const { t } = useTranslation();

  return (
    <div className="space-y-3" data-screen="reports">
      <p className="text-[13px] leading-[19px] text-[color:var(--text-2)]">
        {t("screens.reports.subtitle")}
      </p>
      <PeriodicSummary userId={userId} />
    </div>
  );
}
