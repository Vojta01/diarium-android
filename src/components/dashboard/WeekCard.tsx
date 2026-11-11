"use client";

import { type DashboardData, type DashboardDay } from "@/lib/dashboard";
import { formatAverageMood } from "@/lib/dashboard-format";
import {
  dayLabelKey,
  formatDashboardDate,
} from "@/lib/dashboard-ui";
import { useTranslation } from "@/lib/i18n";
import { isRecordedMood, CHART_MOOD_COLORS } from "@/lib/mood";
import { MOOD_EMOJIS } from "@/lib/stats";

import { ACCENT, Card, Divider, FOCUS_RING, Hint, Lead, MetricRow, withAlpha } from "./primitives";

const WEEK_DAYS = 7;

function DayDisc({
  day,
  today,
  onOpen,
}: {
  day: DashboardDay;
  today: string;
  onOpen: (date: string) => void;
}) {
  const { t, lang } = useTranslation();
  const mood = isRecordedMood(day.mood) ? day.mood : null;
  const isToday = day.date === today;
  const dateLabel = formatDashboardDate(day.date, lang, "numeric");
  const moodLabel = mood
    ? t(`mood.mood_${mood}`)
    : t("dashboard.week.missing_mood");
  const labelKey = dayLabelKey(day.date);

  return (
    <button
      type="button"
      onClick={() => onOpen(day.date)}
      aria-label={t("dashboard.week.mood_of_day", {
        date: dateLabel,
        mood: moodLabel,
      })}
      className={`flex min-h-11 min-w-0 flex-1 flex-col items-center gap-1 rounded-xl px-0.5 py-1 transition-colors hover:bg-white/[0.05] ${FOCUS_RING}`}
    >
      <span
        aria-hidden="true"
        className="flex size-9 items-center justify-center rounded-full border text-[17px] leading-none"
        style={{
          background: mood
            ? `linear-gradient(180deg, ${withAlpha(CHART_MOOD_COLORS[mood], 0.3)}, ${withAlpha(CHART_MOOD_COLORS[mood], 0.1)})`
            : "rgba(255,255,255,0.04)",
          borderColor: isToday
            ? ACCENT.indigo
            : mood
              ? withAlpha(CHART_MOOD_COLORS[mood], 0.55)
              : "rgba(255,255,255,0.14)",
          borderWidth: isToday ? 2 : 1,
        }}
      >
        {mood ? MOOD_EMOJIS[mood] : <span className="text-[color:var(--text-3)]">·</span>}
      </span>
      <span
        className={`w-full truncate text-center text-[10px] leading-3 ${
          isToday
            ? "font-semibold text-[color:var(--text-1)]"
            : "text-[color:var(--text-3)]"
        }`}
      >
        {isToday ? t("dashboard.today_label") : labelKey ? t(labelKey) : ""}
      </span>
    </button>
  );
}

/** 🎭 One disc per day of the week window, coloured by the recorded mood. */
export function WeekCard({
  data,
  onOpenDay,
}: {
  data: DashboardData;
  onOpenDay: (date: string) => void;
}) {
  const { t } = useTranslation();
  const average = data.averageMood;

  return (
    <Card cardId="week" title={t("dashboard.week.title")} className="h-full">
      <div className="mt-1">
        <Lead>{t("dashboard.week.last_days", { days: WEEK_DAYS })}</Lead>
        <Hint>{t("dashboard.week.hint")}</Hint>
      </div>
      <div className="mt-3 flex items-stretch gap-0.5">
        {data.week.map((day) => (
          <DayDisc
            key={day.date}
            day={day}
            today={data.today}
            onOpen={onOpenDay}
          />
        ))}
      </div>
      <Divider />
      {average === null ? (
        <Hint>{t("dashboard.week.no_mood")}</Hint>
      ) : (
        <MetricRow
          label={t("dashboard.week.average")}
          value={t("dashboard.week.average_value", {
            value: formatAverageMood(average),
          })}
        />
      )}
    </Card>
  );
}
