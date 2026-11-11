import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import test from "node:test";
import ts from "typescript";

import {
  persistVerifiedSession,
  verifySupabaseUser,
} from "../src/lib/auth/verified-session.ts";

const CALLBACK_SOURCE = readFileSync(
  new URL("../src/app/auth/callback/page.tsx", import.meta.url),
  "utf8"
);
const CALLBACK_JS = ts.transpile(CALLBACK_SOURCE, {
  jsx: ts.JsxEmit.ReactJSX,
  module: ts.ModuleKind.CommonJS,
  target: ts.ScriptTarget.ES2020,
  esModuleInterop: true,
});
const SESSION_KEY = "sb-test-auth-token";
const PROVIDER_USER = { id: "provider-verified-id", email: "user@example.test" };
const FORGED_SUBJECT = "attacker-controlled-subject";
const FORGED_ACCESS_TOKEN = `header.${Buffer.from(
  JSON.stringify({ sub: FORGED_SUBJECT })
).toString("base64url")}.unverified-signature`;

function deadline<T>(promise: Promise<T>, label: string): Promise<T> {
  let timer: NodeJS.Timeout;
  const timedOut = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error(`Timed out waiting for ${label}`)), 1_000);
    timer.unref();
  });
  return Promise.race([promise, timedOut]).finally(() => clearTimeout(timer));
}

function startCallback(
  providerResult: "valid" | "invalid" | "network-error",
  initialHref = `https://diarium.example/auth/callback#access_token=${encodeURIComponent(
    FORGED_ACCESS_TOKEN
  )}&refresh_token=synthetic-refresh-token&expires_in=3600`
) {
  let currentUrl = new URL(initialHref);
  const historyEntries = [currentUrl.href];
  let currentEntry = 0;
  const location = {
    get href() {
      return currentUrl.href;
    },
    set href(value: string) {
      currentUrl = new URL(value, currentUrl);
      historyEntries.splice(currentEntry + 1);
      historyEntries.push(currentUrl.href);
      currentEntry += 1;
      finish();
    },
    get hash() {
      return currentUrl.hash;
    },
    get pathname() {
      return currentUrl.pathname;
    },
    get search() {
      return currentUrl.search;
    },
    replace(value: string) {
      currentUrl = new URL(value, currentUrl);
      historyEntries[currentEntry] = currentUrl.href;
      finish();
    },
  };
  const history: {
    state: unknown;
    replaceState(state: unknown, title: string, value: string): void;
  } = {
    state: { source: "synthetic-oauth" },
    replaceState(state, _title, value) {
      currentUrl = new URL(value, currentUrl);
      historyEntries[currentEntry] = currentUrl.href;
      this.state = state;
    },
  };
  const browser = { location, history, historyEntries };
  const storageValues = new Map<string, string>();
  const storage = {
    setItem(key: string, value: string) {
      storageValues.set(key, value);
    },
  };
  const asyncModuleUrls: string[] = [];
  const exchangeCalls: string[] = [];
  let providerCallCount = 0;

  let resolveProviderRequest!: (request: { token?: string; url: string }) => void;
  const providerRequest = new Promise<{ token?: string; url: string }>((resolve) => {
    resolveProviderRequest = resolve;
  });
  let resolveProviderResult!: (result: unknown) => void;
  let rejectProviderResult!: (error: unknown) => void;
  const providerResponse = new Promise<unknown>((resolve, reject) => {
    resolveProviderResult = resolve;
    rejectProviderResult = reject;
  });
  let resolveExchangeRequest!: (request: { code: string; url: string }) => void;
  const exchangeRequest = new Promise<{ code: string; url: string }>((resolve) => {
    resolveExchangeRequest = resolve;
  });
  type ExchangeResult = {
    data: { session: { access_token: string } | null };
    error: unknown | null;
  };
  let resolveExchangeResult!: (result: ExchangeResult) => void;
  const exchangeResponse = new Promise<ExchangeResult>((resolve) => {
    resolveExchangeResult = resolve;
  });

  let finish!: () => void;
  const completed = new Promise<void>((resolve) => {
    finish = resolve;
  });
  const auth = {
    getUser(token?: string) {
      providerCallCount += 1;
      resolveProviderRequest({ token, url: location.href });
      if (providerResult === "network-error") {
        return providerResponse.then(() => {
          throw new Error("synthetic network failure");
        });
      }
      return providerResponse;
    },
    exchangeCodeForSession(code: string) {
      exchangeCalls.push(code);
      resolveExchangeRequest({ code, url: location.href });
      return exchangeResponse;
    },
    async setSession() {
      return { data: { session: null }, error: null };
    },
  };

  const effects: Array<() => void> = [];
  const stateUpdates: unknown[] = [];
  const react = {
    Suspense: Symbol("Suspense"),
    useEffect(effect: () => void) {
      effects.push(effect);
    },
    useRef(initial: unknown) {
      return { current: initial };
    },
    useState(initial: unknown) {
      return [initial, (value: unknown) => {
        stateUpdates.push(value);
        finish();
      }];
    },
  };
  const jsx = (type: unknown, props: Record<string, unknown>) => ({ type, props });
  const mockedModules: Record<string, unknown> = {
    react,
    "react/jsx-runtime": { jsx, jsxs: jsx, Fragment: Symbol("Fragment") },
    "next/link": { default: "MockLink" },
    "@/lib/supabase-ref": { getSupabaseAuthTokenKey: () => SESSION_KEY },
    "@/lib/supabase/client": { createSupabaseClient: () => ({ auth }) },
    "@/lib/auth/verified-session": { persistVerifiedSession, verifySupabaseUser },
  };
  const callbackModule = { exports: {} as Record<string, unknown> };
  const requireMock = (name: string) => {
    assert.ok(name in mockedModules, `unexpected callback dependency: ${name}`);
    if (name === "@/lib/supabase/client" || name === "@/lib/auth/verified-session") {
      asyncModuleUrls.push(location.href);
    }
    return mockedModules[name];
  };
  const context = {
    exports: callbackModule.exports,
    module: callbackModule,
    require: requireMock,
    window: browser,
    localStorage: storage,
    URLSearchParams,
    Date,
    Promise,
  };
  const evaluate = runInNewContext(
    `(function (exports, require, module) { ${CALLBACK_JS}\n})`,
    context
  ) as (
    exports: Record<string, unknown>,
    require: (name: string) => unknown,
    callbackModule: { exports: Record<string, unknown> }
  ) => void;
  evaluate(callbackModule.exports, requireMock, callbackModule);

  const callbackPage = callbackModule.exports.default as () => {
    props: { children: { type: () => unknown } };
  };
  const pageTree = callbackPage();
  pageTree.props.children.type();
  assert.equal(effects.length, 1, "the callback's mount effect should be registered");
  effects[0]();

  return {
    browser,
    completed,
    providerRequest,
    resolveProviderResult,
    rejectProviderResult,
    exchangeRequest,
    resolveExchangeResult,
    stateUpdates,
    storageValues,
    asyncModuleUrls,
    exchangeCalls,
    get providerCallCount() {
      return providerCallCount;
    },
    rerunEffect: effects[0],
  };
}

