"use client";

import { useState } from "react";

import { ExternalLink } from "@/components/ExternalLink";
import { LanguageSwitcher } from "@/components/LanguageSwitcher";
import { AndroidIcon, GitHubIcon } from "@/components/ui/icons";
import { resolveOAuthRedirectTo } from "@/lib/auth-redirect";
import { useTranslation } from "@/lib/i18n";
import { ANDROID_APK_URL, GITHUB_WEB_URL } from "@/lib/links";
import { createSupabaseClient } from "@/lib/supabase/client";

export function AuthScreen() {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const signInWithGoogle = async () => {
    setLoading(true);
    setError(null);
    try {
      const sb = createSupabaseClient();
      const { error } = await sb.auth.signInWithOAuth({
        provider: "google",
        options: {
          // Same origin as the page the user is on — never a hardcoded host.
          redirectTo: resolveOAuthRedirectTo(
            window.location.origin,
            process.env.NEXT_PUBLIC_AUTH_CALLBACK_URL
          ),
        },
      });
      if (error) throw error;
      // Redirects to Google OAuth; no local state to update here.
    } catch (e) {
      setError(e instanceof Error ? e.message : t("auth.sign_in_error"));
      setLoading(false);
    }
  };

  return (
    <div className="flex min-h-screen flex-col items-center justify-center px-4 py-10">
      <div className="glass-card relative w-full max-w-md">
        <div className="mb-8 flex items-start justify-between gap-3">
          <div className="min-w-0 flex-1 text-center">
            <h1 className="bg-gradient-to-r from-indigo-400 to-purple-400 bg-clip-text text-4xl font-bold text-transparent">
              {t("auth.title")}
            </h1>
            <p className="mt-2 text-sm text-[color:var(--text-2)]">{t("auth.subtitle")}</p>
          </div>
          <div className="shrink-0 pt-1">
            <LanguageSwitcher />
          </div>
        </div>

        <div className="space-y-4">
          {error && (
            <div
              role="alert"
              className="rounded-xl border border-red-500/20 bg-red-500/10 p-3 text-center text-sm text-red-400"
            >
              {error}
            </div>
          )}

          <button
            onClick={signInWithGoogle}
            disabled={loading}
            className="flex w-full items-center justify-center gap-3 rounded-xl border border-white/10
                       bg-white px-6 py-3 font-medium text-gray-800 transition-colors
                       hover:bg-gray-100 disabled:opacity-50"
          >
            <svg className="h-5 w-5" viewBox="0 0 24 24">
              <path
                fill="currentColor"
                d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z"
              />
              <path
                fill="currentColor"
                d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"
              />
              <path
                fill="currentColor"
                d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"
              />
              <path
                fill="currentColor"
                d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"
              />
            </svg>
            {loading ? t("auth.signing_in") : t("auth.sign_in_google")}
          </button>
        </div>
      </div>

      {/* Public links live below the card so they never compete with the CTA. */}
      <div className="mt-4 w-full max-w-md">
        <div className="glass-divider" aria-hidden="true" />
        <p className="mt-2 px-1 text-[11px] text-white/30">
          {t("auth.links_hint")}
        </p>
        <div className="mt-1 flex flex-col gap-0.5">
          <ExternalLink
            href={GITHUB_WEB_URL}
            label={t("links.github_source")}
            className="justify-start text-white/60"
          >
            <GitHubIcon size={18} />
            <span>{t("links.github_source")}</span>
          </ExternalLink>
          <div className="flex items-center gap-1">
            <ExternalLink
              href={ANDROID_APK_URL}
              label={t("links.download_android")}
              className="justify-start text-white/60"
            >
              <AndroidIcon size={18} />
              <span>{t("links.download_android")}</span>
            </ExternalLink>
            <span className="text-[11px] text-[color:var(--text-3)]">
              {t("links.apk_hint")}
            </span>
          </div>
        </div>
        <p className="mt-4 text-center text-[10px] text-white/20">
          {t("auth.data_privacy")}
        </p>
      </div>
    </div>
  );
}
