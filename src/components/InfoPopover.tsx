"use client";

import { useState } from "react";

/**
 * Reusable floating legend popover. Renders an "ⓘ" button that toggles a
 * floating, dismissible window explaining the chart it sits next to.
 */
export function InfoPopover({ text, side = "right" }: { text: string; side?: "left" | "right" }) {
  const [open, setOpen] = useState(false);

  return (
    <span className="relative inline-flex shrink-0">
      <button
        onClick={(e) => {
          e.stopPropagation();
          setOpen((v) => !v);
        }}
        className={`w-5 h-5 rounded-full bg-white/5 border border-white/10 text-white/40 hover:text-white hover:bg-white/15 text-[10px] font-medium flex items-center justify-center transition-colors ${
          open ? "text-indigo-300 border-indigo-400/40 bg-indigo-500/10" : ""
        }`}
        aria-label="Legenda"
      >
        ⓘ
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-30" onClick={() => setOpen(false)} />
          <div
            className={`absolute top-7 z-40 w-60 p-3 rounded-xl bg-[#151528]/95 border border-white/15 text-xs text-white/70 leading-relaxed shadow-2xl backdrop-blur-xl animate-slide-up ${
              side === "right" ? "right-0" : "left-0"
            }`}
          >
            {text}
          </div>
        </>
      )}
    </span>
  );
}
