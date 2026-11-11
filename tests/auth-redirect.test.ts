import assert from "node:assert/strict";
import test from "node:test";

import {
  AUTH_CALLBACK_PATH,
  resolveOAuthRedirectTo,
} from "../src/lib/auth-redirect.ts";

test("callback is the exact static HTML page", () => {
  assert.equal(AUTH_CALLBACK_PATH, "/auth-callback.html");
  assert.ok(AUTH_CALLBACK_PATH.endsWith(".html"));
});

test("uses the current origin instead of a hardcoded deployment host", () => {
  assert.equal(
    resolveOAuthRedirectTo("https://diarium.digitalni-vedomi.cz"),
    "https://diarium.digitalni-vedomi.cz/auth-callback.html"
  );
  assert.equal(
    resolveOAuthRedirectTo("http://localhost:3000"),
    "http://localhost:3000/auth-callback.html"
  );
});

test("normalizes a trailing slash on the origin", () => {
  assert.equal(
    resolveOAuthRedirectTo("https://diarium.digitalni-vedomi.cz/"),
    "https://diarium.digitalni-vedomi.cz/auth-callback.html"
  );
});

test("falls back to a relative callback when no origin is available", () => {
  assert.equal(resolveOAuthRedirectTo(""), AUTH_CALLBACK_PATH);
  assert.equal(resolveOAuthRedirectTo("   "), AUTH_CALLBACK_PATH);
});

test("accepts an absolute env override and ignores unsafe values", () => {
  assert.equal(
    resolveOAuthRedirectTo(
      "http://localhost:3000",
      "https://diarium.digitalni-vedomi.cz/auth-callback.html"
    ),
    "https://diarium.digitalni-vedomi.cz/auth-callback.html"
  );
  assert.equal(
    resolveOAuthRedirectTo("http://localhost:3000", "javascript:alert(1)"),
    "http://localhost:3000/auth-callback.html"
  );
  assert.equal(
    resolveOAuthRedirectTo("http://localhost:3000", "/auth-callback.html"),
    "http://localhost:3000/auth-callback.html"
  );
  assert.equal(
    resolveOAuthRedirectTo("http://localhost:3000", "   "),
    "http://localhost:3000/auth-callback.html"
  );
});

test("never defaults to the legacy preview deployment", () => {
  const redirect = resolveOAuthRedirectTo("https://example.com");
  assert.ok(!redirect.includes("diarium-two.vercel.app"));
  assert.ok(redirect.startsWith("https://example.com/"));
});
