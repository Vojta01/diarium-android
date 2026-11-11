/**
 * Per-user local state cleanup on logout.
 *
 * Remove auth and account-wide integrations on logout. Check-in drafts are
 * retained for recovery and are scoped to their verified owner; ownerless
 * legacy drafts must not be reassigned or deleted automatically.
 */

import { VERIFIED_WORKSPACE_KEY } from "./auth/verified-session.ts";

export interface RemovableStorage {
  readonly length: number;
  key(index: number): string | null;
  removeItem(key: string): void;
}

export function clearUserLocalState(storage: RemovableStorage): void {
  const keys: string[] = [];
  for (let i = 0; i < storage.length; i++) {
    const key = storage.key(i);
    if (key) keys.push(key);
  }

  for (const key of keys) {
    const isAuth = key.startsWith("sb-") || key.includes("supabase");
    const isUserData =
      key === VERIFIED_WORKSPACE_KEY ||
      key === "diarium_goals" ||
      key === "diarium_google_token" ||
      key === "diarium_google_token_expires_at";
    if (isAuth || isUserData) storage.removeItem(key);
  }
}
