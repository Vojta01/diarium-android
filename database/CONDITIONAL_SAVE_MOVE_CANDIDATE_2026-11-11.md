# Diarium conditional-save + atomic date-move SQL candidate (2026-11-11)

**Status: CI-only production candidate. Not applied to production and not approval to run it.** This branch is based on tested conditional-save commit `098a52b79817e7fedaf208381c83c41fd521500b` (including the `scale_values: null` rejection), integrated with date-move SQL from `9fd634cd4612741526aa5438d4c01240bad0b6f0` and capability SQL from `893d4105b8c3890089255911a1032a91e177218a`. The move and capability files were reviewed and guarded here; their source branches remain untouched.

## Files and order

1. `database/save_daily_entry_v2_2026-11-10.sql`
2. `database/entry_date_move_v1_2026-11-10.sql`
3. `database/android_move_capability_v1_2026-11-11.sql`

Run all three in one explicit transaction. The capability stays false until the exact move RPC and its authenticated execute grant exist. Each file independently retains the `diarium_test_*` path for disposable CI. Every non-test database requires the temporary approval record below; a direct run without it raises before changing objects.

## Why the gate is temporary and explicit

Do not remove the test-only proof. For a non-test database, create `pg_temp.diarium_production_migration_approval` with `ON COMMIT DROP` inside the same transaction as the migration. Each file checks exactly one row, exact `current_database()` equality, non-placeholder backup reference, non-placeholder approved window, and the literal operator confirmation `I_HAVE_VERIFIED_BACKUP_AND_APPROVE_THIS_DATABASE`. Closing the transaction drops the table. A non-test apply without that record fails closed.

The SQL checks that the operator supplied a backup reference; it cannot establish that a Supabase backup exists, is restorable, or includes the intended project. The backup reference, project/database allow-list, approval and change window must be independently verified by the operator. None was supplied for this task.

## Required preflight — operator-owned; not performed here

1. Identify the exact Supabase project and database from the authorized dashboard/connection, and independently compare the project ref, environment and `SELECT current_database()` with the approved allow-list. Do not infer the target from a URL alone.
2. Verify and record a current restorable Supabase backup/PITR point, recovery permissions, retention, and a restore-test result. Confirm the reference is for this exact project. This candidate makes no backup and does not touch Storage.
3. Review production schema/function owners, RLS, API-role memberships, grants, extension placement, and the prerequisites checked in the SQL. Confirm `auth.uid()`, `auth.jwt()`, `anon`, `authenticated`, `service_role`, `extensions.digest(bytea,text)`, unique `(user_id,date)` and `(user_id,scale_id,date)` keys, and the intended NOLOGIN owner-role membership/transfer rights. Stop on any difference; do not broaden grants to make it pass.
4. Record `server_version_num` (must be PostgreSQL 11+) and inspect `pg_total_relation_size('public.entries')`, estimated/live row count, free database storage, active transactions/lock queue, and the expected receipt-table growth. No production size/backup/allow-list data was available here.
5. Schedule an approved change window. Confirm Android beta5 remains supported: this candidate leaves `public.save_daily_entry(jsonb)` and its ACL untouched. Deploy the updated client only after the migration and capability check succeed; do not revoke the legacy RPC until the updated release has been published and its compatibility/cutoff decision is separately approved.
6. Stage the reviewed SQL commit and tests. Use a direct, authorized connection obtained from the operator's secret mechanism; never put database credentials in shell history, commands, SQL files, CI secrets, or this branch.

### Lock/backfill assessment

The previous CAS SQL used `UPDATE public.entries SET revision = 1 WHERE revision IS NULL`, followed by `SET NOT NULL` and a validated CHECK. That is not retained: it would write/WAL every matching row and could create a long table lock, bloat, and disk pressure on an unknown database. This candidate requires PostgreSQL 11+ and adds `revision bigint NOT NULL DEFAULT 1` with a non-volatile constant default, which uses PostgreSQL's fast-default metadata path rather than rewriting old tuples. It does **not** run a revision backfill or validate a full-table CHECK. Existing unknown/partial `revision` columns fail closed for manual review. The `ALTER TABLE` still needs a brief `ACCESS EXCLUSIVE` lock; every migration sets transaction-local `lock_timeout = '5s'`, so it should abort rather than wait indefinitely. A timeout is not a reason to raise the limit blindly; inspect blockers and reschedule.

## Protective transaction command shape (example only; do not run with placeholders)

The following is a template for an authorized operator after the preflight above. It is deliberately interactive so the operator can inspect postflight results before `COMMIT`. Run it from the root of this candidate checkout so the `\i` paths resolve. Use the operator's secure secret mechanism to populate a short-lived `PGPASSFILE` owned by the operator with mode `0600`; do not put a password-bearing connection URI or password in the command line, shell history, SQL files, CI secrets, or this branch. The metadata values below are operator-verified, not secrets.

```sh
export PGHOST="$SUPABASE_DB_HOST"
export PGPORT=5432
export PGDATABASE="$EXPECTED_DATABASE"
export PGUSER="$APPROVED_MIGRATION_ROLE"
export PGSSLMODE=require
export PGPASSFILE="$SECURE_PGPASSFILE"
psql -X -v ON_ERROR_STOP=1 \
  -v expected_database="$EXPECTED_DATABASE" \
  -v verified_backup_reference="$VERIFIED_BACKUP_REFERENCE" \
  -v approved_change_window="$APPROVED_CHANGE_WINDOW" \
  -v confirmation=I_HAVE_VERIFIED_BACKUP_AND_APPROVE_THIS_DATABASE
```

