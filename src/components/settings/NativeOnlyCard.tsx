"use client";

import { SectionHeader } from "@/components/ui/ScreenHeader";
import { useTranslation } from "@/lib/i18n";

const ITEMS = [
  { emoji: "👤", titleKey: "about.native_profile_title", bodyKey: "about.native_profile_body" },
  { emoji: "🚀", titleKey: "about.native_onboarding_title", bodyKey: "about.native_onboarding_body" },
  { emoji: "🔑", titleKey: "about.native_byok_title", bodyKey: "about.native_byok_body" },
  { emoji: "⏰", titleKey: "about.native_scheduling_title", bodyKey: "about.native_scheduling_body" },
  { emoji: "📱", titleKey: "about.native_screen_time_title", bodyKey: "about.native_screen_time_body" },
] as const;

/**
 * Honest catalogue of what only the native app does.
 *
 * Text, never a disabled control: there is no web profile form, no onboarding
 * replay and no local scheduler, so nothing here may look tappable.
 */
export function NativeOnlyCard() {
  const { t } = useTranslation();

  return (
    <section className="glass-card" aria-labelledby="settings-native-title">
      <SectionHeader id="settings-native-title" title={t("about.native_only_title")} />
      <p className="mb-2 text-sm leading-6 text-[color:var(--text-2)]">
        {t("about.native_only_hint")}
      </p>
      <ul className="space-y-3">
        {ITEMS.map((item) => (
          <li key={item.titleKey} className="flex gap-3">
            <span aria-hidden="true" className="mt-0.5 text-lg">
              {item.emoji}
            </span>
            <span className="min-w-0">
              <span className="block text-sm font-medium text-[color:var(--text-1)]">
                {t(item.titleKey)}
              </span>
              <span className="mt-0.5 block text-xs leading-5 text-[color:var(--text-2)]">
                {t(item.bodyKey)}
              </span>
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}
