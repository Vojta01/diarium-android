export interface FcmTokenInsert {
  user_id: string;
  token: string;
  platform: "android";
  updated_at: string;
}

export interface FcmTokenStore {
  insert(record: FcmTokenInsert): Promise<{ code: string | null }>;
  getOwner(token: string): Promise<string | null>;
  deleteOwned(userId: string, token: string): Promise<void>;
}

export type FcmTokenRegistration = "created" | "existing" | "conflict";

/** Treat registration tokens as opaque, but reject blank/control/oversized input. */
export function isValidFcmToken(value: unknown): value is string {
  return typeof value === "string" &&
    value.length > 0 &&
    value.length <= 4096 &&
    value.trim() === value &&
    !/[\u0000-\u0020\u007f]/.test(value);
}

/**
 * Insert-only registration prevents a global unique-token conflict from
 * upserting a different user's token row. A duplicate is idempotent only when
 * a read-back proves the authenticated caller already owns it.
 */
export async function registerFcmToken(
  store: FcmTokenStore,
  userId: string,
  token: string
): Promise<FcmTokenRegistration> {
  const result = await store.insert({
    user_id: userId,
    token,
    platform: "android",
    updated_at: new Date().toISOString(),
  });
  if (!result.code) return "created";
  if (result.code !== "23505") throw new Error("FCM token insert failed");

  const owner = await store.getOwner(token);
  if (owner === userId) return "existing";
  if (owner) return "conflict";
  throw new Error("FCM token conflict owner could not be verified");
}
