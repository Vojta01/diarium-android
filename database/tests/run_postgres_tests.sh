#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DATABASE_NAME="$(python3 "$SCRIPT_DIR/guard_local_test_database.py")"
printf 'Applying synthetic fixture and transactional tests to disposable database %s\n' "$DATABASE_NAME"

psql "$LOCAL_TEST_DATABASE_URL" -X -v ON_ERROR_STOP=1 -f "$SCRIPT_DIR/prepare_fixture.sql"
psql "$LOCAL_TEST_DATABASE_URL" -X -v ON_ERROR_STOP=1 -f "$SCRIPT_DIR/save_daily_entry_v2_transactional.sql"
bash "$SCRIPT_DIR/race_save_daily_entry_v2.sh"
printf '%s\n' 'All PostgreSQL conditional-save integration tests passed.'
