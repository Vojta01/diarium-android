"use client";

import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import type { User } from "@supabase/supabase-js";

import { AchievementsPanel } from "@/components/AchievementsPanel";
import { Dashboard } from "@/components/Dashboard";
import { ExternalLink } from "@/components/ExternalLink";
import { HistoryView } from "@/components/history/HistoryView";
import { LanguageSwitcher } from "@/components/LanguageSwitcher";
import { CheckInScreen } from "@/components/checkin/CheckInScreen";
import { AboutScreen } from "@/components/settings/AboutScreen";
import { SettingsView } from "@/components/settings/SettingsView";
import { GoalsScreen } from "@/components/screens/GoalsScreen";
import { ReportsScreen } from "@/components/screens/ReportsScreen";
import { ScalesScreen } from "@/components/screens/ScalesScreen";
import { TemplatesScreen } from "@/components/screens/TemplatesScreen";
import { BottomNav } from "@/components/shell/BottomNav";
import { StatsDashboard } from "@/components/StatsDashboard";
import { ScreenHeader } from "@/components/ui/ScreenHeader";
import { SubScreenChrome } from "@/components/ui/SubScreenChrome";
import { AndroidIcon, GitHubIcon } from "@/components/ui/icons";
import { restoreFocus } from "@/lib/focus";
import { useTranslation } from "@/lib/i18n";
import { ANDROID_APK_URL, GITHUB_WEB_URL } from "@/lib/links";
import {
  DEFAULT_NAV,
  IMPLEMENTED_SUBS,
  SHELL_NAVIGATION_EVENT,
  SUB_LABEL_KEYS,
  TAB_LABEL_KEYS,
  closeSub,
  isMainTab,
  isSubScreen,
  navScrollKey,
  navToSearch,
  navAfterCheckinSave,
  normalizeNav,
  openCheckin,
  openSub,
  parseNavFromSearch,
  selectTab,
  shellPanelId,
  type MainTab,
  type NavState,
  type SubScreen,
} from "@/lib/navigation";

/**
 * The shell renders a sub-screen only when it is in the navigation model's
 * implemented list; AppShell adds no second allowlist.
 */
interface AppShellProps {
  user: User;
  initialNav?: NavState;
  onLogout: () => void;
}

/**
 * Authenticated app shell: five bottom destinations, Android-beta5 chrome and
 * per-destination scroll memory. Main tabs stay mounted once visited, so
 * switching destinations never refetches or loses a half-written check-in.
 */
