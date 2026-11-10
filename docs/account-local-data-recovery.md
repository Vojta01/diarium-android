# Owner-scoped local data and legacy recovery

New diary-local records are partitioned by the Supabase user id captured when the store or worker is created:

- Drafts use owner-qualified keys in the `diarium_drafts` Preferences DataStore.
- Pending saves use `v2/<base64url-owner>/<date>` keys in `diarium_pending_saves`; the owner is also stored in the JSON record, and every enqueue has a persisted transaction id.
- Cached entry snapshots use an owner-qualified `entries_cache_v2_*.json` file and repeat the owner id in the file header.
- Photo cleanup candidates and pending-save WorkManager requests are also owner-bound. A legacy work request with no owner id is ignored rather than attached to whichever account is currently signed in.

## Legacy records

Older builds stored drafts and pending saves under date-only keys, and used the shared `entries_cache.json` file. Those values do not identify their owner. The app therefore leaves them untouched but does not read, display, replay, migrate, or assign them to any account. Logging into an account does not make those records visible. This is intentional: guessing an owner could expose private journal text or send it under the wrong account.

## Recovery

There is currently no in-app recovery or export tool for quarantined legacy drafts/outbox values. Do not uninstall the app, clear its storage, or edit the private preference files: those actions can destroy the only remaining copy or bypass the owner check. If a legacy draft may contain needed work, keep the app data intact and contact the maintainer with the app version and approximate date. Recovery must first establish the correct account with the user, inspect a preserved copy, and recreate data under the confirmed owner; never relabel or replay an ownerless record automatically. Existing server-backed entries and user-created exports remain the supported recovery sources when available.

Account-scoped data is retained when logging out; it is not deleted. When that same account signs back in, its pending outbox is shown as unsynced on-device data for review. It is not sent automatically or removed by background work. The app has no atomic server compare-and-swap endpoint, so saving a conflicting recovery copy remains blocked; both local and server copies stay intact. A different account cannot read or dispatch that outbox.
