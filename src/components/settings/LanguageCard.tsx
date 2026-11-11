"use client";

import { LanguageSwitcher } from "@/components/LanguageSwitcher";
import { SectionHeader } from "@/components/ui/ScreenHeader";
import { useTranslation } from "@/lib/i18n";

const NOTES = [
  "settings.language_note_interface",
  "settings.language_note_entries",
  "settings.language_note_ai",
  "settings.language_note_translation",
] as const;

/** Settings language card: labelled choice plus Android's honesty notes. */
export function LanguageCard() {
  const { t } = useTranslation();

  return (
    <section className="glass-card" aria-labelledby="settings-language-title">
      <SectionHeader id="settings-language-title" title={t("settings.language")} />
      <LanguageSwitcher variant="full" />
      <ul className="mt-3 space-y-1.5">
        {NOTES.map((key) => (
          <li key={key} className="text-xs leading-5 text-[color:var(--text-2)]">
            {t(key)}
          </li>
        ))}
      </ul>
    </section>
  );
}
