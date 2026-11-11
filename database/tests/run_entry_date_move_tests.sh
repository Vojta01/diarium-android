#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/guard_local_test_database.sh"
DATABASE_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
PHASE="${1:-all}"
DATABASE_NAME="$(python3 "$SCRIPT_DIR/guard_local_test_database.py")"
printf 'Date-move test phase %s in disposable database %s\n' "$PHASE" "$DATABASE_NAME"

case "$PHASE" in
  migration)
    psql -X -1 -v ON_ERROR_STOP=1 \
      -f "$DATABASE_DIR/entry_date_move_v1_2026-11-10.sql"
    ;;
  integration)
    psql -X -v ON_ERROR_STOP=1 \
      -f "$SCRIPT_DIR/entry_date_move_v1_transactional.sql"
    ;;
  races)
    bash "$SCRIPT_DIR/race_entry_date_move_v1.sh"
    ;;
  all)
    psql -X -1 -v ON_ERROR_STOP=1 \
      -f "$DATABASE_DIR/entry_date_move_v1_2026-11-10.sql"
    psql -X -v ON_ERROR_STOP=1 \
      -f "$SCRIPT_DIR/entry_date_move_v1_transactional.sql"
    bash "$SCRIPT_DIR/race_entry_date_move_v1.sh"
    printf '%s\n' 'All atomic date-move PostgreSQL tests passed.'
    ;;
  *)
    printf 'Unknown test phase: %s\n' "$PHASE" >&2
    exit 2
    ;;
esac
