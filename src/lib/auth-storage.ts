/**
 * Shared helper to read the stored Supabase session from localStorage.
 *
 * Supabase stores auth tokens in a localStorage key derived from the project ref
 * (e.g. `sb-{project_ref}-auth-token`). This helper reads and parses that value
 * into a structured session object, returning null if none exists or parsing fails.
 *
 * Use this instead of duplicating the localStorage.getItem + JSON.parse pattern.
 */
import { getSupabaseAuthTokenKey } from "./supabase-ref.ts";

export interface StoredSession {
  access_token: string;
  refresh_token: string;
  expires_at: number;
  token_type: string;
  user: {
    id: string;
    email?: string;
    [key: string]: any;
  };
}

/**
 * Read the stored Supabase session from localStorage.
 * Returns the parsed session object, or null if not found / invalid.
 */
export function readStoredSession(): StoredSession | null {
  if (typeof window === "undefined") return null;
  try {
    const stored = localStorage.getItem(getSupabaseAuthTokenKey());
    if (stored) {
      return JSON.parse(stored) as StoredSession;
    }
  } catch {
    // Ignore parse errors — token may be corrupted
  }
  return null;
}
