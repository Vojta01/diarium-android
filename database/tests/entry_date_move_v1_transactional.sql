\set ON_ERROR_STOP on
BEGIN;

-- All fixtures are deterministic synthetic rows in the guarded CI database.
INSERT INTO public.scales (id, user_id, name) VALUES
  ('11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Synthetic scale one'),
  ('22222222-2222-2222-2222-222222222222', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Synthetic scale two'),
  ('33333333-3333-3333-3333-333333333333', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'Other synthetic scale');

INSERT INTO public.entries (id, user_id, date, mood, note, photo_path, scale_values) VALUES
  ('10000000-0000-0000-0000-000000000001', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1991-01-01', 4, 'same-day source', 'https://photos.example.test/a/1991-01-01.jpg', '{"11111111-1111-1111-1111-111111111111":5}'),
  ('10000000-0000-0000-0000-000000000002', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1991-02-01', 3, 'missing-source target guard', 'https://photos.example.test/a/1991-02-01.jpg', '{}'),
  ('10000000-0000-0000-0000-000000000003', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1992-01-01', 5, 'ordinary source', 'https://photos.example.test/a/1992-01-01.jpg', '{"11111111-1111-1111-1111-111111111111":4,"22222222-2222-2222-2222-222222222222":2}'),
  ('10000000-0000-0000-0000-000000000004', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1993-01-01', 2, 'overwrite source', 'https://photos.example.test/a/1993-01-01.jpg', '{"11111111-1111-1111-1111-111111111111":3}'),
  ('10000000-0000-0000-0000-000000000005', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1993-01-02', 1, 'overwrite target', 'https://photos.example.test/a/1993-01-02.jpg', '{"22222222-2222-2222-2222-222222222222":1}'),
  ('10000000-0000-0000-0000-000000000006', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1994-01-01', 3, 'stale-target source', 'https://photos.example.test/a/1994-01-01.jpg', '{}'),
  ('10000000-0000-0000-0000-000000000007', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1994-01-02', 2, 'target before concurrent update', 'https://photos.example.test/a/1994-01-02.jpg', '{}'),
  ('10000000-0000-0000-0000-000000000008', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1995-01-01', 3, 'source before concurrent update', 'https://photos.example.test/a/1995-01-01.jpg', '{}'),
  ('10000000-0000-0000-0000-000000000009', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1996-01-01', 4, 'rollback source', 'https://photos.example.test/a/1996-01-01.jpg', '{"11111111-1111-1111-1111-111111111111":4}'),
  ('20000000-0000-0000-0000-000000000001', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '1997-01-01', 5, 'other user must remain private', 'https://photos.example.test/b/1997-01-01.jpg', '{}');

INSERT INTO public.scale_entries (user_id, scale_id, date, value) VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', '1991-01-01', 5),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', '1992-01-01', 4),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '22222222-2222-2222-2222-222222222222', '1992-01-01', 2),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', '1993-01-01', 3),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '22222222-2222-2222-2222-222222222222', '1993-01-02', 1),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', '1994-01-02', 4),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', '1996-01-01', 4),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '33333333-3333-3333-3333-333333333333', '1997-01-01', 5);

-- A target-side scale value without an entry row must not be silently lost.
INSERT INTO public.scale_entries (user_id, scale_id, date, value) VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '22222222-2222-2222-2222-222222222222', '1992-02-02', 5);
INSERT INTO public.entries (id, user_id, date, mood, note, scale_values) VALUES
  ('10000000-0000-0000-0000-000000000010', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1992-02-01', 2, 'source with scale-only target', '{}');
INSERT INTO public.scale_entries (user_id, scale_id, date, value) VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', '1992-02-01', 2);

-- Make the stale-source token deterministic: every ordinary UPDATE increments revision.
UPDATE public.entries SET note = 'source updated after read'
 WHERE id = '10000000-0000-0000-0000-000000000008';
UPDATE public.entries SET note = 'target updated after read'
 WHERE id = '10000000-0000-0000-0000-000000000007';

-- This test-only trigger exercises rollback after the function has begun row writes.
CREATE FUNCTION public.test_reject_scale_move()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $test_trigger$
BEGIN
  IF NEW.date = DATE '1996-01-02' THEN
    RAISE EXCEPTION 'synthetic scale-move failure';
  END IF;
  RETURN NEW;
END;
$test_trigger$;
CREATE TRIGGER test_reject_scale_move
  BEFORE UPDATE ON public.scale_entries
  FOR EACH ROW EXECUTE FUNCTION public.test_reject_scale_move();

SET LOCAL ROLE authenticated;
SET LOCAL "request.jwt.claims" = '{"sub":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","role":"authenticated"}';

DO $same_day$
DECLARE
  v_result jsonb;
  v_count bigint;
BEGIN
  v_result := public.move_daily_entry_v1(
    '1991-01-01', '10000000-0000-0000-0000-000000000001', 1,
    '1991-01-01', true, '10000000-0000-0000-0000-000000000001', 1,
    false, '90000000-0000-0000-0000-000000000001'
  );
  SELECT count(*) INTO v_count FROM public.entries
   WHERE user_id = auth.uid() AND date = '1991-01-01';
  IF v_result ->> 'status' <> 'unchanged' OR v_count <> 1
     OR (v_result -> 'entry') ->> 'photo_path' <> 'https://photos.example.test/a/1991-01-01.jpg' THEN
    RAISE EXCEPTION 'same-date move must be a token-checked no-op: %', v_result;
  END IF;
  v_result := public.move_daily_entry_v1(
    '1991-01-01', '10000000-0000-0000-0000-000000000001', 1,
    '1991-01-01', false, NULL, NULL, false,
    '90000000-0000-0000-0000-000000000014'
  );
  IF v_result ->> 'status' <> 'conflict' OR v_result ->> 'reason' <> 'destination_exists'
     OR (SELECT count(*) FROM public.entries WHERE user_id = auth.uid() AND date = '1991-01-01') <> 1 THEN
    RAISE EXCEPTION 'same-date move with an absent-target token must conflict without changing the source: %', v_result;
  END IF;
  v_result := public.move_daily_entry_v1(
    '1991-01-01', '10000000-0000-0000-0000-000000000001', 1,
    '1991-01-01', true, '10000000-0000-0000-0000-000000000099', 1,
    false, '90000000-0000-0000-0000-000000000015'
  );
  IF v_result ->> 'status' <> 'conflict' OR v_result ->> 'reason' <> 'stale_target'
     OR (SELECT count(*) FROM public.entries WHERE user_id = auth.uid() AND date = '1991-01-01') <> 1 THEN
    RAISE EXCEPTION 'same-date move with a stale target token must conflict without changing the source: %', v_result;
  END IF;
  RAISE NOTICE 'PASS same-day no-op validates source and destination tokens';
END;
$same_day$;

DO $missing_source$
DECLARE
  v_result jsonb;
BEGIN
  v_result := public.move_daily_entry_v1(
    '1991-01-03', '10000000-0000-0000-0000-000000000011', 1,
    '1991-01-04', false, NULL, NULL, false,
    '90000000-0000-0000-0000-000000000002'
  );
  IF v_result ->> 'status' <> 'conflict' OR v_result ->> 'reason' <> 'source_missing'
     OR EXISTS (SELECT 1 FROM public.entries WHERE user_id = auth.uid() AND date = '1991-01-04') THEN
    RAISE EXCEPTION 'missing source must conflict without creating a target: %', v_result;
  END IF;
  RAISE NOTICE 'PASS missing source conflicts without creating a target';
END;
$missing_source$;

DO $move_to_absent$
DECLARE
  v_result jsonb;
  v_count bigint;
  v_source_count bigint;
  v_scale_count bigint;
BEGIN
  v_result := public.move_daily_entry_v1(
    '1992-01-01', '10000000-0000-0000-0000-000000000003', 1,
    '1992-01-02', false, NULL, NULL, false,
    '90000000-0000-0000-0000-000000000003'
  );
  SELECT count(*) INTO v_count FROM public.entries
   WHERE user_id = auth.uid() AND date = '1992-01-02'
     AND id = '10000000-0000-0000-0000-000000000003';
  SELECT count(*) INTO v_source_count FROM public.entries
   WHERE user_id = auth.uid() AND date = '1992-01-01';
  SELECT count(*) INTO v_scale_count FROM public.scale_entries
   WHERE user_id = auth.uid() AND date = '1992-01-02';
  IF v_result ->> 'status' <> 'moved' OR v_count <> 1 OR v_source_count <> 0
     OR v_scale_count <> 2 OR (v_result -> 'entry') ->> 'revision' <> '2'
     OR (v_result -> 'entry') ->> 'photo_path' <> 'https://photos.example.test/a/1992-01-01.jpg'
     OR (v_result -> 'entry') -> 'scale_values' <> '{"11111111-1111-1111-1111-111111111111":4,"22222222-2222-2222-2222-222222222222":2}'::jsonb THEN
    RAISE EXCEPTION 'move to absent target lost entry, photo or scale data: %, mirrors=%', v_result, v_scale_count;
  END IF;
  IF (SELECT count(*) FROM public.scale_entries WHERE user_id = auth.uid() AND date = '1992-01-01') <> 0
     OR NOT EXISTS (SELECT 1 FROM public.scale_entries WHERE user_id = auth.uid() AND date = '1992-01-02' AND scale_id = '11111111-1111-1111-1111-111111111111' AND value = 4)
     OR NOT EXISTS (SELECT 1 FROM public.scale_entries WHERE user_id = auth.uid() AND date = '1992-01-02' AND scale_id = '22222222-2222-2222-2222-222222222222' AND value = 2) THEN
    RAISE EXCEPTION 'scale mirror rows were not transferred exactly';
  END IF;
  v_result := public.move_daily_entry_v1(
    '1992-01-01', '10000000-0000-0000-0000-000000000003', 1,
    '1992-01-02', false, NULL, NULL, false,
    '90000000-0000-0000-0000-000000000003'
  );
  IF v_result ->> 'status' <> 'moved' OR v_result ->> 'replayed' <> 'true'
     OR (SELECT count(*) FROM public.entries WHERE user_id = auth.uid() AND date = '1992-01-02') <> 1 THEN
    RAISE EXCEPTION 'ambiguous retry must replay original move receipt: %', v_result;
  END IF;
  RAISE NOTICE 'PASS absent-target move preserves row ID, scale mirrors, photo pointer and replay receipt';
END;
$move_to_absent$;

DO $scale_only_target$
DECLARE
  v_result jsonb;
BEGIN
  v_result := public.move_daily_entry_v1(
    '1992-02-01', '10000000-0000-0000-0000-000000000010', 1,
    '1992-02-02', false, NULL, NULL, false,
    '90000000-0000-0000-0000-000000000004'
  );
  IF v_result ->> 'status' <> 'conflict' OR v_result ->> 'reason' <> 'destination_scale_data_exists'
     OR NOT EXISTS (SELECT 1 FROM public.scale_entries WHERE user_id = auth.uid() AND date = '1992-02-02' AND scale_id = '22222222-2222-2222-2222-222222222222' AND value = 5)
     OR NOT EXISTS (SELECT 1 FROM public.scale_entries WHERE user_id = auth.uid() AND date = '1992-02-01' AND scale_id = '11111111-1111-1111-1111-111111111111' AND value = 2) THEN
    RAISE EXCEPTION 'scale-only destination must be preserved as a conflict: %', v_result;
  END IF;
  RAISE NOTICE 'PASS absent entry with pre-existing target scale data conflicts safely';
END;
$scale_only_target$;

DO $occupied_target$
DECLARE
  v_result jsonb;
  v_source_id uuid := '10000000-0000-0000-0000-000000000004';
  v_target_id uuid := '10000000-0000-0000-0000-000000000005';
BEGIN
  v_result := public.move_daily_entry_v1(
    '1993-01-01', v_source_id, 1, '1993-01-02', true, v_target_id, 1,
    false, '90000000-0000-0000-0000-000000000005'
  );
  IF v_result ->> 'status' <> 'confirmation_required'
     OR NOT EXISTS (SELECT 1 FROM public.entries WHERE user_id = auth.uid() AND id = v_source_id AND date = '1993-01-01' AND note = 'overwrite source')
     OR NOT EXISTS (SELECT 1 FROM public.entries WHERE user_id = auth.uid() AND id = v_target_id AND date = '1993-01-02' AND note = 'overwrite target') THEN
    RAISE EXCEPTION 'occupied target without confirmation must preserve both entries: %', v_result;
  END IF;

  v_result := public.move_daily_entry_v1(
    '1993-01-01', v_source_id, 1, '1993-01-02', true, v_target_id, 1,
    true, '90000000-0000-0000-0000-000000000006'
  );
  IF v_result ->> 'status' <> 'moved' OR v_result ->> 'overwrote' <> 'true'
     OR (v_result -> 'entry') ->> 'id' <> v_source_id::text
     OR (v_result -> 'entry') ->> 'note' <> 'overwrite source'
     OR (v_result -> 'entry') ->> 'photo_path' <> 'https://photos.example.test/a/1993-01-01.jpg'
     OR EXISTS (SELECT 1 FROM public.entries WHERE user_id = auth.uid() AND id = v_target_id)
     OR EXISTS (SELECT 1 FROM public.scale_entries WHERE user_id = auth.uid() AND date = '1993-01-02' AND scale_id = '22222222-2222-2222-2222-222222222222')
     OR NOT EXISTS (SELECT 1 FROM public.scale_entries WHERE user_id = auth.uid() AND date = '1993-01-02' AND scale_id = '11111111-1111-1111-1111-111111111111' AND value = 3)
     OR EXISTS (SELECT 1 FROM public.scale_entries WHERE user_id = auth.uid() AND date = '1993-01-01') THEN
    RAISE EXCEPTION 'confirmed overwrite did not atomically replace target data: %', v_result;
  END IF;
  RAISE NOTICE 'PASS overwrite requires a second confirmation request and exact target token';
END;
$occupied_target$;

DO $stale_target$
DECLARE
  v_result jsonb;
BEGIN
  v_result := public.move_daily_entry_v1(
    '1994-01-01', '10000000-0000-0000-0000-000000000006', 1,
    '1994-01-02', true, '10000000-0000-0000-0000-000000000007', 1,
    true, '90000000-0000-0000-0000-000000000007'
  );
  IF v_result ->> 'status' <> 'conflict' OR v_result ->> 'reason' <> 'stale_target'
     OR NOT EXISTS (SELECT 1 FROM public.entries WHERE user_id = auth.uid() AND date = '1994-01-01' AND id = '10000000-0000-0000-0000-000000000006')
     OR NOT EXISTS (SELECT 1 FROM public.entries WHERE user_id = auth.uid() AND date = '1994-01-02' AND note = 'target updated after read') THEN
    RAISE EXCEPTION 'stale destination token must never authorize overwrite: %', v_result;
  END IF;
  RAISE NOTICE 'PASS stale destination row/version token cannot overwrite';
END;
$stale_target$;

DO $stale_source$
DECLARE
  v_result jsonb;
BEGIN
  v_result := public.move_daily_entry_v1(
    '1995-01-01', '10000000-0000-0000-0000-000000000008', 1,
    '1995-01-02', false, NULL, NULL, false,
    '90000000-0000-0000-0000-000000000008'
  );
  IF v_result ->> 'status' <> 'conflict' OR v_result ->> 'reason' <> 'stale_source'
     OR NOT EXISTS (SELECT 1 FROM public.entries WHERE user_id = auth.uid() AND date = '1995-01-01' AND note = 'source updated after read')
     OR EXISTS (SELECT 1 FROM public.entries WHERE user_id = auth.uid() AND date = '1995-01-02') THEN
    RAISE EXCEPTION 'stale source token must preserve the newer source row: %', v_result;
  END IF;
  RAISE NOTICE 'PASS stale source row/version token preserves newer data';
END;
$stale_source$;

DO $cross_user$
DECLARE
  v_result jsonb;
BEGIN
  v_result := public.move_daily_entry_v1(
    '1997-01-01', '20000000-0000-0000-0000-000000000001', 1,
    '1997-01-02', false, NULL, NULL, false,
    '90000000-0000-0000-0000-000000000009'
  );
  IF v_result ->> 'status' <> 'conflict' OR v_result ->> 'reason' <> 'source_missing'
     OR EXISTS (SELECT 1 FROM public.entries WHERE user_id = auth.uid() AND date = '1997-01-02') THEN
    RAISE EXCEPTION 'cross-user source probe must be indistinguishable from missing: %', v_result;
  END IF;
  RAISE NOTICE 'PASS cross-user source cannot be moved or enumerated';
END;
$cross_user$;

DO $rollback$
DECLARE
  v_failed boolean := false;
  v_result jsonb;
BEGIN
  BEGIN
    PERFORM public.move_daily_entry_v1(
      '1996-01-01', '10000000-0000-0000-0000-000000000009', 1,
      '1996-01-02', false, NULL, NULL, false,
      '90000000-0000-0000-0000-000000000010'
    );
  EXCEPTION WHEN raise_exception THEN
    v_failed := true;
  END;
  IF NOT v_failed
     OR NOT EXISTS (SELECT 1 FROM public.entries WHERE user_id = auth.uid() AND id = '10000000-0000-0000-0000-000000000009' AND date = '1996-01-01')
     OR EXISTS (SELECT 1 FROM public.entries WHERE user_id = auth.uid() AND date = '1996-01-02')
     OR NOT EXISTS (SELECT 1 FROM public.scale_entries WHERE user_id = auth.uid() AND scale_id = '11111111-1111-1111-1111-111111111111' AND date = '1996-01-01' AND value = 4) THEN
    RAISE EXCEPTION 'scale mutation failure did not roll the whole move back';
  END IF;
END;
$rollback$;

RESET ROLE;
DROP TRIGGER test_reject_scale_move ON public.scale_entries;
DROP FUNCTION public.test_reject_scale_move();
COMMIT;

-- Verify failed operation left no committed receipt, then a same-ID retry can commit.
BEGIN;
SET LOCAL ROLE authenticated;
SET LOCAL "request.jwt.claims" = '{"sub":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","role":"authenticated"}';
DO $retry_after_failure$
DECLARE
  v_result jsonb;
BEGIN
  v_result := public.move_daily_entry_v1(
    '1996-01-01', '10000000-0000-0000-0000-000000000009', 1,
    '1996-01-02', false, NULL, NULL, false,
    '90000000-0000-0000-0000-000000000010'
  );
  IF v_result ->> 'status' <> 'moved' OR v_result ->> 'replayed' <> 'false'
     OR NOT EXISTS (SELECT 1 FROM public.entries WHERE user_id = auth.uid() AND id = '10000000-0000-0000-0000-000000000009' AND date = '1996-01-02')
     OR EXISTS (SELECT 1 FROM public.scale_entries WHERE user_id = auth.uid() AND date = '1996-01-01')
     OR NOT EXISTS (SELECT 1 FROM public.scale_entries WHERE user_id = auth.uid() AND date = '1996-01-02' AND value = 4) THEN
    RAISE EXCEPTION 'retry after rolled-back move failed or lost data: %', v_result;
  END IF;
  RAISE NOTICE 'PASS operation failure rolls back source, target, mirrors and request receipt';
END;
$retry_after_failure$;
COMMIT;

RESET ROLE;
DO $verify_other_owner$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM public.entries WHERE user_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb' AND id = '20000000-0000-0000-0000-000000000001' AND date = '1997-01-01')
     OR EXISTS (SELECT 1 FROM public.entries WHERE user_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb' AND date = '1997-01-02') THEN
    RAISE EXCEPTION 'cross-user probe changed the other account row';
  END IF;
END;
$verify_other_owner$;

\echo 'All atomic date-move PostgreSQL integration assertions passed.'
