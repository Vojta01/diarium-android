import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import test from "node:test";

const html = readFileSync(new URL("../public/auth-callback.html", import.meta.url), "utf8");
const debugPage = readFileSync(new URL("../public/debug.html", import.meta.url), "utf8");
const callbackPage = readFileSync(
  new URL("../src/app/auth/callback/page.tsx", import.meta.url),
  "utf8"
);
const homePage = readFileSync(new URL("../src/app/page.tsx", import.meta.url), "utf8");
const appShell = readFileSync(new URL("../src/components/shell/AppShell.tsx", import.meta.url), "utf8");
const checkInScreen = readFileSync(
  new URL("../src/components/checkin/CheckInScreen.tsx", import.meta.url),
  "utf8"
);
const checkInForm = readFileSync(
  new URL("../src/components/checkin/CheckInForm.tsx", import.meta.url),
  "utf8"
);
const supabaseClient = readFileSync(
  new URL("../src/lib/supabase/client.ts", import.meta.url),
  "utf8"
);

test("auth callback never dumps URL/session secrets into the DOM", () => {
  assert.doesNotMatch(html, /sessionStorage/);
  assert.doesNotMatch(html, /JSON\.stringify\([^)]*\)\s*\}\s*<\/pre>/);
  assert.doesNotMatch(html, /dump\(/);
  assert.doesNotMatch(html, /accessToken\.substring/);
  assert.doesNotMatch(html, /refresh_token[^\n]*innerHTML/);
});

test("auth callback sends PKCE codes to the app, not to /debug.html", () => {
  assert.doesNotMatch(html, /debug\.html/);
  assert.match(html, /window\.location\.replace\("\/auth\/callback"/);
});

test("the implicit callback forwards tokens without decoding identity claims", () => {
  assert.doesNotMatch(html, /vmqbslghzgfotwhzgawa/);
  assert.doesNotMatch(html, /decodePayload|payload\.sub/);
  assert.match(html, /window\.location\.replace\("\/auth\/callback".*window\.location\.hash/);
});

test("the static callback hands implicit-flow tokens to the verified app callback", () => {
  assert.match(html, /access_token/);
  assert.match(html, /window\.location\.replace\("\/auth\/callback"/);
  assert.doesNotMatch(html, /localStorage\.setItem\("sb-"/);
});

test("the Next callback page never renders debug lines or code prefixes", () => {
  assert.doesNotMatch(callbackPage, /\bdebug\b/i);
  assert.doesNotMatch(callbackPage, /setDebug/);
  assert.doesNotMatch(callbackPage, /code\.substring/);
  assert.doesNotMatch(callbackPage, /Code found/);
  assert.doesNotMatch(callbackPage, /Params:/);
});

test("debug.html never hardcodes the Supabase project ref", () => {
  assert.doesNotMatch(debugPage, /vmqbslghzgfotwhzgawa/);
  assert.match(debugPage, /projectRefFromStorage/);
});

test("debug.html lists storage and cookie names without exposing values", () => {
  assert.doesNotMatch(debugPage, /access_token/);
  assert.doesNotMatch(debugPage, /refresh_token/);
  assert.doesNotMatch(debugPage, /localStorage\.getItem/);
  assert.doesNotMatch(debugPage, /el\('cookies'\)\.textContent = document\.cookie/);
  assert.doesNotMatch(debugPage, /window\.location\.hash\s*\|\|/);
  assert.doesNotMatch(debugPage, /el\('url'\)\.textContent = window\.location\.href/);
  assert.match(debugPage, /\(hodnoty se nikdy nezobrazují\)/);
  assert.match(debugPage, /hash přítomen/);
});

test("an implicit callback never trusts or stores a forged JWT subject", () => {
  const payload = Buffer.from(
    JSON.stringify({
      iss: "https://forged-project.supabase.co/auth/v1",
      sub: "forged-account-id",
      exp: Math.floor(Date.now() / 1000) + 3600,
    })
  ).toString("base64url");
  const forgedToken = `header.${payload}.unverified-signature`;
  const script = html.match(/<script>([\s\S]*?)<\/script>/)?.[1];
  assert.ok(script, "callback script is present");

  const values = new Map<string, string>();
  let redirect = "";
  const status = { textContent: "", appendChild: () => {} };
  runInNewContext(script, {
    URLSearchParams,
    atob,
    document: {
      getElementById: () => status,
      createElement: () => ({ href: "", textContent: "" }),
    },
    localStorage: {
      get length() { return values.size; },
      key: (index: number) => [...values.keys()][index] ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
    },
    window: {
      location: {
        hash: `#access_token=${forgedToken}&refresh_token=fake-refresh`,
        search: "",
        replace: (url: string) => { redirect = url; },
      },
    },
  });

  assert.equal(values.size, 0, "an unverified token must not create a session");
  assert.match(redirect, /^\/auth\/callback#access_token=/);
});

test("the Next callback verifies provider identity before saving local auth state", () => {
  const persistence = callbackPage.indexOf("persistVerifiedSession");
  const pkceVerification = callbackPage.indexOf("verifySupabaseUser");
  assert.ok(persistence >= 0, "implicit flow must use the verified persistence helper");
  assert.ok(pkceVerification >= 0, "PKCE flow must verify its exchanged token");
  assert.doesNotMatch(callbackPage, /decodeJWT|payload\.sub/);
});

test("the app never grants account access from a cached local user", () => {
  assert.match(homePage, /persistVerifiedSession/);
  assert.match(homePage, /getSession\(\)/);
  assert.doesNotMatch(homePage, /parsed\.user|localStorage\.getItem/);
  assert.match(homePage, /auth\.session_verification_error/);
});

test("local drafts use the provider-verified account identity", () => {
  assert.match(appShell, /userId=\{user\.id\}/);
  assert.match(checkInScreen, /readDraftConflictSnapshot\(resolveBrowserStorage\(\), currentDate, userId\)/);
  assert.match(checkInScreen, /draftWriter\.observe\(currentDate, draftRead\)/);
  assert.doesNotMatch(checkInScreen, /readStoredSession/);
});

test("only a network verification failure may offer the last verified local workspace", () => {
  assert.match(homePage, /isNetworkUnavailableError/);
  assert.match(homePage, /readVerifiedWorkspaceOwner/);
  assert.match(homePage, /setLocalOnlyMode\(true\)/);
  assert.match(homePage, /localOnly/);
});

test("local-only check-in skips cloud loads and has no cloud save or photo/AI controls", () => {
  assert.match(supabaseClient, /cloudFetch/);
  assert.match(checkInScreen, /localOnly/);
  assert.match(checkInScreen, /readDraftConflictSnapshot\(resolveBrowserStorage\(\), currentDate, userId\)/);
  assert.match(checkInForm, /localOnly/);
  assert.match(checkInForm, /offline\.checkin_notice/);
});
