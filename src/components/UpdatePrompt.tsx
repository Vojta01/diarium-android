"use client";

import { useEffect, useState } from "react";

/**
 * Detects when a new version of the PWA is available and shows an update prompt.
 * Uses the service worker's updatefound event — no polling needed.
 */
export function UpdatePrompt() {
  const [updateAvailable, setUpdateAvailable] = useState(false);
  const [waitingWorker, setWaitingWorker] = useState<ServiceWorker | null>(null);

  useEffect(() => {
    if (typeof window === "undefined" || !("serviceWorker" in navigator)) return;

    let swRegistration: ServiceWorkerRegistration | null = null;

    const onUpdateFound = () => {
      const installing = swRegistration?.installing;
      if (!installing) return;

      installing.onstatechange = () => {
        if (installing.state === "installed" && navigator.serviceWorker.controller) {
          // New SW is waiting — show prompt
          setUpdateAvailable(true);
          setWaitingWorker(installing);
        }
      };
    };

    navigator.serviceWorker.ready.then((reg) => {
      swRegistration = reg;
      reg.addEventListener("updatefound", onUpdateFound);

      // Also check if there's already a waiting worker
      if (reg.waiting) {
        setUpdateAvailable(true);
        setWaitingWorker(reg.waiting);
      }
    });

    // Periodically check for updates (every 30 min when app is open)
    const interval = setInterval(() => {
      swRegistration?.update().catch(() => {});
    }, 30 * 60 * 1000);

    return () => {
      clearInterval(interval);
      swRegistration?.removeEventListener("updatefound", onUpdateFound);
    };
  }, []);

  const handleUpdate = () => {
    if (waitingWorker) {
      waitingWorker.postMessage({ type: "SKIP_WAITING" });
    }
    // Reload after the new SW takes over
    window.location.reload();
  };

  if (!updateAvailable) return null;

  return (
    <div className="fixed bottom-[calc(84px+env(safe-area-inset-bottom))] left-4 right-4 z-50 animate-slide-up">
      <div className="glass-card border-indigo-400/30 bg-indigo-950/90 p-4 flex items-center gap-3">
        <span className="text-2xl shrink-0">🔄</span>
        <div className="flex-1 min-w-0">
          <p className="text-white text-sm font-medium">Nová verze k dispozici</p>
          <p className="text-white/40 text-xs mt-0.5">
            Aktualizuj pro nejnovější funkce
          </p>
        </div>
        <button
          onClick={handleUpdate}
          className="shrink-0 bg-indigo-500 hover:bg-indigo-400 text-white px-4 py-2 rounded-xl text-sm font-medium transition-colors cursor-pointer"
        >
          Aktualizovat
        </button>
      </div>
    </div>
  );
}
