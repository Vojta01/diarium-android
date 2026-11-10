-- Transactional integration test. Run only after prepare_fixture.sql against an
-- empty diarium_test_* PostgreSQL database; every data assertion is rolled back.
\set ON_ERROR_STOP on
BEGIN;

DO $guard$
BEGIN
  IF current_database() !~ '^diarium_test_[A-Za-z0-9_]+$' THEN
    RAISE EXCEPTION 'refusing non-disposable database name';
  END IF;
END;
$guard$;

INSERT INTO public.scales (id, user_id, name) VALUES
  ('11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'energy'),
  ('22222222-2222-2222-2222-222222222222', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'focus'),
  ('44444444-4444-4444-4444-444444444444', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'calm'),
  ('33333333-3333-3333-3333-333333333333', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'other-user');
INSERT INTO public.scale_entries (user_id, scale_id, date, value) VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '22222222-2222-2222-2222-222222222222', '2001-01-01', 2),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '33333333-3333-3333-3333-333333333333', '2001-01-01', 5);

SET LOCAL ROLE authenticated;
SET LOCAL "request.jwt.claims" = '{"sub":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","role":"authenticated"}';

DO $test$
DECLARE
  v_payload jsonb;
  v_result jsonb;
  v_replay jsonb;
  v_conflict jsonb;
  v_conflict_replay jsonb;
  v_clear_photo jsonb;
  v_clear_scale jsonb;
  v_entry jsonb;
  v_before jsonb;
  v_after jsonb;
  v_id uuid;
  v_revision bigint;
  v_rejected boolean;
  v_count integer;
  v_value integer;
  v_scale_entry_id uuid;
  v_scale_revision bigint;
  v_scale_state jsonb;
  v_mirror_state jsonb;
  v_scale_error text;
