"use client";

import { useEffect, useRef, type ReactNode } from "react";

import { useTranslation } from "@/lib/i18n";
import { escapeBelongsToDialog } from "@/lib/modal-a11y";
import { ChevronLeftIcon } from "@/components/ui/icons";

interface SubScreenChromeProps {
  title: string;
  subtitle?: string;
  /** Returns to the parent tab (Android back behaviour). */
  onBack: () => void;
  trailing?: ReactNode;
  children: ReactNode;
}

/**
 * Chrome shared by every sub-screen that lives outside the bottom bar
 * (goals, scales, templates, achievements, reports, export).
 */
export function SubScreenChrome({
  title,
  subtitle,
  onBack,
  trailing,
  children,
}: SubScreenChromeProps) {
  const { t } = useTranslation();
  const backRef = useRef<HTMLButtonElement>(null);

  // Move focus into the sub-screen so keyboard and screen-reader users land on
  // its Back affordance instead of staying on the (now hidden) opener. The
  // shell restores that opener when Back closes the sub-screen.
  useEffect(() => {
    backRef.current?.focus({ preventScroll: true });
  }, []);

  // Android back parity: Escape leaves the sub-screen. Dialogs handle their
  // own Escape first and mark the event as handled, so they are skipped.
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || event.defaultPrevented) return;

      const target = event.target;
      const targetInsideDialog =
        target instanceof Element && Boolean(target.closest('[role="dialog"]'));
      const openModalCount = document.querySelectorAll(
        '[role="dialog"][aria-modal="true"]'
      ).length;
      if (escapeBelongsToDialog(targetInsideDialog, openModalCount)) return;

      event.preventDefault();
      onBack();
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onBack]);

  return (
    <section className="relative z-[1] pb-2">
      <div className="sticky top-0 z-20 -mx-4 -mt-3 mb-4 flex items-center gap-2 border-b border-white/5 bg-[#07070b]/90 px-4 pb-3 pt-3 backdrop-blur-xl">
        <button
          ref={backRef}
          type="button"
          onClick={onBack}
          aria-label={t("nav.back")}
          className="flex min-h-11 min-w-11 shrink-0 items-center justify-center rounded-xl border border-white/10 bg-white/5 text-white/70 transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/70"
        >
          <ChevronLeftIcon size={20} />
        </button>
        <div className="min-w-0 flex-1">
          <h1 className="text-[21px] font-semibold leading-7 text-[color:var(--text-1)]">
            {title}
          </h1>
          {subtitle ? (
            <p className="text-[13px] leading-[18px] text-[color:var(--text-2)]">
              {subtitle}
            </p>
          ) : null}
        </div>
        {trailing ? (
          <div className="flex shrink-0 items-center gap-1">{trailing}</div>
        ) : null}
      </div>
      {children}
    </section>
  );
}
