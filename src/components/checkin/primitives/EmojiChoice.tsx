"use client";

import { useRef, type KeyboardEvent } from "react";

import type { EmojiOption } from "@/lib/checkin/options";

/** An emoji option with its already-translated visible label. */
export interface EmojiChoiceOption extends EmojiOption {
  label: string;
}


/**
 * A radio group rendered as emoji cells.
 *
 * Native radio inputs would be the smallest implementation, but the visual is a
 * button grid with an emoji and a label; `role="radio"` + roving tabindex keeps
 * arrow-key semantics without nesting inputs inside buttons. Selection follows
 * focus, matching platform radio behaviour.
 */
export function EmojiChoiceGroup({
  labelId,
  options,
  value,
  onChange,
  columns,
  tone = "indigo",
}: {
  /** id of the visible section title that names the group. */
  labelId: string;
  options: readonly EmojiChoiceOption[];
  value: number;
  onChange: (value: number, emoji: string) => void;
  columns: 3 | 5;
  tone?: "indigo" | "orange";
}) {
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const selectedIndex = options.findIndex((option) => option.value === value);
  // No answer yet: the first cell is the single tab stop.
  const tabStop = selectedIndex >= 0 ? selectedIndex : 0;

  const focusIndex = (index: number) => {
    const bounded = (index + options.length) % options.length;
    refs.current[bounded]?.focus();
    onChange(options[bounded].value, options[bounded].emoji);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const current = options.findIndex((option) => option.value === value);
    const index = current >= 0 ? current : 0;
    switch (event.key) {
      case "ArrowRight":
      case "ArrowDown":
        event.preventDefault();
        focusIndex(index + 1);
        break;
      case "ArrowLeft":
      case "ArrowUp":
        event.preventDefault();
        focusIndex(index - 1);
        break;
      case "Home":
        event.preventDefault();
        focusIndex(0);
        break;
      case "End":
        event.preventDefault();
        focusIndex(options.length - 1);
        break;
      default:
        break;
    }
  };

  const selectedClasses =
    tone === "orange"
      ? "border-orange-400/70 bg-orange-400/15"
      : "border-indigo-400/70 bg-indigo-400/15";

  return (
    <div
      role="radiogroup"
      aria-labelledby={labelId}
      onKeyDown={onKeyDown}
      className={`grid gap-1.5 ${
        columns === 5
          ? "grid-cols-5 sm:max-w-[640px]"
          : "grid-cols-3 sm:max-w-[420px]"
      }`}
    >
      {options.map((option, index) => {
        const selected = option.value === value;
        return (
          <button
            key={option.value}
            ref={(element) => {
              refs.current[index] = element;
            }}
            type="button"
            role="radio"
            aria-checked={selected}
            tabIndex={index === tabStop ? 0 : -1}
            onClick={() => onChange(option.value, option.emoji)}
            className={`relative flex min-h-[62px] min-w-0 flex-col items-center justify-center gap-0.5 rounded-xl border px-1 py-1.5 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80 focus-visible:ring-offset-2 focus-visible:ring-offset-[#07070b] ${
              selected
                ? selectedClasses
                : "border-white/[0.06] bg-white/[0.035] hover:bg-white/[0.07]"
            }`}
            style={
              selected && option.color
                ? { borderColor: `${option.color}b3` }
                : undefined
            }
          >
            <span aria-hidden="true" className="text-[26px] leading-8">
              {option.emoji}
            </span>
            <span
              className={`w-full truncate text-center text-[10px] leading-3 ${
                selected ? "text-[color:var(--text-1)]" : "text-[color:var(--text-2)]"
              }`}
            >
              {option.label}
            </span>
            {selected ? (
              <span
                aria-hidden="true"
                className={`absolute right-1 top-1 flex h-3.5 w-3.5 items-center justify-center rounded-full text-[9px] font-bold leading-none text-white ${
                  tone === "orange" ? "bg-orange-500" : "bg-indigo-500"
                }`}
              >
                ✓
              </span>
            ) : null}
          </button>
        );
      })}
    </div>
  );
}
