\set ON_ERROR_STOP on
BEGIN;
SET LOCAL ROLE authenticated;
SET LOCAL "request.jwt.claims" = '{"sub":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","role":"authenticated"}';
DO $capability_false$
DECLARE
  v_result jsonb;
BEGIN
  IF NOT pg_catalog.has_function_privilege('authenticated', 'public.android_move_capability_v1()', 'EXECUTE')
     OR pg_catalog.has_function_privilege('anon', 'public.android_move_capability_v1()', 'EXECUTE')
     OR pg_catalog.has_function_privilege('service_role', 'public.android_move_capability_v1()', 'EXECUTE') THEN
    RAISE EXCEPTION 'capability execute ACL is not authenticated-only';
  END IF;
  v_result := public.android_move_capability_v1();
  IF v_result ->> 'contract' <> 'diarium.android.move-capability.v1'
     OR v_result ->> 'move_daily_entry_v1' <> 'false' THEN
    RAISE EXCEPTION 'capability must fail closed before the exact move RPC exists: %', v_result;
  END IF;
END;
$capability_false$;
ROLLBACK;
\echo 'Pre-move capability correctly reported unavailable.'
