#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT_DIR="$ROOT/database/tests"
DATABASE_DIR="$ROOT/database"
cd "$ROOT"
DATABASE_NAME="$(python3 "$SCRIPT_DIR/guard_local_test_database.py")"
printf 'Running integrated conditional-save/date-move candidate on disposable database %s\n' "$DATABASE_NAME"

python3 -m unittest \
  database.tests.test_candidate_safety_contract \
  database.tests.test_sql_contract \
  database.tests.test_entry_date_move_sql_contract \
  database.tests.test_android_move_capability_contract -v

bash "$SCRIPT_DIR/run_postgres_tests.sh"

# Test the capability's fail-closed response before installing its exact move
# dependency. Reapply to establish idempotence in the false state.
psql "$LOCAL_TEST_DATABASE_URL" -X -1 -v ON_ERROR_STOP=1 \
  -f "$DATABASE_DIR/android_move_capability_v1_2026-11-11.sql"
psql "$LOCAL_TEST_DATABASE_URL" -X -v ON_ERROR_STOP=1 \
  -f "$SCRIPT_DIR/capability_false_test.sql"
psql "$LOCAL_TEST_DATABASE_URL" -X -1 -v ON_ERROR_STOP=1 \
  -f "$DATABASE_DIR/android_move_capability_v1_2026-11-11.sql"
psql "$LOCAL_TEST_DATABASE_URL" -X -v ON_ERROR_STOP=1 \
  -f "$SCRIPT_DIR/capability_false_test.sql"

# Apply and rerun the date-move bytes, then verify runtime ACL/owner contract.
psql "$LOCAL_TEST_DATABASE_URL" -X -1 -v ON_ERROR_STOP=1 \
  -f "$DATABASE_DIR/entry_date_move_v1_2026-11-10.sql"
psql "$LOCAL_TEST_DATABASE_URL" -X -1 -v ON_ERROR_STOP=1 \
  -f "$DATABASE_DIR/entry_date_move_v1_2026-11-10.sql"
psql "$LOCAL_TEST_DATABASE_URL" -X -v ON_ERROR_STOP=1 \
  -f "$SCRIPT_DIR/entry_date_move_v1_transactional.sql"
bash "$SCRIPT_DIR/race_entry_date_move_v1.sh"

# Reapply capability after the exact RPC exists and again to verify idempotence.
psql "$LOCAL_TEST_DATABASE_URL" -X -1 -v ON_ERROR_STOP=1 \
  -f "$DATABASE_DIR/android_move_capability_v1_2026-11-11.sql"
psql "$LOCAL_TEST_DATABASE_URL" -X -1 -v ON_ERROR_STOP=1 \
  -f "$DATABASE_DIR/android_move_capability_v1_2026-11-11.sql"
psql "$LOCAL_TEST_DATABASE_URL" -X -v ON_ERROR_STOP=1 \
  -f "$SCRIPT_DIR/capability_privileges.sql"

# Third race suite exercises competing CAS-vs-move writes and cross-user RLS.
bash "$SCRIPT_DIR/race_cas_vs_move.sh"
python3 "$SCRIPT_DIR/probe_non_test_migration_guard.py"

printf '%s\n' 'All integrated candidate static, PostgreSQL 16, rollback, rerun, compatibility, ACL, and three race suites passed.'
