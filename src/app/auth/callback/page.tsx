'use client';

import { Suspense, useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { getSupabaseAuthTokenKey } from '@/lib/supabase-ref';

async function storeAndSetSession(access_token: string, refresh_token: string, expires_at?: number) {
  try {
    const { createSupabaseClient } = await import('@/lib/supabase/client');
    const { persistVerifiedSession } = await import('@/lib/auth/verified-session');
    const sb = createSupabaseClient();
    const exp = expires_at || Math.floor(Date.now() / 1000) + 3600;
    await persistVerifiedSession(sb.auth, localStorage, getSupabaseAuthTokenKey(), {
      access_token,
      refresh_token,
      expires_at: exp,
      token_type: 'bearer',
    });

    // Also set the Supabase session for client auth listeners/cookies. Identity
    // is already verified above; failure here does not replace it with a claim.
    try {
      await sb.auth.setSession({ access_token, refresh_token });
    } catch {
      // The verified local session remains available as a fallback.
    }
    return true;
  } catch {
    return false;
  }
}

function CallbackInner() {
  const [error, setError] = useState<string | null>(null);
  const hasProcessedCallback = useRef(false);

  useEffect(() => {
    if (hasProcessedCallback.current) return;
    hasProcessedCallback.current = true;

    (async () => {
      // ── Strategy 1: Implicit flow — token in URL hash ──
      const hash = window.location.hash;
      if (hash) {
        // Fragments are included in the current history entry even though they
        // are not sent to the server. Replace that entry before any async work.
        window.history.replaceState(
          window.history.state,
          '',
          `${window.location.pathname}${window.location.search}`,
        );
      }
      if (hash && hash.length > 1) {
        const params = new URLSearchParams(hash.substring(1));
        const access_token = params.get('access_token');
        const refresh_token = params.get('refresh_token');
        const expires_at = parseInt(params.get('expires_at') || '0') || undefined;

        if (access_token && refresh_token) {
          if (await storeAndSetSession(access_token, refresh_token, expires_at)) {
            window.location.replace('/');
            return;
          }
          setError('Sign-in token could not be verified. Please try logging in again.');
          return;
        }
      }

      // ── Strategy 2: PKCE flow — code in query string ──
      const queryString = window.location.search;
      if (queryString) {
        const searchParams = new URLSearchParams(queryString);
        const code = searchParams.get('code');

        if (code) {
          // The authorization code is a credential. Replace this history entry
          // before loading auth code or making a network request, while keeping
          // unrelated callback parameters intact.
          searchParams.delete('code');
          const remainingQuery = searchParams.toString();
          window.history.replaceState(
            window.history.state,
            '',
            `${window.location.pathname}${remainingQuery ? `?${remainingQuery}` : ''}`,
          );

          try {
            const { createSupabaseClient } = await import('@/lib/supabase/client');
            const { verifySupabaseUser } = await import('@/lib/auth/verified-session');
            const sb = createSupabaseClient();
            const { data, error: exchangeError } = await sb.auth.exchangeCodeForSession(code);
            if (exchangeError) {
              setError('Sign-in link could not be verified. Please try logging in again.');
              return;
            }
            if (data.session) {
              await verifySupabaseUser(sb.auth, data.session.access_token);
              window.location.replace('/');
              return;
            }
            setError('Sign-in link was accepted but no session was created. Please try again.');
          } catch {
            setError('Sign-in link could not be verified. Please try logging in again.');
          }
          return;
        }
      }

      // ── Neither worked ──
      setError('No valid auth data found. Please try logging in again.');
    })();
  }, []);

  if (error) {
    return (
      <div style={{ padding: '2rem', fontFamily: 'monospace', background: '#0f0f23', minHeight: '100vh', color: '#ef4444' }}>
        <p>Auth Error: {error}</p>
        <Link href="/" style={{ color: '#6366f1' }}>← Zpět na přihlášení</Link>
      </div>
    );
  }

  return (
    <div style={{
      display: 'flex',
      flexDirection: 'column',
      justifyContent: 'center',
      alignItems: 'center',
      minHeight: '100vh',
      background: '#0f0f23',
      color: '#a5b4fc',
      fontFamily: 'monospace',
    }}>
      <p>Probíhá přihlašování...</p>
    </div>
  );
}

export default function CallbackPage() {
  return (
    <Suspense fallback={<div style={{ minHeight: '100vh', background: '#0f0f23' }} />}>
      <CallbackInner />
    </Suspense>
  );
}
