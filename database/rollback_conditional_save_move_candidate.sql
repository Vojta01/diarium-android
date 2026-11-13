-- Emergency rollback for the conditional-save / date-move candidate.
-- Run only through rollback_conditional_save_move_candidate.sh.
-- Reverses, in dependency order, everything the atomic migration created.
\set ON_ERROR_STOP on

BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

REVOKE ALL ON FUNCTION public.android_move_capability_v1() FROM PUBLIC, anon, authenticated, service_role;
DROP FUNCTION IF EXISTS public.android_move_capability_v1();

REVOKE ALL ON FUNCTION public.move_daily_entry_v1(date, uuid, bigint, date, boolean, uuid, bigint, boolean, uuid)
  FROM PUBLIC, anon, authenticated, service_role;
DROP FUNCTION IF EXISTS public.move_daily_entry_v1(date, uuid, bigint, date, boolean, uuid, bigint, boolean, uuid);

REVOKE ALL ON FUNCTION public.save_daily_entry_v2(jsonb, boolean, uuid, bigint, uuid)
  FROM PUBLIC, anon, authenticated, service_role;
DROP FUNCTION IF EXISTS public.save_daily_entry_v2(jsonb, boolean, uuid, bigint, uuid);

DROP POLICY IF EXISTS entries_v1_move_owner ON public.entries;
DROP POLICY IF EXISTS scale_entries_v1_move_owner ON public.scale_entries;
DROP POLICY IF EXISTS entries_v2_sync_owner ON public.entries;
DROP POLICY IF EXISTS scales_v2_sync_owner ON public.scales;
DROP POLICY IF EXISTS scale_entries_v2_sync_owner ON public.scale_entries;

DROP TABLE IF EXISTS public.entry_move_v1_receipts;
DROP TABLE IF EXISTS public.entry_save_v2_receipts;

DROP TRIGGER IF EXISTS entries_revision_v2 ON public.entries;
DROP FUNCTION IF EXISTS public.diarium_entry_revision_v2();

ALTER TABLE public.entries DROP COLUMN IF EXISTS revision;

DROP ROLE IF EXISTS diarium_entry_move_owner;
DROP ROLE IF EXISTS diarium_entry_sync_owner;

COMMIT;
\echo 'Rollback of conditional-save/date-move candidate committed.'
