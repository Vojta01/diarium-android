/**
 * Semantic failures for the dashboard data layer.
 *
 * The UI phase must be able to tell a signed-out session apart from a failed
 * read without parsing Czech sentences: `kind` drives the "session expired,
 * sign in again" state, `status` mirrors HTTP, and `code` is an i18n key the
 * screen can pass to `t()`. No user-facing copy lives in the data layer.
 */
export type DashboardErrorKind = "signed_out" | "load_failed";

/** Stable i18n keys the dashboard UI maps to `errors.*` translations. */
export type DashboardErrorCode =
  | "errors.signed_out"
  | "errors.entries_load"
  | "errors.goals_load"
  | "errors.scales_load"
  | "errors.templates_load"
  | "errors.habits_load";

export const DASHBOARD_ERROR_CODES = {
  signedOut: "errors.signed_out",
  entries: "errors.entries_load",
  goals: "errors.goals_load",
  scales: "errors.scales_load",
  templates: "errors.templates_load",
  habits: "errors.habits_load",
} as const satisfies Record<string, DashboardErrorCode>;

/**
 * One coherent dashboard failure.
 *
 * `message` is the semantic code, never a sentence: logging shows the code and
 * the UI renders the translation. `cause` keeps the transport error available
 * for diagnostics.
 */
export class DashboardDataError extends Error {
  readonly kind: DashboardErrorKind;
  readonly code: DashboardErrorCode;
  readonly status: number;

  constructor(
    kind: DashboardErrorKind,
    code: DashboardErrorCode,
    status: number,
    options?: { cause?: unknown }
  ) {
    super(code, options);
    this.name = "DashboardDataError";
    this.kind = kind;
    this.code = code;
    this.status = status;
  }
}

/** The one signed-out error every dashboard read throws. */
export function signedOutError(): DashboardDataError {
  return new DashboardDataError(
    "signed_out",
    DASHBOARD_ERROR_CODES.signedOut,
    401
  );
}

/** A transport/query failure with a stable code instead of a Czech sentence. */
export function loadFailedError(
  code: DashboardErrorCode,
  cause?: unknown
): DashboardDataError {
  return new DashboardDataError("load_failed", code, 500, { cause });
}

/** Pass an existing dashboard error through; wrap anything else as `fallback`. */
export function toDashboardError(
  error: unknown,
  fallback: DashboardErrorCode
): DashboardDataError {
  return error instanceof DashboardDataError
    ? error
    : loadFailedError(fallback, error);
}

/**
 * True when a failed Supabase/PostgREST read means the session is gone.
 *
 * A stored session keeps returning a user id after its access token expires,
 * so the query itself is the first thing to discover the problem: HTTP 401
 * (expired JWT) or PostgREST's `PGRST301`/`PGRST302` auth codes. Treating that
 * as a generic read failure would show "could not load" with a retry that can
 * never succeed.
 */
export function isUnauthorizedRead(
  error: unknown,
  status?: number
): boolean {
  if (status === 401) return true;
  if (!error || typeof error !== "object") return false;

  const candidate = error as {
    status?: unknown;
    code?: unknown;
    message?: unknown;
  };
  if (candidate.status === 401) return true;
  if (candidate.code === "PGRST301" || candidate.code === "PGRST302") {
    return true;
  }
  return (
    typeof candidate.message === "string" &&
    /\bjwt\b|token (?:is )?expired|not authenticated/i.test(candidate.message)
  );
}

/**
 * Map a failed data read: a 401/expired session becomes the coherent
 * `signed_out` error, every other failure keeps the caller's card code.
 */
export function dashboardReadError(
  error: unknown,
  fallback: DashboardErrorCode,
  status?: number
): DashboardDataError {
  return isUnauthorizedRead(error, status)
    ? signedOutError()
    : loadFailedError(fallback, error);
}

/** The semantic code for a bundle field; unknown errors use the card's code. */
export function dashboardErrorCode(
  error: unknown,
  fallback: DashboardErrorCode
): DashboardErrorCode {
  return error instanceof DashboardDataError ? error.code : fallback;
}

/** True when the failure means "no authenticated user", not "read failed". */
export function isDashboardAuthError(error: unknown): boolean {
  return error instanceof DashboardDataError && error.kind === "signed_out";
}
