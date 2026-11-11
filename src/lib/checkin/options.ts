/**
 * Canonical check-in picker options and grouping.
 *
 * Android's check-in reads these from `PickerDefaults`; the web keeps them here
 * so the section components stay declarative and the order/labels can be tested
 * without a DOM. Weather is a first-class picker, not an activity category.
 */
import { WEATHER_KEYS } from "../dashboard-ui.ts";
import type { ActivityDef } from "../supabase/db.ts";

export interface EmojiOption {
  value: number;
  emoji: string;
  /** i18n key for the visible label. */
  labelKey: string;
  /** Optional tint used for the selected state (never the only signal). */
  color?: string;
}

/** 5 = best … 1 = worst, matching the stored `mood` scale. */
export const MOOD_OPTIONS: readonly EmojiOption[] = [
  { value: 5, emoji: "😄", labelKey: "mood.mood_5", color: "#22c55e" },
  { value: 4, emoji: "🙂", labelKey: "mood.mood_4", color: "#3b82f6" },
  { value: 3, emoji: "😐", labelKey: "mood.mood_3", color: "#eab308" },
  { value: 2, emoji: "😟", labelKey: "mood.mood_2", color: "#f97316" },
  { value: 1, emoji: "😡", labelKey: "mood.mood_1", color: "#ef4444" },
];

/** 3 = slept well … 1 = slept badly. */
export const SLEEP_OPTIONS: readonly EmojiOption[] = [
  { value: 3, emoji: "😴", labelKey: "sleep.sleep_3" },
  { value: 2, emoji: "🥱", labelKey: "sleep.sleep_2" },
  { value: 1, emoji: "😪", labelKey: "sleep.sleep_1" },
];

/** 1 = calm … 5 = extreme stress. */
export const STRESS_OPTIONS: readonly EmojiOption[] = [
  { value: 1, emoji: "😌", labelKey: "stress.stress_1" },
  { value: 2, emoji: "🙂", labelKey: "stress.stress_2" },
  { value: 3, emoji: "😐", labelKey: "stress.stress_3" },
  { value: 4, emoji: "😰", labelKey: "stress.stress_4" },
  { value: 5, emoji: "😤", labelKey: "stress.stress_5" },
];

/** Weather chips store these catalogue keys, never their labels. */
export const WEATHER_OPTIONS: readonly { key: string; icon: string }[] = [
  { key: "slunecno", icon: "☀️" },
  { key: "zatazeno", icon: "☁️" },
  { key: "dest", icon: "🌧️" },
  { key: "snih", icon: "❄️" },
  { key: "mraz", icon: "🥶" },
  { key: "horko", icon: "🌡️" },
  { key: "bourka", icon: "🌩️" },
  { key: "vitr", icon: "💨" },
];

/**
 * Activity group order. `počasí` is deliberately absent: weather has its own
 * section and must not be duplicated as an activity (Android filters it out).
 */
export const ACTIVITY_CATEGORY_ORDER = [
  "sociální",
  "práce",
  "volný čas",
  "sport",
  "jídlo",
  "zdraví",
  "wellness",
  "domácí práce",
  "vlastní",
  "obecné",
] as const;

const WEATHER_CATEGORIES = new Set(["počasí", "pocasi", "weather"]);
const WEATHER_KEY_SET = new Set<string>(WEATHER_KEYS);

/** True when an activity definition is really a weather option. */
export function isWeatherActivity(def: {
  key?: string;
  category?: string;
}): boolean {
  const category = (def.category ?? "").trim().toLowerCase();
  if (WEATHER_CATEGORIES.has(category)) return true;
  return typeof def.key === "string" && WEATHER_KEY_SET.has(def.key);
}

/** Group activity definitions by category, weather excluded, order pinned. */
export function groupActivities(
  defs: readonly ActivityDef[],
  getCategoryName: (key: string) => string
): { key: string; title: string; items: ActivityDef[] }[] {
  const groups = new Map<string, ActivityDef[]>();
  for (const def of defs) {
    if (isWeatherActivity(def)) continue;
    const category = (def.category ?? "").trim() || "obecné";
    const list = groups.get(category);
    if (list) list.push(def);
    else groups.set(category, [def]);
  }

  const known = ACTIVITY_CATEGORY_ORDER.filter((category) => groups.has(category));
  // Keep unknown server categories visible instead of silently dropping them.
  const unknown = [...groups.keys()]
    .filter((category) => !(ACTIVITY_CATEGORY_ORDER as readonly string[]).includes(category))
    .sort((a, b) => a.localeCompare(b));

  return [...known, ...unknown].map((category) => ({
    key: category,
    title: getCategoryName(category),
    items: groups.get(category) ?? [],
  }));
}

/** Display label for a definition, preferring the English label. */
export function definitionLabel(
  def: { label: string; labelEn?: string },
  lang: string
): string {
  return lang === "en" ? def.labelEn || def.label : def.label;
}

/**
 * Category label with a safe fallback: a server category outside the known
 * catalogue is shown verbatim instead of leaking a raw `categoryGroups.*` key.
 */
export function categoryLabel(
  key: string,
  translate: (key: string) => string
): string {
  return (ACTIVITY_CATEGORY_ORDER as readonly string[]).includes(key)
    ? translate(`categoryGroups.${key}`)
    : key;
}

/**
 * Multi-select toggle for canonical weather keys.
 *
 * Keeps the catalogue order (never the click order) and never duplicates, so a
 * stored row and a freshly tapped selection serialise identically.
 */
export function toggleWeatherKey(
  selected: readonly string[],
  key: string
): string[] {
  const chosen = new Set(selected);
  if (chosen.has(key)) chosen.delete(key);
  else chosen.add(key);
  const known = WEATHER_OPTIONS.map((option) => option.key).filter((option) =>
    chosen.has(option)
  );
  // Preserve anything the catalogue does not know rather than dropping it.
  const unknown = [...chosen].filter(
    (option) => !WEATHER_OPTIONS.some((candidate) => candidate.key === option)
  );
  return [...known, ...unknown];
}
