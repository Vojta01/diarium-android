"use client";

import type { ReactNode } from "react";

/**
 * A multi-select chip. `aria-pressed` carries the state; the selected style
 * adds a border and a check glyph so colour is never the only signal.
 */
export function Chip({
  pressed,
  onClick,
  children,
  icon,
  disabled = false,
}: {
  pressed: boolean;
  onClick: () => void;
  children: ReactNode;
  icon?: ReactNode;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      disabled={disabled}
      onClick={onClick}
      className={`inline-flex min-h-11 min-w-[64px] items-center justify-center gap-1.5 rounded-full border px-3 py-1.5 text-[12px] leading-4 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80 focus-visible:ring-offset-2 focus-visible:ring-offset-[#07070b] disabled:opacity-50 ${
        pressed
          ? "border-indigo-400/60 bg-indigo-400/15 text-[color:var(--text-1)]"
          : "border-white/10 bg-white/[0.04] text-[color:var(--text-2)] hover:bg-white/[0.08]"
      }`}
    >
      {icon ? (
        <span aria-hidden="true" className="text-[15px] leading-5">
          {icon}
        </span>
      ) : null}
      <span className="min-w-0 truncate">{children}</span>
      {pressed ? <span className="text-[11px] text-indigo-300">✓</span> : null}
    </button>
  );
}

/**
 * A switch. The knob position, `aria-checked` and the caller's text badge all
 * change together, so the state is not conveyed by colour alone.
 */
export function Toggle({
  checked,
  onChange,
  label,
  disabled = false,
}: {
  checked: boolean;
  onChange: () => void;
  /** Accessible name; the row's visible label is a sibling, not a parent. */
  label: string;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={onChange}
      className={`relative h-11 w-[52px] shrink-0 rounded-full border transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80 focus-visible:ring-offset-2 focus-visible:ring-offset-[#07070b] disabled:opacity-50 ${
        checked
          ? "border-emerald-400/50 bg-emerald-500/50"
          : "border-white/10 bg-white/[0.08]"
      }`}
    >
      <span
        aria-hidden="true"
        className={`absolute top-1/2 h-6 w-6 -translate-y-1/2 rounded-full bg-white shadow transition-transform duration-200 motion-reduce:transition-none ${
          checked ? "translate-x-[24px]" : "translate-x-[6px]"
        }`}
      />
    </button>
  );
}
