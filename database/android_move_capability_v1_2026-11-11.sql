-- Read-only server capability for the Android atomic date move.
-- Apply after the migration that defines public.move_daily_entry_v1.
-- This migration does not enable the feature until that exact RPC signature exists
-- and is executable by authenticated users.

CREATE OR REPLACE FUNCTION public.android_move_capability_v1()
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = ''
AS $capability$
  WITH move_function AS (
    SELECT pg_catalog.to_regprocedure(
      'public.move_daily_entry_v1(pg_catalog.date, pg_catalog.uuid, pg_catalog.int8, pg_catalog.date, pg_catalog.bool, pg_catalog.uuid, pg_catalog.int8, pg_catalog.bool, pg_catalog.uuid)'
    ) AS function_oid
  )
  SELECT pg_catalog.jsonb_build_object(
    'contract', 'diarium.android.move-capability.v1',
    'move_daily_entry_v1',
    COALESCE(
      move_function.function_oid IS NOT NULL
      AND EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc AS p
        WHERE p.oid = move_function.function_oid::pg_catalog.oid
          AND p.prokind = 'f'
          AND p.prorettype = 'pg_catalog.jsonb'::pg_catalog.regtype
          AND p.proargnames = ARRAY[
            'p_source_date',
            'p_source_id',
            'p_source_revision',
            'p_target_date',
            'p_target_expected_exists',
            'p_target_expected_id',
            'p_target_expected_revision',
            'p_confirm_overwrite',
            'p_request_id'
          ]::text[]
          AND p.prosecdef
          AND pg_catalog.has_function_privilege(
            'authenticated',
            p.oid,
            'EXECUTE'
          )
      ),
      false
    )
  )
  FROM move_function;
$capability$;

REVOKE ALL ON FUNCTION public.android_move_capability_v1() FROM PUBLIC, anon, service_role;
GRANT EXECUTE ON FUNCTION public.android_move_capability_v1() TO authenticated;
COMMENT ON FUNCTION public.android_move_capability_v1() IS
  'Read-only deployment capability. True only when the exact atomic move RPC signature is installed and executable by authenticated users.';

NOTIFY pgrst, 'reload schema';
