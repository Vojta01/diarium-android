import type { MetricDef, MetricRender, MetricUnit } from "./metric-series.ts";
import type { DiffEstimate } from "./uncertainty.ts";

type Lang = "cs" | "en";

function number(value: number, lang: Lang, digits: number): string {
  return new Intl.NumberFormat(lang === "cs" ? "cs-CZ" : "en-GB", {
    maximumFractionDigits: digits,
    minimumFractionDigits: 0,
  })
    .format(value)
    .replace(/^-/, "\u2212");
}

function unit(def: MetricDef, lang: Lang): string {
  if (def.unit === "minutes") return lang === "cs" ? " min" : " min";
  return "";
}

function digitsFor(def: MetricDef, value: number): number {
  // Daily counts are integers, but their period means and differences need not be.
  if (def.unit === "count") return 1;
  if (def.unit === "minutes") return Math.abs(value) >= 10 ? 0 : 1;
  return 1;
}

function signed(value: number, lang: Lang, def: MetricDef): string {
  const digits = digitsFor(def, value);
  const magnitude = number(Math.abs(value), lang, digits);
  if (value > 0) return `+${magnitude}${unit(def, lang)}`;
  if (value < 0) return `\u2212${magnitude}${unit(def, lang)}`;
  return `0${unit(def, lang)}`;
}

/** One metric value for display; null is always an em dash, never zero. */
export function formatMetric(
  value: number | null,
  def: MetricDef,
  lang: Lang
): string {
  if (value === null || !Number.isFinite(value)) return "—";
  const digits = digitsFor(def, value);
  return `${number(value, lang, digits)}${unit(def, lang)}`;
}

/** A signed difference with the unit; the only signed formatter rows use. */
export function formatDiff(diff: number, def: MetricDef, lang: Lang = "en"): string {
  return signed(diff, lang, def);
}

/** Absolute size without a plus sign, for prose like "0.6 higher". */
export function formatMagnitude(
  value: number,
  def: MetricDef,
  lang: Lang = "en"
): string {
  return `${number(Math.abs(value), lang, digitsFor(def, value))}${unit(def, lang)}`;
}

/**
 * The bare interval (`0.1–1.1`) used inside prose. Null when there is no
 * honest interval, so callers never print a fabricated range.
 */
export function formatInterval(
  est: DiffEstimate,
  def: MetricDef,
  lang: Lang = "en"
): string | null {
  if (est.ciLow === null || est.ciHigh === null) return null;
  const low = number(est.ciLow, lang, digitsFor(def, est.ciLow));
  const high = number(est.ciHigh, lang, digitsFor(def, est.ciHigh));
  return `${low}–${high}${unit(def, lang)}`;
}

/**
 * `95 % CI 0.1–1.1` for a real interval; null when there is no honest one.
 * A degenerate estimate deliberately produces no CI text: a zero-width
 * interval would read as certainty.
 */
export function formatCi(
  est: DiffEstimate,
  def: MetricDef,
  lang: Lang = "en"
): string | null {
  const interval = formatInterval(est, def, lang);
  return interval === null ? null : `95 % CI ${interval}`;
}

/** `24/30`, the coverage fraction without pretending it is a percentage. */
export function formatCoverage(n: number, days: number): string {
  return `${n}/${days}`;
}

/** Locale short date used by day chips and the timeline readout. */
export function formatDayLabel(date: string, lang: Lang): string {
  const parsed = new Date(`${date}T12:00:00Z`);
  return new Intl.DateTimeFormat(lang === "cs" ? "cs-CZ" : "en-GB", {
    day: "numeric",
    month: "short",
    timeZone: "UTC",
  }).format(parsed);
}

/** Long localized date for the day peek heading. */
export function formatLongDate(date: string, lang: Lang): string {
  const parsed = new Date(`${date}T12:00:00Z`);
  return new Intl.DateTimeFormat(lang === "cs" ? "cs-CZ" : "en-GB", {
    day: "numeric",
    month: "long",
    year: "numeric",
    timeZone: "UTC",
  }).format(parsed);
}

/** The unit word used in prose, e.g. "minutes". */
export function unitLabel(def: MetricDef, lang: Lang): string {
  const labels: Record<MetricUnit, { cs: string; en: string }> = {
    points: { cs: "body", en: "points" },
    minutes: { cs: "min", en: "min" },
    count: { cs: "krát", en: "times" },
  };
  return labels[def.unit][lang];
}

/** Render kind, exported so the UI never re-derives it from the key. */
export function renderOf(def: MetricDef): MetricRender {
  return def.render;
}
