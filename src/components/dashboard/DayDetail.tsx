"use client";

/* eslint-disable @next/next/no-img-element */

import { type ReactNode } from "react";

import { Markdown } from "@/components/Markdown";
import {
  formatScreenTimeDuration,
  formatTopAppDuration,
} from "@/lib/dashboard-format";
import {
  dayDetailSections,
  scaleValueText,
  weatherKey,
} from "@/lib/dashboard-ui";
import { useTranslation } from "@/lib/i18n";
import { getFeatureFlags } from "@/lib/feature-flags";
import type { HabitDef } from "@/lib/supabase/db";
import type { Scale } from "@/lib/scales";
import { isRecordedMood } from "@/lib/mood";
import { MOOD_EMOJIS, type DailyEntry } from "@/lib/stats";

import { ActionButton, FOCUS_RING, MetricRow } from "./primitives";

function humanizeKey(key: string): string {
  return key
    .replace(/[_-]+/g, " ")
    .replace(/^\p{Ll}/u, (letter) => letter.toUpperCase());
}

function Section({
  title,
  children,
}: {
  title: string;
  children: ReactNode;
}) {
  return (
    <section className="mt-4">
      <h3 className="text-[11px] font-semibold uppercase tracking-[1.1px] text-[color:var(--text-2)]">
        {title}
      </h3>
      <div className="mt-1.5">{children}</div>
    </section>
  );
}

function Chip({ children }: { children: ReactNode }) {
  return (
    <span className="inline-flex min-h-7 items-center gap-1 rounded-full border border-white/10 bg-white/[0.05] px-2.5 py-0.5 text-[12px] leading-4 text-[color:var(--text-1)]">
      {children}
    </span>
  );
}

function ScalesSection({
  values,
  scales,
  error,
  onRetry,
}: {
  values: Record<string, number>;
  scales: readonly Scale[] | null;
  error: string | null;
  onRetry: () => void;
}) {
  const { t } = useTranslation();
  const hasValues = Object.values(values).some((value) => value > 0);
  if (!hasValues) return null;

  if (error) {
    return (
      <Section title={t("dashboard.day_detail.scales")}>
        <div role="alert" className="flex items-center justify-between gap-2">
          <span className="text-[12px] text-red-200">{error}</span>
          <button
            type="button"
            onClick={onRetry}
            className={`min-h-11 rounded-lg border border-white/10 px-3 text-[12px] text-[color:var(--text-1)] ${FOCUS_RING}`}
          >
            {t("dashboard.retry")}
          </button>
        </div>
      </Section>
    );
  }

  const byId = new Map((scales ?? []).map((scale) => [scale.id, scale]));

  return (
    <Section title={t("dashboard.day_detail.scales")}>
      <div className="flex flex-col gap-1">
        {Object.entries(values)
          .filter(([, value]) => typeof value === "number" && value > 0)
          .map(([id, value]) => {
            const scale = byId.get(id);
            const label = scale
              ? `${scale.emoji} ${scale.name}`
              : humanizeKey(id);
            return (
              <MetricRow
                key={id}
                label={label}
                value={`${scaleValueText(value, scale?.max_value)}${scale?.unit ? ` ${scale.unit}` : ""}`}
              />
            );
          })}
      </div>
    </Section>
  );
}

/**
 * The full record of one day, shared by the dashboard dialog and any future
 * full-screen route. Sections with nothing in them are omitted; an unrecorded
 * day (phone sync only) explains itself instead of pretending to be empty.
 */
