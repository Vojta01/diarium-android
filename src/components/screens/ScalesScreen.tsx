"use client";

import { ScalesWidget } from "@/components/ScalesWidget";
import { useTranslation } from "@/lib/i18n";

/**
 * The Scales destination behind the Overview's Entries card.
 *
 * It deliberately renders the existing widget: no new scale CRUD, no second
 * source of truth. The widget now distinguishes a failed read from an empty
 * account instead of silently showing "no scales".
 */
export function ScalesScreen() {
  const { t } = useTranslation();

  return (
    <div className="space-y-3" data-screen="scales">
      <p className="text-[13px] leading-[19px] text-[color:var(--text-2)]">
        {t("screens.scales.subtitle")}
      </p>
      <ScalesWidget />
    </div>
  );
}
