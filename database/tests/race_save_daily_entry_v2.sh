#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/guard_local_test_database.sh"
DATABASE_NAME="$(python3 "$SCRIPT_DIR/guard_local_test_database.py")"
printf 'Running separate-session CAS races in disposable database %s\n' "$DATABASE_NAME"

TMP_BASE="${TMPDIR:-/tmp}"
mkdir -p "$TMP_BASE"
RACE_DIR="$(mktemp -d "$TMP_BASE/diarium-cas-race.XXXXXX")"
trap 'rm -rf "$RACE_DIR"' EXIT

rpc_sql() {
  local owner="$1" request_id="$2" date_key="$3" note="$4"
  local expected_exists="$5" expected_id="$6" expected_revision="$7"
  local payload_sql expected_id_sql expected_revision_sql
  payload_sql="pg_catalog.jsonb_build_object('user_id', '$owner', 'date', '$date_key', 'note', '$note')"
  if [[ "$expected_id" == NULL ]]; then
    expected_id_sql='NULL::uuid'
  else
    expected_id_sql="'$expected_id'::uuid"
  fi
  if [[ "$expected_revision" == NULL ]]; then
    expected_revision_sql='NULL::bigint'
  else
    expected_revision_sql="$expected_revision::bigint"
  fi
  local sql="BEGIN;
SET LOCAL ROLE authenticated;
SET LOCAL \"request.jwt.claims\" = '{\"sub\":\"$owner\",\"role\":\"authenticated\"}';
SELECT pg_catalog.pg_sleep(0.15);
SELECT public.save_daily_entry_v2($payload_sql, $expected_exists, $expected_id_sql, $expected_revision_sql, '$request_id');
COMMIT;"
  psql -X -qAt -v ON_ERROR_STOP=1 -c "$sql"
}

snapshot_sql() {
  local owner="$1" date_key="$2"
  psql -X -qAt -v ON_ERROR_STOP=1 \
    -c "SELECT pg_catalog.jsonb_build_object('count', count(*), 'revision', max(revision), 'note', max(note))::text FROM public.entries WHERE user_id = '$owner'::uuid AND date = '$date_key'::date;"
}

run_pair() {
  local label="$1" owner="$2" req_a="$3" req_b="$4" date_key="$5"
  local note_a="$6" note_b="$7" expected_exists="$8" expected_id="$9" expected_revision="${10}"
  local a="$RACE_DIR/${label}_a.json" b="$RACE_DIR/${label}_b.json" snapshot="$RACE_DIR/${label}_snapshot.json"

  rpc_sql "$owner" "$req_a" "$date_key" "$note_a" "$expected_exists" "$expected_id" "$expected_revision" >"$a" &
  pid_a=$!
  rpc_sql "$owner" "$req_b" "$date_key" "$note_b" "$expected_exists" "$expected_id" "$expected_revision" >"$b" &
  pid_b=$!
  wait "$pid_a"
  wait "$pid_b"
  snapshot_sql "$owner" "$date_key" >"$snapshot"
  python3 "$SCRIPT_DIR/verify_race.py" "$label" "$a" "$b" "$snapshot"
}

OWNER='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'

# Two independent absent-baseline requests for the same owner/date.
run_pair create "$OWNER" \
  'aaaaaaaa-1000-0000-0000-000000000001' \
  'aaaaaaaa-1000-0000-0000-000000000002' \
  '2002-01-01' 'creation-A' 'creation-B' false NULL NULL

# The same immutable request submitted concurrently must produce one mutation
# and one replay of the same durable receipt.
run_pair duplicate "$OWNER" \
  'aaaaaaaa-2000-0000-0000-000000000001' \
  'aaaaaaaa-2000-0000-0000-000000000001' \
  '2002-01-02' 'same-intent' 'same-intent' false NULL NULL

# Two edits based on the same existing (id,revision) token must not both win.
SEED_DATE='2002-01-03'
SEED_RESULT="$(rpc_sql "$OWNER" 'aaaaaaaa-3000-0000-0000-000000000001' "$SEED_DATE" 'update-seed' false NULL NULL)"
ENTRY_ID="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["entry"]["id"])' "$SEED_RESULT")"
ENTRY_REVISION="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["entry"]["revision"])' "$SEED_RESULT")"
run_pair update "$OWNER" \
  'aaaaaaaa-3000-0000-0000-000000000002' \
  'aaaaaaaa-3000-0000-0000-000000000003' \
  "$SEED_DATE" 'edit-A' 'edit-B' true "$ENTRY_ID" "$ENTRY_REVISION"
