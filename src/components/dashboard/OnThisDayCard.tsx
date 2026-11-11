"use client";

/* eslint-disable @next/next/no-img-element */

import { useState } from "react";

import type { DashboardData } from "@/lib/dashboard";
import {
  formatDashboardDate,
  memoryGratitudeLines,
  memoryPhotoSrc,
} from "@/lib/dashboard-ui";
import { useTranslation } from "@/lib/i18n";
import { isRecordedMood } from "@/lib/mood";
import { MOOD_EMOJIS } from "@/lib/stats";

import { ACCENT, Card, Divider, Hint, Lead, SecondaryButton } from "./primitives";

type Memory = NonNullable<DashboardData["onThisDay"]>;

/**
 * The memory's photo with a real failure state.
 *
 * `photo_path` is a public Storage URL or an inline data URL (the check-in's
 * fallback), so the browser fetches it without the Next image pipeline. A
 * broken URL must not leave a torn image in the card: the placeholder keeps
 * the card's height stable and says what happened instead.
 */
function MemoryPhoto({ src, alt }: { src: string | null; alt: string }) {
  const { t } = useTranslation();
  const [failed, setFailed] = useState(false);

  if (!src || failed) {
    return (
      <div
        role="img"
        aria-label={alt}
        className="mt-2.5 flex h-[140px] w-full items-center justify-center gap-2 rounded-xl border border-white/10 bg-white/[0.04] px-3 text-center text-[12px] leading-[18px] text-[color:var(--text-3)]"
      >
        <span aria-hidden="true" className="shrink-0 text-[20px] leading-none">
          📷
        </span>
        <span className="min-w-0">{t("dashboard.on_this_day.photo_unavailable")}</span>
      </div>
    );
  }

  return (
    <img
      src={src}
      alt={alt}
      loading="lazy"
      decoding="async"
      onError={() => setFailed(true)}
      className="mt-2.5 h-[140px] w-full max-w-full rounded-xl border border-white/10 bg-white/[0.04] object-cover"
    />
  );
}

/** 🕰️ One memory from a year, six months or a month ago. */
export function OnThisDayCard({
  memory,
  onOpenDay,
}: {
  memory: Memory;
  onOpenDay: (date: string) => void;
}) {
  const { t, lang } = useTranslation();
  const mood = isRecordedMood(memory.entry.mood) ? memory.entry.mood : null;
  const note = memory.entry.note.trim();
  const gratitude = memoryGratitudeLines(memory.entry);
  const photoPath = memory.entry.photo_path?.trim() ?? "";
  const photoSrc = memoryPhotoSrc(memory.entry);
  const date = formatDashboardDate(memory.date, lang, "long");
  const photoDate = formatDashboardDate(memory.date, lang, "numeric");
  // A memory may qualify through gratitude or a photo alone. In that case the
  // card shows that content and never the "no note" placeholder — Android does
  // the same, and an empty card with a note apology would hide the memory.
  const hasBody = note.length > 0 || gratitude.length > 0 || photoPath.length > 0;

  return (
    <Card
      cardId="on_this_day"
      title={t("dashboard.on_this_day.title")}
      accent={ACCENT.violet}
      className="h-full"
    >
      <div className="mt-1">
        <Lead>{t(`dashboard.on_this_day.horizon_${memory.horizon}`)}</Lead>
        <Hint>{date}</Hint>
      </div>
      <div className="mt-3 flex items-start gap-2.5">
        <span aria-hidden="true" className="shrink-0 text-[26px] leading-none">
          {mood ? MOOD_EMOJIS[mood] : "🕰️"}
        </span>
        <div className="min-w-0 flex-1">
          {mood ? (
            <p className="text-[12px] font-medium text-[color:var(--text-2)]">
              {t(`mood.mood_${mood}`)}
            </p>
          ) : null}
          {note ? (
            <p className="mt-0.5 line-clamp-4 whitespace-pre-line break-words text-[13px] leading-5 text-[color:var(--text-1)]">
              {note}
            </p>
          ) : null}
          {gratitude.length > 0 ? (
            <ul
              className={`flex min-w-0 flex-col gap-0.5 ${
                note ? "mt-1.5" : "mt-0.5"
              }`}
            >
              {gratitude.map((line, index) => (
                <li
                  key={`${index}-${line}`}
                  className="flex min-w-0 items-baseline gap-1 text-[12px] leading-[18px] text-[color:var(--text-2)]"
                >
                  <span aria-hidden="true" className="shrink-0">
                    🙏
                  </span>
                  <span className="min-w-0 truncate">{line}</span>
                </li>
              ))}
            </ul>
          ) : null}
          {!hasBody ? (
            <p className="mt-0.5 text-[13px] italic leading-5 text-[color:var(--text-3)]">
              {t("dashboard.on_this_day.no_note")}
            </p>
          ) : null}
        </div>
      </div>
      {photoPath ? (
        <MemoryPhoto
          key={photoSrc ?? "unavailable"}
          src={photoSrc}
          alt={t("dashboard.on_this_day.photo_alt", { date: photoDate })}
        />
      ) : null}
      <Divider />
      <SecondaryButton onClick={() => onOpenDay(memory.date)}>
        {t("dashboard.on_this_day.open")}
      </SecondaryButton>
    </Card>
  );
}
