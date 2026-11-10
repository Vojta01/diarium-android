#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DATABASE_NAME="$(python3 "$SCRIPT_DIR/guard_local_test_database.py")"
printf 'Running atomic date-move races in disposable database %s\n' "$DATABASE_NAME"

TMP_BASE="${TMPDIR:-/tmp}"
mkdir -p "$TMP_BASE"
RACE_DIR="$(mktemp -d "$TMP_BASE/diarium-date-move-race.XXXXXX")"
trap 'rm -rf "$RACE_DIR"' EXIT

OWNER_A='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
OWNER_B='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'

psql "$LOCAL_TEST_DATABASE_URL" -X -v ON_ERROR_STOP=1 -c "
INSERT INTO public.entries (id, user_id, date, note, scale_values) VALUES
  ('30000000-0000-0000-0000-000000000001', '$OWNER_A', '2003-01-01', 'same-day race', '{}'),
  ('30000000-0000-0000-0000-000000000003', '$OWNER_A', '2003-02-01', 'same-source race', '{}'),
  ('30000000-0000-0000-0000-000000000004', '$OWNER_A', '2003-03-01', 'target contender A', '{}'),
  ('30000000-0000-0000-0000-000000000005', '$OWNER_A', '2003-03-02', 'target contender B', '{}'),
  ('30000000-0000-0000-0000-000000000006', '$OWNER_A', '2003-04-03', 'occupied target race', '{}'),
  ('30000000-0000-0000-0000-000000000007', '$OWNER_A', '2003-04-01', 'overwrite contender A', '{}'),
  ('30000000-0000-0000-0000-000000000008', '$OWNER_A', '2003-04-02', 'overwrite contender B', '{}'),
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
  psql "$LOCAL_TEST_DATABASE_URL" -X -qAt -v ON_ERROR_STOP=1 -c "$sql"
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
  psql "$LOCAL_TEST_DATABASE_URL" -X -qAt -v ON_ERROR_STOP=1 -c "$sql"
}

snapshot() {
  local from_date="$1" to_date="$2"
  psql "$LOCAL_TEST_DATABASE_URL" -X -qAt -v ON_ERROR_STOP=1 \
    -c "SELECT pg_catalog.jsonb_build_object('rows', COALESCE(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object('user_id', user_id, 'id', id, 'date', date, 'revision', revision, 'note', note) ORDER BY user_id, date), '[]'::jsonb))::text FROM public.entries WHERE user_id IN ('$OWNER_A'::uuid, '$OWNER_B'::uuid) AND date BETWEEN '$from_date'::date AND '$to_date'::date;"
}

run_move_pair() {
  local label="$1" owner="$2" req_a="$3" req_b="$4"
  local source_date="$5" source_id="$6" source_revision="$7"
  local target_a="$8" target_b="$9" target_exists="${10}" target_id="${11}" target_revision="${12}" confirm="${13}"
  local a="$RACE_DIR/${label}_a.json" b="$RACE_DIR/${label}_b.json" snap="$RACE_DIR/${label}_snapshot.json"
  move_rpc "$owner" "$source_date" "$source_id" "$source_revision" "$target_a" "$target_exists" "$target_id" "$target_revision" "$confirm" "$req_a" >"$a" &
  local pid_a=$!
  move_rpc "$owner" "$source_date" "$source_id" "$source_revision" "$target_b" "$target_exists" "$target_id" "$target_revision" "$confirm" "$req_b" >"$b" &
  local pid_b=$!
  wait "$pid_a"
  wait "$pid_b"
  snapshot "$source_date" "$target_b" >"$snap"
  python3 "$SCRIPT_DIR/verify_entry_date_move_race.py" "$label" "$a" "$b" "$snap"
}

run_same_target_pair() {
  local label="$1" req_a="$2" req_b="$3" source_a="$4" id_a="$5" source_b="$6" id_b="$7" target="$8"
  local target_exists="$9" target_id="${10}" target_revision="${11}" confirm="${12}"
  local a="$RACE_DIR/${label}_a.json" b="$RACE_DIR/${label}_b.json" snap="$RACE_DIR/${label}_snapshot.json"
  move_rpc "$OWNER_A" "$source_a" "$id_a" 1 "$target" "$target_exists" "$target_id" "$target_revision" "$confirm" "$req_a" >"$a" &
  local pid_a=$!
  move_rpc "$OWNER_A" "$source_b" "$id_b" 1 "$target" "$target_exists" "$target_id" "$target_revision" "$confirm" "$req_b" >"$b" &
  local pid_b=$!
  wait "$pid_a"
  wait "$pid_b"
  snapshot "$source_a" "$target" >"$snap"
  python3 "$SCRIPT_DIR/verify_entry_date_move_race.py" "$label" "$a" "$b" "$snap"
}

# Same-date retries of one intent return one original outcome and one replay.
run_move_pair same_day "$OWNER_A" \
  '80000000-0000-0000-0000-000000000001' '80000000-0000-0000-0000-000000000001' \
  '2003-01-01' '30000000-0000-0000-0000-000000000001' 1 \
  '2003-01-01' '2003-01-01' true '30000000-0000-0000-0000-000000000001' 1 false

# Distinct requests for one absent source both terminate without creating data.
run_move_pair missing_source "$OWNER_A" \
  '80000000-0000-0000-0000-000000000002' '80000000-0000-0000-0000-000000000003' \
  '2003-01-04' '30000000-0000-0000-0000-000000000002' 1 \
  '2003-01-05' '2003-01-05' false NULL NULL false

# Competing destinations for one source: at most one move can consume it.
run_move_pair same_source "$OWNER_A" \
  '80000000-0000-0000-0000-000000000004' '80000000-0000-0000-0000-000000000005' \
  '2003-02-01' '30000000-0000-0000-0000-000000000003' 1 \
  '2003-02-02' '2003-02-03' false NULL NULL false

# Two different sources contend for the same absent target key.
run_same_target_pair same_target \
  '80000000-0000-0000-0000-000000000006' '80000000-0000-0000-0000-000000000007' \
  '2003-03-01' '30000000-0000-0000-0000-000000000004' \
  '2003-03-02' '30000000-0000-0000-0000-000000000005' '2003-03-03' false NULL NULL false

# Two explicitly confirmed overwrites share one target token; only one can use it.
run_same_target_pair occupied_target \
  '80000000-0000-0000-0000-000000000008' '80000000-0000-0000-0000-000000000009' \
  '2003-04-01' '30000000-0000-0000-0000-000000000007' \
  '2003-04-02' '30000000-0000-0000-0000-000000000008' '2003-04-03' true '30000000-0000-0000-0000-000000000006' 1 true

# Conditional save and move share the source token; only one may commit.
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

# A second account must see a missing source while the owner moves the row.
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

printf '%s\n' 'All concurrent atomic date-move race assertions passed.'
