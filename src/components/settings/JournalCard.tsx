"use client";

import { SettingsRow } from "@/components/settings/SettingsRow";
import { SectionHeader } from "@/components/ui/ScreenHeader";
import { useTranslation } from "@/lib/i18n";
import type { SubScreen } from "@/lib/navigation";
import { JOURNAL_ROWS } from "@/lib/settings";

/** Deník card: goals, scales, note templates, achievements (Android order). */
export function JournalCard({ onOpenSub }: { onOpenSub: (sub: SubScreen) => void }) {
  const { t } = useTranslation();

  return (
    <section className="glass-card" aria-labelledby="settings-journal-title">
      <SectionHeader id="settings-journal-title" title={t("settings.journal")} />
      <p className="mb-1 text-sm leading-6 text-[color:var(--text-2)]">
        {t("settings.journal_hint")}
      </p>
      <div className="divide-y divide-white/5">
        {JOURNAL_ROWS.map((row) => (
          <SettingsRow
            key={row.id}
            emoji={row.emoji}
            title={t(row.labelKey)}
            hint={t(row.hintKey)}
            onClick={() => onOpenSub(row.sub)}
          />
        ))}
      </div>
    </section>
  );
}
