"use client";

import { useEffect, useRef, useState } from "react";
import type { Session, User } from "@supabase/supabase-js";

import { AuthScreen } from "@/components/AuthScreen";
import { ErrorBoundary } from "@/components/ErrorBoundary";
import { OfflineWorkspace } from "@/components/OfflineWorkspace";
import { AppShell } from "@/components/shell/AppShell";
import { useTranslation } from "@/lib/i18n";
import { DEFAULT_NAV, parseNavFromSearch, type NavState } from "@/lib/navigation";
import { createSupabaseClient } from "@/lib/supabase/client";
import { getSupabaseAuthTokenKey } from "@/lib/supabase-ref";
import { clearUserLocalState } from "@/lib/local-state";
import { installCloudNetworkGate, setLocalOnlyMode } from "@/lib/cloud-access";
import {
  isNetworkUnavailableError,
  persistVerifiedSession,
  readVerifiedWorkspaceOwner,
} from "@/lib/auth/verified-session";

function doLogout() {
  clearUserLocalState(localStorage);
  // Also delete supabase cookies
  document.cookie.split(";").forEach((c) => {
    const [name] = c.trim().split("=");
    if (name && (name.includes("sb-") || name.includes("supabase"))) {
      document.cookie = name + "=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;";
    }
  });
  // Try Supabase signOut
  try {
    createSupabaseClient().auth.signOut().catch(() => {});
  } catch {}
  // Full reload to get clean login screen
  window.location.reload();
}

export default function Home() {
  const { t } = useTranslation();
  const [user, setUser] = useState<User | null>(null);
  // Deep links (?view=, ?edit=, ?sub=) are parsed on the first client render,
  // before any auth early return, so a stored session opens the requested
  // destination without a second render pass.
  const [nav] = useState<NavState>(() =>
    typeof window === "undefined"
      ? DEFAULT_NAV
      : parseNavFromSearch(window.location.search)
  );
  const [loading, setLoading] = useState(true);
  const [verificationFailed, setVerificationFailed] = useState(false);
  const [retryNonce, setRetryNonce] = useState(0);
  const [offlineOwnerId, setOfflineOwnerId] = useState<string | null>(null);
  const [localOnly, setLocalOnly] = useState(false);
  const localOnlyRef = useRef(false);

  useEffect(() => {
    let active = true;
    let version = 0;
    installCloudNetworkGate();
    try {
      const sb = createSupabaseClient();
      const { data: listener } = sb.auth.onAuthStateChange((_event, session) => {
        if (localOnlyRef.current) return;
        const eventVersion = ++version;
        if (!session) {
          setUser(null);
          setVerificationFailed(false);
          setOfflineOwnerId(null);
          setLoading(false);
          return;
        }

        // Immediately gate account data while the new identity is checked.
        setUser(null);
        setVerificationFailed(false);
        setOfflineOwnerId(null);
        setLoading(true);
        // Avoid calling Supabase Auth from inside its own state-change lock.
        window.setTimeout(() => {
          if (active && eventVersion === version) verifySession(sb.auth, session);
        }, 0);
      });

      const initialVersion = version;
      sb.auth.getSession().then(({ data, error }) => {
        if (!active || version !== initialVersion) return;
        if (error) {
          failVerification(error, null);
        } else if (data.session) {
          verifySession(sb.auth, data.session);
        } else {
          setUser(null);
          setVerificationFailed(false);
          setLoading(false);
        }
      }).catch((error) => {
        if (active && version === initialVersion) failVerification(error, null);
      });

      return () => {
        active = false;
        version += 1;
        listener.subscription.unsubscribe();
      };
    } catch {
      failVerification();
    }
    function failVerification(error?: unknown, sessionUserId?: string | null) {
      if (!active) return;
      const offlineOwner = isNetworkUnavailableError(error) &&
        typeof sessionUserId === "string" &&
        sessionUserId.length > 0
        ? readVerifiedWorkspaceOwner(localStorage, sessionUserId)
        : null;
      setUser(null);
      setOfflineOwnerId(offlineOwner);
      setVerificationFailed(true);
      setLoading(false);
    }

    function verifySession(auth: ReturnType<typeof createSupabaseClient>["auth"], session: Session) {
      const requestVersion = ++version;
      setUser(null);
      setVerificationFailed(false);
      setLoading(true);
      const expiresAt = session.expires_at ??
        Math.floor(Date.now() / 1000) + (session.expires_in ?? 3600);
      void persistVerifiedSession(auth, localStorage, getSupabaseAuthTokenKey(), {
        access_token: session.access_token,
        refresh_token: session.refresh_token,
        expires_at: expiresAt,
        token_type: session.token_type || "bearer",
      }, () => active && requestVersion === version).then((verifiedUser) => {
        if (!active || requestVersion !== version) return;
        setOfflineOwnerId(null);
        setUser(verifiedUser as User);
        setLoading(false);
      }).catch((error) => {
        if (!active || requestVersion !== version) return;
        failVerification(error, session.user?.id ?? null);
      });
    }
  }, [retryNonce]);

  const continueLocalOnly = () => {
    if (!offlineOwnerId) return;
    setLocalOnlyMode(true);
    localOnlyRef.current = true;
    setLocalOnly(true);
    setVerificationFailed(false);
    setLoading(false);
  };

  const resumeOnline = () => {
    setLocalOnlyMode(false);
    localOnlyRef.current = false;
    setLocalOnly(false);
    setOfflineOwnerId(null);
    setUser(null);
    setVerificationFailed(false);
    setLoading(true);
    setRetryNonce((current) => current + 1);
  };

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="text-white/40 text-lg">{t("common.loading")}</div>
      </div>
    );
  }

  if (localOnly && offlineOwnerId) {
    return (
      <ErrorBoundary>
        <OfflineWorkspace
          ownerId={offlineOwnerId}
          onResumeOnline={resumeOnline}
          onSignOut={doLogout}
        />
      </ErrorBoundary>
    );
  }

  if (!user) {
    if (verificationFailed) {
      return (
        <main role="alert" className="min-h-screen flex flex-col items-center justify-center gap-4 px-6 text-center text-white">
          <p>{t("auth.session_verification_error")}</p>
          <div className="flex flex-wrap justify-center gap-3">
            <button
              type="button"
              className="rounded-xl bg-indigo-500 px-4 py-2 font-semibold"
              onClick={() => {
                setLoading(true);
                setVerificationFailed(false);
                setRetryNonce((current) => current + 1);
              }}
            >
              {t("auth.retry_verification")}
            </button>
            {offlineOwnerId ? (
              <button
                type="button"
                className="rounded-xl border border-amber-200/30 px-4 py-2 font-semibold text-amber-100"
                onClick={continueLocalOnly}
              >
                {t("offline.continue_local_only")}
              </button>
            ) : null}
            <button
              type="button"
              className="rounded-xl border border-white/20 px-4 py-2 font-semibold"
              onClick={doLogout}
            >
              {t("auth.sign_out")}
            </button>
          </div>
        </main>
      );
    }
    return <AuthScreen />;
  }

  return (
    <ErrorBoundary>
      <AppShell key={user.id} user={user} initialNav={nav} onLogout={doLogout} />
    </ErrorBoundary>
  );
}
