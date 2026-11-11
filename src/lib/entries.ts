/**
 * The five rows of the Overview's final Entries card.
 *
 * Android beta5 also lists Notifications here. The web intentionally omits that
 * row: notification settings are not a standalone sub-screen in this build —
 * they live in the Settings screen and in the shell header button. Shipping a
 * sixth row that opened nothing would be a dead link, so the card stays at the
 * five destinations that have a real implementation.
 */
import type { SubScreen } from "./navigation.ts";

export interface DashboardEntryRow {
  sub: SubScreen;
  /** Emoji shown in the row; decorative, the label carries the meaning. */
  emoji: string;
  labelKey: string;
  hintKey: string;
}

/** Exact visual order of the Entries card, top to bottom. */
export const DASHBOARD_ENTRY_ROWS: readonly DashboardEntryRow[] = [
  {
    sub: "goals",
    emoji: "🎯",
    labelKey: "dashboard.entries.goals",
    hintKey: "dashboard.entries.goals_hint",
  },
  {
    sub: "scales",
    emoji: "📊",
    labelKey: "dashboard.entries.scales",
    hintKey: "dashboard.entries.scales_hint",
  },
  {
    sub: "templates",
    emoji: "📝",
    labelKey: "dashboard.entries.templates",
    hintKey: "dashboard.entries.templates_hint",
  },
  {
    sub: "achievements",
    emoji: "🏆",
    labelKey: "dashboard.entries.achievements",
    hintKey: "dashboard.entries.achievements_hint",
  },
  {
    sub: "reports",
    emoji: "🤖",
    labelKey: "dashboard.entries.reports",
    hintKey: "dashboard.entries.reports_hint",
  },
];

/** The sub-screens the Entries card opens, in the same order. */
export const DASHBOARD_ENTRY_SUBS: readonly SubScreen[] = DASHBOARD_ENTRY_ROWS.map(
  (row) => row.sub
);
