"use client";

import type { ReactNode } from "react";

/**
 * One Android-style Settings row: emoji, title, one-line hint, chevron.
 * Always a real 44 px button — never a disabled row that only looks tappable.
 */
export function SettingsRow({
  emoji,
  title,
  hint,
  onClick,
  trailing,
}: {
  emoji: string;
  title: string;
  hint?: string;
  onClick: () => void;
  trailing?: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex min-h-11 w-full items-center gap-3 rounded-xl px-1 py-2 text-left transition-colors hover:bg-white/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/70"
    >
      <span aria-hidden="true" className="w-6 shrink-0 text-center text-lg">
        {emoji}
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-sm font-medium text-[color:var(--text-1)]">
          {title}
        </span>
        {hint ? (
          <span className="mt-0.5 block text-xs leading-5 text-[color:var(--text-2)]">
            {hint}
          </span>
        ) : null}
      </span>
      {trailing ?? (
        <span aria-hidden="true" className="shrink-0 text-[color:var(--text-2)]">
          ›
        </span>
      )}
    </button>
  );
}
