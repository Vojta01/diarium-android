#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="$SCRIPT_DIR/conditional_save_move_candidate_atomic_2026-11-11.sql"

fail() {
  printf 'Refusing migration apply: %s\n' "$1" >&2
  exit 2
}

for name in PGHOST PGPORT PGDATABASE PGUSER PGSSLMODE PGPASSFILE \
  EXPECTED_DATABASE EXPECTED_SUPABASE_PROJECT_REF \
  VERIFIED_BACKUP_REFERENCE APPROVED_CHANGE_WINDOW; do
  [[ -n "${!name:-}" ]] || fail "$name must be set"
done

[[ "$PGSSLMODE" == "verify-full" ]] \
  || fail 'PGSSLMODE must be verify-full'
[[ "$PGPORT" == "5432" ]] || fail 'PGPORT must be 5432 for the direct Supabase endpoint'
[[ "$EXPECTED_SUPABASE_PROJECT_REF" =~ ^[a-z0-9]{20}$ ]] \
  || fail 'EXPECTED_SUPABASE_PROJECT_REF must be the verified 20-character project reference'
EXPECTED_DB_HOST="db.${EXPECTED_SUPABASE_PROJECT_REF}.supabase.co"
[[ "$PGHOST" == "$EXPECTED_DB_HOST" ]] \
  || fail 'PGHOST must exactly match the direct endpoint for EXPECTED_SUPABASE_PROJECT_REF'
[[ "$PGDATABASE" == "$EXPECTED_DATABASE" ]] \
  || fail 'PGDATABASE must exactly match EXPECTED_DATABASE'
[[ -f "$PGPASSFILE" && -r "$PGPASSFILE" ]] \
  || fail 'PGPASSFILE must be a readable regular file'
[[ -n "$VERIFIED_BACKUP_REFERENCE" && "$VERIFIED_BACKUP_REFERENCE" != *'<'*'>'* ]] \
  || fail 'VERIFIED_BACKUP_REFERENCE must be a verified, non-placeholder reference'
[[ -n "$APPROVED_CHANGE_WINDOW" && "$APPROVED_CHANGE_WINDOW" != *'<'*'>'* ]] \
  || fail 'APPROVED_CHANGE_WINDOW must be a non-placeholder approved window'

# Drop every inherited PG* variable except the explicitly validated values
# needed by this runner. In particular, never let libpq use PGHOSTADDR,
# PGPASSWORD, service profiles, options, or inherited TLS overrides.
for name in ${!PG@}; do
  case "$name" in
    PGHOST|PGPORT|PGDATABASE|PGUSER|PGSSLMODE|PGPASSFILE) ;;
    *) unset "$name" ;;
  esac
done

python3 - "$PGPASSFILE" "$PGHOST" "$PGPORT" "$PGDATABASE" "$PGUSER" <<'PY'
import os
import stat
import sys

path, *target = sys.argv[1:]
try:
    info = os.lstat(path)
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid():
        raise OSError
    mode = stat.S_IMODE(info.st_mode)
    if mode & 0o077:
        sys.exit("Refusing migration apply: PGPASSFILE permissions must be 0600 or stricter")
    with open(path, encoding="utf-8") as passfile:
        rows = [line.rstrip("\n") for line in passfile]
except (OSError, UnicodeError):
    sys.exit("Refusing migration apply: PGPASSFILE cannot be inspected")


def fields_for(line):
    values, raw_fields = [], []
    value, raw = [], []
    escaped = False
    for char in line:
        if escaped:
            value.append(char)
            raw.append(char)
            escaped = False
        elif char == "\\":
            raw.append(char)
            escaped = True
        elif char == ":":
            values.append("".join(value))
            raw_fields.append("".join(raw))
            value, raw = [], []
        else:
            value.append(char)
            raw.append(char)
    if escaped:
        return None
    values.append("".join(value))
    raw_fields.append("".join(raw))
    return values, raw_fields


exact_matches = 0
for line in rows:
    if not line or line.startswith("#"):
        continue
    parsed = fields_for(line)
    if parsed is None:
        sys.exit("Refusing migration apply: PGPASSFILE contains a malformed entry")
    values, raw = parsed
    if len(values) != 5:
        sys.exit("Refusing migration apply: PGPASSFILE contains a malformed entry")
    target_fields, password = values[:4], values[4]
    if all(value == expected or raw_value == "*"
           for value, expected, raw_value in zip(target_fields, target, raw[:4])):
        if target_fields != target or any(raw_value == "*" for raw_value in raw[:4]):
            sys.exit("Refusing migration apply: PGPASSFILE has a wildcard entry matching the target")
        if not password:
            sys.exit("Refusing migration apply: PGPASSFILE target entry has no password")
        exact_matches += 1
if exact_matches != 1:
    sys.exit("Refusing migration apply: PGPASSFILE must have exactly one exact target entry")
PY

# Run psql with a clean environment: only the private passfile and verified TLS
# mode are passed to libpq. Host, port, database, and role are explicit options.
psql_run() {
  env -i "PATH=$PATH" "HOME=${HOME:-/nonexistent}" "LC_ALL=C" "LANG=C" \
    "PGPASSFILE=$PGPASSFILE" PGSSLMODE=verify-full PGCONNECT_TIMEOUT=15 "$@"
}
PSQL=(psql -X --no-password --host "$PGHOST" --port "$PGPORT" \
  --username "$PGUSER" --dbname "$PGDATABASE" -v ON_ERROR_STOP=1)

actual_database="$(psql_run "${PSQL[@]}" --quiet --tuples-only --no-align \
  -c 'SELECT pg_catalog.current_database()')" \
  || fail 'could not verify the connected database'
[[ "$actual_database" == "$EXPECTED_DATABASE" ]] \
  || fail 'connected database does not exactly match EXPECTED_DATABASE'
actual_user="$(psql_run "${PSQL[@]}" --quiet --tuples-only --no-align \
  -c 'SELECT current_user')" \
  || fail 'could not verify the connected role'
[[ "$actual_user" == "$PGUSER" ]] \
  || fail 'connected role does not exactly match PGUSER'

psql_run "${PSQL[@]}" \
  -v expected_database="$EXPECTED_DATABASE" \
  -v verified_backup_reference="$VERIFIED_BACKUP_REFERENCE" \
  -v approved_change_window="$APPROVED_CHANGE_WINDOW" \
  -v confirmation=I_HAVE_VERIFIED_BACKUP_AND_APPROVE_THIS_DATABASE \
  -f "$SQL_FILE"
