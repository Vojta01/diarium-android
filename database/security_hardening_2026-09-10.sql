-- ============================================================================
-- Security hardening — Diarium — 2026-09-10
--
-- Fixes holes confirmed by probing the LIVE database (SET ROLE anon, rolled back):
--   anon could SELECT all 22 ai_reports rows and UPDATE them (row-level policies
--   applied to PUBLIC), while the shared catalogs were unreadable to clients
--   (RLS enabled with no policy = deny-all).
--
-- Idempotent: safe to re-run.
-- NOTE: `ai_tools` also has a PUBLIC `USING (true)` policy, but that table belongs
-- to the separate `ai-tool-hub` project (Alza case study), which reads AND writes
-- it from the browser with the anon key. Deliberately NOT touched here.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) ai_reports — policy carried no `TO service_role`, so it applied to PUBLIC.
--    Anyone holding the (public) anon key could read/update/delete every user's
--    AI reports. Scope it to service_role, which is what it was written for.
--    `service_role` also has BYPASSRLS, so this is defence in depth, not the
--    only barrier.
-- ---------------------------------------------------------------------------
drop policy if exists "Service all" on ai_reports;

create policy "Service all" on ai_reports
  for all
  to service_role
  using (true)
  with check (true);

-- ---------------------------------------------------------------------------
-- 2) activity_catalog / habit_catalog — RLS was enabled but zero policies were
--    ever created, i.e. deny-all for anon + authenticated. Consequences today:
--      * src/lib/supabase/db.ts getActivities()/getHabits() always fall back to
--        the hardcoded FALLBACK_ACTIVITIES / 'alkohol' habit, and
--      * because the catalog looks "empty", getActivities() fires
--        POST /api/seed-activities on EVERY load.
--    Catalogs are global reference data seeded once — clients only ever read.
--    Writes remain service_role-only (bypassrls = true); no write policy needed.
-- ---------------------------------------------------------------------------
drop policy if exists "Catalogs readable by clients" on activity_catalog;

create policy "Catalogs readable by clients" on activity_catalog
  for select
  to anon, authenticated
  using (true);

drop policy if exists "Catalogs readable by clients" on habit_catalog;

create policy "Catalogs readable by clients" on habit_catalog
  for select
  to anon, authenticated
  using (true);

-- ---------------------------------------------------------------------------
-- Verification (run manually after applying):
--   begin; set local role anon;
--     select count(*) from ai_reports;        -- expect 0
--     select count(*) from activity_catalog;  -- expect 46
--     select count(*) from habit_catalog;     -- expect 1
--     update ai_reports set type = type;      -- expect ERROR: no policy
--   rollback;
-- ---------------------------------------------------------------------------
