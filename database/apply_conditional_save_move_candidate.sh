#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="$SCRIPT_DIR/conditional_save_move_candidate_atomic_2026-11-11.sql"

fail() {
  printf 'Refusing migration apply: %s\n' "$1" >&2
  exit 2
}

for name in PGHOST PGPORT PGDATABASE PGUSER PGSSLMODE PGPASSFILE \
  EXPECTED_DATABASE VERIFIED_BACKUP_REFERENCE APPROVED_CHANGE_WINDOW; do
  [[ -n "${!name:-}" ]] || fail "$name must be set"
done

[[ "$PGSSLMODE" == "require" ]] || fail 'PGSSLMODE must be require'
[[ "$PGDATABASE" == "$EXPECTED_DATABASE" ]] || fail 'PGDATABASE must exactly match EXPECTED_DATABASE'
[[ -f "$PGPASSFILE" && -r "$PGPASSFILE" ]] || fail 'PGPASSFILE must be a readable file'
[[ -n "$VERIFIED_BACKUP_REFERENCE" && "$VERIFIED_BACKUP_REFERENCE" != *'<'*'>'* ]] \
  || fail 'VERIFIED_BACKUP_REFERENCE must be a verified, non-placeholder reference'
[[ -n "$APPROVED_CHANGE_WINDOW" && "$APPROVED_CHANGE_WINDOW" != *'<'*'>'* ]] \
  || fail 'APPROVED_CHANGE_WINDOW must be a non-placeholder approved window'

python3 - "$PGPASSFILE" <<'PY'
import os
import stat
import sys

try:
    mode = stat.S_IMODE(os.stat(sys.argv[1]).st_mode)
except OSError:
    sys.exit("Refusing migration apply: PGPASSFILE cannot be inspected")
if mode & 0o077:
    sys.exit("Refusing migration apply: PGPASSFILE permissions must be 0600 or stricter")
PY

# Do not allow an ambient service/profile to silently redirect the connection.
unset PGSERVICE PGSERVICEFILE PGOPTIONS
PSQL=(psql -X --no-password --host "$PGHOST" --port "$PGPORT" \
  --username "$PGUSER" --dbname "$PGDATABASE" -v ON_ERROR_STOP=1)

actual_database="$("${PSQL[@]}" --quiet --tuples-only --no-align \
  -c 'SELECT pg_catalog.current_database()')" \
  || fail 'could not verify the connected database'
[[ "$actual_database" == "$EXPECTED_DATABASE" ]] \
  || fail 'connected database does not exactly match EXPECTED_DATABASE'

"${PSQL[@]}" \
  -v expected_database="$EXPECTED_DATABASE" \
  -v verified_backup_reference="$VERIFIED_BACKUP_REFERENCE" \
  -v approved_change_window="$APPROVED_CHANGE_WINDOW" \
  -v confirmation=I_HAVE_VERIFIED_BACKUP_AND_APPROVE_THIS_DATABASE \
  -f "$SQL_FILE"
