# Android atomic date-move availability gate

The Android client no longer treats a local move outbox as proof that the backend
supports `move_daily_entry_v1`. Before enabling or staging a move it requires both:

1. The authenticated PostgREST OpenAPI document exposes
   `/rpc/move_daily_entry_v1` as `POST` and
   `/rpc/android_move_capability_v1` as `GET`.
2. `android_move_capability_v1()` returns the versioned contract
   `diarium.android.move-capability.v1` with `move_daily_entry_v1: true`.

The read-only SQL function in `database/android_move_capability_v1_2026-11-11.sql`
checks the live PostgreSQL catalog for the exact move-RPC identity and argument
names, `SECURITY DEFINER`, and `EXECUTE` privilege for `authenticated`. Missing,
malformed, stale, unreadable, or offline checks fail closed. The Android app checks
again immediately before staging a new request and before retrying an existing one;
it does not persist a positive capability result. The check is repeated on screen
entry/foreground return and is user-retryable after a reconnect or later deployment.

A server rollout can race the check. If a supposedly available endpoint returns
404 after the request was staged, the request and both local snapshots remain in the
outbox. The app does not cancel or rewrite an already-sent request; it only retries
the same request ID after a new positive capability check. Do not manually delete an
ambiguous request to unblock the dates.

This capability reports only whether the required RPC is currently exposed and
callable. It does **not** certify coordination with older app versions, web clients,
or other direct database writers. No claim of safety against legacy writers should
be made based on this check.

## Deployment and release gate

The atomic move RPC is not deployed in the current backend. Existing beta5 move UI
must therefore remain a temporary release blocker until the server is deployed and
verified. No backend migration was run on production as part of this client work.

When a backend deployment is authorized:

1. Apply the existing `move_daily_entry_v1` migration first.
2. Apply `database/android_move_capability_v1_2026-11-11.sql`. It can also be applied
   before the move RPC; it then returns `false` until the exact function is present.
3. Using an authenticated test account, inspect `GET /rest/v1/` with
   `Accept: application/openapi+json`; confirm the two exact paths and methods above.
4. Call `GET /rest/v1/rpc/android_move_capability_v1` and verify the contract and
   `move_daily_entry_v1: true` response. A local fake transport or the SQL file alone
   is not proof of production availability.
5. Verify the move RPC in the target environment before clearing the release blocker.

If either endpoint is missing, the response is unreadable, or the capability is
false, the UI explains that move is disabled and does not create a new request. An
unknown request already in the outbox remains retained and visible for recovery.
