/**
 * Helper to dynamically derive the Supabase project ref from the URL.
 *
 * Supabase localStorage keys use the format `sb-{project_ref}-auth-token`.
 * Instead of hardcoding the project ref, we extract it from
 * NEXT_PUBLIC_SUPABASE_URL (e.g. `https://abc123.supabase.co`).
 */

const FALLBACK_REF = "";

function getSupabaseUrl(): string {
  // process.env.NEXT_PUBLIC_SUPABASE_URL is inlined at build time for client components
  return (
    (typeof process !== "undefined" && process.env?.NEXT_PUBLIC_SUPABASE_URL) ||
    `https://${FALLBACK_REF}.supabase.co`
  );
}

/**
 * Extract the project ref from NEXT_PUBLIC_SUPABASE_URL.
 * e.g. `https://abc123.supabase.co` → `abc123`
 */
export function getSupabaseProjectRef(): string {
  try {
    const hostname = new URL(getSupabaseUrl()).hostname;
    const match = hostname.match(/^(.+?)\.supabase\.co$/);
    return match ? match[1] : FALLBACK_REF;
  } catch {
    return FALLBACK_REF;
  }
}

/**
 * Get the localStorage key used by Supabase for the auth token.
 * This is dynamic per project so it works with any Supabase instance.
 */
export function getSupabaseAuthTokenKey(): string {
  return `sb-${getSupabaseProjectRef()}-auth-token`;
}
