"use client";

import { useState, type ReactNode } from "react";

/**
 * Accessible collapsible section card (Android beta5 hierarchy).
 *
 * The body stays mounted while collapsed so a half-written field is never
 * discarded by toggling a section; `hidden` + `aria-expanded` keep the state
 * machine honest for assistive technology.
 */
export function checkInSectionTitleId(id: string): string {
  return `checkin-${id}-title`;
}

export function checkInSectionBodyId(id: string): string {
  return `checkin-${id}-body`;
}

export function CheckInSection({
  id,
  title,
  trailing,
  children,
  defaultOpen = true,
  className = "",
}: {
  /** Stable section id; also the `data-section` hook the structure test reads. */
  id: string;
  title: string;
  /** Header actions. Siblings of the toggle button, never nested inside it. */
  trailing?: ReactNode;
  children: ReactNode;
  defaultOpen?: boolean;
  className?: string;
}) {
  const [open, setOpen] = useState(defaultOpen);
  const titleId = checkInSectionTitleId(id);
  const bodyId = checkInSectionBodyId(id);

  return (
    <section
      data-section={id}
      aria-labelledby={titleId}
      className={`checkin-section ${className}`}
    >
      <div className="flex items-start gap-2 px-3.5 pt-1 sm:px-4">
        <h2 id={titleId} className="min-w-0 flex-1">
          <button
            type="button"
            aria-expanded={open}
            aria-controls={bodyId}
            onClick={() => setOpen((value) => !value)}
            className="flex min-h-11 w-full items-center gap-2.5 rounded-lg text-left text-[13px] font-semibold text-[color:var(--text-1)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80 focus-visible:ring-offset-2 focus-visible:ring-offset-[#07070b]"
          >
            <span aria-hidden="true" className="checkin-section__bar" />
            <span className="min-w-0 flex-1 truncate">{title}</span>
            <span
              aria-hidden="true"
              className={`shrink-0 text-[11px] text-[color:var(--text-3)] transition-transform duration-200 motion-reduce:transition-none ${
                open ? "rotate-180" : ""
              }`}
            >
              ▼
            </span>
          </button>
        </h2>
        {trailing ? (
          <div className="flex shrink-0 items-center gap-1 pt-1">{trailing}</div>
        ) : null}
      </div>
      <div
        id={bodyId}
        hidden={!open}
        className="px-3.5 pb-3.5 pt-0.5 sm:px-4 sm:pb-4"
      >
        {children}
      </div>
    </section>
  );
}
