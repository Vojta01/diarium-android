/**
 * Localized calendar labels.
 *
 * `t()` can only return strings, so month/day name arrays cannot live in the
 * translation dictionaries. Intl.DateTimeFormat is the single source of truth
 * for both Czech and English labels; UTC noon avoids DST/time-zone drift.
 */

const LOCALES = { cs: "cs-CZ", en: "en-US" } as const;
export type CalendarLocale = keyof typeof LOCALES;

export function intlLocale(lang: "cs" | "en"): string {
  return LOCALES[lang];
}

function utcDate(year: number, monthIndex: number, day: number): Date {
  return new Date(Date.UTC(year, monthIndex, day, 12));
}

/** Full month names, January first. */
export function monthNames(lang: "cs" | "en", year = 2024): string[] {
  const formatter = new Intl.DateTimeFormat(intlLocale(lang), {
    month: "long",
    timeZone: "UTC",
  });
  return Array.from({ length: 12 }, (_, month) => formatter.format(utcDate(year, month, 15)));
}

/** One month name, 0 = January. */
export function monthName(lang: "cs" | "en", monthIndex: number, year = 2024): string {
  return new Intl.DateTimeFormat(intlLocale(lang), {
    month: "long",
    timeZone: "UTC",
  }).format(utcDate(year, monthIndex, 15));
}

/** Short weekday names in the app's Monday-first order. */
export function weekdayNames(lang: "cs" | "en"): string[] {
  const formatter = new Intl.DateTimeFormat(intlLocale(lang), {
    weekday: "short",
    timeZone: "UTC",
  });
  // 2024-01-01 was a Monday.
  return Array.from({ length: 7 }, (_, day) => formatter.format(utcDate(2024, 0, 1 + day)));
}

/** Full weekday names in Monday-first order, for accessible abbreviations. */
export function weekdayFullNames(lang: "cs" | "en"): string[] {
  const formatter = new Intl.DateTimeFormat(intlLocale(lang), {
    weekday: "long",
    timeZone: "UTC",
  });
  // 2024-01-01 was a Monday.
  return Array.from({ length: 7 }, (_, day) => formatter.format(utcDate(2024, 0, 1 + day)));
}
