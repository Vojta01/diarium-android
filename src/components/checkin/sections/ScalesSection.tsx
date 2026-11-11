"use client";

import { useTranslation } from "@/lib/i18n";
import type { Scale } from "@/lib/scales";

import { CheckInSection } from "../CheckInSection";

/**
 * Škály — one native range input per scale plus an explicit clear.
 *
 * An unset scale renders the thumb at `min_value` but reports `—` and a
 * "tap to set" hint; clearing stores `0` (not a missing key) so the save route
 * can delete the mirrored `scale_entries` row. The numeric `value / max` label
 * and the clear button carry the state, so colour is never the only signal.
 */
export function ScalesSection({
  scales,
  values,
  loading,
  error,
  onChange,
  onClear,
  onRetry,
}: {
  scales: readonly Scale[];
  values: Record<string, number>;
  loading: boolean;
  /** i18n key for a failed scales read. */
  error: string | null;
  onChange: (scale: Scale, value: number) => void;
  onClear: (scale: Scale) => void;
  onRetry: () => void;
}) {
  const { t } = useTranslation();

  const sharedRange = (() => {
    if (scales.length === 0) return null;
    const first = scales[0];
    return scales.every(
      (scale) =>
        scale.min_value === first.min_value && scale.max_value === first.max_value
    )
      ? { min: first.min_value, max: first.max_value }
      : null;
  })();

  return (
    <CheckInSection
      id="scales"
      title={t("checkin.scales_title")}
      trailing={
        sharedRange ? (
          <span className="hidden text-[10px] text-[color:var(--text-3)] sm:inline">
            {t("checkin.scales_range", {
              min: sharedRange.min,
              max: sharedRange.max,
            })}
          </span>
        ) : undefined
      }
    >
      {loading ? (
        <p role="status" className="py-1 text-[12px] text-[color:var(--text-2)]">
          {t("common.loading")}
        </p>
      ) : error ? (
        <div role="alert" className="flex items-center justify-between gap-2">
          <span className="text-[12px] text-red-300">{t(error)}</span>
          <button
            type="button"
            onClick={onRetry}
            className="min-h-11 rounded-lg border border-white/10 px-3 text-[12px] text-[color:var(--text-1)] hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80"
          >
            {t("checkin.error_retry")}
          </button>
        </div>
      ) : scales.length === 0 ? (
        <p className="py-1 text-[12px] italic text-[color:var(--text-3)]">
          {t("checkin.scales_empty")}
        </p>
      ) : (
        <ul className="space-y-3">
          {scales.map((scale) => {
            const stored = values[scale.id] ?? 0;
            const set = stored > 0;
            const sliderValue = set ? stored : scale.min_value;
            const valueLabel = set ? `${stored} / ${scale.max_value}` : "—";
            return (
              <li key={scale.id} className="min-w-0">
                <div className="flex items-center gap-2">
                  <span aria-hidden="true" className="text-[16px] leading-5">
                    {scale.emoji}
                  </span>
                  <span className="min-w-0 flex-1 truncate text-[13px] text-[color:var(--text-1)]">
                    {scale.name}
                  </span>
                  <span
                    className={`shrink-0 text-[12px] tabular-nums ${
                      set
                        ? "text-[color:var(--text-1)]"
                        : "text-[color:var(--text-3)]"
                    }`}
                  >
                    {valueLabel}
                  </span>
                  {set ? (
                    <button
                      type="button"
                      onClick={() => onClear(scale)}
                      aria-label={t("checkin.scales_clear", { name: scale.name })}
                      className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg text-[13px] text-[color:var(--text-3)] transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80"
                    >
                      ✕
                    </button>
                  ) : (
                    <span className="h-11 w-11 shrink-0" aria-hidden="true" />
                  )}
                </div>
                <input
                  type="range"
                  min={scale.min_value}
                  max={scale.max_value}
                  step={1}
                  value={sliderValue}
                  onChange={(event) => onChange(scale, Number(event.target.value))}
                  aria-label={scale.name}
                  aria-valuetext={
                    set ? `${stored} / ${scale.max_value}` : t("checkin.scales_set_hint")
                  }
                  className={`checkin-range ${set ? "" : "opacity-70"}`}
                />
                {!set ? (
                  <p className="mt-0.5 text-[10px] text-[color:var(--text-3)]">
                    {t("checkin.scales_set_hint")}
                  </p>
                ) : null}
              </li>
            );
          })}
        </ul>
      )}
    </CheckInSection>
  );
}
