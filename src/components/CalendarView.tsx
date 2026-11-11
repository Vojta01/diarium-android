"use client";

import { useState, useMemo } from "react";
import { MOOD_COLORS, MOOD_EMOJIS, type DailyEntry } from "@/lib/stats";
import { useTranslation } from "@/lib/i18n";
import { monthNames, weekdayNames } from "@/lib/calendar-labels";
import { pragueDateKey } from "@/lib/date";

interface CalendarViewProps {
  entries: DailyEntry[];
  onNavigateToDate?: (date: string) => void;
  onViewMonth?: (year: number, month: number) => void;
}

export function CalendarView({ entries, onNavigateToDate, onViewMonth }: CalendarViewProps) {
  const { lang } = useTranslation();
  const todayKey = pragueDateKey();
  const [viewYear, setViewYear] = useState(Number(todayKey.slice(0, 4)));
  const [viewMonth, setViewMonth] = useState(Number(todayKey.slice(5, 7)) - 1);

  const moodMap = useMemo(() => {
    const map: Record<string, DailyEntry> = {};
    entries.forEach((e) => {
      map[e.date] = e;
    });
    return map;
  }, [entries]);

  // Build calendar grid
  const calendarDays = useMemo(() => {
    const firstDay = new Date(viewYear, viewMonth, 1);
    const lastDay = new Date(viewYear, viewMonth + 1, 0);
    const daysInMonth = lastDay.getDate();

    // Monday = 0, Sunday = 6
    let startDow = firstDay.getDay() - 1;
    if (startDow < 0) startDow = 6;

    const days: (string | null)[] = [];
    // Empty cells before start
    for (let i = 0; i < startDow; i++) days.push(null);
    // Actual days
    for (let d = 1; d <= daysInMonth; d++) {
      const ds = `${viewYear}-${String(viewMonth + 1).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
      days.push(ds);
    }

    return days;
  }, [viewYear, viewMonth]);

  const prevMonth = () => {
    const nextMonth = (viewMonth + 11) % 12;
    const nextYear = viewMonth === 0 ? viewYear - 1 : viewYear;
    if (viewMonth === 0) {
      setViewMonth(11);
      setViewYear(viewYear - 1);
    } else {
      setViewMonth(viewMonth - 1);
    }
    onViewMonth?.(nextYear, nextMonth + 1);
  };

  const nextMonth = () => {
    const nextMonth = (viewMonth + 1) % 12;
    const nextYear = viewMonth === 11 ? viewYear + 1 : viewYear;
    if (viewMonth === 11) {
      setViewMonth(0);
      setViewYear(viewYear + 1);
    } else {
      setViewMonth(viewMonth + 1);
    }
    onViewMonth?.(nextYear, nextMonth + 1);
  };

  const dayNames = weekdayNames(lang);
  const months = monthNames(lang, viewYear);

  return (
    <div className="glass-card">
      {/* Month navigation */}
      <div className="flex items-center justify-between mb-4">
        <button onClick={prevMonth} className="btn-glass text-sm px-3">
          ←
        </button>
        <h2 className="text-lg font-semibold">
          {months[viewMonth]} {viewYear}
        </h2>
        <button onClick={nextMonth} className="btn-glass text-sm px-3">
          →
        </button>
      </div>

      {/* Day headers */}
      <div className="grid grid-cols-7 mb-2">
        {dayNames.map((d) => (
          <div key={d} className="text-center text-[10px] text-white/25 uppercase">
            {d}
          </div>
        ))}
      </div>

      {/* Calendar grid */}
      <div className="grid grid-cols-7 gap-1">
        {calendarDays.map((dateStr, i) => {
          if (!dateStr) return <div key={`empty-${i}`} />;

          const entry = moodMap[dateStr];
          const day = parseInt(dateStr.split("-")[2]);
          const isToday = dateStr === todayKey;

          return (
            <button
              key={dateStr}
              onClick={() => onNavigateToDate?.(dateStr)}
              className={`aspect-square rounded-lg flex flex-col items-center justify-center text-xs transition-all
                ${isToday ? "ring-1 ring-indigo-400/50" : ""}
                hover:bg-white/5`}
              style={{
                background: entry
                  ? `${MOOD_COLORS[entry.mood] || "#6366f1"}33`
                  : "transparent",
              }}
            >
              <span className={entry ? "text-white/90 font-medium" : "text-white/25"}>
                {day}
              </span>
              {entry && (
                <span className="text-[14px] leading-none flex items-center gap-0.5">
                  {MOOD_EMOJIS[entry.mood] || (entry.mood > 0 ? MOOD_EMOJIS[3] : "—")}
                  {entry.photo_path && <span className="text-[8px]">📷</span>}
                </span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}