BEGIN
  v_payload := pg_catalog.jsonb_build_object(
    'user_id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    'date', '2001-01-01',
    'mood', 4,
    'mood_emoji', '🙂',
    'sleep_quality', 3,
    'stress', 2,
    'activities', pg_catalog.jsonb_build_array('walk', 'reading'),
    'habits', pg_catalog.jsonb_build_object('meditation', true),
    'gratitude', pg_catalog.jsonb_build_array('friends'),
    'note', 'private synthetic note',
    'weather', pg_catalog.jsonb_build_array('sunny'),
    'photo_path', 'https://photos.example.test/a.jpg',
    'phone_screen_time', 120,
    'phone_unlocks', 8,
    'phone_top_apps', 'App:2,Empty:,Zero:0',
    'ai_reflection', 'synthetic reflection',
    'scale_values', pg_catalog.jsonb_build_object(
      '11111111-1111-1111-1111-111111111111', 4,
      '22222222-2222-2222-2222-222222222222', 0,
      '44444444-4444-4444-4444-444444444444', 3,
      '33333333-3333-3333-3333-333333333333', 5,
      'not-a-scale-id', 3
    )
  );

  v_result := public.save_daily_entry_v2(
    v_payload, false, NULL, NULL,
    'aaaaaaaa-0000-0000-0000-000000000001'
  );
  IF v_result ->> 'status' <> 'saved' OR v_result ->> 'replayed' <> 'false' THEN
    RAISE EXCEPTION 'expected first insert to be saved, got %', v_result;
  END IF;
  v_entry := v_result -> 'entry';
  v_id := (v_entry ->> 'id')::uuid;
  v_revision := (v_entry ->> 'revision')::bigint;
  IF v_id IS NULL OR v_revision <> 1 THEN
    RAISE EXCEPTION 'insert must return a server id and revision 1: %', v_entry;
  END IF;

  -- Every field written by save_daily_entry(jsonb) survives the new contract.
  IF v_entry ->> 'mood' <> '4'
     OR v_entry ->> 'mood_emoji' <> '🙂'
     OR v_entry ->> 'sleep_quality' <> '3'
     OR v_entry ->> 'stress' <> '2'
     OR v_entry -> 'activities' <> '["walk", "reading"]'::jsonb
     OR v_entry -> 'habits' <> '{"meditation": true}'::jsonb
     OR v_entry -> 'gratitude' <> '["friends"]'::jsonb
     OR v_entry ->> 'note' <> 'private synthetic note'
     OR v_entry -> 'weather' <> '["sunny"]'::jsonb
     OR v_entry ->> 'photo_path' <> 'https://photos.example.test/a.jpg'
     OR v_entry ->> 'phone_screen_time' <> '120'
     OR v_entry ->> 'phone_unlocks' <> '8'
     OR v_entry -> 'phone_top_apps' <> '[{"app":"App","time_sec":120}]'::jsonb
     OR v_entry ->> 'ai_reflection' <> 'synthetic reflection'
     OR v_entry -> 'scale_values' <> (v_payload -> 'scale_values')
     OR v_entry ->> 'created_at' IS NULL
     OR v_entry ->> 'updated_at' IS NULL THEN
    RAISE EXCEPTION 'one or more legacy save fields changed: %', v_entry;
  END IF;

  SELECT count(*), max(se.value) INTO v_count, v_value
    FROM public.scale_entries se
   WHERE se.user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
     AND se.date = '2001-01-01'
     AND se.scale_id = '11111111-1111-1111-1111-111111111111';
  IF v_count <> 1 OR v_value <> 4 THEN
    RAISE EXCEPTION 'owned positive scale value was not mirrored';
  END IF;
  SELECT count(*), max(se.value) INTO v_count, v_value
    FROM public.scale_entries se
   WHERE se.user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
     AND se.date = '2001-01-01'
     AND se.scale_id = '44444444-4444-4444-4444-444444444444';
  IF v_count <> 1 OR v_value <> 3 THEN
    RAISE EXCEPTION 'second owned positive scale value was not mirrored';
  END IF;
  SELECT count(*), max(se.value) INTO v_count, v_value
    FROM public.scale_entries se
   WHERE se.user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
     AND se.date = '2001-01-01'
     AND se.scale_id = '22222222-2222-2222-2222-222222222222';
  IF v_count <> 1 OR v_value <> 2 THEN
    RAISE EXCEPTION 'legacy scale_values zero must not silently delete an existing mirror';
  END IF;
  SELECT count(*) INTO v_count
    FROM public.scale_entries se
   WHERE se.user_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
     AND se.date = '2001-01-01'
     AND se.scale_id = '33333333-3333-3333-3333-333333333333';
  -- RLS hides user B's row from user A; the explicit scale id lookup cannot
  -- change it. A superuser verifies the exact row after RESET ROLE below.
  IF v_count <> 0 THEN
    RAISE EXCEPTION 'cross-owner mirror row unexpectedly visible';
  END IF;

  -- Replay of the same request ID/fingerprint is a receipt, not a second save.
  v_replay := public.save_daily_entry_v2(
    v_payload, false, NULL, NULL,
    'aaaaaaaa-0000-0000-0000-000000000001'
  );
  IF v_replay ->> 'status' <> 'saved'
     OR v_replay ->> 'replayed' <> 'true'
     OR (v_replay ->> 'revision')::bigint <> 1
     OR v_replay ? 'entry' THEN
    RAISE EXCEPTION 'same request retry must return its immutable receipt: %', v_replay;
  END IF;

  -- Partial update: omitted fields, null/empty photo, sleep=0, and empty
  -- reflection must retain their existing values. Only note is changed.
  v_result := public.save_daily_entry_v2(
    pg_catalog.jsonb_build_object(
      'user_id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
      'date', '2001-01-01',
      'note', 'partial update',
      'sleep_quality', 0,
      'photo_path', NULL,
      'ai_reflection', ''
    ),
    true, v_id, v_revision,
    'aaaaaaaa-0000-0000-0000-000000000002'
  );
  IF v_result ->> 'status' <> 'saved' THEN
    RAISE EXCEPTION 'matching row token should save: %', v_result;
  END IF;
  v_entry := v_result -> 'entry';
  v_revision := (v_entry ->> 'revision')::bigint;
  IF v_revision <> 2
     OR v_entry ->> 'note' <> 'partial update'
     OR v_entry ->> 'mood' <> '4'
     OR v_entry ->> 'sleep_quality' <> '3'
     OR v_entry ->> 'photo_path' <> 'https://photos.example.test/a.jpg'
     OR v_entry ->> 'ai_reflection' <> 'synthetic reflection'
     OR v_entry -> 'scale_values' <> (v_payload -> 'scale_values') THEN
    RAISE EXCEPTION 'partial save changed an omitted/ignored field: %', v_entry;
  END IF;
  SELECT se.value INTO v_value
    FROM public.scale_entries se
   WHERE se.user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
     AND se.scale_id = '11111111-1111-1111-1111-111111111111'
     AND se.date = '2001-01-01';
  IF v_value <> 4 THEN
    RAISE EXCEPTION 'omitted scale_values must leave mirror rows unchanged';
  END IF;

  -- Stale revision is a no-op, including every column and mirror row.
  SELECT pg_catalog.to_jsonb(e) INTO v_before
    FROM public.entries e
   WHERE e.id = v_id;
  v_conflict := public.save_daily_entry_v2(
    pg_catalog.jsonb_build_object(
      'user_id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
      'date', '2001-01-01', 'note', 'stale overwrite attempt'
    ),
    true, v_id, 1,
    'aaaaaaaa-0000-0000-0000-000000000003'
  );
  SELECT pg_catalog.to_jsonb(e) INTO v_after
    FROM public.entries e
   WHERE e.id = v_id;
  IF v_conflict ->> 'status' <> 'conflict'
     OR v_conflict ->> 'reason' <> 'stale_revision'
     OR v_before IS DISTINCT FROM v_after THEN
    RAISE EXCEPTION 'stale token must retain exact row contents: %, before %, after %', v_conflict, v_before, v_after;
  END IF;

  -- A conflict receipt remains a conflict after a later successful write.
  v_clear_photo := public.save_daily_entry_v2(
    pg_catalog.jsonb_build_object(
      'user_id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
      'date', '2001-01-01', 'clear_photo', true
    ),
    true, v_id, v_revision,
    'aaaaaaaa-0000-0000-0000-000000000004'
  );
  IF v_clear_photo ->> 'status' <> 'saved'
     OR (v_clear_photo -> 'entry') -> 'photo_path' <> 'null'::jsonb THEN
    RAISE EXCEPTION 'explicit clear_photo must atomically clear the row path: %', v_clear_photo;
  END IF;
  v_conflict_replay := public.save_daily_entry_v2(
    pg_catalog.jsonb_build_object(
      'user_id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
      'date', '2001-01-01', 'note', 'stale overwrite attempt'
    ),
    true, v_id, 1,
    'aaaaaaaa-0000-0000-0000-000000000003'
  );
  IF v_conflict_replay ->> 'status' <> 'conflict'
     OR v_conflict_replay ->> 'reason' <> 'stale_revision'
     OR v_conflict_replay ->> 'replayed' <> 'true'
     OR (v_conflict_replay ->> 'current_revision')::bigint <> 2 THEN
    RAISE EXCEPTION 'stale conflict receipt must be immutable: %', v_conflict_replay;
  END IF;

  -- Explicit scale clear supports Android's clearScaleIds while scale_values is
  -- omitted; it removes only that user's exact mirror and preserves JSONB.
  v_revision := ((v_clear_photo -> 'entry') ->> 'revision')::bigint;
  UPDATE public.scale_entries
     SET value = 1
   WHERE user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
     AND scale_id = '22222222-2222-2222-2222-222222222222'
     AND date = '2001-01-01';
  IF NOT FOUND THEN
    RAISE EXCEPTION 'explicit clear test requires an existing mirror row';
  END IF;
  v_clear_scale := public.save_daily_entry_v2(
    pg_catalog.jsonb_build_object(
      'user_id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
      'date', '2001-01-01',
      'clear_scale_ids', pg_catalog.jsonb_build_array('22222222-2222-2222-2222-222222222222')
    ),
    true, v_id, v_revision,
    'aaaaaaaa-0000-0000-0000-000000000005'
  );
  IF v_clear_scale ->> 'status' <> 'saved'
     OR (v_clear_scale -> 'entry') -> 'scale_values'
          <> ((v_payload -> 'scale_values') - '22222222-2222-2222-2222-222222222222') THEN
    RAISE EXCEPTION 'explicit scale clear must remove the JSON key and preserve unrelated values: %', v_clear_scale;
  END IF;
  SELECT count(*) INTO v_count
    FROM public.scale_entries se
   WHERE se.user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
     AND se.scale_id = '22222222-2222-2222-2222-222222222222'
     AND se.date = '2001-01-01';
  IF v_count <> 0 THEN
    RAISE EXCEPTION 'explicit scale clear did not remove exact owner mirror';
  END IF;
  SELECT count(*), max(se.value) INTO v_count, v_value
    FROM public.scale_entries se
   WHERE se.user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
     AND se.scale_id = '44444444-4444-4444-4444-444444444444'
     AND se.date = '2001-01-01';
  IF v_count <> 1 OR v_value <> 3 THEN
    RAISE EXCEPTION 'clearing one scale must preserve an unrelated scale mirror';
  END IF;

  -- Replacing scale_values removes keys absent from the new JSON and their
  -- stale mirrors, while preserving unrelated entry fields and other owners.
  v_revision := ((v_clear_scale -> 'entry') ->> 'revision')::bigint;
  v_result := public.save_daily_entry_v2(
    pg_catalog.jsonb_build_object(
      'user_id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
      'date', '2001-01-01',
      'scale_values', pg_catalog.jsonb_build_object(
        '11111111-1111-1111-1111-111111111111', 7
      )
    ),
    true, v_id, v_revision,
    'aaaaaaaa-0000-0000-0000-00000000000a'
  );
  IF v_result ->> 'status' <> 'saved' THEN
    RAISE EXCEPTION 'scale replacement should save: %', v_result;
  END IF;
  v_entry := v_result -> 'entry';
  IF v_entry -> 'scale_values' <> '{"11111111-1111-1111-1111-111111111111": 7}'::jsonb
     OR v_entry ->> 'note' <> 'partial update'
     OR v_entry ->> 'mood' <> '4' THEN
    RAISE EXCEPTION 'scale replacement changed unrelated JSON or entry fields: %', v_entry;
  END IF;
  SELECT count(*), max(se.value) INTO v_count, v_value
    FROM public.scale_entries se
   WHERE se.user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
     AND se.scale_id = '11111111-1111-1111-1111-111111111111'
     AND se.date = '2001-01-01';
  IF v_count <> 1 OR v_value <> 7 THEN
    RAISE EXCEPTION 'scale replacement did not update the retained mirror';
  END IF;
  SELECT count(*) INTO v_count
    FROM public.scale_entries se
   WHERE se.user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
     AND se.scale_id = '44444444-4444-4444-4444-444444444444'
     AND se.date = '2001-01-01';
  IF v_count <> 0 THEN
    RAISE EXCEPTION 'scale replacement left a stale mirror for a removed JSON key';
  END IF;

  -- An explicit JSON null is not a request to clear scale state: reject it
  -- before writes. Omission remains a no-op; an empty object is the explicit
  -- replacement that clears the map and its positive mirrors.
  v_result := public.save_daily_entry_v2(
    pg_catalog.jsonb_build_object(
      'user_id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
      'date', '2001-01-02',
      'scale_values', pg_catalog.jsonb_build_object(
        '11111111-1111-1111-1111-111111111111', 4,
        '44444444-4444-4444-4444-444444444444', 3
      )
    ),
    false, NULL, NULL,
    'aaaaaaaa-0000-0000-0000-000000000010'
  );
  IF v_result ->> 'status' <> 'saved' THEN
    RAISE EXCEPTION 'scale null test setup should save: %', v_result;
  END IF;
  v_scale_entry_id := ((v_result -> 'entry') ->> 'id')::uuid;
  v_scale_revision := ((v_result -> 'entry') ->> 'revision')::bigint;
  v_rejected := false;
  v_scale_error := NULL;
  BEGIN
    PERFORM public.save_daily_entry_v2(
      pg_catalog.jsonb_build_object(
        'user_id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        'date', '2001-01-02',
        'scale_values', NULL
      ),
      true, v_scale_entry_id, v_scale_revision,
      'aaaaaaaa-0000-0000-0000-000000000011'
    );
  EXCEPTION WHEN SQLSTATE '22023' THEN
    v_rejected := true;
    GET STACKED DIAGNOSTICS v_scale_error = MESSAGE_TEXT;
  END;
  IF NOT v_rejected OR v_scale_error IS DISTINCT FROM
       'save_daily_entry_v2: scale_values must not be null; omit it to leave scale state unchanged or use {} to clear it' THEN
    RAISE EXCEPTION 'explicit scale_values null must raise the controlled validation error, got %', v_scale_error;
  END IF;
  SELECT e.scale_values, e.revision INTO v_scale_state, v_scale_revision
    FROM public.entries e WHERE e.id = v_scale_entry_id;
  SELECT COALESCE(pg_catalog.jsonb_object_agg(se.scale_id::text, se.value), '{}'::jsonb)
    INTO v_mirror_state
    FROM public.scale_entries se
   WHERE se.user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
     AND se.date = '2001-01-02';
  IF v_scale_state IS DISTINCT FROM
       '{"11111111-1111-1111-1111-111111111111": 4, "44444444-4444-4444-4444-444444444444": 3}'::jsonb
     OR v_mirror_state IS DISTINCT FROM
       '{"11111111-1111-1111-1111-111111111111": 4, "44444444-4444-4444-4444-444444444444": 3}'::jsonb
     OR v_scale_revision <> 1 THEN
    RAISE EXCEPTION 'rejected scale_values null changed JSON, mirrors, or revision: %, %, %',
      v_scale_state, v_mirror_state, v_scale_revision;
  END IF;

  v_result := public.save_daily_entry_v2(
    pg_catalog.jsonb_build_object(
      'user_id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
      'date', '2001-01-02', 'note', 'omitted scale map'
    ),
    true, v_scale_entry_id, v_scale_revision,
    'aaaaaaaa-0000-0000-0000-000000000012'
  );
  IF v_result ->> 'status' <> 'saved'
     OR (v_result -> 'entry') -> 'scale_values' IS DISTINCT FROM
       '{"11111111-1111-1111-1111-111111111111": 4, "44444444-4444-4444-4444-444444444444": 3}'::jsonb THEN
    RAISE EXCEPTION 'omitted scale_values must preserve the JSON map: %', v_result;
  END IF;
  v_scale_revision := ((v_result -> 'entry') ->> 'revision')::bigint;
  SELECT e.scale_values INTO v_scale_state
    FROM public.entries e WHERE e.id = v_scale_entry_id;
  SELECT COALESCE(pg_catalog.jsonb_object_agg(se.scale_id::text, se.value), '{}'::jsonb)
    INTO v_mirror_state
    FROM public.scale_entries se
   WHERE se.user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
     AND se.date = '2001-01-02';
  IF v_scale_state IS DISTINCT FROM
       '{"11111111-1111-1111-1111-111111111111": 4, "44444444-4444-4444-4444-444444444444": 3}'::jsonb
     OR v_mirror_state IS DISTINCT FROM
       '{"11111111-1111-1111-1111-111111111111": 4, "44444444-4444-4444-4444-444444444444": 3}'::jsonb THEN
    RAISE EXCEPTION 'omitted scale_values changed JSON or positive mirrors: %, %',
      v_scale_state, v_mirror_state;
  END IF;

  v_result := public.save_daily_entry_v2(
    pg_catalog.jsonb_build_object(
      'user_id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
      'date', '2001-01-02', 'scale_values', '{}'::jsonb
    ),
    true, v_scale_entry_id, v_scale_revision,
    'aaaaaaaa-0000-0000-0000-000000000013'
  );
  IF v_result ->> 'status' <> 'saved'
     OR (v_result -> 'entry') -> 'scale_values' IS DISTINCT FROM '{}'::jsonb THEN
    RAISE EXCEPTION 'empty scale_values object must explicitly clear the JSON map: %', v_result;
  END IF;
  SELECT e.scale_values INTO v_scale_state
    FROM public.entries e WHERE e.id = v_scale_entry_id;
  SELECT COALESCE(pg_catalog.jsonb_object_agg(se.scale_id::text, se.value), '{}'::jsonb)
    INTO v_mirror_state
    FROM public.scale_entries se
   WHERE se.user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
     AND se.date = '2001-01-02';
  IF v_scale_state IS DISTINCT FROM '{}'::jsonb
     OR v_mirror_state IS DISTINCT FROM '{}'::jsonb THEN
    RAISE EXCEPTION 'empty scale_values object must clear the JSON map and positive mirrors: %, %',
      v_scale_state, v_mirror_state;
  END IF;

  -- The same contract applies when clear_scale_ids accompanies scale_values:
  -- null rejects the entire request without applying its clear; omission lets
  -- only the explicit clear run; {} replaces the whole map and all mirrors.
  v_result := public.save_daily_entry_v2(
    pg_catalog.jsonb_build_object(
      'user_id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
      'date', '2001-01-03',
      'scale_values', pg_catalog.jsonb_build_object(
        '11111111-1111-1111-1111-111111111111', 4,
        '44444444-4444-4444-4444-444444444444', 3
      )
    ),
    false, NULL, NULL,
    'aaaaaaaa-0000-0000-0000-000000000014'
  );
  IF v_result ->> 'status' <> 'saved' THEN
    RAISE EXCEPTION 'clear_scale_ids null test setup should save: %', v_result;
  END IF;
  v_scale_entry_id := ((v_result -> 'entry') ->> 'id')::uuid;
  v_scale_revision := ((v_result -> 'entry') ->> 'revision')::bigint;
  v_rejected := false;
  v_scale_error := NULL;
  BEGIN
    PERFORM public.save_daily_entry_v2(
      pg_catalog.jsonb_build_object(
        'user_id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        'date', '2001-01-03',
        'scale_values', NULL,
        'clear_scale_ids', pg_catalog.jsonb_build_array('11111111-1111-1111-1111-111111111111')
      ),
      true, v_scale_entry_id, v_scale_revision,
      'aaaaaaaa-0000-0000-0000-000000000015'
    );
  EXCEPTION WHEN SQLSTATE '22023' THEN
    v_rejected := true;
    GET STACKED DIAGNOSTICS v_scale_error = MESSAGE_TEXT;
  END;
  IF NOT v_rejected OR v_scale_error IS DISTINCT FROM
       'save_daily_entry_v2: scale_values must not be null; omit it to leave scale state unchanged or use {} to clear it' THEN
    RAISE EXCEPTION 'scale_values null with clear_scale_ids must raise the controlled validation error, got %', v_scale_error;
  END IF;
  SELECT e.scale_values, e.revision INTO v_scale_state, v_scale_revision
    FROM public.entries e WHERE e.id = v_scale_entry_id;
  SELECT COALESCE(pg_catalog.jsonb_object_agg(se.scale_id::text, se.value), '{}'::jsonb)
    INTO v_mirror_state
    FROM public.scale_entries se
   WHERE se.user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
     AND se.date = '2001-01-03';
  IF v_scale_state IS DISTINCT FROM
       '{"11111111-1111-1111-1111-111111111111": 4, "44444444-4444-4444-4444-444444444444": 3}'::jsonb
     OR v_mirror_state IS DISTINCT FROM
       '{"11111111-1111-1111-1111-111111111111": 4, "44444444-4444-4444-4444-444444444444": 3}'::jsonb
     OR v_scale_revision <> 1 THEN
    RAISE EXCEPTION 'rejected null plus clear_scale_ids changed JSON, mirrors, or revision: %, %, %',
      v_scale_state, v_mirror_state, v_scale_revision;
  END IF;

  v_result := public.save_daily_entry_v2(
    pg_catalog.jsonb_build_object(
      'user_id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
      'date', '2001-01-03',
      'clear_scale_ids', pg_catalog.jsonb_build_array('11111111-1111-1111-1111-111111111111')
    ),
    true, v_scale_entry_id, v_scale_revision,
    'aaaaaaaa-0000-0000-0000-000000000016'
  );
  IF v_result ->> 'status' <> 'saved'
     OR (v_result -> 'entry') -> 'scale_values' IS DISTINCT FROM
       '{"44444444-4444-4444-4444-444444444444": 3}'::jsonb THEN
    RAISE EXCEPTION 'omitted scale_values with clear_scale_ids must clear only the named JSON key: %', v_result;
  END IF;
  v_scale_revision := ((v_result -> 'entry') ->> 'revision')::bigint;
  SELECT e.scale_values INTO v_scale_state
    FROM public.entries e WHERE e.id = v_scale_entry_id;
  SELECT COALESCE(pg_catalog.jsonb_object_agg(se.scale_id::text, se.value), '{}'::jsonb)
    INTO v_mirror_state
    FROM public.scale_entries se
   WHERE se.user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
     AND se.date = '2001-01-03';
  IF v_scale_state IS DISTINCT FROM '{"44444444-4444-4444-4444-444444444444": 3}'::jsonb
     OR v_mirror_state IS DISTINCT FROM '{"44444444-4444-4444-4444-444444444444": 3}'::jsonb THEN
    RAISE EXCEPTION 'omitted map plus clear_scale_ids changed an unrelated JSON value or mirror: %, %',
      v_scale_state, v_mirror_state;
  END IF;

  v_result := public.save_daily_entry_v2(
    pg_catalog.jsonb_build_object(
      'user_id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
      'date', '2001-01-03',
      'scale_values', '{}'::jsonb,
      'clear_scale_ids', pg_catalog.jsonb_build_array('44444444-4444-4444-4444-444444444444')
    ),
    true, v_scale_entry_id, v_scale_revision,
    'aaaaaaaa-0000-0000-0000-000000000017'
  );
  IF v_result ->> 'status' <> 'saved'
     OR (v_result -> 'entry') -> 'scale_values' IS DISTINCT FROM '{}'::jsonb THEN
    RAISE EXCEPTION 'empty map with clear_scale_ids must replace the JSON map: %', v_result;
  END IF;
  SELECT e.scale_values INTO v_scale_state
    FROM public.entries e WHERE e.id = v_scale_entry_id;
  SELECT COALESCE(pg_catalog.jsonb_object_agg(se.scale_id::text, se.value), '{}'::jsonb)
    INTO v_mirror_state
    FROM public.scale_entries se
   WHERE se.user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
     AND se.date = '2001-01-03';
  IF v_scale_state IS DISTINCT FROM '{}'::jsonb
     OR v_mirror_state IS DISTINCT FROM '{}'::jsonb THEN
    RAISE EXCEPTION 'empty map with clear_scale_ids must clear the full JSON map and mirrors: %, %',
      v_scale_state, v_mirror_state;
  END IF;

  -- Same request ID with different semantics is rejected without a write.
  v_rejected := false;
  BEGIN
    PERFORM public.save_daily_entry_v2(
      pg_catalog.jsonb_build_object(
        'user_id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        'date', '2001-01-01', 'note', 'tampered replay'
      ),
      false, NULL, NULL,
      'aaaaaaaa-0000-0000-0000-000000000001'
    );
  EXCEPTION WHEN SQLSTATE '22023' THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN
    RAISE EXCEPTION 'request ID reuse with a different fingerprint must be rejected';
  END IF;

  -- Caller cannot name another owner even when supplying a plausible date.
  v_rejected := false;
  BEGIN
    PERFORM public.save_daily_entry_v2(
      pg_catalog.jsonb_build_object(
        'user_id', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
        'date', '2001-01-01', 'note', 'cross-owner attempt'
      ),
      false, NULL, NULL,
      'aaaaaaaa-0000-0000-0000-000000000006'
    );
  EXCEPTION WHEN insufficient_privilege THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN
    RAISE EXCEPTION 'cross-user save must be denied';
  END IF;

  -- Date checks are server-side: impossible calendar dates and dates after
  -- Europe/Prague today are rejected before a receipt or row is created.
  v_rejected := false;
  BEGIN
    PERFORM public.save_daily_entry_v2(
      pg_catalog.jsonb_build_object(
        'user_id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        'date', '2026-02-30', 'note', 'impossible date'
      ),
      false, NULL, NULL,
      'aaaaaaaa-0000-0000-0000-000000000008'
    );
  EXCEPTION WHEN SQLSTATE '22023' THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN
    RAISE EXCEPTION 'impossible date must be rejected';
  END IF;

  v_rejected := false;
  BEGIN
    PERFORM public.save_daily_entry_v2(
      pg_catalog.jsonb_build_object(
        'user_id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        'date', pg_catalog.to_char((pg_catalog.now() AT TIME ZONE 'Europe/Prague')::date + 1, 'YYYY-MM-DD'),
        'note', 'future date'
      ),
      false, NULL, NULL,
      'aaaaaaaa-0000-0000-0000-000000000009'
    );
  EXCEPTION WHEN SQLSTATE '22023' THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN
    RAISE EXCEPTION 'future date must be rejected';
  END IF;
