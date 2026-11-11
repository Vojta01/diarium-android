"use client";

import { SettingsRow } from "@/components/settings/SettingsRow";
import { SectionHeader } from "@/components/ui/ScreenHeader";
import { useTranslation } from "@/lib/i18n";
import type { SubScreen } from "@/lib/navigation";
import { REPORTS_ROW } from "@/lib/settings";

/** Reports card: the AI weekly/monthly summaries live in their own sub-screen. */
export function ReportsCard({ onOpenSub }: { onOpenSub: (sub: SubScreen) => void }) {
  const { t } = useTranslation();

  return (
    <section className="glass-card" aria-labelledby="settings-reports-title">
      <SectionHeader id="settings-reports-title" title={t("settings.reports")} />
      <p className="mb-1 text-sm leading-6 text-[color:var(--text-2)]">
        {t("settings.reports_hint")}
      </p>
      <SettingsRow
        emoji={REPORTS_ROW.emoji}
        title={t(REPORTS_ROW.labelKey)}
        hint={t(REPORTS_ROW.hintKey)}
        onClick={() => onOpenSub(REPORTS_ROW.sub)}
      />
    </section>
  );
}
