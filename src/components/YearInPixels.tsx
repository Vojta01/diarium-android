"use client";

import { useState, useEffect, useMemo, useCallback } from "react";
import { fetchDailyEntries, MOOD_EMOJIS, type DailyEntry } from "@/lib/stats";
import { averageMood, CHART_MOOD_COLORS, recordedMoods } from "@/lib/mood";
import { pragueDateKey, pragueYear } from "@/lib/date";
import { monthNames, weekdayFullNames, weekdayNames } from "@/lib/calendar-labels";
import { useTranslation } from "@/lib/i18n";
import { useDataRefresh } from "@/lib/use-data-refresh";

export function YearInPixels() {
  const { t, lang } = useTranslation();
  const [entries, setEntries] = useState<DailyEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedYear, setSelectedYear] = useState(() => pragueYear());
  const [hoveredDay, setHoveredDay] = useState<string | null>(null);

  const months = monthNames(lang, selectedYear);
  const weekdays = weekdayNames(lang);
  const fullWeekdays = weekdayFullNames(lang);

  const load = useCallback(() => {
    fetchDailyEntries()
      .then(data => { setEntries(data); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  useDataRefresh(["entries"], load);

  const moodMap = useMemo(() => {
    const map: Record<string, number> = {};
    entries.forEach(e => { map[e.date] = e.mood; });
    return map;
  }, [entries]);

  const noteMap = useMemo(() => {
    const map: Record<string, string> = {};
    entries.forEach(e => { if (e.note) map[e.date] = e.note; });
    return map;
  }, [entries]);

  // Build each month as a matrix: rows × 7 columns
  const monthsData = useMemo(() => {
    const result: { name: string; index: number; weeks: { date: string; day: number; mood: number; note?: string }[][] }[] = [];

    for (let m = 0; m < 12; m++) {
      const firstDay = new Date(selectedYear, m, 1);
      const daysInMonth = new Date(selectedYear, m + 1, 0).getDate();
      // Monday = 1, Sunday = 7
      const startDow = firstDay.getDay() || 7; // 1=Mon..7=Sun

      const weeks: { date: string; day: number; mood: number; note?: string }[][] = [];
      let currentWeek: { date: string; day: number; mood: number; note?: string }[] = [];

      // Padding before the 1st
      for (let p = 1; p < startDow; p++) {
        currentWeek.push({ date: "", day: 0, mood: 0 });
      }

      for (let d = 1; d <= daysInMonth; d++) {
        const dateStr = `${selectedYear}-${String(m + 1).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
        currentWeek.push({
          date: dateStr,
          day: d,
          mood: moodMap[dateStr] || 0,
          note: noteMap[dateStr],
        });

        if (currentWeek.length === 7) {
          weeks.push(currentWeek);
          currentWeek = [];
        }
      }

      // Padding after the last day
      if (currentWeek.length > 0) {
        while (currentWeek.length < 7) {
          currentWeek.push({ date: "", day: 0, mood: 0 });
        }
        weeks.push(currentWeek);
      }

      result.push({ name: months[m], index: m, weeks });
    }

    return result;
  }, [selectedYear, moodMap, noteMap, months]);

  const stats = useMemo(() => {
    const yearEntries = entries.filter(e => e.date.startsWith(String(selectedYear)));
    const avg = averageMood(yearEntries);
    if (avg === null) return null;
    return { avg: avg.toFixed(1), total: recordedMoods(yearEntries).length };
  }, [entries, selectedYear]);

  // "Today" is the Prague calendar day, never the UTC or browser-local day.
  const today = pragueDateKey();
  const currentYear = pragueYear();
  const yearOptions = [currentYear, currentYear - 1, currentYear - 2];

  if (loading) {
    return <div className="glass-card"><div className="text-center py-8 text-white/40">{t("yearInPixels.loading")}</div></div>;
  }

  const cellSize = 15;
  const gapSize = 2;
  const weekWidth = cellSize * 7 + gapSize * 6;

  // Calculate counts per month for a mini summary
  const monthEntryCounts = monthsData.map(m => {
    let count = 0;
    for (const week of m.weeks) {
      for (const cell of week) {
        if (cell.mood > 0) count++;
      }
    }
    return count;
  });

  return (
    <div className="space-y-4">
      {/* ── Title & Year Selector ── */}
      <div className="glass-card">
        <div className="flex items-center justify-between mb-1">
          <h2 className="text-lg font-semibold text-white">
            {t("yearInPixels.title", { year: selectedYear })}
          </h2>
          <select
            value={selectedYear}
            onChange={e => setSelectedYear(Number(e.target.value))}
            className="bg-white/5 border border-white/10 rounded-lg px-3 py-1.5 text-sm text-white/80 cursor-pointer"
          >
            {yearOptions.map(y => <option key={y} value={y}>{y}</option>)}
          </select>
        </div>
        <p className="text-white/30 text-xs mb-2">
          {t("yearInPixels.description")}
        </p>
        {stats && (
          <div className="flex gap-4 text-xs">
            <span className="text-white/40">
              {t("yearInPixels.avg_mood")}{" "}
              <span className="text-white font-semibold">{stats.avg}</span>
            </span>
            <span className="text-white/40">
              {t("yearInPixels.days_tracked")}{" "}
              <span className="text-white font-semibold">{stats.total}</span>
            </span>
          </div>
        )}
      </div>

      {/* ── Months Grid ── */}
      {monthsData.map((month, mi) => (
        <div key={month.name} className="glass-card">
          {/* Month header */}
          <div className="flex items-center justify-between mb-2">
            <h3 className="text-sm font-semibold text-white/80">
              {month.name}
            </h3>
            <span className="text-[10px] text-white/25">
              {monthEntryCounts[mi]}/{month.weeks.reduce((s, w) => s + w.filter(c => c.day > 0).length, 0)} {t("yearInPixels.days_tracked")}
            </span>
          </div>

          {/* Weekday headers */}
          <div
            role="group"
            aria-label={t("yearInPixels.weekdays_label")}
            className="mx-auto mb-1 grid"
            style={{
              gridTemplateColumns: `repeat(7, ${cellSize}px)`,
              gap: `${gapSize}px`,
              width: `${weekWidth}px`,
            }}
          >
            {weekdays.map((day, index) => (
              <abbr
                key={`${index}-${day}`}
                aria-label={fullWeekdays[index]}
                title={fullWeekdays[index]}
                className="text-[8px] leading-none text-white/20 text-center no-underline"
                style={{ width: cellSize, justifySelf: "center" }}
              >
                {day}
              </abbr>
            ))}
          </div>

          {/* Day cells */}
          <div className="flex flex-col" style={{ gap: gapSize }}>
            {month.weeks.map((week, wi) => (
              <div key={wi} className="flex justify-center" style={{ gap: `${gapSize}px` }}>
                {week.map((cell, ci) => {
                  if (cell.day === 0) {
                    // Padding cell
                    return (
                      <div
                        key={`pad-${wi}-${ci}`}
                        style={{ width: cellSize, height: cellSize }}
                        className="shrink-0"
                      />
                    );
                  }

                  const isToday = cell.date === today;

                  return (
                    <div
                      key={cell.date}
                      className="rounded-[2px] transition-all hover:scale-[2.2] hover:z-10 hover:shadow-lg relative cursor-default shrink-0"
                      style={{
                        width: cellSize,
                        height: cellSize,
                        background: cell.mood
                          ? CHART_MOOD_COLORS[cell.mood]
                          : "rgba(255,255,255,0.04)",
                        outline: isToday
                          ? "2px solid rgba(99,102,241,0.6)"
                          : "none",
                        outlineOffset: "1px",
                      }}
                      title={
                        cell.mood
                          ? `${cell.date}: ${t("yearInPixels.mood")} ${cell.mood}/5${cell.note ? " — " + cell.note : ""}`
                          : cell.date
                      }
                      onMouseEnter={() => cell.mood > 0 && setHoveredDay(cell.date)}
                      onMouseLeave={() => setHoveredDay(null)}
                    >
                      {hoveredDay === cell.date && cell.mood > 0 && (
                        <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-1.5 bg-gray-900 border border-white/20 rounded-lg px-2.5 py-1.5 shadow-xl z-20 whitespace-nowrap pointer-events-none">
                          <span className="text-xs text-white font-medium">
                            {MOOD_EMOJIS[cell.mood]}{" "}
                            {new Date(cell.date).toLocaleDateString(lang === "cs" ? "cs-CZ" : "en-US", {
                              day: "numeric",
                              month: "short",
                            })}
                          </span>
                          {cell.note && (
                            <span className="text-[10px] text-white/30 ml-1.5">
                              — {cell.note.slice(0, 40)}{cell.note.length > 40 ? "…" : ""}
                            </span>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            ))}
          </div>
        </div>
      ))}

      {/* Legend */}
      <div className="glass-card">
        <div className="flex items-center justify-center gap-3 text-[10px]">
          {[5,4,3,2,1].map(m => (
            <span key={m} className="flex items-center gap-1 text-white/30">
              <span
                className="w-2.5 h-2.5 rounded-sm inline-block"
                style={{ background: CHART_MOOD_COLORS[m] }}
              />
              {MOOD_EMOJIS[m]}
            </span>
          ))}
          <span className="flex items-center gap-1 text-white/30">
            <span className="w-2.5 h-2.5 rounded-sm inline-block border border-indigo-400/40" />
            {t("yearInPixels.today")}
          </span>
        </div>
      </div>
    </div>
  );
}