export function AppShell({ user, initialNav = DEFAULT_NAV, onLogout }: AppShellProps) {
  const { t, lang } = useTranslation();

  const [nav, setNav] = useState<NavState>(() => normalizeNav(initialNav, IMPLEMENTED_SUBS));
  const [visited, setVisited] = useState<Set<MainTab>>(() => new Set([nav.tab]));
  // Identity of the mounted check-in screen. `date` is what the screen opened
  // with; `nonce` changes only when a *new* check-in session starts. Saving must
  // not change either one, or the confirmation card would be unmounted and the
  // screen would silently jump back to today.
  const [checkin, setCheckin] = useState<{ date: string | null; nonce: number }>(() => ({
    date: nav.checkinDate,
    nonce: 0,
  }));

  const initialNavRef = useRef(nav);
  const subPushedRef = useRef(false);
  // Refs mirror the committed navigation/session so async callbacks (a save
  // that resolves after a tab switch) never act on a stale render closure.
  const navRef = useRef(nav);
  const checkinRef = useRef(checkin);
  const checkinDraftNavigationGuard = useRef<(() => Promise<boolean>) | null>(null);

  const registerCheckinDraftNavigationGuard = useCallback(
    (guard: () => Promise<boolean>) => {
      checkinDraftNavigationGuard.current = guard;
      return () => {
        if (checkinDraftNavigationGuard.current === guard) {
          checkinDraftNavigationGuard.current = null;
        }
      };
    },
    []
  );

  // Focus return: remember the control that opened a sub-screen and restore it
  // after Back/browser popstate, but never steal focus after a tab switch.
  const focusOpenerRef = useRef<HTMLElement | null>(null);
  const pendingFocusRestoreRef = useRef(false);

  const rememberFocusOpener = useCallback(() => {
    const active = typeof document === "undefined" ? null : document.activeElement;
    focusOpenerRef.current = active instanceof HTMLElement ? active : null;
  }, []);

  const forgetFocusOpener = useCallback(() => {
    focusOpenerRef.current = null;
    pendingFocusRestoreRef.current = false;
  }, []);

  const scheduleFocusRestore = useCallback(() => {
    // A deep link has no opener element, but Back still unmounts the sub-screen
    // and must not leave focus on the removed Back button. The effect below
    // falls back to the active bottom tab when there is no opener to restore.
    pendingFocusRestoreRef.current = true;
  }, []);

  const markVisited = useCallback((tab: MainTab) => {
    setVisited((previous) =>
      previous.has(tab) ? previous : new Set(previous).add(tab)
    );
  }, []);

  const commitNav = useCallback((next: NavState) => {
    navRef.current = next;
    setNav(next);
  }, []);

  const startCheckinSession = useCallback((date: string | null) => {
    const next = { date, nonce: checkinRef.current.nonce + 1 };
    checkinRef.current = next;
    setCheckin(next);
  }, []);

  const activeKey = navScrollKey(nav);
  const scrollPositions = useRef<Record<string, number>>({});
  const suppressScrollSave = useRef(false);
  const previousKey = useRef(activeKey);

  const urlFor = useCallback(
    (state: NavState) => `${window.location.pathname}${navToSearch(state)}`,
    []
  );

  const writeHistory = useCallback(
    (state: NavState, mode: "push" | "replace", urlState: NavState = state) => {
      const method = mode === "push" ? "pushState" : "replaceState";
      window.history[method]({ diariumNav: state }, "", urlFor(urlState));
    },
    [urlFor]
  );

  // Seed the first history entry so browser back can return to it.
  useEffect(() => {
    const initial = initialNavRef.current;
    window.history.replaceState({ diariumNav: initial }, "", urlFor(initial));
  }, [urlFor]);

  // Notify overlays (and later data screens) when the destination changes.
  // Modals portal outside the hidden tab panel, so without this a dialog could
  // stay visible after the shell navigates away from its screen.
  const navKey = `${nav.tab}:${nav.sub ?? ""}`;
  const lastNavKeyRef = useRef(navKey);
  useEffect(() => {
    if (lastNavKeyRef.current === navKey) return;
    lastNavKeyRef.current = navKey;
    window.dispatchEvent(new CustomEvent(SHELL_NAVIGATION_EVENT, { detail: nav }));
  }, [nav, navKey]);

  // Browser back/forward: restore the destination stored in the history entry.
  // An open sub-screen must close instead of leaving the app.
  useEffect(() => {
    const onPopState = async (event: PopStateEvent) => {
      const current = navRef.current;
      const stored = (event.state as { diariumNav?: NavState } | null)?.diariumNav;
      const hasStoredNav = Boolean(
        stored &&
          isMainTab(stored.tab) &&
          (stored.sub === null || isSubScreen(stored.sub))
      );
      const next = hasStoredNav
        ? normalizeNav(stored as NavState, IMPLEMENTED_SUBS)
        : current.sub
          ? closeSub(current)
          : normalizeNav(parseNavFromSearch(window.location.search), IMPLEMENTED_SUBS);
      const leavesToday = current.tab === "today" && next.tab !== "today";
      const changesTodaySession =
        next.tab === "today" && next.checkinDate !== checkinRef.current.date;
      const opensTodaySub =
        current.tab === "today" && next.tab === "today" && current.sub !== next.sub && next.sub !== null;
      if (leavesToday || changesTodaySession || opensTodaySub) {
        const guard = checkinDraftNavigationGuard.current;
        if (guard && !(await guard())) {
          writeHistory(current, "replace");
          return;
        }
        if (navRef.current !== current) return;
      }

      if (hasStoredNav) {
        // Returning from a sub-screen to its parent tab: hand focus back to
        // the control that opened it. Navigating elsewhere keeps the browser's
        // own focus target.
        if (current.sub !== null && next.sub === null && next.tab === current.tab) {
          scheduleFocusRestore();
        }
        subPushedRef.current = false;
        markVisited(next.tab);
        if (next.tab === "today" && next.checkinDate !== checkinRef.current.date) {
          startCheckinSession(next.checkinDate);
        }
        commitNav(next);
        return;
      }

      // Drop stale/unimplemented query state (e.g. ?sub=goals) so the URL and
      // the rendered destination cannot disagree after Back/Forward.
      if (current.sub === null && navToSearch(next) !== window.location.search) {
        writeHistory(next, "replace");
      }
      if (current.sub !== null && next.sub === null) scheduleFocusRestore();
      if (next.tab === "today" && next.checkinDate !== checkinRef.current.date) {
        startCheckinSession(next.checkinDate);
      }
      markVisited(next.tab);
      commitNav(next);
    };

    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, [
    commitNav,
    markVisited,
    nav,
    scheduleFocusRestore,
    startCheckinSession,
    writeHistory,
  ]);

  // Restore focus after the parent panel is visible again. Waiting one frame
  // avoids focusing an element that is still inside the hidden tab panel.
  // The pending flag is cleared inside the frame (not before scheduling), so a
  // StrictMode effect re-run cannot cancel the only restore.
  useEffect(() => {
    if (nav.sub !== null || !pendingFocusRestoreRef.current) return;

    const opener = focusOpenerRef.current;
    const frame = requestAnimationFrame(() => {
      pendingFocusRestoreRef.current = false;
      focusOpenerRef.current = null;
      // A normal Back returns focus to the row that opened the sub-screen.
      // A deep link (`?sub=goals`) has no opener, so land on the active bottom
      // destination instead of leaving focus on an unmounted Back button.
      // `restoreFocus(null)` returns false, which is the deep-link path.
      if (!restoreFocus(opener)) {
        const activeTab = document.querySelector<HTMLElement>(
          '[role="tab"][aria-selected="true"]'
        );
        activeTab?.focus({ preventScroll: true });
      }
    });

    return () => cancelAnimationFrame(frame);
  }, [nav.sub]);

  // Remember the scroll offset of the destination currently on screen.
  useEffect(() => {
    const key = activeKey;
    const onScroll = () => {
      if (suppressScrollSave.current) return;
      scrollPositions.current[key] = window.scrollY;
    };

    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, [activeKey]);

  // Restore the offset of the destination that just became active.
  useLayoutEffect(() => {
    if (previousKey.current === activeKey) return;
    previousKey.current = activeKey;

    const offset = scrollPositions.current[activeKey] ?? 0;
    suppressScrollSave.current = true;
    window.scrollTo(0, offset);

    const frame = requestAnimationFrame(() => {
      window.scrollTo(0, offset);
      suppressScrollSave.current = false;
    });

    return () => {
      cancelAnimationFrame(frame);
      suppressScrollSave.current = false;
    };
  }, [activeKey]);

  const handleSelectTab = useCallback(
    async (tab: MainTab) => {
      const next = selectTab(nav, tab);
      if (next === nav) return;

      const replacesCheckinSession = tab === "today" && checkinRef.current.date !== null;
      const leavesActiveCheckin = nav.tab === "today" && tab !== "today";
      if (replacesCheckinSession || leavesActiveCheckin) {
        const guard = checkinDraftNavigationGuard.current;
        if (guard && !(await guard())) return;
        if (navRef.current !== nav) return;
      }

      subPushedRef.current = false;
      forgetFocusOpener();
      // Tapping Today always means today, even when the screen was opened on a
      // past day through ?edit=. Only then does it need a new session; a plain
      // Today screen stays mounted so a half-written check-in survives a tab
      // round-trip.
      if (tab === "today" && checkinRef.current.date !== null) {
        startCheckinSession(null);
      }
      markVisited(next.tab);
      writeHistory(next, "push");
      commitNav(next);
    },
    [commitNav, forgetFocusOpener, markVisited, nav, startCheckinSession, writeHistory]
  );

  const handleNavigateToCheckIn = useCallback(
    (date: string) => {
      const next = openCheckin(nav, date);
      startCheckinSession(next.checkinDate);
      forgetFocusOpener();
      markVisited(next.tab);
      writeHistory(next, "push");
      commitNav(next);
    },
    [
      commitNav,
      forgetFocusOpener,
      markVisited,
      nav,
      startCheckinSession,
      writeHistory,
    ]
  );

  const handleCheckinSaved = useCallback(() => {
    // Read the live navigation state instead of the render closure captured
    // when the save started: a tab switch during the request must win.
    const current = navRef.current;
    const urlState = navAfterCheckinSave(current);
    if (urlState === current) return;

    // Keep `checkinDate` in the rendered/history state so the mounted screen
    // stays on the saved day and shows its confirmation; drop only the ?edit=
    // deep link from the address bar. No setNav here — changing the nav state
    // would remount the check-in screen as today.
    writeHistory(current, "replace", urlState);
  }, [writeHistory]);

  const handleOpenSub = useCallback(
    async (sub: SubScreen) => {
      const next = openSub(nav, sub);
      if (nav.tab === "today") {
        const guard = checkinDraftNavigationGuard.current;
        if (guard && !(await guard())) return;
        if (navRef.current !== nav) return;
      }
      rememberFocusOpener();
      subPushedRef.current = true;
      markVisited(next.tab);
      writeHistory(next, "push");
      commitNav(next);
    },
    [commitNav, markVisited, nav, rememberFocusOpener, writeHistory]
  );

  const handleBack = useCallback(() => {
    scheduleFocusRestore();

    if (nav.sub && subPushedRef.current) {
      subPushedRef.current = false;
      window.history.back();
      return;
    }

    const next = closeSub(nav);
    writeHistory(next, "replace");
    commitNav(next);
  }, [commitNav, nav, scheduleFocusRestore, writeHistory]);

  const todayLabel = new Date().toLocaleDateString(lang === "cs" ? "cs-CZ" : "en-US", {
    weekday: "long",
    day: "numeric",
    month: "long",
    timeZone: "Europe/Prague",
  });

  const subtitle =
    nav.tab === "overview"
      ? todayLabel
      : nav.tab === "settings"
        ? t("settings.subtitle")
        : t(`${nav.tab}.subtitle`);

  const headerActions = (
    <div className="flex items-center gap-0.5">
      <LanguageSwitcher />
      <ExternalLink
        href={GITHUB_WEB_URL}
        label={t("links.github")}
        compact
        showIcon={false}
        className="text-white/45 hover:text-white"
      >
        <GitHubIcon size={19} />
      </ExternalLink>
      <ExternalLink
        href={ANDROID_APK_URL}
        label={t("links.download_apk")}
        compact
        showIcon={false}
        className="text-white/45 hover:text-white"
      >
        <AndroidIcon size={19} />
      </ExternalLink>
    </div>
  );

  const panel = (tab: MainTab, content: ReactNode) => {
    const mounted = visited.has(tab) || nav.tab === tab;
    return (
      <div
        role="tabpanel"
        id={shellPanelId(tab)}
        aria-label={t(TAB_LABEL_KEYS[tab])}
        hidden={nav.tab !== tab || nav.sub !== null}
      >
        {mounted ? content : null}
      </div>
    );
  };

  return (
    <div className="app-shell">
      <div
        className={`mx-auto w-full px-4 pb-[calc(84px+env(safe-area-inset-bottom))] pt-3 ${
          nav.tab === "overview" && nav.sub === null
            ? "max-w-[620px] lg:max-w-[1040px]"
            : "max-w-[620px]"
        }`}
      >
        {nav.sub === null && nav.tab !== "today" ? (
          <ScreenHeader
            title={t(TAB_LABEL_KEYS[nav.tab])}
            subtitle={subtitle}
            trailing={nav.tab === "overview" ? headerActions : undefined}
          />
        ) : null}

        <main className="relative z-[1]">
          {panel(
            "overview",
            <Dashboard
              onNavigateToCheckIn={handleNavigateToCheckIn}
              onOpenSub={handleOpenSub}
            />
          )}
          {panel(
            "today",
            <CheckInScreen
              key={`checkin-${checkin.nonce}`}
              userId={user.id}
              userName={
                typeof user.user_metadata?.full_name === "string"
                  ? user.user_metadata.full_name
                  : user.email ?? ""
              }
              initialDate={checkin.date}
              onSaveDone={handleCheckinSaved}
              onOpenGoals={() => handleOpenSub("goals")}
              onOpenOverview={() => handleSelectTab("overview")}
              onRegisterDraftNavigationGuard={registerCheckinDraftNavigationGuard}
            />
          )}
          {panel("history", <HistoryView onNavigateToDate={handleNavigateToCheckIn} />)}
          {panel("stats", <StatsDashboard onNavigateToDate={handleNavigateToCheckIn} />)}
          {panel(
            "settings",
            <SettingsView
              email={user.email}
              provider={
                typeof user.app_metadata?.provider === "string"
                  ? user.app_metadata.provider
                  : null
              }
              onLogout={onLogout}
              onOpenSub={handleOpenSub}
            />
          )}
        </main>

        {nav.sub ? (
          <SubScreenChrome title={t(SUB_LABEL_KEYS[nav.sub])} onBack={handleBack}>
            {nav.sub === "goals" ? <GoalsScreen /> : null}
            {nav.sub === "scales" ? <ScalesScreen /> : null}
            {nav.sub === "templates" ? <TemplatesScreen /> : null}
            {nav.sub === "achievements" ? <AchievementsPanel /> : null}
            {nav.sub === "reports" ? <ReportsScreen userId={user.id} /> : null}
            {nav.sub === "about" ? <AboutScreen /> : null}
          </SubScreenChrome>
        ) : null}
      </div>

      <BottomNav active={nav.sub ? null : nav.tab} onSelect={handleSelectTab} />
    </div>
  );
}
