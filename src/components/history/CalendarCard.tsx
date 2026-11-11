"use client";

import { useRef, type KeyboardEvent } from "react";

import { FOCUS_RING, withAlpha } from "@/components/dashboard/primitives";
import { safePhotoSrc } from "@/lib/dashboard-ui";
import { shiftDateKey } from "@/lib/date";
import {
  HISTORY_MOOD_COLORS,
  historyDayAriaLabel,
  historyEntryMap,
  historyIsSelectable,
  historyMonthTitle,
  historyMonthWeeks,
  historyRovingDate,
  historyWeekdayHeaders,
} from "@/lib/history";
import { useTranslation } from "@/lib/i18n";
import { isRecordedMood } from "@/lib/mood";
import { MOOD_EMOJIS, type DailyEntry } from "@/lib/stats";

/** The Android `moodColor()` palette; 5 is cyan, not the check-in chip green. */
const MOOD_TINTS = HISTORY_MOOD_COLORS;

const ARROW_OFFSETS: Record<string, number> = {
  ArrowLeft: -1,
  ArrowRight: 1,
  ArrowUp: -7,
  ArrowDown: 7,
};

/**
 * Month header: `◀` / title / `Dnes` / `▶`.
 *
 * Shared by the calendar and the web-only photo mode, so both can move through
 * months and the centre title names the month the content belongs to.
 */
export function HistoryMonthNav({
  title,
  onPrevious,
  onNext,
  onToday,
}: {
  title: string;
  onPrevious: () => void;
  onNext: () => void;
  onToday: () => void;
}) {
  const { t } = useTranslation();
  return (
    <div className="flex items-center gap-1">
      <button
        type="button"
        onClick={onPrevious}
        aria-label={t("history.previous_month")}
        className={`flex size-11 shrink-0 items-center justify-center rounded-xl text-[13px] text-[color:var(--text-2)] transition-colors hover:bg-white/[0.07] hover:text-[color:var(--text-1)] ${FOCUS_RING}`}
      >
        <span aria-hidden="true">◀</span>
      </button>
      <h2
        id="history-calendar-title"
        aria-live="polite"
        className="min-w-0 flex-1 truncate text-center text-[17px] font-semibold leading-6 tracking-[-0.2px] text-[color:var(--text-1)]"
      >
        {title}
      </h2>
      <button
        type="button"
        onClick={onToday}
        aria-label={t("history.goto_today")}
        className={`flex min-h-11 shrink-0 items-center rounded-xl px-2 text-[12px] font-semibold text-indigo-200 transition-colors hover:bg-indigo-500/15 ${FOCUS_RING}`}
      >
        {t("history.today")}
      </button>
      <button
        type="button"
        onClick={onNext}
        aria-label={t("history.next_month")}
        className={`flex size-11 shrink-0 items-center justify-center rounded-xl text-[13px] text-[color:var(--text-2)] transition-colors hover:bg-white/[0.07] hover:text-[color:var(--text-1)] ${FOCUS_RING}`}
      >
        <span aria-hidden="true">▶</span>
      </button>
    </div>
  );
}

/**
 * The month grid: `◀` / title / `Dnes` / `▶`, Monday-first captions, then
 * whole weeks. The title and the arrows stay usable while a month is loading
 * or after a failed read, so navigation never dead-ends.
 *
 * Cells follow Android's marking rule exactly: only a filled mood paints the
 * mood colour, emoji and photo/scale indicators. A synced-only row is drawn as
 * a neutral, tappable cell — its detail still shows the synced numbers.
 */
