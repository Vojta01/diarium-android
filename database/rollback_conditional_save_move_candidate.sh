#!/usr/bin/env bash
# Emergency rollback entry point. Same connection guards as the apply runner.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="$SCRIPT_DIR/rollback_conditional_save_move_candidate.sql"

fail() { printf 'Refusing rollback: %s\n' "$1" >&2; exit 2; }

for name in PGHOST PGPORT PGDATABASE PGUSER PGSSLMODE PGPASSFILE \
  EXPECTED_DATABASE EXPECTED_SUPABASE_PROJECT_REF ROLLBACK_CONFIRMATION; do
  [[ -n "${!name:-}" ]] || fail "$name must be set"
done
[[ "$PGSSLMODE" == "verify-full" ]] || fail 'PGSSLMODE must be verify-full'
[[ "$PGPORT" == "5432" ]] || fail 'PGPORT must be 5432 for the direct Supabase endpoint'
[[ "$EXPECTED_SUPABASE_PROJECT_REF" =~ ^[a-z0-9]{20}$ ]] || fail 'EXPECTED_SUPABASE_PROJECT_REF invalid'
[[ "$PGHOST" == "db.${EXPECTED_SUPABASE_PROJECT_REF}.supabase.co" ]] || fail 'PGHOST mismatch'
[[ "$PGDATABASE" == "$EXPECTED_DATABASE" ]] || fail 'PGDATABASE mismatch'
[[ "$ROLLBACK_CONFIRMATION" == "I_UNDERSTAND_THIS_REMOVES_THE_NEW_RPCS" ]] \
  || fail 'ROLLBACK_CONFIRMATION missing'
[[ -f "$PGPASSFILE" && -r "$PGPASSFILE" ]] || fail 'PGPASSFILE must be a readable regular file'
[[ "$(stat -c '%a' "$PGPASSFILE")" =~ ^[0-7]00$ ]] || fail 'PGPASSFILE permissions must be 0600 or stricter'

for name in ${!PG@}; do
  case "$name" in
    PGHOST|PGPORT|PGDATABASE|PGUSER|PGSSLMODE|PGPASSFILE) ;;
    *) unset "$name" ;;
  esac
done

psql_run() {
  env -i "PATH=$PATH" "HOME=${HOME:-/nonexistent}" "LC_ALL=C" "LANG=C" \
    "PGPASSFILE=$PGPASSFILE" PGSSLMODE=verify-full PGCONNECT_TIMEOUT=15 "$@"
}
PSQL=(psql -X --no-password --host "$PGHOST" --port "$PGPORT" \
  --username "$PGUSER" --dbname "$PGDATABASE" -v ON_ERROR_STOP=1)

actual="$(psql_run "${PSQL[@]}" -qAt -c 'SELECT current_database()')" || fail 'connect failed'
[[ "$actual" == "$EXPECTED_DATABASE" ]] || fail 'connected database mismatch'

psql_run "${PSQL[@]}" -f "$SQL_FILE"