function assertTokenFreeHistory(historyEntries: string[]) {
  assert.ok(
    historyEntries.every((entry) => !entry.includes("access_token=") && !entry.includes("refresh_token=")),
    "no browser history entry may retain OAuth token parameters"
  );
}

function assertNoFragmentAtAsyncBoundaries(urls: string[]) {
  assert.ok(urls.length > 0, "the callback should lazy-load its auth dependencies");
  assert.ok(
    urls.every((url) => new URL(url).hash.length === 0),
    "the URL must be scrubbed before loading asynchronous auth dependencies"
  );
}

function assertPkceCodeIsAbsent(url: string) {
  assert.equal(
    new URL(url).searchParams.has("code"),
    false,
    "the PKCE code must be removed from the current URL"
  );
}

function assertPkceCodeWasNotPersisted(storageValues: Map<string, string>) {
  assert.ok(
    [...storageValues].every(([key, value]) =>
      !key.includes("synthetic-pkce-code") && !value.includes("synthetic-pkce-code")
    ),
    "the callback code must never be persisted"
  );
}

test("implicit OAuth callback scrubs the fragment before provider verification and replaces token history", async () => {
  const callback = startCallback("valid");
  callback.rerunEffect?.(); // React Strict Mode may replay the mount effect.
  const request = await deadline(callback.providerRequest, "provider verification");

  assert.ok(request.token === FORGED_ACCESS_TOKEN, "the callback should pass the token to provider verification");
  assert.equal(new URL(request.url).hash.length, 0, "the token fragment must be gone before the provider request");
  assertNoFragmentAtAsyncBoundaries(callback.asyncModuleUrls);
  callback.resolveProviderResult({ data: { user: PROVIDER_USER }, error: null });
  await deadline(callback.completed, "successful callback completion");

  const stored = JSON.parse(callback.storageValues.get(SESSION_KEY)!);
  assert.equal(stored.user.id, PROVIDER_USER.id, "only the provider-verified identity is persisted");
  assert.notEqual(stored.user.id, FORGED_SUBJECT);
  assert.equal(callback.providerCallCount, 1, "effect replay must not verify or store the callback twice");
  assert.equal(callback.browser.location.href, "https://diarium.example/");
  assertTokenFreeHistory(callback.browser.historyEntries);
});

test("invalid implicit token leaves a clean URL and no history entry containing tokens", async () => {
  const callback = startCallback("invalid");
  const request = await deadline(callback.providerRequest, "provider verification");

  assert.equal(new URL(request.url).hash.length, 0, "the token fragment must be gone before the provider request");
  assertNoFragmentAtAsyncBoundaries(callback.asyncModuleUrls);
  callback.resolveProviderResult({ data: { user: null }, error: new Error("invalid token") });
  await deadline(callback.completed, "invalid-token callback completion");

  assert.equal(callback.storageValues.size, 0);
  assertTokenFreeHistory(callback.browser.historyEntries);
  assert.match(String(callback.stateUpdates.at(-1)), /could not be verified/i);
});

