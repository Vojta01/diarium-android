"use client";

/* eslint-disable @next/next/no-img-element */

import { type ReactNode } from "react";

import { Markdown } from "@/components/Markdown";
import {
  ActionButton,
  EmptyState,
  FOCUS_RING,
  MetricRow,
  withAlpha,
} from "@/components/dashboard/primitives";
import {
  formatScreenTimeDuration,
  formatTopAppDuration,
} from "@/lib/dashboard-format";
import { scaleValueText, weatherKey } from "@/lib/dashboard-ui";
import { getFeatureFlags } from "@/lib/feature-flags";
import {
  HISTORY_MOOD_COLORS,
  historyDetail,
  historyDetailDate,
  historyHumanizeKey,
  type HistoryDetailView,
} from "@/lib/history";
import { useTranslation } from "@/lib/i18n";
import { SLEEP_OPTIONS, STRESS_OPTIONS } from "@/lib/checkin/options";
import type { Scale } from "@/lib/scales";
import type { HabitDef } from "@/lib/supabase/db";
import { MOOD_EMOJIS, type DailyEntry } from "@/lib/stats";

/** Android's `moodColor()` — the calendar/graph palette, 5 = cyan. */
const MOOD_TINTS = HISTORY_MOOD_COLORS;

const UNRECORDED_ACCENT = "#6366f1";

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="mt-3.5">
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

function optionLabel(
  options: readonly { value: number; labelKey: string }[],
  value: number | null,
  t: (path: string) => string
): string {
  if (value === null) return "—";
  const match = options.find((option) => option.value === value);
  return match ? t(match.labelKey) : "—";
}

/** "☀️ Slunečno, 🌧️ Déšť"; an unknown legacy value stays verbatim. */
function weatherText(
  values: readonly string[],
  t: (path: string, params?: Record<string, string | number>) => string
): string {
  if (values.length === 0) return "—";
  return values
    .map((raw) => {
      const key = weatherKey(raw);
      // The weather translations already carry their icon (`🌧️ Déšť`), so no
      // second glyph is prepended here.
      return key ? t(`dashboard.weather.${key}`) : raw;
    })
    .join(", ");
}

function ScalesSection({
  view,
  scales,
  scalesError,
  onRetry,
}: {
  view: HistoryDetailView;
  scales: readonly Scale[] | null;
  scalesError: boolean;
  onRetry: () => void;
}) {
  const { t } = useTranslation();
  if (view.scales.length === 0) return null;

  if (scalesError) {
    return (
      <Section title={t("dashboard.day_detail.scales")}>
        <div role="alert" className="flex items-center justify-between gap-2">
          <span className="text-[12px] text-red-200">
            {t("dashboard.day_detail.scales_error")}
          </span>
          <button
            type="button"
            onClick={onRetry}
            className={`min-h-11 rounded-lg border border-white/10 px-3 text-[12px] text-[color:var(--text-1)] ${FOCUS_RING}`}
          >
            {t("history.retry")}
          </button>
        </div>
      </Section>
    );
  }

  const byId = new Map((scales ?? []).map((scale) => [scale.id, scale]));

  return (
    <Section title={t("dashboard.day_detail.scales")}>
      <div className="flex flex-col gap-1">
        {view.scales.map(({ id, value }) => {
          const scale = byId.get(id);
          const label = scale ? `${scale.emoji} ${scale.name}` : historyHumanizeKey(id);
          const text = `${scaleValueText(value, scale?.max_value)}${
            scale?.unit ? ` ${scale.unit}` : ""
          }`;
          return <MetricRow key={id} label={label} value={text} />;
        })}
      </div>
    </Section>
  );
}

/**
 * One day of the calendar, opened: what was written that day, read-only.
 *
 * The order and wording mirror Android's `ui/history/DayDetail.kt`:
 * date + mood → sleep / stress / weather → activities → habits → gratitude →
 * note → scales → screen time → photo → AI reflection → edit.
 *
 * A synced-only row (screen time, no mood) is listed but visually demoted: an
 * explicit "check-in not filled" note, a faded body and a create action
 * instead of "edit". A day with no row at all never becomes a card full of
 * dashes — it says there is no check-in and offers the way in.
 */
