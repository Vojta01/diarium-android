"use client";

import { useTranslation } from "@/lib/i18n";

const OPTIONS = [
  { value: "cs", code: "CS", label: "Čeština", flag: "🇨🇿", switchKey: "language.switch_to_cs" },
  { value: "en", code: "EN", label: "English", flag: "🇬🇧", switchKey: "language.switch_to_en" },
] as const;

/**
 * Language choice.
 *
 * `full` is the Settings card: labelled Čeština / English options, 44 px,
 * `aria-pressed` and a visible focus ring. `compact` is the header control:
 * same 44 px target but a two-letter label so three header actions still fit
 * at 320 px. Switching never reloads and `diarium-lang` persistence lives in
 * the i18n provider.
 */
export function LanguageSwitcher({ variant = "compact" }: { variant?: "compact" | "full" }) {
  const { lang, setLang, t } = useTranslation();
  const full = variant === "full";

  return (
    <div
      role="group"
      aria-label={t("settings.language")}
      className={full ? "flex gap-2" : "flex items-center gap-0.5"}
    >
      {OPTIONS.map((option) => {
        const active = lang === option.value;
        return (
          <button
            key={option.value}
            type="button"
            onClick={() => setLang(option.value)}
            aria-pressed={active}
            aria-label={t(option.switchKey)}
            title={t(option.switchKey)}
            className={
              full
                ? `flex min-h-11 flex-1 items-center justify-center gap-2 rounded-xl border px-3 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/70 ${
                    active
                      ? "border-indigo-400/40 bg-indigo-500/25 text-indigo-100"
                      : "border-white/15 text-[color:var(--text-1)] hover:bg-white/5"
                  }`
                : `flex min-h-11 min-w-11 items-center justify-center rounded-xl px-1 text-[11px] font-semibold tracking-wide transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/70 ${
                    active
                      ? "bg-indigo-500/25 text-indigo-100"
                      : "text-[color:var(--text-2)] hover:bg-white/5 hover:text-[color:var(--text-1)]"
                  }`
            }
          >
            <span aria-hidden="true" className={full ? "text-base" : "sr-only"}>
              {option.flag}
            </span>
            <span>{full ? option.label : option.code}</span>
          </button>
        );
      })}
    </div>
  );
}
