-- Disposable PostgreSQL fixture only. run_postgres_tests.sh refuses non-loopback
-- URLs and database names outside diarium_test_*. This file refuses non-empty DBs.
\set ON_ERROR_STOP on
BEGIN;

DO $guard$
BEGIN
  IF current_database() !~ '^diarium_test_[A-Za-z0-9_]+$' THEN
    RAISE EXCEPTION 'refusing non-disposable database name';
  END IF;
  IF pg_catalog.to_regclass('public.entries') IS NOT NULL
     OR pg_catalog.to_regclass('public.scale_entries') IS NOT NULL
     OR pg_catalog.to_regclass('public.entry_save_v2_receipts') IS NOT NULL
     OR EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = 'auth') THEN
    RAISE EXCEPTION 'refusing a database that already contains Diarium/Auth objects';
  END IF;
END;
$guard$;

DO $roles$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'anon') THEN
    EXECUTE 'CREATE ROLE anon NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'authenticated') THEN
    EXECUTE 'CREATE ROLE authenticated NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'service_role') THEN
    EXECUTE 'CREATE ROLE service_role NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION BYPASSRLS';
  END IF;
END;
$roles$;

CREATE SCHEMA auth;
CREATE OR REPLACE FUNCTION auth.uid()
RETURNS uuid
LANGUAGE sql
STABLE
SET search_path = ''
AS $uid$
  SELECT NULLIF(pg_catalog.current_setting('request.jwt.claims', true)::jsonb ->> 'sub', '')::uuid
$uid$;
CREATE OR REPLACE FUNCTION auth.jwt()
RETURNS jsonb
LANGUAGE sql
STABLE
SET search_path = ''
AS $jwt$
  SELECT COALESCE(
    NULLIF(pg_catalog.current_setting('request.jwt.claims', true), '')::jsonb,
    '{}'::jsonb
  )
$jwt$;
GRANT USAGE ON SCHEMA auth TO authenticated;
GRANT EXECUTE ON FUNCTION auth.uid() TO authenticated;
GRANT EXECUTE ON FUNCTION auth.jwt() TO authenticated;

CREATE TABLE public.entries (
  id uuid PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
  user_id uuid NOT NULL,
  date date NOT NULL,
  mood smallint CHECK (mood BETWEEN 1 AND 5),
  mood_emoji text,
  sleep_quality smallint CHECK (sleep_quality BETWEEN 1 AND 5),
  stress smallint CHECK (stress BETWEEN 1 AND 5),
  activities jsonb DEFAULT '[]'::jsonb,
  habits jsonb DEFAULT '{}'::jsonb,
  gratitude jsonb DEFAULT '[]'::jsonb,
  note text DEFAULT '',
  weather jsonb DEFAULT '[]'::jsonb,
  phone_screen_time integer,
  phone_unlocks integer,
  phone_top_apps jsonb DEFAULT '[]'::jsonb,
  photo_path text,
  created_at timestamptz DEFAULT pg_catalog.now(),
  updated_at timestamptz DEFAULT pg_catalog.now(),
  ai_reflection text,
  scale_values jsonb DEFAULT '{}'::jsonb,
  UNIQUE (user_id, date)
);
CREATE TABLE public.scales (
  id uuid PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
  user_id uuid NOT NULL,
  name text NOT NULL
);
CREATE TABLE public.scale_entries (
  id uuid PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
  user_id uuid NOT NULL,
  scale_id uuid NOT NULL REFERENCES public.scales(id) ON DELETE CASCADE,
  date date NOT NULL,
  value integer NOT NULL,
  created_at timestamptz DEFAULT pg_catalog.now(),
  UNIQUE (user_id, scale_id, date)
);

CREATE FUNCTION public.set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $updated$
BEGIN
  NEW.updated_at := pg_catalog.now();
  RETURN NEW;
END;
$updated$;
CREATE TRIGGER trg_entries_updated_at
  BEFORE UPDATE ON public.entries
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

ALTER TABLE public.entries ENABLE ROW LEVEL SECURITY;
CREATE POLICY entries_authenticated_own ON public.entries
  FOR ALL TO authenticated
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
ALTER TABLE public.scales ENABLE ROW LEVEL SECURITY;
CREATE POLICY scales_authenticated_own ON public.scales
  FOR ALL TO authenticated
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
ALTER TABLE public.scale_entries ENABLE ROW LEVEL SECURITY;
CREATE POLICY scale_entries_authenticated_own ON public.scale_entries
  FOR ALL TO authenticated
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
GRANT USAGE ON SCHEMA public TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.entries, public.scales, public.scale_entries TO authenticated;

-- Synthetic row present before the migration: the fast default must expose its
-- revision as 1 without an UPDATE/backfill pass.
INSERT INTO public.entries (user_id, date, note) VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '1990-01-01', 'pre-migration synthetic row');

-- Minimal synthetic beta5 contract retained for post-migration compatibility
-- tests. It is an invoker function with an explicit authenticated-only ACL.
CREATE FUNCTION public.save_daily_entry(payload jsonb)
RETURNS public.entries
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = ''
AS $beta5$
DECLARE
  v_entry public.entries;
BEGIN
  INSERT INTO public.entries (user_id, date, note)
  VALUES ((payload ->> 'user_id')::uuid, (payload ->> 'date')::date,
          COALESCE(payload ->> 'note', ''))
  ON CONFLICT (user_id, date)
  DO UPDATE SET note = EXCLUDED.note, updated_at = pg_catalog.now()
  RETURNING * INTO v_entry;
  RETURN v_entry;
END;
$beta5$;
REVOKE ALL ON FUNCTION public.save_daily_entry(jsonb) FROM PUBLIC, anon, service_role;
GRANT EXECUTE ON FUNCTION public.save_daily_entry(jsonb) TO authenticated;
CREATE TABLE public.test_beta5_rpc_baseline AS
SELECT p.proowner, p.proacl, p.prosrc, p.prosecdef, p.proconfig
  FROM pg_catalog.pg_proc AS p
 WHERE p.oid = 'public.save_daily_entry(jsonb)'::pg_catalog.regprocedure;

COMMIT;