END;
$test$;

RESET ROLE;
DO $privileges$
BEGIN
  IF pg_catalog.has_function_privilege(
       'service_role',
       'public.save_daily_entry_v2(jsonb,boolean,uuid,bigint,uuid)',
       'EXECUTE'
     ) THEN
    RAISE EXCEPTION 'service_role must not have direct EXECUTE';
  END IF;
  IF pg_catalog.has_function_privilege(
       'anon',
       'public.save_daily_entry_v2(jsonb,boolean,uuid,bigint,uuid)',
       'EXECUTE'
     ) THEN
    RAISE EXCEPTION 'anon must not have EXECUTE';
  END IF;
  IF pg_catalog.has_table_privilege('authenticated', 'public.entry_save_v2_receipts', 'SELECT')
     OR pg_catalog.has_table_privilege('service_role', 'public.entry_save_v2_receipts', 'SELECT') THEN
    RAISE EXCEPTION 'API roles must not read request receipts directly';
  END IF;
  IF pg_catalog.pg_has_role('authenticated'::name, 'diarium_entry_sync_owner'::name, 'MEMBER')
     OR pg_catalog.pg_has_role('service_role'::name, 'diarium_entry_sync_owner'::name, 'MEMBER')
     OR pg_catalog.pg_has_role('anon'::name, 'diarium_entry_sync_owner'::name, 'MEMBER') THEN
    RAISE EXCEPTION 'an API role can assume the function owner';
  END IF;
  IF pg_catalog.has_schema_privilege('diarium_entry_sync_owner', 'public', 'CREATE') THEN
    RAISE EXCEPTION 'function owner must not retain CREATE on public';
  END IF;
  IF EXISTS (
    SELECT 1 FROM public.entries e
     WHERE e.user_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
       AND e.date = '2001-01-01'
  ) THEN
    RAISE EXCEPTION 'cross-user attempt created an entry';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.scale_entries se
     WHERE se.user_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
       AND se.scale_id = '33333333-3333-3333-3333-333333333333'
       AND se.date = '2001-01-01' AND se.value = 5
  ) THEN
    RAISE EXCEPTION 'other user scale row changed';
  END IF;
  IF EXISTS (
    SELECT 1 FROM public.entry_save_v2_receipts r
     WHERE r.user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
       AND pg_catalog.strpos(r.outcome::text, 'private synthetic note') > 0
  ) THEN
    RAISE EXCEPTION 'receipt must not persist journal text';
  END IF;
  IF EXISTS (
    SELECT 1 FROM public.entry_save_v2_receipts r
     WHERE r.user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
       AND r.request_id IN (
         'aaaaaaaa-0000-0000-0000-000000000011',
         'aaaaaaaa-0000-0000-0000-000000000015'
       )
  ) THEN
    RAISE EXCEPTION 'rejected scale_values null requests must not create receipts';
  END IF;
END;
$privileges$;

-- A service-role JWT is denied even if it somehow reaches the authenticated
-- function ACL through an inherited/misconfigured database membership.
SET LOCAL ROLE authenticated;
SET LOCAL "request.jwt.claims" = '{"sub":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","role":"service_role"}';
DO $service_jwt$
DECLARE
  v_rejected boolean := false;
BEGIN
  BEGIN
    PERFORM public.save_daily_entry_v2(
      pg_catalog.jsonb_build_object(
        'user_id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        'date', '2001-01-01', 'note', 'service-role attempt'
      ),
      false, NULL, NULL,
      'aaaaaaaa-0000-0000-0000-000000000007'
    );
  EXCEPTION WHEN insufficient_privilege THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN
    RAISE EXCEPTION 'service_role JWT must be rejected by the function body';
  END IF;
END;
$service_jwt$;

ROLLBACK;