test("provider network failure leaves a clean URL and no history entry containing tokens", async () => {
  const callback = startCallback("network-error");
  const request = await deadline(callback.providerRequest, "provider verification");

  assert.equal(new URL(request.url).hash.length, 0, "the token fragment must be gone before the provider request");
  assertNoFragmentAtAsyncBoundaries(callback.asyncModuleUrls);
  callback.rejectProviderResult(new Error("synthetic network failure"));
  await deadline(callback.completed, "network-error callback completion");

  assert.equal(callback.storageValues.size, 0);
  assertTokenFreeHistory(callback.browser.historyEntries);
  assert.match(String(callback.stateUpdates.at(-1)), /could not be verified/i);
});

test("PKCE success scrubs the URL before exchange and verification, then replaces history", async () => {
  const callback = startCallback(
    "valid",
    "https://diarium.example/auth/callback?code=synthetic-pkce-code&state=synthetic-state&next=%2Fjournal"
  );
  assertPkceCodeIsAbsent(callback.browser.location.href);
  assertPkceCodeWasNotPersisted(callback.storageValues);

  const exchange = await deadline(callback.exchangeRequest, "PKCE exchange");
  assert.equal(exchange.code, "synthetic-pkce-code");
  assertPkceCodeIsAbsent(exchange.url);
  assertPkceCodeIsAbsent(callback.browser.location.href);
  assert.equal(new URL(callback.browser.location.href).searchParams.get("state"), "synthetic-state");
  assert.equal(new URL(callback.browser.location.href).searchParams.get("next"), "/journal");
  assert.ok(callback.asyncModuleUrls.every((url) => !new URL(url).searchParams.has("code")));

  callback.resolveExchangeResult({
    data: { session: { access_token: FORGED_ACCESS_TOKEN } },
    error: null,
  });
  const verification = await deadline(callback.providerRequest, "PKCE user verification");
  assert.equal(verification.token, FORGED_ACCESS_TOKEN);
  assertPkceCodeIsAbsent(verification.url);
  assertPkceCodeIsAbsent(callback.browser.location.href);

  callback.resolveProviderResult({ data: { user: PROVIDER_USER }, error: null });
  await deadline(callback.completed, "PKCE callback completion");

  assert.deepEqual(callback.exchangeCalls, ["synthetic-pkce-code"]);
  assert.equal(callback.browser.location.href, "https://diarium.example/");
  assert.deepEqual(callback.browser.historyEntries, ["https://diarium.example/"]);
  assertPkceCodeWasNotPersisted(callback.storageValues);
});

test("PKCE exchange failure leaves the query code scrubbed and unpersisted", async () => {
  const callback = startCallback(
    "valid",
    "https://diarium.example/auth/callback?code=synthetic-pkce-code&state=synthetic-state"
  );
  assertPkceCodeIsAbsent(callback.browser.location.href);
  const exchange = await deadline(callback.exchangeRequest, "PKCE exchange");
  assertPkceCodeIsAbsent(exchange.url);
  assertPkceCodeIsAbsent(callback.browser.location.href);

  callback.resolveExchangeResult({ data: { session: null }, error: new Error("synthetic exchange failure") });
  await deadline(callback.completed, "PKCE exchange failure completion");

  assert.equal(callback.providerCallCount, 0, "failed exchange must not start identity verification");
  assertPkceCodeIsAbsent(callback.browser.location.href);
  assert.ok(callback.browser.historyEntries.every((entry) => !new URL(entry).searchParams.has("code")));
  assertPkceCodeWasNotPersisted(callback.storageValues);
  assert.match(String(callback.stateUpdates.at(-1)), /could not be verified/i);
});

test("PKCE verification failure keeps the scrubbed URL and persists no callback credentials", async () => {
  const callback = startCallback(
    "invalid",
    "https://diarium.example/auth/callback?code=synthetic-pkce-code&state=synthetic-state"
  );
  assertPkceCodeIsAbsent(callback.browser.location.href);
  const exchange = await deadline(callback.exchangeRequest, "PKCE exchange");
  assertPkceCodeIsAbsent(exchange.url);
  callback.resolveExchangeResult({
    data: { session: { access_token: FORGED_ACCESS_TOKEN } },
    error: null,
  });

  const verification = await deadline(callback.providerRequest, "PKCE user verification");
  assertPkceCodeIsAbsent(verification.url);
  assertPkceCodeIsAbsent(callback.browser.location.href);
  callback.resolveProviderResult({ data: { user: null }, error: new Error("synthetic verification failure") });
  await deadline(callback.completed, "PKCE verification failure completion");

  assert.equal(callback.storageValues.size, 0);
  assertPkceCodeWasNotPersisted(callback.storageValues);
  assert.ok(callback.browser.historyEntries.every((entry) => !new URL(entry).searchParams.has("code")));
  assert.match(String(callback.stateUpdates.at(-1)), /could not be verified/i);
});
