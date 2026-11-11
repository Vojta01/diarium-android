/**
 * Android-order Settings model.
 *
 * `DiariumApp.kt` renders: Language, Deník, Notifikace, Přehledy, AI, Data,
 * Account. The web adds one honest "O aplikaci" row (Android has no About
 * screen) and keeps Account last. This list is the single source of the order;
 * `SettingsView` renders it, and the model test pins it.
 */
import type { SubScreen } from "./navigation.ts";

export const SETTINGS_SECTIONS = [
  { id: "language", titleKey: "settings.language" },
  { id: "journal", titleKey: "settings.journal" },
  { id: "notifications", titleKey: "settings.notifications" },
  { id: "reports", titleKey: "settings.reports" },
  { id: "ai", titleKey: "settings.ai" },
  { id: "data", titleKey: "settings.data" },
  { id: "about", titleKey: "settings.about_app" },
  { id: "account", titleKey: "settings.account" },
] as const;

export type SettingsSectionId = (typeof SETTINGS_SECTIONS)[number]["id"];

export interface SettingsRowModel {
  id: string;
  emoji: string;
  labelKey: string;
  hintKey: string;
  sub: SubScreen;
}

/** Rows inside the Deník card, in Android's order. */
export const JOURNAL_ROWS: readonly SettingsRowModel[] = [
  {
    id: "goals",
    emoji: "🎯",
    labelKey: "nav.goals",
    hintKey: "settings.hint_goals",
    sub: "goals",
  },
  {
    id: "scales",
    emoji: "📏",
    labelKey: "nav.scales",
    hintKey: "settings.hint_scales",
    sub: "scales",
  },
  {
    id: "templates",
    emoji: "📝",
    labelKey: "nav.templates",
    hintKey: "settings.hint_templates",
    sub: "templates",
  },
  {
    id: "achievements",
    emoji: "🏆",
    labelKey: "nav.achievements",
    hintKey: "settings.hint_achievements",
    sub: "achievements",
  },
];

/** The only row in the Reports card. */
export const REPORTS_ROW: SettingsRowModel = {
  id: "reports",
  emoji: "🤖",
  labelKey: "settings.reports_row",
  hintKey: "settings.reports_row_hint",
  sub: "reports",
};

/** Every sub-screen reachable from Settings, for navigation tests. */
export const SETTINGS_SUBS: readonly SubScreen[] = [
  ...JOURNAL_ROWS.map((row) => row.sub),
  REPORTS_ROW.sub,
  "about",
];
