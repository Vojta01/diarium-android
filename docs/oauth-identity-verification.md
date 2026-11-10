# Android OAuth identity verification

## New callback sessions

An Android login now starts a Supabase authorization-code flow with PKCE. The app creates a high-entropy state and PKCE verifier, stores the pending attempt with Android encrypted preferences, and sends only the S256 challenge in the authorization URL. The redirect URI is `diarium://auth-callback?state=<state>`; callback parsing requires the exact configured scheme and authority, an empty path, no fragment or extra query fields, and the same state on the outstanding attempt. A fragment carrying access/refresh tokens, an unsolicited callback, a wrong or missing state, or a callback for a superseded/expired attempt is rejected before any Auth request.

The app exchanges the callback `code` at `/auth/v1/token?grant_type=pkce` with that attempt's stored verifier. An intercepted code alone cannot satisfy the PKCE exchange. During this code-exchange request, network/timeout, HTTP 408/429, and server failures retain the attempt for retry; a successful exchange, terminal client error, expiry, or superseding login consumes/clears it. After a successful exchange the verifier is no longer needed; if the following identity check fails, no session is committed and the user must start a fresh login. The returned access token is not sufficient to change accounts: before a session commit, the app sends `GET <SUPABASE_URL>/auth/v1/user` with the public anon key and bearer token. The non-empty `id` in that successful response is authoritative. If the JWT's `sub` is present, it is only a consistency check and must match the response id. No raw token, verifier, or server error is shown in logs/UI.

The state record also pins whether a local session existed and its owner when the login began. A sign-out or owner change before the callback/commit invalidates that attempt; an owner change during network verification prevents a stale response from replacing the newer session. The committed session stores the verified response id as `verified_user_id`; session ownership rejects later mismatch with user metadata or a present JWT subject. Refresh-token rotation carries this pin forward.

The configured Supabase redirect allow-list/provider settings are external to this repository and were not changed or verified against production. Production must allow the exact `diarium://auth-callback?state=…` redirect shape (including its state query) before release; do not infer production compatibility from the local parser or fake-transport tests.

## Previously saved offline sessions

Cold-start restoration of a pre-existing session does not make an Auth API request. This preserves the app's offline behavior and does not clear, rewrite, or force-sign-out a previously saved session. Legacy sessions without `verified_user_id` continue to use their existing locally stored owner id (or JWT subject when that is all they have) for the already partitioned local cache. Network requests still carry the session token and are accepted or rejected by Supabase; a revoked/expired token cannot authenticate remote requests. A failed new callback leaves this legacy session and its local account partition intact instead of replacing it with the callback candidate.

There is an unavoidable migration limitation: the prior callback implementation trusted an unsigned JWT `sub`, so an already-persisted legacy session cannot be distinguished offline from one that may have been forged before this fix. No new callback is granted that exception. A legacy session that cannot be trusted should be reauthenticated online; its local files must not be deleted as a side effect. Ownerless legacy records remain quarantined and preserved for manual recovery as documented in [account-local-data-recovery.md](account-local-data-recovery.md). The legacy recovery scanner is unchanged.

The new `verified_user_id` is a local provenance marker, not a server-side proof against a rooted or otherwise compromised device. The security boundary addressed here is an exported/deep-link OAuth callback arriving with a forged bearer token.

## Test boundary

The Auth PKCE and identity-verification tests use an injected fake `HttpTransport` and synthetic tokens/responses. They cover valid code exchange, forged-token injection, exact URI/state checks, one-time/replaced attempts, verifier-required redemption, timeout/retry, terminal exchange failure, and owner-switch races. They do not make requests to production Supabase.
