import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

function source(path: string): string {
  return readFileSync(new URL(path, import.meta.url), "utf8");
}

test("every bottom-nav tab exposes aria-controls for its shell panel", () => {
  const bottomNav = source("../src/components/shell/BottomNav.tsx");
  const appShell = source("../src/components/shell/AppShell.tsx");

  assert.match(bottomNav, /role="tab"/);
  assert.match(bottomNav, /aria-controls=\{shellPanelId\(tab\)\}/);
  assert.match(appShell, /id=\{shellPanelId\(tab\)\}/);
  assert.match(appShell, /role="tabpanel"/);
});

test("Escape closes the sub-screen and the listener is cleaned up", () => {
  const chrome = source("../src/components/ui/SubScreenChrome.tsx");

  assert.match(chrome, /event\.key !== "Escape"/);
  assert.match(chrome, /window\.addEventListener\("keydown", onKeyDown\)/);
  assert.match(
    chrome,
    /return \(\) => window\.removeEventListener\("keydown", onKeyDown\)/
  );
  assert.match(chrome, /onBack\(\)/);
});

test("AppShell remembers the opener and restores focus after Back/popstate", () => {
  const appShell = source("../src/components/shell/AppShell.tsx");

  assert.match(appShell, /document\.activeElement/);
  assert.match(appShell, /rememberFocusOpener\(\)/);
  assert.match(appShell, /scheduleFocusRestore\(\)/);
  assert.match(appShell, /restoreFocus\(opener\)/);
  assert.match(appShell, /aria-controls|shellPanelId/);
});

test("check-in save completion keeps the mounted day and never navigates", () => {
  const appShell = source("../src/components/shell/AppShell.tsx");
  const start = appShell.indexOf("const handleCheckinSaved");
  const end = appShell.indexOf("const handleOpenSub");
  const handler = appShell.slice(start, end);

  assert.ok(start > 0 && end > start, "save handler must exist");
  // The screen key changes only for a new check-in session, never on save.
  assert.match(appShell, /key=\{`checkin-\$\{checkin\.nonce\}`\}/);
  assert.match(appShell, /initialDate=\{checkin\.date\}/);
  // A late save reads the live nav and must not call setNav at all.
  assert.doesNotMatch(handler, /setNav\(/);
  assert.match(handler, /navRef\.current/);
  assert.match(handler, /navAfterCheckinSave/);
  assert.match(handler, /writeHistory\(current, "replace", urlState\)/);
});

test("Modal locks body scroll and contains stray focus", () => {
  const modal = source("../src/components/ui/Modal.tsx");

  assert.match(modal, /lockBodyScroll\(document\.body\)/);
  assert.match(modal, /document\.addEventListener\("focusin", onFocusIn\)/);
  assert.match(modal, /document\.removeEventListener\("focusin", onFocusIn\)/);
});

test("ScreenHeader and SubScreenChrome stick above long content", () => {
  for (const path of [
    "../src/components/ui/ScreenHeader.tsx",
    "../src/components/ui/SubScreenChrome.tsx",
  ]) {
    const component = source(path);
    assert.match(component, /sticky top-0 z-20/);
    assert.match(component, /backdrop-blur-xl/);
    assert.match(component, /bg-\[#07070b\]\/90/);
  }
});

test("auth card shows the links hint from i18n", () => {
  const auth = source("../src/components/AuthScreen.tsx");

  assert.match(auth, /t\("auth\.links_hint"\)/);
});

test("Settings exposes the About sub-screen entry", () => {
  const settings = source("../src/components/settings/SettingsView.tsx");

  assert.match(settings, /onOpenSub\("about"\)/);
});

test("About is a real screen with version, parity and all public links", () => {
  const about = source("../src/components/settings/AboutScreen.tsx");

  assert.match(about, /WEB_PACKAGE_NAME/);
  assert.match(about, /WEB_VERSION/);
  assert.match(about, /about\.parity/);
  assert.match(about, /GITHUB_WEB_URL/);
  assert.match(about, /GITHUB_ANDROID_URL/);
  assert.match(about, /ANDROID_APK_URL/);
  assert.match(about, /<ExternalLink/);
  assert.doesNotMatch(about, /ComingSoon|TODO|Lorem/);
});
