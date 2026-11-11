#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DATABASE_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
DATABASE_NAME="$(python3 "$SCRIPT_DIR/guard_local_test_database.py")"
printf 'Applying CAS candidate and transactional tests to disposable database %s\n' "$DATABASE_NAME"

psql "$LOCAL_TEST_DATABASE_URL" -X -v ON_ERROR_STOP=1 -f "$SCRIPT_DIR/prepare_fixture.sql"

# Force a mid-migration prerequisite failure after the extension DDL is reached.
# The psql -f wrapper owns the transaction and must roll back without caller -1.
psql "$LOCAL_TEST_DATABASE_URL" -X -v ON_ERROR_STOP=1 \
  -c 'CREATE ROLE diarium_entry_sync_owner LOGIN;'
if output="$(psql "$LOCAL_TEST_DATABASE_URL" -X -v ON_ERROR_STOP=1 \
    -v expected_database="$DATABASE_NAME" \
    -v verified_backup_reference='ci-synthetic-not-a-backup' \
    -v approved_change_window='disposable-ci-only' \
    -v confirmation=I_HAVE_VERIFIED_BACKUP_AND_APPROVE_THIS_DATABASE \
    -f "$DATABASE_DIR/conditional_save_move_candidate_atomic_2026-11-11.sql" 2>&1)"; then
  printf '%s\n' 'Expected unsafe-owner preflight failure, but migration unexpectedly succeeded.' >&2
  exit 1
fi
case "$output" in
  *'diarium_entry_sync_owner exists with unsafe role attributes'*)
    printf '%s\n' 'PASS unsafe-owner failure was reached inside the candidate transaction.'
    ;;
  *)
    printf '%s\n' "$output" >&2
    printf '%s\n' 'Migration failed for an unexpected reason; rollback assertion is invalid.' >&2
    exit 1
    ;;
esac
rollback_state="$(psql "$LOCAL_TEST_DATABASE_URL" -X -qAt -v ON_ERROR_STOP=1 -c "
SELECT concat_ws('|',
  EXISTS (SELECT 1 FROM pg_catalog.pg_attribute
           WHERE attrelid = 'public.entries'::pg_catalog.regclass
             AND attname = 'revision' AND attnum > 0 AND NOT attisdropped),
  pg_catalog.to_regclass('public.entry_save_v2_receipts') IS NOT NULL,
  EXISTS (SELECT 1 FROM pg_catalog.pg_extension WHERE extname = 'pgcrypto'),
  pg_catalog.to_regprocedure('public.save_daily_entry_v2(jsonb,boolean,uuid,bigint,uuid)') IS NOT NULL,
  EXISTS (SELECT 1 FROM pg_catalog.pg_trigger
           WHERE tgrelid = 'public.entries'::pg_catalog.regclass
             AND tgname = 'trg_entries_revision_v2' AND NOT tgisinternal)
);")"
if [[ "$rollback_state" != 'f|f|f|f|f' ]]; then
  printf 'Failure rollback left unexpected candidate state: %s\n' "$rollback_state" >&2
  exit 1
fi
psql "$LOCAL_TEST_DATABASE_URL" -X -v ON_ERROR_STOP=1 \
  -c 'DROP ROLE diarium_entry_sync_owner;'
printf '%s\n' 'PASS migration failure rolled back extension, column, function, receipt table, and trigger.'

# Fresh integrated apply on the untouched fixture.
fresh_before_state="$(
  psql "$LOCAL_TEST_DATABASE_URL" -X -qAt -v ON_ERROR_STOP=1 -c "
SELECT concat_ws('|',
  EXISTS (SELECT 1 FROM pg_catalog.pg_attribute
           WHERE attrelid = 'public.entries'::pg_catalog.regclass
             AND attname = 'revision' AND attnum > 0 AND NOT attisdropped),
  pg_catalog.to_regclass('public.entry_save_v2_receipts') IS NOT NULL,
  EXISTS (SELECT 1 FROM pg_catalog.pg_extension WHERE extname = 'pgcrypto'),
  pg_catalog.to_regprocedure('public.save_daily_entry_v2(jsonb,boolean,uuid,bigint,uuid)') IS NOT NULL,
  pg_catalog.to_regprocedure('public.move_daily_entry_v1(date,uuid,bigint,date,boolean,uuid,bigint,boolean,uuid)') IS NOT NULL,
  pg_catalog.to_regprocedure('public.android_move_capability_v1()') IS NOT NULL,
  EXISTS (SELECT 1 FROM pg_catalog.pg_trigger
           WHERE tgrelid = 'public.entries'::pg_catalog.regclass
             AND tgname = 'trg_entries_revision_v2' AND NOT tgisinternal)
);
"
)"
if [[ "$fresh_before_state" != 'f|f|f|f|f|f|f' ]]; then
  echo "Fresh integrated apply precondition was not clean: $fresh_before_state" >&2
  exit 1
