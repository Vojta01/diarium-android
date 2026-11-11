\set ON_ERROR_STOP on
BEGIN;
DO $owner_and_capability_grants$
DECLARE
  v_role pg_catalog.pg_roles%ROWTYPE;
  v_cap_owner oid;
  v_cap_security_definer boolean;
  v_move_owner oid;
  v_move_security_definer boolean;
  v_search_path text[];
  v_row_security text[];
  v_capability jsonb;
BEGIN
  SELECT * INTO v_role
    FROM pg_catalog.pg_roles
   WHERE rolname = 'diarium_entry_move_owner';
  IF NOT FOUND OR v_role.rolcanlogin OR v_role.rolsuper OR v_role.rolcreatedb
     OR v_role.rolcreaterole OR v_role.rolinherit OR v_role.rolreplication
     OR v_role.rolbypassrls THEN
    RAISE EXCEPTION 'move function owner is absent or has unsafe role attributes';
  END IF;
  IF pg_catalog.pg_has_role('anon', 'diarium_entry_move_owner', 'MEMBER')
     OR pg_catalog.pg_has_role('authenticated', 'diarium_entry_move_owner', 'MEMBER')
     OR pg_catalog.pg_has_role('service_role', 'diarium_entry_move_owner', 'MEMBER')
     OR pg_catalog.has_schema_privilege('diarium_entry_move_owner', 'public', 'CREATE') THEN
    RAISE EXCEPTION 'API roles can assume move owner or move owner retains public CREATE';
  END IF;
  IF NOT pg_catalog.has_table_privilege('diarium_entry_move_owner', 'public.entries', 'SELECT')
     OR NOT pg_catalog.has_table_privilege('diarium_entry_move_owner', 'public.entries', 'UPDATE')
     OR NOT pg_catalog.has_table_privilege('diarium_entry_move_owner', 'public.entries', 'DELETE')
     OR pg_catalog.has_table_privilege('diarium_entry_move_owner', 'public.entries', 'INSERT')
     OR NOT pg_catalog.has_table_privilege('diarium_entry_move_owner', 'public.scale_entries', 'SELECT')
     OR NOT pg_catalog.has_table_privilege('diarium_entry_move_owner', 'public.scale_entries', 'UPDATE')
     OR NOT pg_catalog.has_table_privilege('diarium_entry_move_owner', 'public.scale_entries', 'DELETE')
     OR pg_catalog.has_table_privilege('diarium_entry_move_owner', 'public.scale_entries', 'INSERT')
     OR pg_catalog.has_table_privilege('authenticated', 'public.entry_move_v1_receipts', 'SELECT')
     OR pg_catalog.has_table_privilege('anon', 'public.entry_move_v1_receipts', 'SELECT')
     OR pg_catalog.has_table_privilege('service_role', 'public.entry_move_v1_receipts', 'SELECT') THEN
    RAISE EXCEPTION 'move owner table grants are broader/narrower than the RLS-scoped contract';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_class c
     WHERE c.oid = 'public.entry_move_v1_receipts'::pg_catalog.regclass
       AND c.relrowsecurity AND c.relforcerowsecurity
       AND c.relowner = 'diarium_entry_move_owner'::pg_catalog.regrole
  ) THEN
    RAISE EXCEPTION 'move receipt table must be owned by the NOLOGIN role with RLS FORCE';
  END IF;
  SELECT p.proowner, p.prosecdef,
         (SELECT pg_catalog.array_agg(setting ORDER BY setting)
            FROM pg_catalog.unnest(p.proconfig) AS setting
           WHERE setting LIKE 'search_path=%'),
         (SELECT pg_catalog.array_agg(setting ORDER BY setting)
            FROM pg_catalog.unnest(p.proconfig) AS setting
           WHERE setting LIKE 'row_security=%')
    INTO v_move_owner, v_move_security_definer, v_search_path, v_row_security
    FROM pg_catalog.pg_proc p
   WHERE p.oid = 'public.move_daily_entry_v1(date,uuid,bigint,date,boolean,uuid,bigint,boolean,uuid)'::pg_catalog.regprocedure;
  IF v_move_owner IS DISTINCT FROM 'diarium_entry_move_owner'::pg_catalog.regrole
     OR v_move_security_definer IS DISTINCT FROM true
     OR v_search_path IS DISTINCT FROM ARRAY['search_path=""']::text[]
     OR v_row_security IS DISTINCT FROM ARRAY['row_security=on']::text[]
     OR NOT pg_catalog.has_function_privilege('authenticated', 'public.move_daily_entry_v1(date,uuid,bigint,date,boolean,uuid,bigint,boolean,uuid)', 'EXECUTE')
     OR pg_catalog.has_function_privilege('anon', 'public.move_daily_entry_v1(date,uuid,bigint,date,boolean,uuid,bigint,boolean,uuid)', 'EXECUTE')
     OR pg_catalog.has_function_privilege('service_role', 'public.move_daily_entry_v1(date,uuid,bigint,date,boolean,uuid,bigint,boolean,uuid)', 'EXECUTE') THEN
    RAISE EXCEPTION 'move RPC owner, security settings, or execute ACL mismatch';
  END IF;
  SELECT p.proowner, p.prosecdef,
         (SELECT pg_catalog.array_agg(setting ORDER BY setting)
            FROM pg_catalog.unnest(p.proconfig) AS setting
           WHERE setting LIKE 'search_path=%')
    INTO v_cap_owner, v_cap_security_definer, v_search_path
    FROM pg_catalog.pg_proc p
   WHERE p.oid = 'public.android_move_capability_v1()'::pg_catalog.regprocedure;
  IF v_cap_owner IS DISTINCT FROM current_user::pg_catalog.regrole
     OR v_cap_security_definer IS DISTINCT FROM false
     OR v_search_path IS DISTINCT FROM ARRAY['search_path=""']::text[]
     OR NOT pg_catalog.has_function_privilege('authenticated', 'public.android_move_capability_v1()', 'EXECUTE')
     OR pg_catalog.has_function_privilege('anon', 'public.android_move_capability_v1()', 'EXECUTE')
     OR pg_catalog.has_function_privilege('service_role', 'public.android_move_capability_v1()', 'EXECUTE') THEN
    RAISE EXCEPTION 'capability must remain invoker-only, owned by the migration role, and authenticated-only';
  END IF;
  v_capability := public.android_move_capability_v1();
  IF v_capability ->> 'contract' <> 'diarium.android.move-capability.v1'
     OR v_capability ->> 'move_daily_entry_v1' <> 'true' THEN
    RAISE EXCEPTION 'capability did not become available after the exact move RPC was installed: %', v_capability;
  END IF;
END;
$owner_and_capability_grants$;

SET LOCAL ROLE authenticated;
SET LOCAL "request.jwt.claims" = '{"sub":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","role":"authenticated"}';
DO $authenticated_runtime$
DECLARE
  v_result jsonb;
BEGIN
  v_result := public.android_move_capability_v1();
  IF v_result ->> 'move_daily_entry_v1' <> 'true' THEN
    RAISE EXCEPTION 'authenticated caller must see the enabled move capability';
  END IF;
END;
$authenticated_runtime$;
RESET ROLE;
ROLLBACK;
\echo 'Move/capability owners, RLS grants, and authenticated runtime assertions passed.'
