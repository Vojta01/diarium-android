"use client";

import { useMemo, useState } from "react";
import type { DailyEntry } from "@/lib/stats";
import { useTranslation } from "@/lib/i18n";
import { weekdayNames } from "@/lib/calendar-labels";

interface ScreenTimeEntry extends DailyEntry {
  phone_screen_time?: number;
  phone_unlocks?: number;
}

/** A day slot in the 7-day window — either a real entry or an empty placeholder. */
interface DaySlot {
  date: string;
  entry: ScreenTimeEntry | null;
}

function formatTime(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (h > 0) return h + "h " + m + "m";
  return m + "m";
}

/** Compact date label, e.g. "27. 8." */
function formatDay(date: string): string {
  const [, m, d] = date.split("-");
  return `${Number(d)}. ${Number(m)}.`;
}

/** Local YYYY-MM-DD from a Date (avoids toISOString() UTC offset shifting the day). */
function toLocalDateStr(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

/** Barva podle počtu odemknutí — stejný princip jako screen time */
function getUnlockColor(unlocks: number): string {
  if (unlocks < 30) return "#22c55e";    // zelená — málo
  if (unlocks < 60) return "#3b82f6";    // modrá — střed
  if (unlocks < 100) return "#eab308";   // žlutá — hodně
  return "#ef4444";                        // červená — extrém
}

/** Max height of bar area in pixels */
const BAR_AREA_H = 176;
/** Max height of unlock line chart in pixels */
const UNLOCK_AREA_H = 132;

/** App color palette for stacked bar segments */
const APP_COLORS = [
  "#6366f1", // indigo
  "#8b5cf6", // violet
  "#ec4899", // pink
  "#f97316", // orange
  "#22c55e", // green
  "#3b82f6", // blue
  "#eab308", // yellow
  "#ef4444", // red
];

export function ScreenTimeChart({ entries }: { entries: DailyEntry[] }) {
  const { t, lang } = useTranslation();
  const [selectedIdx, setSelectedIdx] = useState<number | null>(null);

  // ── 7-day window over calendar days (not just days-with-data) ──
  // Anchor on the most recent day that actually has screen-time/unlock data,
  // and always show 7 consecutive calendar days ending there. This skips an
  // empty trailing day (e.g. today with no data yet) so we never render a
  // pointless empty current-day column. Missing interior days still show as
  // placeholders.
  const days = useMemo((): DaySlot[] | null => {
    const typed = entries as ScreenTimeEntry[];
    // Group by date, keep the entry with the highest screen time
    const byDate = new Map<string, ScreenTimeEntry>();
    for (const e of typed) {
      const existing = byDate.get(e.date);
      const eTime = e.phone_screen_time || 0;
      const existingTime = existing?.phone_screen_time || 0;
      if (!existing || eTime > existingTime) {
        byDate.set(e.date, e);
      }
    }
    if (byDate.size === 0) return null;

    // Most recent date that has real data (screen time or unlocks)
    const lastDataDate = [...byDate.entries()]
      .filter(([, e]) => (e.phone_screen_time || 0) > 0 || (e.phone_unlocks || 0) > 0)
      .map(([d]) => d)
      .sort()
      .pop();
    if (!lastDataDate) return null;

    const base = new Date(lastDataDate + "T00:00:00");
    const slots: DaySlot[] = [];
    for (let i = 6; i >= 0; i--) {
      const d = new Date(base);
      d.setDate(d.getDate() - i);
      const ds = toLocalDateStr(d);
      slots.push({ date: ds, entry: byDate.get(ds) || null });
    }
    return slots;
  }, [entries]);

  // Only meaningful when there is at least some screen-time data.
  const hasAnyUnlocks = days?.some(s => s.entry && s.entry.phone_unlocks! > 0) ?? false;
  const showUnlocks = hasAnyUnlocks;

  // Per-app breakdown (colors across all days in window)
  const appData = useMemo(() => {
    if (!days) return null;
    const allApps = new Map<string, number>();
    for (const s of days) {
      const apps = s.entry?.phone_top_apps;
      if (!apps || !Array.isArray(apps)) continue;
      for (const a of apps) {
        if (a && typeof a === "object" && a.app && typeof a.time_sec === "number") {
          allApps.set(a.app, (allApps.get(a.app) || 0) + a.time_sec);
        }
      }
    }
    if (allApps.size === 0) return null;
    const sortedApps = [...allApps.entries()].sort((a, b) => b[1] - a[1]);
    const appColorMap = new Map<string, string>();
    sortedApps.forEach(([app], i) => appColorMap.set(app, APP_COLORS[i % APP_COLORS.length]));
    return { appColorMap };
  }, [days]);

  const weekdays = weekdayNames(lang);

  const todayStr = toLocalDateStr(new Date());

  if (!days || days.length === 0) {
    return (
      <div className="glass-card">
        <h2 className="text-lg font-semibold mb-2">{t("screenTime.title")}</h2>
        <div className="text-center py-8 text-white/30 text-sm">
          {t("screenTime.no_data")}
          <br />
          <span className="text-[11px]">{t("screenTime.no_data_hint")}</span>
        </div>
      </div>
    );
  }

  const dayOf = (s: DaySlot) => (s.entry ? s.entry.date : s.date);

  // ── Screen time stats (only over days that actually have data) ──
  const timed = days.filter(s => s.entry && s.entry.phone_screen_time! > 0);
  const maxTime = timed.length > 0
    ? Math.max(...timed.map(s => s.entry!.phone_screen_time!))
    : 0;
  const avgTime = timed.length > 0
    ? timed.reduce((sum, s) => sum + s.entry!.phone_screen_time!, 0) / timed.length
    : 0;
  const totalHours = days.reduce((s, d) => s + (d.entry?.phone_screen_time || 0), 0) / 3600;
  const maxDay = timed.reduce<ScreenTimeEntry | null>(
    (best, s) => (!best || s.entry!.phone_screen_time! > best.phone_screen_time!) ? s.entry! : best,
    null
  );

  const maxUnlocks = showUnlocks
    ? Math.max(...days.map(s => s.entry?.phone_unlocks || 0), 1)
    : 0;

  const selected = selectedIdx !== null ? days[selectedIdx] : null;

  return (
    <div className="glass-card">
      <h2 className="text-lg font-semibold mb-1">{t("screenTime.title")}</h2>
      <p className="text-white/30 text-xs mb-4">
        {t("screenTime.last_7_days")}
        <span className="text-white/20 ml-1">({days[0]?.date.slice(8, 10)}.{days[0]?.date.slice(5, 7)} – {days[days.length - 1]?.date.slice(8, 10)}.{days[days.length - 1]?.date.slice(5, 7)})</span>
      </p>

      {/* Summary cards */}
      <div className="grid grid-cols-3 gap-3 mb-6">
        <div className="text-center p-3 rounded-xl bg-white/5">
          <div className="text-xl font-bold text-white">{formatTime(Math.round(avgTime))}</div>
          <div className="text-[10px] text-white/30">{t("screenTime.avg_daily")}</div>
        </div>
        <div className="text-center p-3 rounded-xl bg-white/5">
          <div className="text-xl font-bold text-white">{Math.round(totalHours)}h</div>
          <div className="text-[10px] text-white/30">{t("screenTime.total")}</div>
        </div>
        <div className="text-center p-3 rounded-xl bg-white/5">
          <div className="text-xl font-bold" style={{ color: maxDay ? getBarColor(maxDay.phone_screen_time!).bg : "#5b5b5b" }}>
            {maxDay ? formatTime(maxDay.phone_screen_time!) : "—"}
          </div>
          <div className="text-[10px] text-white/30">{t("screenTime.max")}</div>
        </div>
      </div>

      {/* Screen time bar chart */}
      <div className="mb-2">
        <h3 className="text-xs font-medium text-white/40 uppercase tracking-wider mb-3">
          {t("screenTime.screen_time_chart")}
        </h3>

        <div className="relative" style={{ height: BAR_AREA_H + 48 }}>
          {/* Grid lines */}
          {[0.25, 0.5, 0.75].map(fraction => (
            <div
              key={fraction}
              className="absolute left-0 right-0 border-t border-white/5"
              style={{ bottom: fraction * BAR_AREA_H + 32 }}
            />
          ))}

          {/* Average line */}
          {maxTime > 0 && (
            <div
              className="absolute left-0 right-0 border-t border-dashed border-white/20 z-10"
              style={{ bottom: (avgTime / maxTime) * BAR_AREA_H + 32 }}
            >
              <span className="absolute -top-3 right-0 text-[9px] text-white/20 bg-[#0f0f0f] px-1 rounded">
                Ø {formatTime(Math.round(avgTime))}
              </span>
            </div>
          )}

          {/* Bars */}
          <div className="absolute bottom-8 left-0 right-0 flex items-end gap-1.5" style={{ height: BAR_AREA_H }}>
            {days.map((s, i) => {
              const seconds = s.entry?.phone_screen_time || 0;
              const barH = seconds > 0 ? Math.max(4, (seconds / maxTime) * BAR_AREA_H) : 0;
              const isToday = dayOf(s) === todayStr;
              const isSelected = selectedIdx === i;
              const hasApps = s.entry && s.entry.phone_top_apps && Array.isArray(s.entry.phone_top_apps) && s.entry.phone_top_apps.length > 0;

              return (
                <button
                  key={s.date}
                  onClick={() => seconds > 0 && setSelectedIdx(isSelected ? null : i)}
                  disabled={seconds === 0}
                  title={seconds > 0 ? `${formatDay(s.date)} — ${formatTime(seconds)}` : formatDay(s.date)}
                  className={`flex-1 flex flex-col items-center gap-1 justify-end ${seconds > 0 ? "cursor-pointer" : "cursor-default"}`}
                >
                  {seconds === 0 ? (
                    <div className="flex-1 w-full flex items-center justify-center">
                      <span className="text-[9px] leading-none text-white/20">—</span>
                    </div>
                  ) : hasApps ? (
                    <div
                      className="w-full max-w-[56px] mx-auto rounded-t-md relative transition-all"
                      style={{
                        height: barH,
                        opacity: isSelected ? 1 : isToday ? 0.9 : 0.65,
                        boxShadow: isToday ? "0 0 8px rgba(99,102,241,0.15)" : "none",
                        filter: isSelected ? "brightness(1.25)" : "none",
                      }}
                    >
                      {(() => {
                        const apps = s.entry!.phone_top_apps!;
                        const sorted = [...apps].sort((a, b) => b.time_sec - a.time_sec);
                        const top3 = sorted.slice(0, 3);
                        const otherSec = sorted.slice(3).reduce((sum, a) => sum + a.time_sec, 0);
                        const appsSum = sorted.reduce((sum, a) => sum + a.time_sec, 0);
                        // Time not covered by the tracked apps — still part of the day.
                        // Render it as a muted filler so the bar ALWAYS reflects the
                        // true total screen time (not just the listed apps).
                        const restSec = Math.max(0, seconds - appsSum);
                        const segments = [
                          ...top3.map(a => ({
                            name: a.app,
                            seconds: a.time_sec,
                            color: appData!.appColorMap.get(a.app) || "#6b7280",
                          })),
                          ...(otherSec > 0 ? [{ name: t("screenTime.other"), seconds: otherSec, color: "#374151" }] : []),
                          ...(restSec > 60 ? [{ name: t("screenTime.other_time"), seconds: restSec, color: "#27272d" }] : []),
                        ].reverse(); // bottom to top
                        let cumH = 0;
                        return segments.map((seg, si) => {
                          const segH = (seg.seconds / seconds) * barH;
                          const prevCum = cumH;
                          cumH += segH;
                          return (
                            <div
                              key={si}
                              className="absolute left-0 right-0"
                              style={{
                                bottom: prevCum,
                                height: Math.max(1, segH),
                                background: seg.color,
                                ...(si === segments.length - 1 ? { borderTopLeftRadius: 4, borderTopRightRadius: 4 } : {}),
                              }}
                            />
                          );
                        });
                      })()}
                    </div>
                  ) : (
                    <div
                      className="w-full max-w-[56px] mx-auto rounded-t-md transition-all"
                      style={{
                        height: barH,
                        background: isToday ? "linear-gradient(180deg, #818cf8, #6366f1)" : "#818cf8",
                        opacity: isSelected ? 1 : 0.85,
                        filter: isSelected ? "brightness(1.25)" : "none",
                      }}
                    />
                  )}
                  <span className={`text-[9px] leading-none ${isSelected ? "text-white font-bold" : isToday ? "text-white font-semibold" : "text-white/40"}`}>
                    {seconds > 0 ? formatTime(seconds) : "—"}
                  </span>
                </button>
              );
            })}
          </div>

          {/* Day labels incl. date */}
          <div className="absolute bottom-0 left-0 right-0 flex gap-1.5">
            {days.map((s, i) => {
              const date = new Date(s.date);
              const dayName = weekdays[(date.getDay() || 7) - 1];
              const isToday = dayOf(s) === todayStr;
              return (
                <div key={s.date} className={`flex-1 text-center ${selectedIdx === i ? "text-white font-semibold" : isToday ? "text-indigo-300" : "text-white/25"}`}>
                  <div className="text-[10px]">{dayName}</div>
                </div>
              );
            })}
          </div>
        </div>
      </div>

      {/* Legend — app colors when available, time-based otherwise */}
      {appData ? (
        <div className="flex items-center gap-2 mb-4 text-[9px] text-white/30 flex-wrap">
          <span className="text-white/40 mr-1">{t("screenTime.apps")}</span>
          {[...appData.appColorMap.entries()].slice(0, 6).map(([app, color]) => (
            <span key={app} className="flex items-center gap-1">
              <span className="w-2.5 h-2.5 rounded-sm" style={{ background: color }} />
              <span className="max-w-[60px] truncate">{app}</span>
            </span>
          ))}
          <span className="mx-1 text-white/10">|</span>
          <span className="flex items-center gap-1">
            <span className="w-2.5 h-2.5 rounded-sm" style={{ background: "#374151" }} />
            {t("screenTime.other")}
          </span>
          <span className="flex items-center gap-1">
            <span className="w-2.5 h-2.5 rounded-sm" style={{ background: "#27272d" }} />
            {t("screenTime.other_time")}
          </span>
          <span className="ml-auto text-white/15">HA</span>
        </div>
      ) : (
        <div className="flex items-center gap-2 mb-4 text-[9px] text-white/30 flex-wrap">
          <span className="text-white/40 mr-1">{t("screenTime.legend")}</span>
          {getBarColorTotals().map(({ label, color }) => (
            <span key={label} className="flex items-center gap-1">
              <span className="w-2.5 h-2.5 rounded-sm" style={{ background: color }} />
              {label}
            </span>
          ))}
          <span className="ml-auto text-white/15">HA</span>
        </div>
      )}

      {/* Phone Unlocks — interactive line chart */}
      {showUnlocks && (
        <div className="mt-4 pt-4 border-t border-white/5">
          <h3 className="text-xs font-medium text-white/40 uppercase tracking-wider mb-3">
            {t("screenTime.unlocks_chart")}
          </h3>

          <div className="relative" style={{ height: UNLOCK_AREA_H + 48 }}>
            {/* Grid lines */}
            {[0.25, 0.5, 0.75].map(f => (
              <div key={f} className="absolute left-0 right-0 border-t border-white/5"
                style={{ bottom: f * UNLOCK_AREA_H + 32 }} />
            ))}

            {/* Average line */}
            {(() => {
              const avgU = days.reduce((s, d) => s + (d.entry?.phone_unlocks || 0), 0) / days.length;
              return (
                <div
                  className="absolute left-0 right-0 border-t border-dashed border-white/20 z-10"
                  style={{ bottom: maxUnlocks > 0 ? (avgU / maxUnlocks) * UNLOCK_AREA_H + 32 : 32 }}
                >
                  <span className="absolute -top-3 right-0 text-[9px] text-white/20 bg-[#0f0f0f] px-1 rounded">
                    Ø {Math.round(avgU)}×
                  </span>
                </div>
              );
            })()}

            {/* Line chart */}
            <div className="absolute bottom-8 left-0 right-0" style={{ height: UNLOCK_AREA_H }}>
              {/* SVG line + dots drawn IN THE SAME COORDINATE SPACE so each dot
                  sits exactly on the connecting line (no HTML/SVG drift). */}
              <svg
                className="absolute inset-0 overflow-visible pointer-events-none"
                viewBox={`0 0 ${days.length} 4`}
                preserveAspectRatio="none"
              >
                {/* Connecting line */}
                <polyline
                  points={days
                    .map((s, i) => (s.entry?.phone_unlocks || 0) > 0
                      ? `${i + 0.5},${4 - (s.entry!.phone_unlocks! / maxUnlocks) * 4}`
                      : null)
                    .filter(Boolean)
                    .join(" ")}
                  fill="none"
                  stroke="#a78bfa"
                  strokeWidth="0.2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  opacity="0.9"
                />
                {/* Dots at the same vertices as the line */}
                {days.map((s, i) => {
                  if ((s.entry?.phone_unlocks || 0) <= 0) return null;
                  const color = getUnlockColor(s.entry!.phone_unlocks!);
                  const isSelected = selectedIdx === i;
                  const isToday = dayOf(s) === todayStr;
                  const cx = i + 0.5;
                  const cy = 4 - (s.entry!.phone_unlocks! / maxUnlocks) * 4;
                  return (
                    <g key={s.date}>
                      {isSelected && <circle cx={cx} cy={cy} r="0.42" fill={color} opacity="0.25" />}
                      <circle
                        cx={cx}
                        cy={cy}
                        r="0.22"
                        fill={color}
                        stroke={isSelected ? "#fff" : "#none"}
                        strokeWidth={isSelected ? 0.06 : 0}
                        opacity={isSelected ? 1 : isToday ? 1 : 0.8}
                      />
                    </g>
                  );
                })}
              </svg>

              {/* Clickable points — each unlock column is a button.
                  NO gap: flush columns make each center sit at x=i+0.5,
                  matching the SVG line coordinates above. */}
              <div className="absolute inset-0 flex items-end">
                {days.map((s, i) => {
                  const unlocks = s.entry?.phone_unlocks || 0;
                  const isToday = dayOf(s) === todayStr;
                  const isSelected = selectedIdx === i;
                  if (unlocks === 0) {
                    return (
                      <div key={s.date} className="flex-1 flex items-center justify-center h-full">
                        <span className="text-[9px] text-white/20">—</span>
                      </div>
                    );
                  }
                  const topPct = (1 - unlocks / maxUnlocks) * 100;
                  return (
                    <button
                      key={s.date}
                      onClick={() => setSelectedIdx(isSelected ? null : i)}
                      title={`${formatDay(s.date)} — ${unlocks}×`}
                      className="flex-1 relative h-full cursor-pointer"
                    >
                      {/* value above the point */}
                      <span
                        className={`absolute left-1/2 -translate-x-1/2 text-[9px] leading-none ${isSelected ? "text-white font-bold" : isToday ? "text-purple-200 font-semibold" : "text-white/40"}`}
                        style={{ top: `${Math.max(2, topPct - 14)}%` }}
                      >
                        {unlocks}×
                      </span>
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Day labels */}
            <div className="absolute bottom-0 left-0 right-0 flex gap-1.5">
              {days.map((s, i) => {
                const date = new Date(s.date);
                const dayName = weekdays[(date.getDay() || 7) - 1];
                const isToday = dayOf(s) === todayStr;
                return (
                  <div key={s.date} className={`flex-1 text-center ${selectedIdx === i ? "text-white font-semibold" : isToday ? "text-purple-300 font-semibold" : "text-white/25"}`}>
                    <div className="text-[10px]">{dayName}</div>
                  </div>
                );
              })}
            </div>
          </div>

          <div className="flex items-center gap-2 mt-3 text-[9px] text-white/30">
            <span className="text-white/40 mr-1">{t("screenTime.legend")}</span>
            {[
              { val: 15, label: "<30×", color: "#22c55e" },
              { val: 45, label: "30–60×", color: "#3b82f6" },
              { val: 80, label: "60–100×", color: "#eab308" },
              { val: 120, label: "100×+", color: "#ef4444" },
            ].map(({ label, color }) => (
              <span key={label} className="flex items-center gap-1">
                <span className="w-2.5 h-2.5 rounded-sm" style={{ background: color }} />
                {label}
              </span>
            ))}
            <span className="text-white/15 ml-auto">HA</span>
          </div>
        </div>
      )}

      {/* Hint */}
      <p className="text-white/20 text-[9px] mt-3 text-center">{t("screenTime.click_hint")}</p>

      {/* Day detail panel */}
      {selected && (
        <div className="mt-3 p-3 rounded-xl bg-white/5 border border-white/10 animate-slide-up">
          <div className="flex items-center justify-between mb-2">
            <span className="text-sm font-semibold text-white">
              📅 {formatDay(selected.date)} ({weekdays[(new Date(selected.date).getDay() || 7) - 1]})
            </span>
            <button onClick={() => setSelectedIdx(null)} className="text-white/40 hover:text-white text-sm px-1" aria-label={t("screenTime.close")}>
              {t("screenTime.close")}
            </button>
          </div>

          <div className="grid grid-cols-2 gap-2 mb-3">
            <div className="p-2 rounded-lg bg-white/3">
              <div className="text-[9px] text-white/30 uppercase tracking-wider">{t("screenTime.screen_time_label")}</div>
              <div className="text-base font-mono text-white">
                {selected.entry?.phone_screen_time ? formatTime(selected.entry.phone_screen_time) : "—"}
              </div>
            </div>
            <div className="p-2 rounded-lg bg-white/3">
              <div className="text-[9px] text-white/30 uppercase tracking-wider">{t("screenTime.unlocks_label")}</div>
              <div className="text-base font-mono" style={{ color: selected.entry?.phone_unlocks ? getUnlockColor(selected.entry.phone_unlocks) : "#fff" }}>
                {selected.entry?.phone_unlocks ? `${selected.entry.phone_unlocks}×` : "—"}
              </div>
            </div>
          </div>

          {/* Per-app breakdown */}
          <div className="text-[10px] text-white/50 font-medium mb-1.5">{t("screenTime.apps")}</div>
          {selected.entry?.phone_top_apps && selected.entry.phone_top_apps.length > 0 ? (
            <div className="space-y-1">
              {[...selected.entry.phone_top_apps]
                .sort((a, b) => b.time_sec - a.time_sec)
                .map((a) => {
                  const share = selected.entry!.phone_screen_time ? (a.time_sec / selected.entry!.phone_screen_time) * 100 : 0;
                  return (
                    <div key={a.app} className="flex items-center gap-2">
                      <span
                        className="w-2.5 h-2.5 rounded-sm flex-shrink-0"
                        style={{ background: appData?.appColorMap.get(a.app) || "#6b7280" }}
                      />
                      <span className="flex-1 text-white/70 text-xs truncate">{a.app}</span>
                      <span className="text-[9px] text-white/25 w-9 text-right shrink-0">{Math.round(share)}%</span>
                      <span className="text-[10px] font-mono text-white/60 w-12 text-right shrink-0">{formatTime(a.time_sec)}</span>
                    </div>
                  );
                })}
              {/* Filler: time not covered by the listed apps (other apps / system) */}
              {(() => {
                const total = selected.entry!.phone_screen_time || 0;
                const appsSum = (selected.entry!.phone_top_apps || []).reduce((s, a) => s + a.time_sec, 0);
                const rest = Math.max(0, total - appsSum);
                if (rest <= 60) return null;
                const share = total > 0 ? (rest / total) * 100 : 0;
                return (
                  <div className="flex items-center gap-2 opacity-80">
                    <span className="w-2.5 h-2.5 rounded-sm flex-shrink-0" style={{ background: "#27272d" }} />
                    <span className="flex-1 text-white/50 text-xs truncate">{t("screenTime.other_time")}</span>
                    <span className="text-[9px] text-white/25 w-9 text-right shrink-0">{Math.round(share)}%</span>
                    <span className="text-[10px] font-mono text-white/40 w-12 text-right shrink-0">{formatTime(rest)}</span>
                  </div>
                );
              })()}
            </div>
          ) : (
            <div className="text-white/25 text-[10px]">{t("screenTime.no_apps")}</div>
          )}
        </div>
      )}
    </div>
  );
}

function getBarColor(seconds: number): { bg: string; label: string } {
  if (seconds < 1800) return { bg: "#22c55e", label: "🟢 <30m" };
  if (seconds < 3600) return { bg: "#4ade80", label: "🟢 30m–1h" };
  if (seconds < 7200) return { bg: "#3b82f6", label: "🔵 1–2h" };
  if (seconds < 14400) return { bg: "#eab308", label: "🟡 2–4h" };
  if (seconds < 21600) return { bg: "#f97316", label: "🟠 4–6h" };
  return { bg: "#ef4444", label: "🔴 6h+" };
}

function getBarColorTotals(): { label: string; color: string }[] {
  return [
    { label: "<30m", color: "#22c55e" },
    { label: "30m–1h", color: "#4ade80" },
    { label: "1–2h", color: "#3b82f6" },
    { label: "2–4h", color: "#eab308" },
    { label: "4–6h", color: "#f97316" },
    { label: "6h+", color: "#ef4444" },
  ];
}