fi
psql "$LOCAL_TEST_DATABASE_URL" -X -v ON_ERROR_STOP=1 -v expected_database="$DATABASE_NAME" -v verified_backup_reference='ci-synthetic-not-a-backup' -v approved_change_window='disposable-ci-only' -v confirmation=I_HAVE_VERIFIED_BACKUP_AND_APPROVE_THIS_DATABASE -f "$DATABASE_DIR/conditional_save_move_candidate_atomic_2026-11-11.sql"
fresh_integrated_state="$(
  psql "$LOCAL_TEST_DATABASE_URL" -X -qAt -v ON_ERROR_STOP=1 -c "
SELECT concat_ws('|',
  EXISTS (SELECT 1 FROM pg_catalog.pg_attribute
           WHERE attrelid = 'public.entries'::pg_catalog.regclass
             AND attname = 'revision' AND atttypid = 'bigint'::pg_catalog.regtype
             AND attnotnull AND attnum > 0 AND NOT attisdropped),
  pg_catalog.to_regclass('public.entry_save_v2_receipts') IS NOT NULL,
  EXISTS (SELECT 1 FROM pg_catalog.pg_extension WHERE extname = 'pgcrypto'),
  pg_catalog.to_regprocedure('public.save_daily_entry_v2(jsonb,boolean,uuid,bigint,uuid)') IS NOT NULL,
  pg_catalog.to_regprocedure('public.move_daily_entry_v1(date,uuid,bigint,date,boolean,uuid,bigint,boolean,uuid)') IS NOT NULL,
  pg_catalog.to_regprocedure('public.android_move_capability_v1()') IS NOT NULL,
  EXISTS (SELECT 1 FROM pg_catalog.pg_trigger
           WHERE tgrelid = 'public.entries'::pg_catalog.regclass
             AND tgname = 'trg_entries_revision_v2' AND NOT tgisinternal)
);
"
)"
if [[ "$fresh_integrated_state" != 't|t|t|t|t|t|t' ]]; then
  echo "Fresh integrated apply left unexpected state: $fresh_integrated_state" >&2
  exit 1
fi
echo 'PASS exact atomic wrapper applied successfully on the fresh fixture before component migrations.'

# Apply the candidate bytes, inspect the existing-row fast default, then rerun
# the exact migration to prove its intended repeatability.
psql "$LOCAL_TEST_DATABASE_URL" -X -1 -v ON_ERROR_STOP=1 \
  -f "$DATABASE_DIR/save_daily_entry_v2_2026-11-10.sql"
psql "$LOCAL_TEST_DATABASE_URL" -X -v ON_ERROR_STOP=1 \
  -f "$SCRIPT_DIR/revision_fast_default.sql"
psql "$LOCAL_TEST_DATABASE_URL" -X -1 -v ON_ERROR_STOP=1 \
  -f "$DATABASE_DIR/save_daily_entry_v2_2026-11-10.sql"
psql "$LOCAL_TEST_DATABASE_URL" -X -v ON_ERROR_STOP=1 \
  -f "$SCRIPT_DIR/revision_fast_default.sql"
psql "$LOCAL_TEST_DATABASE_URL" -X -v ON_ERROR_STOP=1 \
  -f "$SCRIPT_DIR/save_daily_entry_v2_transactional.sql"
psql "$LOCAL_TEST_DATABASE_URL" -X -v ON_ERROR_STOP=1 \
  -f "$SCRIPT_DIR/beta5_compatibility.sql"
bash "$SCRIPT_DIR/race_save_daily_entry_v2.sh"
printf '%s\n' 'CAS, rerun, failure-rollback, fast-default, and beta5 tests passed.'
