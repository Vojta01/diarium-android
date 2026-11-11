# Diarium conditional-save + atomic date-move SQL candidate (2026-11-11)

**Status: CI-only production candidate. Not applied to production and not approval to run it.** This branch is based on tested conditional-save commit `098a52b79817e7fedaf208381c83c41fd521500b` (including the `scale_values: null` rejection), integrated with date-move SQL from `9fd634cd4612741526aa5438d4c01240bad0b6f0` and capability SQL from `893d4105b8c3890089255911a1032a91e177218a`. The move and capability files were reviewed and guarded here; their source branches remain untouched.

## Files and order

1. `database/save_daily_entry_v2_2026-11-10.sql`
2. `database/entry_date_move_v1_2026-11-10.sql`
3. `database/android_move_capability_v1_2026-11-11.sql`

Use only `database/apply_conditional_save_move_candidate.sh` as the apply entry point. It runs the psql-only atomic wrapper, which opens one explicit transaction, creates the temporary opt-in record inside that transaction, includes all three migration sections in order, and commits automatically only after all succeed. The wrapper is designed for plain `psql -f`; it does not depend on caller-supplied `psql -1`.

The three numbered SQL files below are migration fragments retained for review and disposable test harnesses, not production entry points. Their individual guards do not make standalone `psql -f` atomic: direct fragment execution can commit earlier DDL before a later guard fails. Never invoke the fragments individually or paste them into a SQL editor. A privileged operator can always bypass a wrapper, so this workflow does not claim to make arbitrary fragment execution safe.

For a non-test database, the wrapper creates `pg_temp.diarium_production_migration_approval` with `ON COMMIT DROP` in the same transaction as all migrations. Each migration checks exactly one row, exact `current_database()` equality, non-placeholder backup reference, non-placeholder approved window, and the literal confirmation `I_HAVE_VERIFIED_BACKUP_AND_APPROVE_THIS_DATABASE`. Disposable `diarium_test_*` databases retain their existing test path.

The SQL checks that the operator supplied a backup reference; it cannot establish that a Supabase backup exists, is restorable, or includes the intended project. The backup reference, project/database allow-list, approval and change window must be independently verified by the operator. None was supplied for this task.

## Required preflight — operator-owned; not performed here

1. Identify the exact Supabase project and database from the authorized dashboard/connection, and independently compare the project ref, environment and `SELECT current_database()` with the approved allow-list. Do not infer the target from a URL alone.
2. Verify and record a current restorable Supabase backup/PITR point, recovery permissions, retention, and a restore-test result. Confirm the reference is for this exact project. This candidate makes no backup and does not touch Storage.
3. Review production schema/function owners, RLS, API-role memberships, grants, extension placement, and the prerequisites checked in the SQL. Confirm `auth.uid()`, `auth.jwt()`, `anon`, `authenticated`, `service_role`, `extensions.digest(bytea,text)`, unique `(user_id,date)` and `(user_id,scale_id,date)` keys, and the intended NOLOGIN owner-role membership/transfer rights. Stop on any difference; do not broaden grants to make it pass.
4. Record `server_version_num` (must be PostgreSQL 11+) and inspect `pg_total_relation_size('public.entries')`, estimated/live row count, free database storage, active transactions/lock queue, and the expected receipt-table growth. No production size/backup/allow-list data was available here.
5. Schedule an approved change window. Confirm Android beta5 remains supported: this candidate leaves `public.save_daily_entry(jsonb)` and its ACL untouched. Deploy the updated client only after the migration and capability check succeed; do not revoke the legacy RPC until the updated release has been published and its compatibility/cutoff decision is separately approved.
6. Stage the reviewed SQL commit and tests. Use a direct, authorized connection obtained from the operator's secret mechanism; never put database credentials in shell history, commands, SQL files, CI secrets, or this branch.

### Lock/backfill assessment

The previous CAS SQL used `UPDATE public.entries SET revision = 1 WHERE revision IS NULL`, followed by `SET NOT NULL` and a validated CHECK. That is not retained: it would write/WAL every matching row and could create a long table lock, bloat, and disk pressure on an unknown database. This candidate requires PostgreSQL 11+ and adds `revision bigint NOT NULL DEFAULT 1` with a non-volatile constant default, which uses PostgreSQL's fast-default metadata path rather than rewriting old tuples. It does **not** run a revision backfill or validate a full-table CHECK. Existing unknown/partial `revision` columns fail closed for manual review. The `ALTER TABLE` still needs a brief `ACCESS EXCLUSIVE` lock. The wrapper bounds each lock wait with `lock_timeout = '5s'`, each statement with `statement_timeout = '120s'`, and idle-in-transaction time with `idle_in_transaction_session_timeout = '30s'`; these transaction-local limits remain active across all three files. A timeout aborts the wrapper transaction. Do not raise the limit blindly; inspect blockers and reschedule. The operator must not pause for a review while the lock is held; postflight is after commit.

## Automated apply — no review pauses inside the transaction

