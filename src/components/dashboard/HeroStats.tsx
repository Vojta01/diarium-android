"use client";

import {
  averagePerDay,
  metricTrend,
  type DashboardData,
  type DashboardMetricKind,
  type MetricTrend,
  type TrendTone,
} from "@/lib/dashboard";
import {
  formatAverageMood,
  formatMetricValue,
  moodFace,
  moodWord,
} from "@/lib/dashboard-format";
import { useTranslation } from "@/lib/i18n";

import {
  ACCENT,
  Card,
  FOCUS_RING,
  Hint,
  IconBadge,
  StatValue,
  TrendLine,
  withAlpha,
} from "./primitives";

const WEEK_DAYS = 7;

function HeroHalf({
  cardId,
  emoji,
  label,
  accent,
  value,
  unit,
  caption,
  lines,
  trend,
  tone,
  onOpen,
  openLabel,
}: {
  cardId: string;
  emoji: string;
  label: string;
  accent: string;
  value: string;
  unit?: string;
  caption: string;
  lines: string[];
  trend: MetricTrend;
  tone: TrendTone;
  onOpen: () => void;
  openLabel: string;
}) {
  return (
    <Card cardId={cardId} title={label} accent={accent} className="h-full">
      <div className="mt-3">
        <span aria-hidden="true" className="text-[20px] leading-none">
          {emoji}
        </span>
        <div className="mt-2">
          <StatValue value={value} unit={unit} size="md" />
        </div>
        <Hint>{caption}</Hint>
        {lines.map((line) => (
          <Hint key={line}>{line}</Hint>
        ))}
        <TrendLine trend={trend} tone={tone} />
      </div>
      <button
        type="button"
        onClick={onOpen}
        aria-label={openLabel}
        className={`absolute inset-0 z-[1] rounded-[20px] ${FOCUS_RING}`}
      />
    </Card>
  );
}

/**
 * The three headline numbers of the last seven days: screen time across the
 * full width, unlocks and average mood side by side underneath.
 *
 * Every caption is derived from the same week the figure comes from, so a hero
 * can never disagree with the chart below it. A metric the phone has not
 * synced is an em dash, never a zero.
 */
export function HeroStats({
  data,
  onOpenMetric,
}: {
  data: DashboardData;
  onOpenMetric: (kind: DashboardMetricKind) => void;
}) {
  const { t } = useTranslation();

  const timeTrend = metricTrend(data.week, data.previousWeek, "screen_time");
  const unlockTrend = metricTrend(data.week, data.previousWeek, "unlocks");
  const moodTrend = metricTrend(data.week, data.previousWeek, "mood");
  const timePerDay = averagePerDay(data.week, "screen_time");
  const unlocksPerDay = averagePerDay(data.week, "unlocks");

  const mood = data.averageMood;
  const face = moodFace(mood);
  const word = moodWord(mood);
  const moodLines = [
    mood !== null && face && word
      ? t("dashboard.hero.mood_word_line", {
          face,
          word: t(`dashboard.mood_words.${word}`),
        })
      : null,
    mood !== null
      ? t("dashboard.hero.mood_caption", { days: WEEK_DAYS })
      : null,
  ].filter((line): line is string => line !== null);

  return (
    <div className="flex min-w-0 flex-col gap-4" data-card-group="hero">
      <Card
        cardId="hero_stats"
        title={t("dashboard.hero.screen_time")}
        accent={ACCENT.indigo}
        className="overflow-hidden"
      >
        <div
          aria-hidden="true"
          className="pointer-events-none absolute -right-10 -top-12 size-40 rounded-full"
          style={{
            background: `radial-gradient(circle, ${withAlpha(ACCENT.indigo, 0.22)}, transparent 70%)`,
          }}
        />
        <div className="mt-3 flex items-center gap-3">
          <IconBadge emoji="📱" accent={ACCENT.indigo} size={48} />
          <div className="min-w-0 flex-1">
            <StatValue
              value={formatMetricValue(data.screenTimeMinutes, "screen_time")}
            />
            <Hint>
              {data.screenTimeMinutes === null
                ? t("dashboard.hero.no_screen_time")
                : t("dashboard.hero.last_days", { days: WEEK_DAYS })}
            </Hint>
            {timePerDay !== null ? (
              <Hint>
                {t("dashboard.hero.per_day_minutes", {
                  value: formatMetricValue(timePerDay, "screen_time"),
                })}
              </Hint>
            ) : null}
            <TrendLine trend={timeTrend} tone="usage" />
          </div>
        </div>
        <button
          type="button"
          onClick={() => onOpenMetric("screen_time")}
          aria-label={t("dashboard.hero.open_metric", {
            metric: t("dashboard.metric.screen_time"),
          })}
          className={`absolute inset-0 z-[1] rounded-[20px] ${FOCUS_RING}`}
        />
      </Card>

      <div className="grid min-w-0 grid-cols-2 gap-4">
        <HeroHalf
          cardId="hero_unlocks"
          emoji="🔓"
          label={t("dashboard.hero.unlocks")}
          accent={ACCENT.cyan}
          value={formatMetricValue(data.unlocks, "unlocks")}
          caption={
            data.unlocks === null
              ? t("dashboard.hero.no_unlocks")
              : t("dashboard.hero.last_days_short", { days: WEEK_DAYS })
          }
          lines={
            unlocksPerDay !== null
              ? [
                  t("dashboard.hero.per_day_unlocks", {
                    value: Math.round(unlocksPerDay),
                  }),
                ]
              : []
          }
          trend={unlockTrend}
          tone="usage"
          onOpen={() => onOpenMetric("unlocks")}
          openLabel={t("dashboard.hero.open_metric", {
            metric: t("dashboard.metric.unlocks"),
          })}
        />
        <HeroHalf
          cardId="hero_mood"
          emoji="🎭"
          label={t("dashboard.hero.mood")}
          accent={mood === null ? ACCENT.muted : ACCENT.indigoLight}
          value={formatAverageMood(mood)}
          unit="/ 5"
          caption={
            mood === null
              ? t("dashboard.hero.no_mood")
              : t("dashboard.hero.last_days_short", { days: WEEK_DAYS })
          }
          lines={moodLines}
          trend={moodTrend}
          tone="mood"
          onOpen={() => onOpenMetric("mood")}
          openLabel={t("dashboard.hero.open_metric", {
            metric: t("dashboard.metric.mood"),
          })}
        />
      </div>
    </div>
  );
}
