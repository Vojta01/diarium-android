/** Check-in date guards. `pragueDateKey` remains the single source of "today". */
import { PRAGUE_TIME_ZONE, pragueDateKey, shiftDateKey } from "../date.ts";

const DATE_KEY = /^(\d{4})-(\d{2})-(\d{2})$/;

const pragueHourFormatter = new Intl.DateTimeFormat("en-GB", {
  timeZone: PRAGUE_TIME_ZONE,
  hour: "2-digit",
  hour12: false,
});

/** True only for a real `YYYY-MM-DD` calendar date. */
export function isValidDateKey(value: unknown): value is string {
  if (typeof value !== "string") return false;
  const match = DATE_KEY.exec(value);
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const date = new Date(Date.UTC(year, month - 1, day));
  return (
    date.getUTCFullYear() === year &&
    date.getUTCMonth() === month - 1 &&
    date.getUTCDate() === day
  );
}

/**
 * A check-in can only open a real date that is not in the future. An invalid or
 * future `?edit=` value falls back to today instead of rendering a phantom day.
 */
export function clampCheckInDate(
  value: string | null | undefined,
  today: string = pragueDateKey()
): string {
  if (!isValidDateKey(value)) return today;
  return value > today ? today : value;
}

/**
 * Server-side guard for the write path. The UI clamps dates, but a direct API
 * caller must still be rejected: a future `entries` row would poison streaks,
 * goals and the dashboard's "today" queries.
 */
export function isFutureDateKey(
  value: string,
  today: string = pragueDateKey()
): boolean {
  return isValidDateKey(value) && value > today;
}

/** Hour 0–23 in Europe/Prague. Used by the after-midnight hint. */
export function pragueHour(instant: Date = new Date()): number {
  const hour = pragueHourFormatter
    .formatToParts(instant)
    .find((part) => part.type === "hour")?.value;
  const parsed = Number(hour);
  // `24` is a legal Intl hour at midnight in some ICU versions.
  return Number.isFinite(parsed) ? parsed % 24 : 0;
}

/** True between midnight and 04:00 Prague — Android's late-night window. */
export function isLateNight(instant: Date = new Date()): boolean {
  return pragueHour(instant) < 4;
}

/** `today` / `yesterday` / `tomorrow` for the date card, else null. */
export function relativeDateKey(
  date: string,
  today: string = pragueDateKey()
): "today" | "yesterday" | "tomorrow" | null {
  if (date === today) return "today";
  if (date === shiftDateKey(today, -1)) return "yesterday";
  if (date === shiftDateKey(today, 1)) return "tomorrow";
  return null;
}

/** Long, language-aware label for a date key (UTC anchor, no DST surprises). */
export function formatCheckInDate(date: string, lang: string): string {
  const [year, month, day] = date.split("-").map(Number);
  return new Intl.DateTimeFormat(lang === "cs" ? "cs-CZ" : "en-US", {
    day: "numeric",
    month: "long",
    year: "numeric",
    timeZone: "UTC",
  }).format(new Date(Date.UTC(year, month - 1, day)));
}
