"use client";

import { useState } from "react";
import { useTranslation } from "@/lib/i18n";

/**
 * Curated emoji catalog for goals + habits.
 * Ordered so the most useful entries for a daily tracker come first —
 * a goal like "Krátké cvičení" should be pickable in one tap.
 */
const EMOJI_GROUPS: { id: string; emojis: string[] }[] = [
  {
    id: "sport",
    emojis: [
      "🏋️", "🏃", "🚶", "🚴", "🏊", "🧘", "🤸", "💪",
      "⚽", "🏀", "🎾", "🏸", "🥊", "🛹", "⛷️", "🏔️",
      "🚣", "🧗", "🚵", "🏓", "⛹️", "🤾", "🥾", "🤽",
    ],
  },
  {
    id: "zdravi",
    emojis: [
      "💧", "🥗", "🥦", "🍎", "😴", "🛌", "☀️", "🌙",
      "💊", "🩺", "🧠", "🦷", "🚿", "🧴", "🫀", "🩸",
      "🫁", "🧘‍♂️", "🍵", "⏰", "🛡️", "🫶", "🔋", "♻️",
    ],
  },
  {
    id: "jidlo",
    emojis: [
      "🍺", "🍷", "🥤", "☕", "🍰", "🍫", "🍬", "🍕",
      "🍔", "🥩", "🍟", "🍩", "🚬", "💨", "🧃", "🍽️",
      "🥑", "🍌", "🥚", "🍚", "🚫", "🚫🍺", "🚫🍰", "🚫🥤",
    ],
  },
  {
    id: "prace",
    emojis: [
      "🎯", "💻", "📚", "✍️", "🗓️", "✅", "📈", "📝",
      "🧑‍💻", "💡", "🗣️", "🎓", "💰", "📊", "🔍", "⏳",
      "📬", "🗂️", "🖥️", "🧪", "🔧", "📌", "🏆", "🚀",
    ],
  },
  {
    id: "relax",
    emojis: [
      "🌿", "🎧", "🎮", "📱", "📖", "🎨", "🎸", "🎬",
      "👥", "❤️", "🙏", "🧩", "🚴‍♂️", "🏕️", "🌊", "🎲",
      "🐶", "🐱", "🛁", "🕯️", "🛒", "🧹", "🚗", "✈️",
    ],
  },
  {
    id: "ostatni",
    emojis: [
      "🔥", "⭐", "✨", "💎", "🌟", "🦉", "🍀", "⛅",
      "🌍", "🎉", "🤝", "🗿", "🧭", "🕹️", "🛠️", "📦",
      "🔒", "🔔", "🎁", "🧾", "🧊", "🌱", "🐝", "🦾",
    ],
  },
];

interface EmojiPickerProps {
  value: string;
  onChange: (emoji: string) => void;
  /** Label rendered above the grid. Defaults to the shared "Icon" label. */
  label?: string;
}

/**
 * Visual icon picker used by the goal and habit dialogs.
 * Tapping an emoji selects it immediately; the free-text field at the bottom
 * is there for anything outside the curated set (works on mobile keyboards,
 * where JS `prompt()` emoji input is painful).
 */
export function EmojiPicker({ value, onChange, label }: EmojiPickerProps) {
  const { t } = useTranslation();
  const [custom, setCustom] = useState("");

  const applyCustom = () => {
    const next = custom.trim();
    if (!next) return;
    onChange(next);
    setCustom("");
  };

  return (
    <div>
      <div className="flex items-baseline justify-between mb-1">
        <span className="block text-[11px] text-white/40 uppercase tracking-wider">
          {label ?? t("emojiPicker.label")}
        </span>
        <span className="text-xl leading-none">{value || "—"}</span>
      </div>

      <div className="max-h-52 overflow-y-auto rounded-xl bg-white/5 border border-white/10 p-2 space-y-3">
        {EMOJI_GROUPS.map(group => (
          <div key={group.id}>
            <p className="text-[10px] uppercase tracking-wider text-white/25 mb-1">
              {t(`emojiPicker.groups.${group.id}`)}
            </p>
            <div className="grid grid-cols-8 gap-1">
              {group.emojis.map(emoji => {
                const selected = value === emoji;
                return (
                  <button
                    key={emoji}
                    type="button"
                    onClick={() => onChange(emoji)}
                    aria-label={emoji}
                    className={`h-9 rounded-lg text-lg leading-none flex items-center justify-center transition-all ${
                      selected
                        ? "bg-indigo-500/30 ring-1 ring-indigo-400"
                        : "hover:bg-white/10 active:scale-95"
                    }`}
                  >
                    {emoji}
                  </button>
                );
              })}
            </div>
          </div>
        ))}
      </div>

      <div className="flex gap-2 mt-2">
        <input
          value={custom}
          onChange={e => setCustom(e.target.value)}
          onKeyDown={e => {
            if (e.key === "Enter") {
              e.preventDefault();
              applyCustom();
            }
          }}
          placeholder={t("emojiPicker.custom_placeholder")}
          maxLength={8}
          className="flex-1 min-w-0 bg-white/5 border border-white/10 rounded-lg px-3 py-1.5 text-sm text-white placeholder:text-white/15 focus:outline-none focus:border-indigo-400/50"
        />
        <button
          type="button"
          onClick={applyCustom}
          disabled={!custom.trim()}
          className="px-3 py-1.5 rounded-lg bg-white/10 text-white/70 text-xs font-medium hover:bg-white/20 transition-colors disabled:opacity-30"
        >
          {t("emojiPicker.use")}
        </button>
      </div>
    </div>
  );
}
