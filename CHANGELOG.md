# Changelog

All notable changes to the Diarium Android app. The web app has its own
changelog in the `diarium` repository.

## 2.0.0-beta9 — 2026-11-14

versionCode `2000109`. Compared with `2.0.0-beta8` (`2000108`).

### Fixed

- **Screen-time sync no longer bumps the diary revision.** The database
  trigger now increases `entries.revision` only when diary content changes
  (mood, note, gratitude, activities, habits, sleep, stress, weather, photo,
  scales, AI reflection). An update of `phone_screen_time`, `phone_unlocks` or
  `phone_top_apps` keeps the revision, so it cannot make a draft look stale.
- **Unchanged phone data is not sent again.** The app stores a fingerprint of
  the last confirmed payload for each day and skips the network write when the
  payload is identical.

### Changed

- Saving the same content twice is not a new version. The revision stays.

### Verified

- Revision contract test on PostgreSQL 16 and 17.
- Complete app unit-test suite and `buildSrc` tests on GitHub Actions.

## 2.0.0-beta8 — 2026-11-14

versionCode `2000108`. Compared with `2.0.0-beta7` (`2000107`).

### Fixed

- **A conflicted local draft can now be saved.** When the server copy changed
  after editing started, the screen only offered "review both copies" and the
  save stayed blocked. The panel now offers **"Připravit místní kopii k uložení"**
  for every draft, not only for legacy recovered drafts. After that the normal
  **Uložit** replaces the server copy with the local one through the conditional
  v2 comparison.
- **Yesterday's entry no longer disappears.** The blocked draft for 13 November
  2026 can be prepared and saved, so the entry reaches the server.

### Changed

- The conflict panel states that saving replaces the server copy. The owner
  attestation checkbox stays only for legacy recovered drafts.

## 2.0.0-beta7 — 2026-11-13

versionCode `2000107`. Compared with `2.0.0-beta6` (`2000106`).

### Added

- **Adoption of beta5 profile data and the beta5 AI key.** The old-data screen
  now offers the name, the birth year and the DeepSeek API key that the phone
  still holds from beta5. Adoption runs only after explicit confirmation, fills
  only empty fields and never deletes the original values.
- **The key never leaves the phone.** The adopted key is written to the
  encrypted per-account slot. It is never sent to the server and never shown on
  screen.

### Changed

- **AI consent is not adopted.** The signed-in account must switch AI on again.
  Consent cannot be inherited from an ownerless beta5 value.
- **The guide flag is not adopted.** A new account still walks through the
  first-run guide once.

### Fixed

- The old-data screen no longer says that name, birth year and the AI key
  cannot be transferred. It lists what is available and offers the transfer.

### Verified

- Complete app unit-test suite: `1181` tests, `0` failures, plus `buildSrc`
  tests, on GitHub Actions.

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

- **Screen time sync uses conditional saves.** A conflicting server row is
  reported instead of overwritten. Known limitation in this build: the replay
  marker is read leniently (`optBoolean("replayed", false)`), so an unreadable
  marker can clear the durable request. A stricter check is prepared for the
  next build.
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
