"use client";

import { ExternalLink } from "@/components/ExternalLink";
import { NativeOnlyCard } from "@/components/settings/NativeOnlyCard";
import { SectionHeader } from "@/components/ui/ScreenHeader";
import { AndroidIcon, GitHubIcon } from "@/components/ui/icons";
import { ANDROID_VERSION, WEB_PACKAGE_NAME, WEB_VERSION } from "@/lib/app-meta";
import { useTranslation } from "@/lib/i18n";
import {
  ANDROID_APK_URL,
  GITHUB_ANDROID_URL,
  GITHUB_WEB_URL,
} from "@/lib/links";

/**
 * About is the single canonical home of the public links and of the honest
 * platform matrix. Android has no About screen, so this is a web-only surface,
 * not missing parity.
 */
export function AboutScreen() {
  const { t } = useTranslation();
  const linkClass = "w-full justify-start";

  return (
    <div className="space-y-4">
      <section className="glass-card" aria-labelledby="about-identity-title">
        <p className="text-[11px] font-semibold uppercase tracking-[1.2px] text-[color:var(--text-2)]">
          {t("about.app_name")}
        </p>
        <h2
          id="about-identity-title"
          className="mt-1 bg-gradient-to-r from-indigo-400 to-purple-400 bg-clip-text text-3xl font-bold text-transparent"
        >
          Diarium
        </h2>
        <dl className="mt-2 space-y-1 font-mono text-[13px] text-[color:var(--text-2)]">
          <div className="flex flex-wrap gap-x-2">
            <dt className="sr-only">{t("about.version_web_label")}</dt>
            <dd>
              {t("about.version_web", {
                package: WEB_PACKAGE_NAME,
                version: WEB_VERSION,
              })}
            </dd>
          </div>
          <div className="flex flex-wrap gap-x-2">
            <dt className="sr-only">{t("about.version_android_label")}</dt>
            <dd>{t("about.version_android", { version: ANDROID_VERSION })}</dd>
          </div>
        </dl>
        <p className="mt-3 text-sm leading-6 text-[color:var(--text-2)]">
          {t("about.parity")}
        </p>
      </section>

      <section className="glass-card" aria-labelledby="about-web-only-title">
        <SectionHeader id="about-web-only-title" title={t("about.web_only_title")} />
        <p className="text-sm leading-6 text-[color:var(--text-2)]">
          {t("about.web_only_body")}
        </p>
      </section>

      <NativeOnlyCard />

      <section className="glass-card" aria-labelledby="about-links-title">
        <SectionHeader id="about-links-title" title={t("about.links")} />
        <div className="space-y-1">
          <ExternalLink
            href={GITHUB_WEB_URL}
            label={t("links.github_source")}
            className={linkClass}
          >
            <GitHubIcon size={18} />
            <span>{t("links.github_source")}</span>
          </ExternalLink>
          <ExternalLink
            href={GITHUB_ANDROID_URL}
            label={t("links.android")}
            className={linkClass}
          >
            <AndroidIcon size={18} />
            <span>{t("links.android")}</span>
          </ExternalLink>
          <ExternalLink
            href={ANDROID_APK_URL}
            label={t("links.download_apk")}
            className={linkClass}
          >
            <AndroidIcon size={18} />
            <span>{t("links.download_apk")}</span>
            <span className="text-[11px] text-[color:var(--text-2)]">
              {t("links.apk_hint")}
            </span>
          </ExternalLink>
        </div>
      </section>

      <section className="glass-card" aria-labelledby="about-data-title">
        <SectionHeader id="about-data-title" title={t("about.data_ai_title")} />
        <p className="text-sm leading-6 text-[color:var(--text-2)]">
          {t("about.data_ai")}
        </p>
        <p className="mt-3 text-xs leading-5 text-[color:var(--text-2)]">
          {t("about.deletion_gap")}
        </p>
        <p className="mt-3 text-xs leading-5 text-[color:var(--text-2)]">
          {t("about.license")}
        </p>
      </section>
    </div>
  );
}