Complete and sign off the required preflight above before starting. Use the executable runner from an authorized operator shell, with a mode-0600-or-stricter `PGPASSFILE` populated by the operator's secure secret mechanism. Do not put a password-bearing URI or password in a command, SQL file, shell history, CI secret, or this branch. The following consumes operator-verified environment values; it intentionally contains no runnable placeholder credentials:

```sh
: "${SUPABASE_DB_HOST:?set from the verified project connection}"
: "${EXPECTED_DATABASE:?set from the approved database allow-list}"
: "${APPROVED_MIGRATION_ROLE:?set from the reviewed owner/transfer preflight}"
: "${SECURE_PGPASSFILE:?set to the operator-managed mode-0600 PGPASSFILE}"
: "${VERIFIED_BACKUP_REFERENCE:?set from the verified backup/PITR record}"
: "${APPROVED_CHANGE_WINDOW:?set from the approved change record}"

export PGHOST="$SUPABASE_DB_HOST"
export PGPORT=5432
export PGDATABASE="$EXPECTED_DATABASE"
export PGUSER="$APPROVED_MIGRATION_ROLE"
export PGSSLMODE=require
export PGPASSFILE="$SECURE_PGPASSFILE"
database/apply_conditional_save_move_candidate.sh
```

The runner fails closed unless the database/approval metadata is present, the `PGPASSFILE` is private and readable, TLS is required, and `PGDATABASE`, `EXPECTED_DATABASE`, and the server's actual `current_database()` agree exactly. It uses `psql -X --no-password -v ON_ERROR_STOP=1 -f` on the wrapper and does not use `-1`. The psql-only wrapper starts `BEGIN`, sets `lock_timeout = '5s'`, `statement_timeout = '120s'` (per statement), and `idle_in_transaction_session_timeout = '30s'`, creates the temporary approval record with `ON COMMIT DROP`, includes the three migration files in the order above, and reaches `COMMIT` only after all succeed. On success it performs an automatic commit and prints a success line. On any SQL error `ON_ERROR_STOP` exits before `COMMIT`; psql closes the connection and PostgreSQL automatically rolls back the open transaction. Do not pause for review, open another query, or wait for operator input while this transaction is running.

The wrapper uses psql meta-commands (`\set`, `\ir`) and is not valid input for Supabase SQL Editor. Do not paste the wrapper or the individual fragments into SQL Editor. Use the documented runner from an authorized environment with the verified connection and secure password file. If that runner cannot be used, stop and arrange a separately reviewed supported apply path; do not improvise a manual multi-file apply. This wrapper protects only this documented entry path. A privileged operator can still bypass it by running fragments or handwritten SQL outside a transaction.

## Postflight after commit

After the runner reports success and the transaction has committed, use a new psql session from the authorized operator environment and review these read-only results. No migration locks are held during this review:

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

Expected: `revision` is `bigint NOT NULL`; save/move security-definer functions are owned by the dedicated NOLOGIN owners with `search_path=''` and `row_security=on`; the capability function is `SECURITY INVOKER`, executable by `authenticated` but not `anon`, and reports the exact move contract as available. If a postflight differs, stop client rollout and investigate using the approved recovery plan; do not run ad-hoc DDL to make the output match.

## Rollback

- **Automatic rollback on failure before `COMMIT`:** through the documented runner, any SQL error in a migration makes psql exit before the wrapper reaches `COMMIT`; closing the connection rolls the full transaction back, including the extension, schema, tables, policies, grants, and role changes attempted earlier in that transaction. Confirm the runner returned nonzero and verify candidate objects are absent before retrying. If the connection drops during/after `COMMIT` or its outcome is otherwise ambiguous, do not assume rollback and do not blindly retry; reconnect and inspect postflight first. This guarantee applies to the wrapper invocation, not standalone migration fragments. Do not retry until the cause is understood.
- **After `COMMIT`:** do not run ad-hoc `DROP`/`REVOKE` statements. The candidate introduces durable receipts and a revision column, and clients may have started using the new RPCs. Stop client rollout, keep beta5's `save_daily_entry` available, and use the independently verified backup/PITR or a separately reviewed compensating migration. No production rollback was attempted or claimed here.

## Compatibility and limits

- The beta5 `save_daily_entry(jsonb)` function is not modified or revoked. The synthetic integration suite calls it before a cutoff and verifies the server revision trigger still observes its writes.
- Conditional save and move use deterministic advisory date-key locks, row tokens, durable idempotency receipts, RLS-scoped NOLOGIN owners, and explicit authenticated-only grants. Direct legacy writers do not take all the same locks; do not claim global cross-device guarantees or revoke beta5 until a separately approved cutoff after updated release.
- Moving an entry only moves the `photo_path` pointer and SQL mirror rows. An overwritten target photo is a pending actor-bound candidate, never deletion authority. Storage owner/reference checks must be independently verified; retain on ambiguity.
- This branch is test-only candidate material. No live database, user data, credentials, main branch, or APK was changed.
