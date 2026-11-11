-- PRODUCTION CANDIDATE — review and preflight required; not applied to production.
-- This file is a migration fragment, not a safe standalone entry point. Apply it
-- only through database/apply_conditional_save_move_candidate.sh with the other
-- two candidate files in its explicit transaction.
-- Read-only server capability for the Android atomic date move. Apply after the
-- exact public.move_daily_entry_v1 signature, in the same approved transaction.
-- Non-test databases require the operator-created temporary approval table
-- documented in database/CONDITIONAL_SAVE_MOVE_CANDIDATE_2026-11-11.md, with
-- ON COMMIT DROP. The diarium_test_* guard is retained for disposable CI.

DO $candidate_operator_guard$
DECLARE
  v_expected_database text;
  v_backup_reference text;
  v_change_window text;
  v_confirmation text;
BEGIN
  IF current_database() !~ '^diarium_test_[A-Za-z0-9_]+$' THEN
    IF pg_catalog.to_regclass('pg_temp.diarium_production_migration_approval') IS NULL THEN
      RAISE EXCEPTION 'production candidate refuses non-test database without transaction-local operator approval';
    END IF;
    SELECT a.expected_database, a.verified_backup_reference,
           a.approved_change_window, a.confirmation
      INTO v_expected_database, v_backup_reference, v_change_window, v_confirmation
      FROM pg_temp.diarium_production_migration_approval AS a
     LIMIT 1;
    IF NOT FOUND
       OR (SELECT pg_catalog.count(*) FROM pg_temp.diarium_production_migration_approval) <> 1
       OR v_expected_database IS DISTINCT FROM current_database()
       OR NULLIF(pg_catalog.btrim(v_backup_reference), '') IS NULL
       OR v_backup_reference LIKE '<%>'
       OR NULLIF(pg_catalog.btrim(v_change_window), '') IS NULL
       OR v_change_window LIKE '<%>'
       OR v_confirmation IS DISTINCT FROM 'I_HAVE_VERIFIED_BACKUP_AND_APPROVE_THIS_DATABASE' THEN
      RAISE EXCEPTION 'production candidate approval record is incomplete or does not match this database';
    END IF;
  END IF;
  PERFORM pg_catalog.set_config('lock_timeout', '5s', true);
END;
$candidate_operator_guard$;

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
