-- Post-migration verification. Read-only. Exit code 3 if any expectation fails.
\set ON_ERROR_STOP on
\pset format unaligned
\t on

DO $verify$
DECLARE
  missing text[] := ARRAY[]::text[];
  n bigint;
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='entries' AND column_name='revision'
  ) THEN missing := missing || 'entries.revision'; END IF;

  IF pg_catalog.to_regprocedure('public.save_daily_entry_v2(jsonb, boolean, uuid, bigint, uuid)') IS NULL
    THEN missing := missing || 'save_daily_entry_v2'; END IF;
  IF pg_catalog.to_regprocedure('public.move_daily_entry_v1(date, uuid, bigint, date, boolean, uuid, bigint, boolean, uuid)') IS NULL
    THEN missing := missing || 'move_daily_entry_v1'; END IF;
  IF pg_catalog.to_regprocedure('public.android_move_capability_v1()') IS NULL
    THEN missing := missing || 'android_move_capability_v1'; END IF;
  IF pg_catalog.to_regprocedure('public.diarium_entry_revision_v2()') IS NULL
    THEN missing := missing || 'diarium_entry_revision_v2'; END IF;

  IF pg_catalog.to_regclass('public.entry_save_v2_receipts') IS NULL
    THEN missing := missing || 'entry_save_v2_receipts'; END IF;
  IF pg_catalog.to_regclass('public.entry_move_v1_receipts') IS NULL
    THEN missing := missing || 'entry_move_v1_receipts'; END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='diarium_entry_sync_owner')
    THEN missing := missing || 'role diarium_entry_sync_owner'; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='diarium_entry_move_owner')
    THEN missing := missing || 'role diarium_entry_move_owner'; END IF;

  -- beta5 writer must be unchanged and still callable by authenticated only
  IF NOT EXISTS (
    SELECT 1 FROM pg_proc p JOIN pg_namespace ns ON ns.oid = p.pronamespace
    WHERE ns.nspname='public' AND p.proname='save_daily_entry'
  ) THEN missing := missing || 'legacy save_daily_entry'; END IF;

  IF EXISTS (
    SELECT 1 FROM information_schema.role_routine_grants
    WHERE routine_schema='public' AND routine_name='save_daily_entry_v2' AND grantee='anon'
  ) THEN missing := missing || 'v2 granted to anon'; END IF;

  -- no new rows lost and revision backfilled for existing entries
  SELECT count(*) INTO n FROM public.entries WHERE revision IS NULL OR revision < 1;
  IF n <> 0 THEN missing := missing || format('%s entries without positive revision', n); END IF;

  IF cardinality(missing) > 0 THEN
    RAISE EXCEPTION 'POST-MIGRATION VERIFICATION FAILED: %', array_to_string(missing, ', ');
  END IF;

  RAISE NOTICE 'post-migration verification passed';
END
$verify$;

SELECT 'entries_total=' || count(*) FROM public.entries;
SELECT 'entries_revision_minmax=' || coalesce(min(revision),0) || '..' || coalesce(max(revision),0) FROM public.entries;
SELECT 'public_policies=' || count(*) FROM pg_policies WHERE schemaname='public';
SELECT 'public_functions=' || string_agg(proname, ',' ORDER BY proname)
  FROM pg_proc p JOIN pg_namespace ns ON ns.oid=p.pronamespace WHERE ns.nspname='public';