At the `psql` prompt, run:

```sql
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '5s';
CREATE TEMP TABLE diarium_production_migration_approval (
  expected_database text NOT NULL,
  verified_backup_reference text NOT NULL,
  approved_change_window text NOT NULL,
  confirmation text NOT NULL
) ON COMMIT DROP;
INSERT INTO pg_temp.diarium_production_migration_approval VALUES (
  :'expected_database', :'verified_backup_reference',
  :'approved_change_window', :'confirmation'
);
\i database/save_daily_entry_v2_2026-11-10.sql
\i database/entry_date_move_v1_2026-11-10.sql
\i database/android_move_capability_v1_2026-11-11.sql
```

Before commit, inspect the same-session postflight:

```sql
SELECT current_database(), current_setting('server_version_num');
SELECT attname, atttypid::regtype, attnotnull
  FROM pg_catalog.pg_attribute
 WHERE attrelid = 'public.entries'::regclass
   AND attname = 'revision' AND attnum > 0 AND NOT attisdropped;
SELECT p.oid::regprocedure AS function_name,
       pg_catalog.pg_get_userbyid(p.proowner) AS owner,
       p.prosecdef, p.proacl, p.proconfig
  FROM pg_catalog.pg_proc p
 WHERE p.oid = 'public.save_daily_entry_v2(jsonb,boolean,uuid,bigint,uuid)'::regprocedure
    OR p.oid = 'public.move_daily_entry_v1(date,uuid,bigint,date,boolean,uuid,bigint,boolean,uuid)'::regprocedure
    OR p.oid = 'public.android_move_capability_v1()'::regprocedure;
SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole,
       rolinherit, rolreplication, rolbypassrls
  FROM pg_catalog.pg_roles
 WHERE rolname IN ('diarium_entry_sync_owner', 'diarium_entry_move_owner');
SELECT pg_catalog.has_function_privilege('authenticated',
       'public.save_daily_entry_v2(jsonb,boolean,uuid,bigint,uuid)', 'EXECUTE') AS authenticated_save,
       pg_catalog.has_function_privilege('anon',
       'public.save_daily_entry_v2(jsonb,boolean,uuid,bigint,uuid)', 'EXECUTE') AS anon_save,
       pg_catalog.has_function_privilege('authenticated',
       'public.move_daily_entry_v1(date,uuid,bigint,date,boolean,uuid,bigint,boolean,uuid)', 'EXECUTE') AS authenticated_move,
       pg_catalog.has_function_privilege('anon',
       'public.move_daily_entry_v1(date,uuid,bigint,date,boolean,uuid,bigint,boolean,uuid)', 'EXECUTE') AS anon_move,
       pg_catalog.has_function_privilege('authenticated',
       'public.android_move_capability_v1()', 'EXECUTE') AS authenticated_capability,
       pg_catalog.has_function_privilege('anon',
       'public.android_move_capability_v1()', 'EXECUTE') AS anon_capability;
SELECT public.android_move_capability_v1();
```

Expected: `revision` is `bigint NOT NULL`; the save/move security-definer functions are owned by their dedicated NOLOGIN owners with `search_path=''` and `row_security=on`; the capability is `SECURITY INVOKER`, executable by `authenticated` but not `anon`; its response reports the exact move contract as available. Review all output and the expected API-role grants. If any result differs, issue `ROLLBACK;` and stop. Only after explicit operator review should the transaction be committed with `COMMIT;`.

## Rollback

- **Before `COMMIT`:** on any error or unexpected result, run `ROLLBACK;`. If `psql` exits with an error, its connection closes and PostgreSQL rolls back the open transaction. Verify no revision column/trigger, receipts, move RPC, capability function, policies, grants, or owner roles were committed. Do not retry until the cause is understood.
- **After `COMMIT`:** do not run ad-hoc `DROP`/`REVOKE` statements. The candidate introduces durable receipts and a revision column, and clients may have started using the new RPCs. Stop client rollout, keep beta5's `save_daily_entry` available, and use the independently verified backup/PITR or a separately reviewed compensating migration. No production rollback was attempted or claimed here.

## Compatibility and limits

- The beta5 `save_daily_entry(jsonb)` function is not modified or revoked. The synthetic integration suite calls it before a cutoff and verifies the server revision trigger still observes its writes.
- Conditional save and move use deterministic advisory date-key locks, row tokens, durable idempotency receipts, RLS-scoped NOLOGIN owners, and explicit authenticated-only grants. Direct legacy writers do not take all the same locks; do not claim global cross-device guarantees or revoke beta5 until a separately approved cutoff after updated release.
- Moving an entry only moves the `photo_path` pointer and SQL mirror rows. An overwritten target photo is a pending actor-bound candidate, never deletion authority. Storage owner/reference checks must be independently verified; retain on ambiguity.
- This branch is test-only candidate material. No live database, user data, credentials, main branch, or APK was changed.
