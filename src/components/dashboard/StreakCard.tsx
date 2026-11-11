"use client";

import { isRecordedDay, type DashboardData } from "@/lib/dashboard";
import { dayForm } from "@/lib/dashboard-ui";
import { useTranslation } from "@/lib/i18n";

import { ACCENT, Card, Divider, Hint, MetricRow } from "./primitives";

const LOAD_DAYS = 30;

/** 🔥 The running streak and the longest run in the loaded window. */
export function StreakCard({ data }: { data: DashboardData }) {
  const { t, lang } = useTranslation();

  const streakWord = t(
    `dashboard.streak.unit_${dayForm(data.streak, lang)}`
  );
  const longestWord = t(
    `dashboard.streak.longest_${dayForm(data.longestStreak, lang)}`,
    { count: data.longestStreak }
  );

  const hint =
    data.streak === 0
      ? t("dashboard.streak.none")
      : !isRecordedDay(data.todayEntry)
        ? t("dashboard.streak.today_missing")
        : t("dashboard.streak.today_done");

  return (
    <Card
      cardId="streak"
      title={t("dashboard.streak.title")}
      accent={ACCENT.indigo}
      className="h-full"
    >
      <div className="mt-3 flex items-baseline gap-2">
        <span className="text-[30px] font-bold leading-9 tracking-[-0.5px] text-[color:var(--text-1)] tabular-nums">
          {data.streak}
        </span>
        <span className="pb-1 text-[13px] text-[color:var(--text-2)]">
          {streakWord}
        </span>
      </div>
      <Hint>{hint}</Hint>
      <Divider />
      <MetricRow
        label={t("dashboard.streak.longest", { days: LOAD_DAYS })}
        value={longestWord}
      />
    </Card>
  );
}
