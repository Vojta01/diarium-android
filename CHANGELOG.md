# Changelog

All notable changes to the Diarium Android app. The web app has its own
changelog in the `diarium` repository.

## 2.0.0-beta6 — 2026-11-12

versionCode `2000106`. Compared with `2.0.0-beta5` (`2000105`).

### Added

- **Conditional saves with revision check.** A save carries the row ID and
  revision it started from. The server returns a conflict instead of silently
  overwriting a newer entry from another device.
- **Offline outbox with durable requests.** A save intent is stored before the
  network call. Retry after reconnect returns the original receipt, so the same
  request is never applied twice.
- **Atomic date move.** A new server RPC `move_daily_entry_v1` moves an entry,
  its scale mirror and its photo reference in one transaction. The action is
  shown only when the server reports the capability.
- **Manual recovery of older local data.** Data from an older local store can be
  copied into the signed-in account after explicit confirmation. The original
  local data is kept and never deleted by the recovery flow.
- **Per-account isolation.** Drafts, outbox, caches and the dashboard are keyed
  by account. Switching account cannot show or send another account's draft.
- **OAuth sign-in with PKCE (S256).** A one-time state value protects the
  redirect, the verifier is stored encrypted, and the returned identity is
  verified against `/auth/v1/user` before the session is used.
- **Unsaved AI reflection survives navigation**, with clipboard recovery when a
  retry fails.

### Changed

- **Screen time sync uses conditional saves.** A replay marker is validated
  strictly; an unreadable marker keeps the request instead of clearing it.
- **Photo handling.** Removing a photo from an entry clears the reference and
  keeps the stored file.
- **Date-move UI is server-gated.** Without the capability the action is hidden.

### Fixed

- Recovered drafts are rebased through conditional saves instead of replacing
  the destination row.
- A failed reflection retry no longer loses the staged text.
- Account switch during recovery reports the outcome instead of silently
  discarding the copy.

### Database

Requires the same migration as the web app:
`database/conditional_save_move_candidate_atomic_2026-11-11.sql` applied through
`database/apply_conditional_save_move_candidate.sh`. The legacy
`save_daily_entry(jsonb)` remains callable during the transition, so an older
beta5 client can still write until a separately approved cutoff.
