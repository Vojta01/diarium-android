/**
 * Shell navigation model for the web app.
 *
 * Mirrors the Android `TopLevelDestination` set: five main destinations plus
 * sub-screens that live outside the bottom bar. Everything here is pure so the
 * transitions can be unit-tested without a browser.
 */

export const MAIN_TABS = ["overview", "today", "history", "stats", "settings"] as const;
export type MainTab = (typeof MAIN_TABS)[number];

export const SUB_SCREENS = [
  "goals",
  "scales",
  "templates",
  "achievements",
  "reports",
  "export",
  "about",
] as const;
export type SubScreen = (typeof SUB_SCREENS)[number];

/**
 * Sub-screens with a real implementation in this build. The navigation model
 * knows every Android destination; this list decides which ones may render, so
 * a stale bookmark can never open an empty shell. Kept here (not in AppShell)
 * so the navigation tests can pin it without importing a client component.
 */
export const IMPLEMENTED_SUBS: readonly SubScreen[] = [
  "goals",
  "scales",
  "templates",
  "achievements",
  "reports",
  "about",
];

export interface NavState {
  tab: MainTab;
  /** Sub-screen rendered on top of its parent tab, or null for the tab itself. */
  sub: SubScreen | null;
  /** Date the Today tab should open (`?edit=YYYY-MM-DD`), null = today. */
  checkinDate: string | null;
}

export const DEFAULT_NAV: NavState = { tab: "overview", sub: null, checkinDate: null };

/**
 * Window event dispatched by the shell whenever the active destination changes.
 * Modals listen to it so a dialog can never stay open inside a hidden tab
 * panel after the user navigates (browser Back included).
 */
export const SHELL_NAVIGATION_EVENT = "diarium:shell-navigation";

/** i18n keys for the five bottom destinations (cs/en both define them). */
export const TAB_LABEL_KEYS: Record<MainTab, string> = {
  overview: "nav.overview",
  today: "nav.today",
  history: "nav.history",
  stats: "nav.stats",
  settings: "nav.settings",
};

export const SUB_LABEL_KEYS: Record<SubScreen, string> = {
  goals: "nav.goals",
  scales: "nav.scales",
  templates: "nav.templates",
  achievements: "nav.achievements",
  reports: "nav.reports",
  export: "nav.export",
  about: "nav.about",
};

/**
 * Tab a sub-screen belongs to. Back from a sub-screen returns here, and a
 * `?sub=` deep link opens the parent tab behind the chrome.
 */
export const SUB_PARENT_TAB: Record<SubScreen, MainTab> = {
  goals: "overview",
  scales: "overview",
  templates: "overview",
  achievements: "overview",
  reports: "stats",
  export: "settings",
  about: "settings",
};

export function isMainTab(value: string | null | undefined): value is MainTab {
  return value != null && (MAIN_TABS as readonly string[]).includes(value);
}

export function isSubScreen(value: string | null | undefined): value is SubScreen {
  return value != null && (SUB_SCREENS as readonly string[]).includes(value);
}

/** Strict `YYYY-MM-DD` check that rejects impossible dates such as 2026-02-31. */
export function isValidIsoDate(value: string | null | undefined): value is string {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}

export function parentTabForSub(sub: SubScreen): MainTab {
  return SUB_PARENT_TAB[sub];
}

/**
 * Parse the shell state from a location search string.
 * Unknown values fall back to the default destination instead of throwing, so a
 * stale bookmarked link never breaks the app.
 */
export function parseNavFromSearch(search: string): NavState {
  const params = new URLSearchParams(search.startsWith("?") ? search : `?${search}`);

  const subParam = params.get("sub");
  const sub = isSubScreen(subParam) ? subParam : null;

  const editParam = params.get("edit");
  const checkinDate = isValidIsoDate(editParam) ? editParam : null;

  const viewParam = params.get("view");
  const explicitTab = isMainTab(viewParam) ? viewParam : null;

  // `?edit=` alone means "open Today on that date", matching the old deep link.
  // An explicit `?view=` wins, so `?view=settings&sub=achievements` reopens the
  // same stack the user was on before a reload.
  const tab = explicitTab ?? (sub ? parentTabForSub(sub) : checkinDate ? "today" : "overview");

  return { tab, sub, checkinDate };
}

/**
 * Serialize shell state into a location search string.
 * The default destination serializes to an empty string (clean `/` URL).
 */
export function navToSearch(nav: NavState): string {
  const params = new URLSearchParams();
  if (nav.tab !== "overview") params.set("view", nav.tab);
  if (nav.sub) params.set("sub", nav.sub);
  if (nav.tab === "today" && nav.checkinDate) params.set("edit", nav.checkinDate);

  const query = params.toString();
  return query ? `?${query}` : "";
}

/** Switch bottom destination; any open sub-screen closes with it. */
export function selectTab(nav: NavState, tab: MainTab): NavState {
  // Tapping a bottom destination always opens its default state. In particular
  // "Today" means today — a stale `?edit=` date from an earlier deep link must
  // not keep the destination pinned to that day.
  if (nav.tab === tab && nav.sub === null && nav.checkinDate === null) return nav;
  return {
    tab,
    sub: null,
    checkinDate: null,
  };
}

/**
 * Drop sub-screens that are not implemented in this build. A stale bookmark
 * (`?sub=goals`) must fall back to the parent tab instead of rendering an empty
 * sub-screen.
 */
export function normalizeNav(
  state: NavState,
  implemented: readonly SubScreen[]
): NavState {
  return state.sub && !implemented.includes(state.sub) ? closeSub(state) : state;
}

/** Open the Today tab on a specific date (dashboard day tap, calendar day tap). */
export function openCheckin(nav: NavState, date: string | null): NavState {
  return { tab: "today", sub: null, checkinDate: isValidIsoDate(date) ? date : null };
}

/**
 * Address-bar state after a check-in save completes.
 *
 * The rendered state deliberately keeps `checkinDate`: the mounted check-in
 * screen must stay on the day that was just saved so its confirmation card
 * remains visible. Only the one-shot `?edit=` deep link is dropped, so a reload
 * starts on today instead of reopening a stale edit.
 *
 * When the user has already navigated to another destination this returns the
 * same object, so a late save completion can never pull them back.
 */
export function navAfterCheckinSave(nav: NavState): NavState {
  return nav.tab === "today" && nav.checkinDate !== null
    ? { ...nav, checkinDate: null }
    : nav;
}

export function openSub(nav: NavState, sub: SubScreen): NavState {
  return { ...nav, sub };
}

export function closeSub(nav: NavState): NavState {
  return nav.sub === null ? nav : { ...nav, sub: null };
}

/**
 * DOM id of the panel a bottom-nav tab controls. Shared by `BottomNav`
 * (`aria-controls`) and `AppShell` (panel `id`) so the two can never drift.
 */
export function shellPanelId(tab: MainTab): string {
  return `shell-panel-${tab}`;
}

/** Stable key for per-destination scroll memory. */
export function navScrollKey(nav: NavState): string {
  return nav.sub ? `sub:${nav.sub}` : `tab:${nav.tab}`;
}
