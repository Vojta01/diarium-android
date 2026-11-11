export interface VerifiedUserIdentity {
  id: string;
}

export interface AuthUserResponse<TUser extends VerifiedUserIdentity> {
  data: { user: TUser | null };
  error: unknown | null;
}

export interface SupabaseUserVerifier<TUser extends VerifiedUserIdentity> {
  getUser(jwt?: string): Promise<AuthUserResponse<TUser>>;
}

export interface PersistableAuthSession {
  access_token: string;
  refresh_token: string;
  expires_at: number;
  token_type: string;
}

export const VERIFIED_WORKSPACE_KEY = "diarium_verified_workspace_v1";

export type AuthVerificationFailureKind = "network" | "rejected";

export class AuthVerificationError extends Error {
  readonly kind: AuthVerificationFailureKind;

  constructor(kind: AuthVerificationFailureKind) {
    super("Supabase session could not be verified");
    this.name = "AuthVerificationError";
    this.kind = kind;
  }
}

/**
 * This binding is a local workspace selector, not proof of identity or token
 * validity. Only `persistVerifiedSession` writes it after provider verification.
 * An optional unverified session ID can only veto fallback on a mismatch; it is
 * never used to select a workspace or establish its owner.
 */
export function readVerifiedWorkspaceOwner(
  storage: Pick<Storage, "getItem">,
  unverifiedSessionUserId?: string | null
): string | null {
  try {
    const raw = storage.getItem(VERIFIED_WORKSPACE_KEY);
    if (!raw) return null;
    const binding: unknown = JSON.parse(raw);
    if (!binding || typeof binding !== "object" || Array.isArray(binding)) {
      return null;
    }
    const record = binding as Record<string, unknown>;
    if (
      record.version !== 1 ||
      typeof record.ownerId !== "string" ||
      record.ownerId.trim().length === 0
    ) {
      return null;
    }
    if (
      unverifiedSessionUserId !== undefined &&
      record.ownerId !== unverifiedSessionUserId
    ) {
      return null;
    }
    return record.ownerId;
  } catch {
    return null;
  }
}

/** True only for provider transport/infrastructure failures, never 4xx auth. */
export function isNetworkUnavailableError(error: unknown): boolean {
  if (!error || typeof error !== "object") return false;

  const candidate = error as {
    name?: unknown;
    status?: unknown;
    message?: unknown;
    kind?: unknown;
  };
  const name = typeof candidate.name === "string" ? candidate.name : "";
  if (name === "AuthVerificationError") return candidate.kind === "network";
  const status = typeof candidate.status === "number" ? candidate.status : null;

  if (status !== null) {
    if (status === 0) return name === "AuthRetryableFetchError";
    return name === "AuthRetryableFetchError" && status >= 500 && status <= 599;
  }

  if (name === "AuthRetryableFetchError") return true;
  if (name !== "TypeError" || typeof candidate.message !== "string") return false;
  return /failed to fetch|fetch failed|network(error| request failed)|load failed|offline/i.test(
    candidate.message
  );
}

/**
 * Ask Supabase Auth to validate a token and return the authoritative user.
 * JWT payload decoding is intentionally not an identity source.
 */
export async function verifySupabaseUser<
  TUser extends VerifiedUserIdentity,
>(auth: SupabaseUserVerifier<TUser>, accessToken?: string): Promise<TUser> {
  try {
    const { data, error } = await auth.getUser(accessToken);
    if (error || !data?.user || typeof data.user.id !== "string" || !data.user.id) {
      throw error ?? new Error("verification rejected");
    }
    return data.user;
  } catch (error) {
    // Avoid leaking tokens or transport/provider details to UI and logs.
    throw new AuthVerificationError(
      isNetworkUnavailableError(error) ? "network" : "rejected"
    );
  }
}

/** Persist a session only after Supabase has verified its access token. */
export async function persistVerifiedSession<
  TUser extends VerifiedUserIdentity,
>(
  auth: SupabaseUserVerifier<TUser>,
  storage: Pick<Storage, "setItem">,
  storageKey: string,
  session: PersistableAuthSession,
  shouldPersist: () => boolean = () => true,
): Promise<TUser> {
  const user = await verifySupabaseUser(auth, session.access_token);
  if (shouldPersist()) {
    storage.setItem(
      VERIFIED_WORKSPACE_KEY,
      JSON.stringify({ version: 1, ownerId: user.id })
    );
    storage.setItem(storageKey, JSON.stringify({ ...session, user }));
  }
  return user;
}
