\set ON_ERROR_STOP on
BEGIN;

DO $beta5_acl$
DECLARE
  v_before public.test_beta5_rpc_baseline%ROWTYPE;
  v_after record;
BEGIN
  SELECT * INTO v_before FROM public.test_beta5_rpc_baseline;
  SELECT p.proowner, p.proacl, p.prosrc, p.prosecdef, p.proconfig
    INTO v_after
    FROM pg_catalog.pg_proc AS p
   WHERE p.oid = 'public.save_daily_entry(jsonb)'::pg_catalog.regprocedure;
  IF v_before.proowner IS DISTINCT FROM v_after.proowner
     OR v_before.proacl IS DISTINCT FROM v_after.proacl
     OR v_before.prosrc IS DISTINCT FROM v_after.prosrc
     OR v_before.prosecdef IS DISTINCT FROM v_after.prosecdef
     OR v_before.proconfig IS DISTINCT FROM v_after.proconfig THEN
    RAISE EXCEPTION 'beta5 RPC owner, ACL, body, or execution settings changed';
  END IF;
  IF NOT pg_catalog.has_function_privilege('authenticated', 'public.save_daily_entry(jsonb)', 'EXECUTE')
     OR pg_catalog.has_function_privilege('anon', 'public.save_daily_entry(jsonb)', 'EXECUTE')
     OR pg_catalog.has_function_privilege('service_role', 'public.save_daily_entry(jsonb)', 'EXECUTE') THEN
    RAISE EXCEPTION 'beta5 authenticated-only execute compatibility changed';
  END IF;
END;
$beta5_acl$;

SET LOCAL ROLE authenticated;
SET LOCAL "request.jwt.claims" = '{"sub":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","role":"authenticated"}';
DO $beta5_calls$
DECLARE
  v_entry public.entries;
BEGIN
  v_entry := public.save_daily_entry(pg_catalog.jsonb_build_object(
    'user_id', auth.uid()::text, 'date', '2001-05-01', 'note', 'beta5 legacy create'
  ));
  IF v_entry.revision <> 1 OR v_entry.note <> 'beta5 legacy create' THEN
    RAISE EXCEPTION 'beta5 create did not remain callable under the revision trigger: %', pg_catalog.to_jsonb(v_entry);
  END IF;

  v_entry := public.save_daily_entry(pg_catalog.jsonb_build_object(
    'user_id', auth.uid()::text, 'date', '2001-05-01', 'note', 'beta5 legacy update'
  ));
  IF v_entry.revision <> 2 OR v_entry.note <> 'beta5 legacy update' THEN
    RAISE EXCEPTION 'beta5 legacy update did not remain callable or advance revision: %', pg_catalog.to_jsonb(v_entry);
  END IF;
END;
$beta5_calls$;

RESET ROLE;
DO $beta5_postcheck$
BEGIN
  IF (SELECT count(*) FROM public.entries
       WHERE user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
         AND date = '2001-05-01'
         AND note = 'beta5 legacy update'
         AND revision = 2) <> 1 THEN
    RAISE EXCEPTION 'beta5 legacy update was not persisted with the server revision';
  END IF;
  IF NOT EXISTS (
    SELECT 1
      FROM pg_catalog.pg_proc AS p
      JOIN public.test_beta5_rpc_baseline AS b ON b.proowner = p.proowner
     WHERE p.oid = 'public.save_daily_entry(jsonb)'::pg_catalog.regprocedure
       AND p.proacl IS NOT DISTINCT FROM b.proacl
       AND p.prosrc IS NOT DISTINCT FROM b.prosrc
       AND p.prosecdef IS NOT DISTINCT FROM b.prosecdef
       AND p.proconfig IS NOT DISTINCT FROM b.proconfig
  ) THEN
    RAISE EXCEPTION 'beta5 RPC changed during its synthetic compatibility calls';
  END IF;
END;
$beta5_postcheck$;

ROLLBACK;
\echo 'Synthetic beta5 compatibility and unchanged ACL/body assertions passed.'