export function CalendarCard({
  year,
  monthIndex,
  todayKey,
  entries,
  loading,
  selected,
  onPrevious,
  onNext,
  onToday,
  onSelect,
}: {
  year: number;
  monthIndex: number;
  /** Europe/Prague today, supplied by the screen so it cannot drift. */
  todayKey: string;
  entries: readonly DailyEntry[];
  loading: boolean;
  selected: string | null;
  onPrevious: () => void;
  onNext: () => void;
  onToday: () => void;
  onSelect: (date: string) => void;
}) {
  const { t, lang } = useTranslation();
  const gridRef = useRef<HTMLDivElement>(null);
  const weeks = historyMonthWeeks(year, monthIndex);
  const entryMap = historyEntryMap(entries);
  const title = historyMonthTitle(lang, year, monthIndex);
  const roving = historyRovingDate({
    selected,
    year,
    monthIndex,
    todayKey,
    weeks,
  });

  const focusDay = (event: KeyboardEvent<HTMLButtonElement>, date: string) => {
    const offset = ARROW_OFFSETS[event.key];
    if (!offset) return;

    const target = shiftDateKey(date, offset);
    const button = gridRef.current?.querySelector<HTMLButtonElement>(
      `[data-date="${target}"]`
    );
    if (!button || button.disabled) return;

    event.preventDefault();
    button.focus();
  };

  return (
    <section className="glass-card" aria-labelledby="history-calendar-title">
      <HistoryMonthNav
        title={title}
        onPrevious={onPrevious}
        onNext={onNext}
        onToday={onToday}
      />

      <div className="mt-3.5 grid grid-cols-7 gap-1" aria-hidden="true">
        {historyWeekdayHeaders(lang).map((name) => (
          <div
            key={name}
            className="text-center text-[11px] font-medium uppercase tracking-[0.4px] text-[color:var(--text-3)]"
          >
            {name}
          </div>
        ))}
      </div>

      {loading ? (
        <div
          role="status"
          aria-label={t("history.loading")}
          className="flex h-[184px] flex-col items-center justify-center gap-2"
        >
          <span
            aria-hidden="true"
            className="inline-block size-5 animate-spin rounded-full border-2 border-indigo-300/40 border-t-indigo-200 motion-reduce:animate-none"
          />
          <span className="text-[12px] text-[color:var(--text-2)]">
            {t("history.loading")}
          </span>
        </div>
      ) : (
        <div
          ref={gridRef}
          role="grid"
          aria-label={t("history.calendar_aria", { month: title })}
          className="mt-1.5 flex flex-col gap-1"
        >
          {weeks.map((week, weekIndex) => (
            <div role="row" key={`week-${weekIndex}`} className="grid grid-cols-7 gap-1">
              {week.map((date, cellIndex) => {
                if (!date) {
                  return (
                    <div
                      key={`blank-${weekIndex}-${cellIndex}`}
                      role="gridcell"
                      aria-hidden="true"
                      className="aspect-square"
                    />
                  );
                }

                const entry = entryMap.get(date) ?? null;
                const recordedMood =
                  entry && isRecordedMood(entry.mood) ? entry.mood : null;
                const recorded = recordedMood !== null;
                const moodEmoji = recordedMood ? MOOD_EMOJIS[recordedMood] : "";
                const tint = recordedMood ? MOOD_TINTS[recordedMood] : null;
                const accent = tint ?? "#6366f1";
                const isToday = date === todayKey;
                const isSelected = date === selected;
                const selectable = historyIsSelectable(date, todayKey);
                const hasPhoto = Boolean(safePhotoSrc(entry?.photo_path));
                const hasScales = Object.values(entry?.scale_values ?? {}).some(
                  (value) => typeof value === "number" && value > 0
                );
                const dayOfMonth = Number(date.slice(8, 10));
                const label = historyDayAriaLabel({
                  date,
                  entry,
                  lang,
                  t,
                  todayKey,
                });

                return (
                  <div
                    key={date}
                    role="gridcell"
                    aria-selected={isSelected}
                    className="min-w-0"
                  >
                    <button
                      type="button"
                      data-testid={`calendar-day-${date}`}
                      data-date={date}
                      data-mood={recordedMood === null ? "" : String(recordedMood)}
                      data-recorded={recorded ? "true" : "false"}
                      data-photo={hasPhoto ? "true" : "false"}
                      data-scales={hasScales ? "true" : "false"}
                      aria-label={label}
                      aria-current={isToday ? "date" : undefined}
                      aria-pressed={isSelected}
                      aria-disabled={!selectable || undefined}
                      disabled={!selectable}
                      tabIndex={date === roving ? 0 : -1}
                      onClick={() => onSelect(date)}
                      onKeyDown={(event) => focusDay(event, date)}
                      className={`flex aspect-square w-full flex-col items-center justify-center rounded-[14px] border text-[12px] leading-none transition-colors ${FOCUS_RING} ${
                        !selectable
                          ? "cursor-default border-white/[0.04] bg-white/[0.02] text-[color:var(--text-3)] opacity-50"
                          : isSelected
                            ? "border-transparent text-[color:var(--text-1)]"
                            : recorded
                              ? "text-[color:var(--text-1)]"
                              : "border-white/10 bg-white/[0.04] text-[color:var(--text-2)] hover:bg-white/[0.08]"
                      } ${isToday && !isSelected ? "ring-2 ring-indigo-400/80" : ""}`}
                      style={
                        isSelected
                          ? {
                              backgroundColor: withAlpha(accent, 0.3),
                              borderColor: withAlpha(accent, 0.7),
                              boxShadow: `0 0 0 2px ${withAlpha(accent, 0.85)}`,
                            }
                          : tint
                            ? {
                                backgroundColor: withAlpha(tint, 0.2),
                                borderColor: withAlpha(tint, 0.55),
                              }
                            : undefined
                      }
                    >
                      <span className="tabular-nums">{dayOfMonth}</span>
                      {recorded ? (
                        <span className="mt-0.5 flex items-center gap-0.5">
                          <span aria-hidden="true" className="text-[13px] leading-none">
                            {moodEmoji}
                          </span>
                          {hasPhoto ? (
                            <span aria-hidden="true" className="text-[8px] leading-none">
                              📷
                            </span>
                          ) : null}
                          {hasScales ? (
                            <span
                              aria-hidden="true"
                              className="size-1 rounded-full bg-white/75"
                            />
                          ) : null}
                        </span>
                      ) : null}
                    </button>
                  </div>
                );
              })}
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
