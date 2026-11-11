"use client";

import { useState } from "react";

import { ExportDialog } from "@/components/ExportDialog";
import { AiPrivacyCard } from "@/components/settings/AiPrivacyCard";
import { JournalCard } from "@/components/settings/JournalCard";
import { LanguageCard } from "@/components/settings/LanguageCard";
import { NotificationsCard } from "@/components/settings/NotificationsCard";
import { ReportsCard } from "@/components/settings/ReportsCard";
import { SettingsRow } from "@/components/settings/SettingsRow";
import { SectionHeader } from "@/components/ui/ScreenHeader";
import { ANDROID_VERSION, WEB_VERSION } from "@/lib/app-meta";
import { useTranslation } from "@/lib/i18n";
import type { SubScreen } from "@/lib/navigation";

interface SettingsViewProps {
  email?: string | null;
  /** Supabase auth provider, e.g. "google"; shown as an honest label. */
  provider?: string | null;
  onLogout: () => void;
  onOpenSub: (sub: SubScreen) => void;
}

/**
 * Android-order Settings: Language, Deník, Notifications, Reports, AI, Data,
 * About, Account last. The web has no profile fields, no local scheduler and
 * no BYOK key, so those are described in About instead of faked here.
 */
export function SettingsView({ email, provider, onLogout, onOpenSub }: SettingsViewProps) {
  const { t } = useTranslation();
  const [showExport, setShowExport] = useState(false);
  const versionLabel = `Web ${WEB_VERSION} · Android ${ANDROID_VERSION}`;

  return (
    <div className="space-y-4">
      <LanguageCard />

      <JournalCard onOpenSub={onOpenSub} />

      <section className="glass-card" aria-labelledby="settings-notifications-title">
        <SectionHeader
          id="settings-notifications-title"
          title={t("settings.notifications")}
        />
        <NotificationsCard />
      </section>

      <ReportsCard onOpenSub={onOpenSub} />

      <AiPrivacyCard onOpenSub={onOpenSub} />

      <section className="glass-card" aria-labelledby="settings-data-title">
        <SectionHeader id="settings-data-title" title={t("settings.data")} />
        <p className="mb-1 text-sm leading-6 text-[color:var(--text-2)]">
          {t("settings.data_hint")}
        </p>
        <SettingsRow
          emoji="📤"
          title={t("settings.export")}
          hint={t("settings.export_hint")}
          onClick={() => setShowExport(true)}
        />
      </section>

      <section className="glass-card" aria-labelledby="settings-about-title">
        <SectionHeader id="settings-about-title" title={t("settings.about_app")} />
        <SettingsRow
          emoji="ℹ️"
          title={t("settings.about_app")}
          hint={versionLabel}
          onClick={() => onOpenSub("about")}
        />
      </section>

      <section className="glass-card" aria-labelledby="settings-account-title">
        <SectionHeader id="settings-account-title" title={t("settings.account")} />
        <div className="flex flex-wrap items-center gap-2">
          <p className="min-w-0 truncate text-sm text-[color:var(--text-1)]">
            {email || t("settings.unknown_user")}
          </p>
          {provider === "google" ? (
            <span className="rounded-full border border-white/15 px-2 py-0.5 text-[11px] text-[color:var(--text-2)]">
              {t("settings.account_provider_google")}
            </span>
          ) : null}
        </div>
        <p className="mt-2 text-xs leading-5 text-[color:var(--text-2)]">
          {t("settings.account_signout_hint")}
        </p>
        <button
          type="button"
          onClick={onLogout}
          className="mt-3 min-h-11 rounded-xl border border-red-400/30 px-4 text-sm font-medium text-red-300 transition-colors hover:bg-red-500/10 hover:text-red-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-400/60"
        >
          {t("settings.logout")}
        </button>
      </section>

      <ExportDialog open={showExport} onClose={() => setShowExport(false)} />
    </div>
  );
}
