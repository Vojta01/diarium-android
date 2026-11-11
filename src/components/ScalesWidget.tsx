"use client";

import { useState, useEffect, useCallback } from "react";
import { getScaleEntries, getScaleAverage, getScalesOrThrow, seedDefaultScales, type Scale, type ScaleEntry } from "@/lib/scales";
import { useTranslation } from "@/lib/i18n";
import { pragueDateKey, shiftDateKey } from "@/lib/date";
import { useDataRefresh } from "@/lib/use-data-refresh";

interface ScalesWidgetProps {
  todayScaleValues?: Record<string, number>;
}

const DAYS = 7;

export function ScalesWidget({ todayScaleValues }: ScalesWidgetProps) {
  const { t, lang } = useTranslation();
  const [scales, setScales] = useState<Scale[]>([]);
  const [entries, setEntries] = useState<Record<string, ScaleEntry[]>>({});
  const [averages, setAverages] = useState<Record<string, number>>({});
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);

  const loadScales = useCallback(async () => {
    setLoading(true);
    setLoadError(false);
    try {
      const existing = await getScalesOrThrow();
      // Seeding is additive and only for an account with no scales at all; a
      // failed read rejects above instead of creating defaults.
      const data = existing.length === 0 ? await seedDefaultScales() : existing;
      setScales(data);

      const entriesMap: Record<string, ScaleEntry[]> = {};
      const avgMap: Record<string, number> = {};

      for (const scale of data) {
        try {
          entriesMap[scale.id] = await getScaleEntries(scale.id, DAYS);
          avgMap[scale.id] = await getScaleAverage(scale.id, DAYS);
        } catch {}
      }

      setEntries(entriesMap);
      setAverages(avgMap);
    } catch (e) {
      console.error("Failed to load scales:", e);
      setScales([]);
      setLoadError(true);
    }
    setLoading(false);
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async fetch resolves after the effect body
    loadScales();
  }, [loadScales]);

  useDataRefresh(["entries", "scales"], loadScales);

  if (loading) {
    return (
      <div className="glass-card p-4" role="status" aria-live="polite">
        <div className="text-white/40 text-sm">{t("common.loading")}</div>
      </div>
    );
  }

  if (loadError) {
    return (
      <div className="glass-card p-4" role="alert">
        <h3 className="text-lg font-semibold text-white mb-2">
          📊 {t("scales.title")}
        </h3>
        <p className="text-sm font-medium text-[color:var(--text-1)]">
          {t("screens.scales.error_title")}
        </p>
        <p className="mt-1 text-xs leading-5 text-white/50">
          {t("screens.scales.error_hint")}
        </p>
        <button
          type="button"
          onClick={() => void loadScales()}
          className="mt-3 min-h-11 rounded-xl border border-white/10 bg-white/5 px-4 text-sm font-medium text-white/80 transition-colors hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/70"
        >
          {t("screens.scales.retry")}
        </button>
      </div>
    );
  }

  if (scales.length === 0) {
    return (
      <div className="glass-card p-4">
        <h3 className="text-lg font-semibold text-white mb-2">📊 {t("scales.title")}</h3>
        <p className="text-white/40 text-sm">{t("scales.no_scales")}</p>
      </div>
    );
  }

  const todayKey = pragueDateKey();
  const days = Array.from({ length: DAYS }, (_, i) => shiftDateKey(todayKey, i - (DAYS - 1)));

  return (
    <div className="glass-card p-4">
      <h3 className="text-lg font-semibold text-white mb-1">📊 {t("scales.title")}</h3>
      <p className="text-xs text-white/40 mb-3">{t("scales.last_7_days")}</p>

      <div className="space-y-3">
        {scales.map((scale) => {
          const scaleEntries = entries[scale.id] || [];
          const avg = averages[scale.id] || 0;
          const todayValue = todayScaleValues?.[scale.id];
          const maxVal = scale.max_value;
          const minVal = scale.min_value;
          const range = maxVal - minVal || 1;

          // Build day → value map
          const byDate: Record<string, number> = {};
          scaleEntries.forEach((e) => {
            byDate[e.date] = e.value;
          });

          return (
            <div key={scale.id} className="rounded-xl bg-white/5 p-3">
              <div className="flex items-center justify-between mb-2">
                <div className="flex items-center gap-2">
                  <span className="text-lg">{scale.emoji}</span>
                  <span className="text-sm font-medium text-white">{scale.name}</span>
                  {scale.unit && (
                    <span className="text-xs text-white/40">{scale.unit}</span>
                  )}
                </div>
                <div className="flex items-center gap-2">
                  {todayValue !== undefined && todayValue > 0 && (
                    <span
                      className="text-sm font-bold px-2 py-0.5 rounded-lg"
                      style={{
                        color: scale.color,
                        backgroundColor: `${scale.color}20`,
                      }}
                    >
                      {todayValue}
                    </span>
                  )}
                  {avg > 0 && (
                    <span className="text-xs text-white/40">
                      Ø {avg.toFixed(1)}
                    </span>
                  )}
                </div>
              </div>

              {/* Last 7 days bar chart */}
              <div className="flex items-end gap-1.5">
                {days.map((day) => {
                  const key = day;
                  const value = byDate[key];
                  const hasValue = typeof value === "number" && value > 0;
                  const normalized = hasValue ? (value - minVal) / range : 0;
                  const height = hasValue ? Math.max(12, normalized * 100) : 8;
                  const isToday = key === todayKey;
                  const dayLabel = new Date(`${key}T12:00:00Z`).toLocaleDateString(
                    lang === "cs" ? "cs-CZ" : "en-US",
                    { weekday: "short", timeZone: "UTC" }
                  );

                  return (
                    <div
                      key={key}
                      className="flex-1 flex flex-col items-center gap-1"
                      title={`${key}: ${hasValue ? value : "—"}`}
                    >
                      <span
                        className="text-[10px] font-bold leading-none"
                        style={{ color: hasValue ? scale.color : "transparent" }}
                      >
                        {hasValue ? value : "·"}
                      </span>
                      <div
                        className="w-full rounded-t transition-all duration-300"
                        style={{
                          height: `${height}px`,
                          minHeight: 4,
                          backgroundColor: hasValue
                            ? isToday
                              ? scale.color
                              : `${scale.color}80`
                            : "rgba(255,255,255,0.08)",
                          boxShadow: isToday && hasValue ? `0 0 8px ${scale.color}60` : "none",
                        }}
                      />
                      <span
                        className={`text-[9px] leading-none ${
                          isToday ? "text-white/80 font-semibold" : "text-white/30"
                        }`}
                      >
                        {dayLabel}
                      </span>
                    </div>
                  );
                })}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