export function DayDetail({
  date,
  entry,
  habits,
  scales,
  scalesError,
  onRetry,
  onEdit,
}: {
  date: string;
  entry: DailyEntry | null;
  habits: readonly HabitDef[] | null;
  scales: readonly Scale[] | null;
  scalesError: string | null;
  onRetry: () => void;
  onEdit: (date: string) => void;
}) {
  const { t, lang } = useTranslation();

  if (!entry) {
    return (
      <div>
        <p className="text-[15px] font-semibold text-[color:var(--text-1)]">
          {t("dashboard.day_detail.empty_title")}
        </p>
        <p className="mt-1 text-[13px] leading-5 text-[color:var(--text-2)]">
          {t("dashboard.day_detail.empty_message")}
        </p>
        <div className="mt-4">
          <ActionButton onClick={() => onEdit(date)}>
            {t("dashboard.day_detail.open_checkin")}
          </ActionButton>
        </div>
      </div>
    );
  }

  const sections = dayDetailSections(entry);
  const mood = isRecordedMood(entry.mood) ? entry.mood : null;
  const habitById = new Map((habits ?? []).map((habit) => [habit.key, habit]));
  const gratitude = entry.gratitude.filter((line) => line.trim().length > 0);

  return (
    <div>
      {!sections.recorded ? (
        <div className="rounded-xl border border-amber-400/25 bg-amber-500/[0.08] px-3 py-2.5">
          <p className="text-[12px] font-semibold text-amber-100">
            {t("dashboard.day_detail.unrecorded_hint")}
          </p>
          <p className="mt-0.5 text-[12px] leading-[18px] text-[color:var(--text-2)]">
            {t("dashboard.day_detail.unrecorded_body")}
          </p>
        </div>
      ) : null}

      {mood ? (
        <Section title={t("dashboard.day_detail.mood")}>
          <div className="flex items-center gap-3">
            <span aria-hidden="true" className="text-[36px] leading-none">
              {MOOD_EMOJIS[mood]}
            </span>
            <div>
              <p className="text-[15px] font-semibold text-[color:var(--text-1)]">
                {t(`mood.mood_${mood}`)}
              </p>
              <p className="text-[12px] tabular-nums text-[color:var(--text-2)]">
                {mood} / 5
              </p>
            </div>
          </div>
        </Section>
      ) : null}

      {sections.sleep || sections.stress ? (
        <div className="grid grid-cols-2 gap-3">
          {sections.sleep ? (
            <Section title={t("dashboard.day_detail.sleep")}>
              <p className="text-[13px] text-[color:var(--text-1)]">
                {entry.sleep_quality
                  ? t(`sleep.sleep_${entry.sleep_quality}`)
                  : "—"}
              </p>
            </Section>
          ) : null}
          {sections.stress ? (
            <Section title={t("dashboard.day_detail.stress")}>
              <p className="text-[13px] text-[color:var(--text-1)]">
                {entry.stress ? t(`stress.stress_${entry.stress}`) : "—"}
              </p>
            </Section>
          ) : null}
        </div>
      ) : null}

      {sections.weather ? (
        <Section title={t("dashboard.day_detail.weather")}>
          <div className="flex flex-wrap gap-1.5">
            {entry.weather
              .filter((value) => value.trim().length > 0)
              .map((value, index) => {
                const key = weatherKey(value);
                return (
                  <Chip key={`${value}-${index}`}>
                    {key ? t(`dashboard.weather.${key}`) : value}
                  </Chip>
                );
              })}
          </div>
        </Section>
      ) : null}

      {sections.activities ? (
        <Section title={t("dashboard.day_detail.activities")}>
          <div className="flex flex-wrap gap-1.5">
            {entry.activities
              .filter((value) => value.trim().length > 0)
              .map((value, index) => (
                <Chip key={`${value}-${index}`}>{value}</Chip>
              ))}
          </div>
        </Section>
      ) : null}

      {sections.habits ? (
        <Section title={t("dashboard.day_detail.habits")}>
          <ul className="flex flex-col gap-1">
            {Object.entries(entry.habits).map(([key, done]) => {
              const habit = habitById.get(key);
              const label = habit
                ? `${habit.icon} ${
                    lang === "en" && habit.labelEn ? habit.labelEn : habit.label
                  }`
                : humanizeKey(key);
              return (
                <li
                  key={key}
                  className="flex items-center justify-between gap-3 py-0.5"
                >
                  <span className="flex min-w-0 items-center gap-1.5 text-[13px] text-[color:var(--text-1)]">
                    <span className="truncate">{label}</span>
                    {habit?.is_negative ? (
                      <span className="shrink-0 text-[10px] text-[color:var(--text-3)]">
                        {t("habits.negative_badge")}
                      </span>
                    ) : null}
                  </span>
                  <span
                    className={`shrink-0 text-[12px] font-medium ${
                      done ? "text-emerald-300" : "text-[color:var(--text-3)]"
                    }`}
                  >
                    {done
                      ? t("dashboard.day_detail.habit_done")
                      : t("dashboard.day_detail.habit_not_done")}
                  </span>
                </li>
              );
            })}
          </ul>
        </Section>
      ) : null}

      {sections.gratitude ? (
        <Section title={t("dashboard.day_detail.gratitude")}>
          <ul className="flex flex-col gap-0.5">
            {gratitude.map((line, index) => (
              <li
                key={`${line}-${index}`}
                className="text-[13px] leading-5 text-[color:var(--text-1)]"
              >
                <span aria-hidden="true" className="text-[color:var(--text-3)]">
                  –{" "}
                </span>
                {line}
              </li>
            ))}
          </ul>
        </Section>
      ) : null}

      {sections.note ? (
        <Section title={t("dashboard.day_detail.note")}>
          <p className="whitespace-pre-line text-[13px] leading-5 text-[color:var(--text-1)]">
            {entry.note}
          </p>
        </Section>
      ) : null}

      {sections.scales ? (
        <ScalesSection
          values={entry.scale_values}
          scales={scales}
          error={scalesError}
          onRetry={onRetry}
        />
      ) : null}

      {sections.phone && getFeatureFlags().screenTime ? (
        <Section title={t("dashboard.day_detail.screen_time")}>
          <div className="flex flex-col gap-0.5">
            {entry.phone_screen_time !== null ? (
              <MetricRow
                label={t("dashboard.day_detail.screen_time_row")}
                value={formatScreenTimeDuration(entry.phone_screen_time)}
              />
            ) : null}
            {entry.phone_unlocks !== null ? (
              <MetricRow
                label={t("dashboard.day_detail.unlocks_row")}
                value={String(entry.phone_unlocks)}
              />
            ) : null}
          </div>
          {entry.phone_top_apps && entry.phone_top_apps.length > 0 ? (
            <div className="mt-2">
              <p className="text-[11px] font-medium text-[color:var(--text-2)]">
                {t("dashboard.day_detail.top_apps")}
              </p>
              <ul className="mt-1 flex flex-col gap-0.5">
                {entry.phone_top_apps.slice(0, 5).map((app, index) => (
                  <li
                    key={`${app.app}-${index}`}
                    className="flex items-baseline justify-between gap-2 text-[12px]"
                  >
                    <span className="min-w-0 truncate text-[color:var(--text-1)]">
                      {app.app}
                    </span>
                    <span className="shrink-0 tabular-nums text-[color:var(--text-2)]">
                      {formatTopAppDuration(app.time_sec)}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </Section>
      ) : null}

      {sections.photo && entry.photo_path ? (
        <Section title={t("dashboard.day_detail.photo")}>
          <img
            src={entry.photo_path}
            alt={t("dashboard.day_detail.photo_row")}
            className="max-h-[240px] w-full rounded-xl border border-white/10 object-cover"
          />
        </Section>
      ) : null}

      {sections.reflection && entry.ai_reflection ? (
        <Section title={t("dashboard.day_detail.reflection")}>
          <div className="rounded-xl border border-white/[0.07] bg-black/20 px-3 py-2.5">
            <Markdown content={entry.ai_reflection} />
          </div>
        </Section>
      ) : null}

      <div className="mt-5">
        <ActionButton onClick={() => onEdit(date)}>
          {sections.recorded
            ? t("dashboard.day_detail.edit")
            : t("dashboard.day_detail.open_checkin")}
        </ActionButton>
      </div>
    </div>
  );
}
