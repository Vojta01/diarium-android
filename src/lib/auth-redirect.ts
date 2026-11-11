/**
 * OAuth redirect resolution.
 *
 * The callback is a static HTML page (`public/auth-callback.html`) that forwards
 * implicit-flow token hashes to the app callback for Supabase Auth verification.
 * It must live on the same origin the user is actually browsing: hardcoding the
 * production host breaks local development, preview deployments and the custom
 * domain.
 */

export const AUTH_CALLBACK_PATH = "/auth-callback.html";

function isAbsoluteHttpUrl(value: string): boolean {
  return /^https?:\/\/[^\s]+$/i.test(value);
}

/**
 * Resolve the exact static callback URL.
 *
 * @param origin   `window.location.origin` of the current page.
 * @param override Optional `NEXT_PUBLIC_AUTH_CALLBACK_URL`; only an absolute
 *                 http(s) URL is accepted, anything else is ignored.
 */
export function resolveOAuthRedirectTo(
  origin: string,
  override?: string | null
): string {
  const configured = override?.trim();
  if (configured && isAbsoluteHttpUrl(configured)) return configured;

  const base = origin.trim().replace(/\/+$/, "");
  return base ? `${base}${AUTH_CALLBACK_PATH}` : AUTH_CALLBACK_PATH;
}
