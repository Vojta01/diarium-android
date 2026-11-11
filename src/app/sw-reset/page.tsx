'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';

/**
 * SW Reset / Self-healing kill-switch page.
 *
 * Minimal, dependency-free page that:
 * 1. Unregisters ALL service worker registrations
 * 2. Deletes ALL caches
 * 3. Shows a loading message in Czech
 * 4. Redirects to / with a full page load
 *
 * Must load fast even when things are broken — NO heavy imports.
 */

export default function SwResetPage() {
  const [status, setStatus] = useState('Obnovuji aplikaci…');

  useEffect(() => {
    let cancelled = false;

    (async () => {
      // ── Step 1: Unregister all service workers ──
      if ('serviceWorker' in navigator) {
        try {
          // Message the active SW to unregister itself first
          const regs = await navigator.serviceWorker.getRegistrations();
          for (const reg of regs) {
            if (reg.active) {
              reg.active.postMessage({ type: 'UNREGISTER_SELF' });
            }
          }

          // Then unregister every registration
          for (const reg of regs) {
            await reg.unregister();
          }
          if (regs.length > 0) {
            setStatus('Odpojuji service worker…');
          }
        } catch (e) {
          console.warn('[SW-Reset] Failed to unregister SW:', e);
        }
      }

      // ── Step 2: Clear all caches ──
      if ('caches' in window) {
        try {
          const keys = await caches.keys();
          await Promise.all(keys.map((k) => caches.delete(k)));
          if (keys.length > 0) {
            setStatus('Mažu mezipaměť…');
          }
        } catch (e) {
          console.warn('[SW-Reset] Failed to delete caches:', e);
        }
      }

      // ── Step 3: Redirect — full page load ──
      if (!cancelled) {
        window.location.replace('/');
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: '100vh',
        background: '#0f0f23',
        color: '#a5b4fc',
        fontFamily: 'system-ui, -apple-system, sans-serif',
        textAlign: 'center',
        padding: '2rem',
      }}
    >
      <div
        style={{
          width: 32,
          height: 32,
          border: '3px solid rgba(165,180,252,0.2)',
          borderTopColor: '#6366f1',
          borderRadius: '50%',
          animation: 'sw-spin 0.8s linear infinite',
          margin: '0 auto 1.5rem',
        }}
      />
      <style>{`@keyframes sw-spin { to { transform: rotate(360deg); } }`}</style>
      <p>{status}</p>
      <p
        style={{
          fontSize: '0.8rem',
          color: 'rgba(165,180,252,0.5)',
          marginTop: '0.75rem',
        }}
      >
        Pokud se nic neděje,{' '}
        <Link href="/" style={{ color: '#6366f1' }}>
          klikni sem
        </Link>
        .
      </p>
    </div>
  );
}
