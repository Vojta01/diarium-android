"use client";

import { SectionHeader } from "@/components/ui/ScreenHeader";
import { useTranslation } from "@/lib/i18n";
import type { SubScreen } from "@/lib/navigation";

/**
 * AI card: explains where generation runs.
 *
 * No switch. The web has no `profiles.ai_enabled` column and no server-side
 * switch, so a localStorage toggle would lie — it would not stop generation.
 */
export function AiPrivacyCard({ onOpenSub }: { onOpenSub: (sub: SubScreen) => void }) {
  const { t } = useTranslation();

  return (
    <section className="glass-card" aria-labelledby="settings-ai-title">
      <SectionHeader id="settings-ai-title" title={t("settings.ai")} />
      <p className="text-sm leading-6 text-[color:var(--text-2)]">
        {t("settings.ai_web_body")}
      </p>
      <p className="mt-2 text-sm leading-6 text-[color:var(--text-2)]">
        {t("settings.ai_android_body")}
      </p>
      <button
        type="button"
        onClick={() => onOpenSub("about")}
        className="mt-3 flex min-h-11 items-center rounded-xl px-1 text-sm font-medium text-indigo-300 transition-colors hover:bg-white/5 hover:text-indigo-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/70"
      >
        {t("settings.ai_more")} <span aria-hidden="true">›</span>
      </button>
    </section>
  );
}
