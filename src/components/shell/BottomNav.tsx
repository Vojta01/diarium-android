"use client";

import type { ComponentType, KeyboardEvent } from "react";
import { useRef } from "react";

import { useTranslation } from "@/lib/i18n";
import {
  MAIN_TABS,
  TAB_LABEL_KEYS,
  shellPanelId,
  type MainTab,
} from "@/lib/navigation";
import {
  ChartIcon,
  HistoryIcon,
  HomeIcon,
  PenIcon,
  SettingsIcon,
  type IconProps,
} from "@/components/ui/icons";

const TAB_ICONS: Record<MainTab, ComponentType<IconProps>> = {
  overview: HomeIcon,
  today: PenIcon,
  history: HistoryIcon,
  stats: ChartIcon,
  settings: SettingsIcon,
};

interface BottomNavProps {
  /** Active destination, or null while a sub-screen is open (capsule hidden). */
  active: MainTab | null;
  onSelect: (tab: MainTab) => void;
}

/**
 * Fixed five-destination bottom bar matching Android beta5: icon + label,
 * indigo capsule on the selected destination, safe-area aware.
 */
export function BottomNav({ active, onSelect }: BottomNavProps) {
  const { t } = useTranslation();
  const tabRefs = useRef<Partial<Record<MainTab, HTMLButtonElement | null>>>({});

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const keys = ["ArrowLeft", "ArrowRight", "Home", "End"];
    if (!keys.includes(event.key)) return;
    event.preventDefault();

    const current = active ?? MAIN_TABS[0];
    const index = MAIN_TABS.indexOf(current);
    let nextIndex = index;
    if (event.key === "ArrowLeft") nextIndex = (index - 1 + MAIN_TABS.length) % MAIN_TABS.length;
    if (event.key === "ArrowRight") nextIndex = (index + 1) % MAIN_TABS.length;
    if (event.key === "Home") nextIndex = 0;
    if (event.key === "End") nextIndex = MAIN_TABS.length - 1;

    const next = MAIN_TABS[nextIndex];
    onSelect(next);
    tabRefs.current[next]?.focus();
  };

  return (
    <nav aria-label={t("nav.primary")} className="fixed inset-x-0 bottom-0 z-40">
      <div aria-hidden="true" className="nav-shell__hairline" />
      <div className="nav-shell pb-[env(safe-area-inset-bottom)]">
        <div
          role="tablist"
          aria-label={t("nav.primary")}
          aria-orientation="horizontal"
          onKeyDown={handleKeyDown}
          className="mx-auto flex w-full max-w-[620px] items-stretch gap-0.5 px-2 pt-1.5"
        >
          {MAIN_TABS.map((tab) => {
            const selected = tab === active;
            const Icon = TAB_ICONS[tab];
            const label = t(TAB_LABEL_KEYS[tab]);

            return (
              <button
                key={tab}
                ref={(element) => {
                  tabRefs.current[tab] = element;
                }}
                type="button"
                role="tab"
                aria-selected={selected}
                aria-controls={shellPanelId(tab)}
                tabIndex={selected || (active === null && tab === MAIN_TABS[0]) ? 0 : -1}
                onClick={() => onSelect(tab)}
                className="flex min-h-[62px] flex-1 items-center justify-center rounded-2xl px-1 py-1.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/70"
              >
                <span className={`nav-tab ${selected ? "nav-tab--selected" : ""}`}>
                  <Icon size={22} strokeWidth={selected ? 2 : 1.75} />
                  <span className="text-[11px] font-medium leading-[15px] tracking-[0.2px]">
                    {label}
                  </span>
                </span>
              </button>
            );
          })}
        </div>
      </div>
    </nav>
  );
}
