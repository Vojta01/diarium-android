"use client";

import { DASHBOARD_ENTRY_ROWS } from "@/lib/entries";
import { useTranslation } from "@/lib/i18n";
import type { SubScreen } from "@/lib/navigation";

import { ACCENT, Card } from "./primitives";

/**
 * The final Overview card: one glass row per real destination.
 *
 * It renders no data of its own, so it is equally valid while the rest of the
 * dashboard is loading, empty or failed. Every row is a button; the shell
 * remembers it as the focus opener and restores it on Back.
 */
export function EntriesCard({
  onOpenSub,
}: {
  onOpenSub: (sub: SubScreen) => void;
}) {
  const { t } = useTranslation();

  return (
    <Card
      cardId="entries"
      title={t("dashboard.entries.title")}
      accent={ACCENT.violet}
      className="h-full"
    >
      <ul className="mt-2 divide-y divide-white/[0.06]">
        {DASHBOARD_ENTRY_ROWS.map((row) => {
          const label = t(row.labelKey);
          const hint = t(row.hintKey);
          return (
            <li key={row.sub}>
              <button
                type="button"
                data-entry={row.sub}
                onClick={() => onOpenSub(row.sub)}
                aria-label={t("dashboard.entries.open", { section: label })}
                className="group flex min-h-[58px] w-full items-center gap-3 rounded-xl px-1 py-2.5 text-left transition-colors hover:bg-white/[0.05] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80 focus-visible:ring-offset-2 focus-visible:ring-offset-[#07070b]"
              >
                <span
                  aria-hidden="true"
                  className="flex size-10 shrink-0 items-center justify-center rounded-xl border border-white/10 bg-white/[0.05] text-[19px] leading-none"
                >
                  {row.emoji}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[14px] font-semibold leading-5 text-[color:var(--text-1)]">
                    {label}
                  </span>
                  <span className="mt-0.5 block truncate text-[12px] leading-[18px] text-[color:var(--text-2)]">
                    {hint}
                  </span>
                </span>
                <span
                  aria-hidden="true"
                  className="shrink-0 pr-1 text-[22px] leading-none text-[color:var(--text-3)] transition-transform group-hover:translate-x-0.5 motion-reduce:transform-none"
                >
                  ›
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </Card>
  );
}
