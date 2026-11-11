#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/guard_local_test_database.sh"
DATABASE_NAME="$(python3 "$SCRIPT_DIR/guard_local_test_database.py")"
printf 'Running cross-contract CAS/move races in disposable database %s\n' "$DATABASE_NAME"

TMP_BASE="${TMPDIR:-/tmp}"
mkdir -p "$TMP_BASE"
RACE_DIR="$(mktemp -d "$TMP_BASE/diarium-cas-move-race.XXXXXX")"
trap 'rm -rf "$RACE_DIR"' EXIT

OWNER_A='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
OWNER_B='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'

psql -X -v ON_ERROR_STOP=1 -c "
INSERT INTO public.entries (id, user_id, date, note, scale_values) VALUES
  ('30000000-0000-0000-0000-000000000009', '$OWNER_A', '2003-05-01', 'update race seed', '{}'),
  ('30000000-0000-0000-0000-000000000010', '$OWNER_A', '2003-06-01', 'cross-user race source', '{}');
"

move_rpc() {
  local owner="$1" source_date="$2" source_id="$3" source_revision="$4"
  local target_date="$5" target_exists="$6" target_id="$7" target_revision="$8"
  local confirm="$9" request_id="${10}"
  local target_id_sql target_revision_sql sql
  if [[ "$target_id" == NULL ]]; then target_id_sql='NULL::uuid'; else target_id_sql="'$target_id'::uuid"; fi
  if [[ "$target_revision" == NULL ]]; then target_revision_sql='NULL::bigint'; else target_revision_sql="$target_revision::bigint"; fi
  sql="BEGIN;
SET LOCAL ROLE authenticated;
SET LOCAL \"request.jwt.claims\" = '{\"sub\":\"$owner\",\"role\":\"authenticated\"}';
SELECT pg_catalog.pg_sleep(0.15);
SELECT public.move_daily_entry_v1('$source_date'::date, '$source_id'::uuid, $source_revision::bigint, '$target_date'::date, $target_exists::boolean, $target_id_sql, $target_revision_sql, $confirm::boolean, '$request_id'::uuid);
COMMIT;"
  psql -X -qAt -v ON_ERROR_STOP=1 -c "$sql"
}

save_rpc() {
  local owner="$1" request_id="$2" date_key="$3" note="$4" entry_id="$5" revision="$6"
  local sql
  sql="BEGIN;
SET LOCAL ROLE authenticated;
SET LOCAL \"request.jwt.claims\" = '{\"sub\":\"$owner\",\"role\":\"authenticated\"}';
SELECT pg_catalog.pg_sleep(0.15);
SELECT public.save_daily_entry_v2(pg_catalog.jsonb_build_object('user_id', '$owner', 'date', '$date_key', 'note', '$note'), true, '$entry_id'::uuid, $revision::bigint, '$request_id'::uuid);
COMMIT;"
  psql -X -qAt -v ON_ERROR_STOP=1 -c "$sql"
}

snapshot() {
  local from_date="$1" to_date="$2"
  psql -X -qAt -v ON_ERROR_STOP=1 \
    -c "SELECT pg_catalog.jsonb_build_object('rows', COALESCE(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object('user_id', user_id, 'id', id, 'date', date, 'revision', revision, 'note', note) ORDER BY user_id, date), '[]'::jsonb))::text FROM public.entries WHERE user_id IN ('$OWNER_A'::uuid, '$OWNER_B'::uuid) AND date BETWEEN '$from_date'::date AND '$to_date'::date;"
}

# A conditional save and a date move use the same source row token. Exactly one
# may commit; the loser must receive a conflict without losing the entry.
UPDATE_RESULT="$RACE_DIR/update_save.json"
MOVE_RESULT="$RACE_DIR/update_move.json"
UPDATE_SNAPSHOT="$RACE_DIR/update_race_snapshot.json"
move_rpc "$OWNER_A" '2003-05-01' '30000000-0000-0000-0000-000000000009' 1 \
  '2003-05-02' false NULL NULL false '80000000-0000-0000-0000-000000000010' >"$MOVE_RESULT" &
MOVE_PID=$!
save_rpc "$OWNER_A" '80000000-0000-0000-0000-000000000011' '2003-05-01' \
  'concurrent update won' '30000000-0000-0000-0000-000000000009' 1 >"$UPDATE_RESULT" &
SAVE_PID=$!
wait "$MOVE_PID"
wait "$SAVE_PID"
snapshot '2003-05-01' '2003-05-02' >"$UPDATE_SNAPSHOT"
python3 "$SCRIPT_DIR/verify_entry_date_move_race.py" update_race "$MOVE_RESULT" "$UPDATE_RESULT" "$UPDATE_SNAPSHOT"

# A second account must see an indistinguishable missing source while the owner
# concurrently moves the row.
CROSS_USER_RESULT="$RACE_DIR/cross_user_other.json"
OWNER_MOVE_RESULT="$RACE_DIR/cross_user_owner.json"
CROSS_USER_SNAPSHOT="$RACE_DIR/cross_user_snapshot.json"
move_rpc "$OWNER_B" '2003-06-01' '30000000-0000-0000-0000-000000000010' 1 \
  '2003-06-03' false NULL NULL false '80000000-0000-0000-0000-000000000012' >"$CROSS_USER_RESULT" &
OTHER_PID=$!
move_rpc "$OWNER_A" '2003-06-01' '30000000-0000-0000-0000-000000000010' 1 \
  '2003-06-02' false NULL NULL false '80000000-0000-0000-0000-000000000013' >"$OWNER_MOVE_RESULT" &
OWNER_PID=$!
wait "$OTHER_PID"
wait "$OWNER_PID"
snapshot '2003-06-01' '2003-06-03' >"$CROSS_USER_SNAPSHOT"
python3 "$SCRIPT_DIR/verify_entry_date_move_race.py" cross_user "$OWNER_MOVE_RESULT" "$CROSS_USER_RESULT" "$CROSS_USER_SNAPSHOT"

printf '%s\n' 'All cross-contract CAS/move race assertions passed.'
