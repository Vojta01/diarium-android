import assert from "node:assert/strict";
import test from "node:test";

import {
  DASHBOARD_ERROR_CODES,
  DashboardDataError,
  dashboardReadError,
  isUnauthorizedRead,
} from "../src/lib/dashboard-errors.ts";

/**
 * A 401 is what an expired access token looks like on the wire. The stored
 * session can still hand `resolveUserId` a user id, so the read failure — not
 * the missing id — is the only evidence that the user is signed out.
 */
test("a 401 entries read maps to signed_out even with a stored user id", () => {
  const postgrest401 = {
    code: "PGRST301",
    message: "JWT expired",
    details: null,
    hint: null,
  };

  const error = dashboardReadError(
    postgrest401,
    DASHBOARD_ERROR_CODES.entries,
    401
  );

  assert.ok(error instanceof DashboardDataError);
  assert.equal(error.kind, "signed_out");
  assert.equal(error.code, "errors.signed_out");
  assert.equal(error.status, 401);
  assert.equal(error.message, "errors.signed_out");
});

test("the PostgREST auth code alone is enough when no status is exposed", () => {
  // PostgrestError does not carry the HTTP status; supabase-js returns it
  // beside the error. Both paths must classify the same way.
  const error = dashboardReadError(
    { code: "PGRST301", message: "JWT expired" },
    DASHBOARD_ERROR_CODES.entries
  );

  assert.equal(error.kind, "signed_out");
  assert.equal(error.code, "errors.signed_out");
  assert.equal(error.status, 401);
});

test("a generic read failure stays a card-scoped load error", () => {
  const error = dashboardReadError(
    { code: "42501", message: "permission denied for table entries" },
    DASHBOARD_ERROR_CODES.entries,
    500
  );

  assert.equal(error.kind, "load_failed");
  assert.equal(error.code, "errors.entries_load");
  assert.equal(error.status, 500);
  assert.equal(isUnauthorizedRead(error, 500), false);
});

test("network errors and non-auth statuses never become signed_out", () => {
  for (const value of [null, undefined, "offline", new Error("fetch failed")]) {
    assert.equal(isUnauthorizedRead(value, 0), false);
  }
  assert.equal(isUnauthorizedRead({ status: 403 }, 403), false);
  assert.equal(isUnauthorizedRead({ code: "PGRST116" }, 500), false);
});