export function DayDetailCard({
  date,
  entry,
  habits,
  scales,
  scalesError,
  onRetryScales,
  onEdit,
}: {
  date: string;
  entry: DailyEntry | null;
  habits: readonly HabitDef[] | null;
  scales: readonly Scale[] | null;
  scalesError: boolean;
  onRetryScales: () => void;
  onEdit: (date: string) => void;
}) {
  const { t, lang } = useTranslation();

  if (!entry) {
    return (
      <section
        className="glass-card"
        aria-labelledby="history-day-detail-title"
      >
        <h2
          id="history-day-detail-title"
          className="text-[11px] font-semibold uppercase tracking-[1.1px] text-[color:var(--text-2)]"
        >
          {t("history.detail_title")}
        </h2>
        <p className="mt-1.5 text-[15px] font-semibold leading-5 text-[color:var(--text-1)]">
          {historyDetailDate(date, lang)}
        </p>
        <div className="mt-3">
          <EmptyState
            emoji="🗓️"
            title={t("dashboard.day_detail.empty_title")}
            message={t("dashboard.day_detail.empty_message")}
            action={
              <ActionButton onClick={() => onEdit(date)}>
                {t("dashboard.day_detail.open_checkin")}
              </ActionButton>
            }
          />
        </div>
      </section>
    );
  }

  const view = historyDetail(entry);
  const accent = view.mood ? MOOD_TINTS[view.mood] : UNRECORDED_ACCENT;
  const habitById = new Map((habits ?? []).map((habit) => [habit.key, habit]));

  return (
    <section
      className="glass-card"
      aria-labelledby="history-day-detail-title"
      style={{ borderColor: withAlpha(accent, 0.32) }}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2
            id="history-day-detail-title"
            className="text-[11px] font-semibold uppercase tracking-[1.1px] text-[color:var(--text-2)]"
          >
            {t("history.detail_title")}
          </h2>
          <p
            className={`mt-1.5 text-[15px] font-semibold leading-5 ${
              view.recorded
                ? "text-[color:var(--text-1)]"
                : "text-[color:var(--text-2)]"
            }`}
          >
            {historyDetailDate(date, lang)}
          </p>
        </div>
        {view.recorded && view.mood ? (
          <span
            aria-hidden="true"
            className="flex size-11 shrink-0 items-center justify-center rounded-full border text-[22px] leading-none"
            style={{
              backgroundColor: withAlpha(accent, 0.18),
              borderColor: withAlpha(accent, 0.45),
            }}
          >
            {MOOD_EMOJIS[view.mood]}
          </span>
        ) : null}
      </div>

      {!view.recorded ? (
        <div className="mt-3 rounded-xl border border-amber-400/25 bg-amber-500/[0.08] px-3 py-2.5">
          <p className="text-[12px] font-semibold text-amber-100">
            {t("dashboard.day_detail.unrecorded_hint")}
          </p>
          <p className="mt-0.5 text-[12px] leading-[18px] text-[color:var(--text-2)]">
            {t("dashboard.day_detail.unrecorded_body")}
          </p>
        </div>
      ) : null}

      <div className={view.recorded ? "" : "opacity-[0.72]"}>
        {view.recorded && view.mood ? (
          <p
            className="mt-3 text-[18px] font-semibold leading-6"
            style={{ color: MOOD_TINTS[view.mood] }}
          >
            {t(`mood.mood_${view.mood}`)}
          </p>
        ) : null}

        <div className="glass-divider my-3" />

        <MetricRow
          label={t("dashboard.day_detail.sleep")}
          value={optionLabel(SLEEP_OPTIONS, view.sleepQuality, t)}
        />
        <MetricRow
          label={t("dashboard.day_detail.stress")}
          value={optionLabel(STRESS_OPTIONS, view.stress, t)}
        />
        <MetricRow
          label={t("dashboard.day_detail.weather")}
          value={weatherText(view.weather, t)}
        />

        {view.activities.length > 0 ? (
          <Section title={t("dashboard.day_detail.activities")}>
            <div className="flex flex-wrap gap-1.5">
              {view.activities.map((value, index) => (
                <Chip key={`${value}-${index}`}>{value}</Chip>
              ))}
            </div>
          </Section>
        ) : null}

        {view.habits.length > 0 ? (
          <Section title={t("dashboard.day_detail.habits")}>
            <ul className="flex flex-col gap-1">
              {view.habits.map(({ key, done }) => {
                const habit = habitById.get(key);
                const label = habit
                  ? `${habit.icon} ${
                      lang === "en" && habit.labelEn ? habit.labelEn : habit.label
                    }`
                  : historyHumanizeKey(key);
                return (
                  <li
                    key={key}
                    className="flex items-center justify-between gap-3 py-0.5"
                  >
                    <span className="min-w-0 truncate text-[13px] text-[color:var(--text-1)]">
                      {label}
                    </span>
                    <span
                      className={`shrink-0 text-[12px] font-medium ${
                        done
                          ? "text-emerald-300"
                          : "text-[color:var(--text-3)]"
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

        {view.gratitude.length > 0 ? (
          <Section title={t("dashboard.day_detail.gratitude")}>
            <ul className="flex flex-col gap-0.5">
              {view.gratitude.map((line, index) => (
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

        {view.note.trim() ? (
          <Section title={t("dashboard.day_detail.note")}>
            <p className="whitespace-pre-line text-[13px] leading-5 text-[color:var(--text-1)]">
              {view.note}
            </p>
          </Section>
        ) : null}

        <ScalesSection
          view={view}
          scales={scales}
          scalesError={scalesError}
          onRetry={onRetryScales}
        />

        {view.phone && getFeatureFlags().screenTime ? (
          <Section title={t("dashboard.day_detail.screen_time")}>
            <div className="flex flex-col gap-0.5">
              {view.phone.screenTimeSeconds !== null ? (
                <MetricRow
                  label={t("dashboard.day_detail.screen_time_row")}
                  value={formatScreenTimeDuration(view.phone.screenTimeSeconds)}
                />
              ) : null}
              {view.phone.unlocks !== null ? (
                <MetricRow
                  label={t("dashboard.day_detail.unlocks_row")}
                  value={String(view.phone.unlocks)}
                />
              ) : null}
            </div>
            {view.phone.topApps.length > 0 ? (
              <div className="mt-2">
                <p className="text-[11px] font-medium text-[color:var(--text-2)]">
                  {t("dashboard.day_detail.top_apps")}
                </p>
                <ul className="mt-1 flex flex-col gap-0.5">
                  {view.phone.topApps.slice(0, 5).map((app, index) => (
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

        <Section title={t("dashboard.day_detail.photo")}>
          {view.photoSrc ? (
            <>
              <img
                src={view.photoSrc}
                alt={t("dashboard.day_detail.photo_row")}
                loading="lazy"
                className="max-h-[240px] w-full rounded-xl border border-white/10 object-cover"
              />
              <div className="mt-1">
                <MetricRow
                  label={t("dashboard.day_detail.photo_row")}
                  value={t("dashboard.day_detail.photo_attached")}
                />
              </div>
            </>
          ) : (
            <MetricRow
              label={t("dashboard.day_detail.photo_row")}
              value={t("dashboard.day_detail.photo_missing")}
              muted
            />
          )}
        </Section>

        {view.reflection ? (
          <Section title={t("dashboard.day_detail.reflection")}>
            <div className="rounded-xl border border-indigo-400/40 bg-indigo-500/[0.12] px-3 py-2.5">
              <Markdown content={view.reflection} />
            </div>
          </Section>
        ) : null}
      </div>

      <div className="mt-4">
        <ActionButton onClick={() => onEdit(date)}>
          {view.recorded
            ? t("dashboard.day_detail.edit")
            : t("dashboard.day_detail.open_checkin")}
        </ActionButton>
      </div>
    </section>
  );
}
