-- PRODUCTION CANDIDATE — review and preflight required; not applied to production.
-- This file is a migration fragment, not a safe standalone entry point. Apply it
-- only through database/apply_conditional_save_move_candidate.sh, which wraps
-- all three candidate files in one explicit transaction. A direct psql -f run
-- can commit earlier DDL before a later guard fails.
-- Additive authenticated conditional-save foundation. This candidate adds
-- entries.revision, a dedicated NOLOGIN function owner, durable request
-- receipts, and public.save_daily_entry_v2(...). It does not alter, disable,
-- replace, revoke, or grant anything on the beta5 public.save_daily_entry(jsonb)
-- RPC. Legacy writes remain possible, so this alone does NOT provide full
-- cross-device protection until all legacy writers are retired.
--
-- Non-test databases require the operator-created temporary approval table
-- described in database/CONDITIONAL_SAVE_MOVE_CANDIDATE_2026-11-11.md. That
-- table must be created with ON COMMIT DROP inside the explicit protective
-- transaction which applies all three migrations. The test database-name guard
-- is retained for disposable CI fixtures.
--
-- Prerequisites: current entries/scales/scale_entries schemas; public.diarium_auth_uid(),
-- public.diarium_auth_jwt(); Supabase API roles; PostgreSQL 11+; and pgcrypto installed/exposed
-- as extensions.digest(bytea,text). Set lock_timeout to a low value; this file
-- also sets a transaction-local 5-second lock_timeout. Never use production
-- data in CI.

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

-- Supabase's postgres role cannot grant USAGE on the auth schema, so the
-- candidate's owner roles cannot call auth.uid()/auth.jwt() directly. These
-- helpers read the same request GUCs without requiring access to that schema.
CREATE OR REPLACE FUNCTION public.diarium_auth_jwt()
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = ''
AS $auth_jwt$
  SELECT coalesce(
    nullif(pg_catalog.current_setting('request.jwt.claim', true), ''),
    nullif(pg_catalog.current_setting('request.jwt.claims', true), ''),
    '{}'
  )::jsonb
$auth_jwt$;

CREATE OR REPLACE FUNCTION public.diarium_auth_uid()
RETURNS uuid
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = ''
AS $auth_uid$
  SELECT coalesce(
    nullif(pg_catalog.current_setting('request.jwt.claim.sub', true), ''),
    public.diarium_auth_jwt() ->> 'sub'
  )::uuid
$auth_uid$;

REVOKE ALL ON FUNCTION public.diarium_auth_jwt() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.diarium_auth_uid() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.diarium_auth_jwt() TO anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.diarium_auth_uid() TO anon, authenticated, service_role;

-- Fail early when this is not the expected current Diarium schema.
DO $preflight$
DECLARE
  v_missing text;
BEGIN
  IF pg_catalog.to_regclass('public.entries') IS NULL
     OR pg_catalog.to_regclass('public.scales') IS NULL
     OR pg_catalog.to_regclass('public.scale_entries') IS NULL THEN
    RAISE EXCEPTION 'save_daily_entry_v2 requires public.entries, public.scales, and public.scale_entries';
  END IF;

  SELECT pg_catalog.string_agg(required.column_name, ', ' ORDER BY required.column_name)
    INTO v_missing
    FROM pg_catalog.unnest(ARRAY[
      'id', 'user_id', 'date', 'mood', 'mood_emoji', 'sleep_quality', 'stress',
      'activities', 'habits', 'gratitude', 'note', 'weather', 'photo_path',
      'phone_screen_time', 'phone_unlocks', 'phone_top_apps', 'ai_reflection',
      'scale_values', 'created_at', 'updated_at'
    ]) AS required(column_name)
   WHERE NOT EXISTS (
     SELECT 1
       FROM pg_catalog.pg_attribute a
      WHERE a.attrelid = 'public.entries'::pg_catalog.regclass
        AND a.attname = required.column_name
        AND a.attnum > 0
        AND NOT a.attisdropped
   );
  IF v_missing IS NOT NULL THEN
    RAISE EXCEPTION 'public.entries is missing required columns: %', v_missing;
  END IF;

  IF NOT EXISTS (
    SELECT 1
      FROM pg_catalog.pg_constraint c
     WHERE c.conrelid = 'public.entries'::pg_catalog.regclass
       AND c.contype = 'u'
       AND c.conkey = ARRAY[
         (SELECT a.attnum FROM pg_catalog.pg_attribute a WHERE a.attrelid = 'public.entries'::pg_catalog.regclass AND a.attname = 'user_id'),
         (SELECT a.attnum FROM pg_catalog.pg_attribute a WHERE a.attrelid = 'public.entries'::pg_catalog.regclass AND a.attname = 'date')
       ]::smallint[]
  ) THEN
    RAISE EXCEPTION 'public.entries requires UNIQUE (user_id, date)';
  END IF;
  IF NOT EXISTS (
    SELECT 1
      FROM pg_catalog.pg_constraint c
     WHERE c.conrelid = 'public.scale_entries'::pg_catalog.regclass
       AND c.contype = 'u'
       AND c.conkey = ARRAY[
         (SELECT a.attnum FROM pg_catalog.pg_attribute a WHERE a.attrelid = 'public.scale_entries'::pg_catalog.regclass AND a.attname = 'user_id'),
         (SELECT a.attnum FROM pg_catalog.pg_attribute a WHERE a.attrelid = 'public.scale_entries'::pg_catalog.regclass AND a.attname = 'scale_id'),
         (SELECT a.attnum FROM pg_catalog.pg_attribute a WHERE a.attrelid = 'public.scale_entries'::pg_catalog.regclass AND a.attname = 'date')
       ]::smallint[]
  ) THEN
    RAISE EXCEPTION 'public.scale_entries requires UNIQUE (user_id, scale_id, date)';
  END IF;

  IF pg_catalog.to_regprocedure('public.diarium_auth_uid()') IS NULL
     OR pg_catalog.to_regprocedure('public.diarium_auth_jwt()') IS NULL THEN
    RAISE EXCEPTION 'save_daily_entry_v2 requires public.diarium_auth_uid() and public.diarium_auth_jwt()';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'anon')
     OR NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'authenticated')
     OR NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'service_role') THEN
    RAISE EXCEPTION 'save_daily_entry_v2 requires anon, authenticated, and service_role roles';
  END IF;
END;
$preflight$;

-- pgcrypto is used only to fingerprint canonical jsonb request semantics. A
-- non-cryptographic hash is not an acceptable substitute for request-ID reuse.
CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;
DO $crypto_preflight$
BEGIN
  IF pg_catalog.to_regprocedure('extensions.digest(bytea,text)') IS NULL THEN
    RAISE EXCEPTION 'pgcrypto digest(bytea,text) must be available as extensions.digest';
  END IF;
END;
$crypto_preflight$;

-- The RPC owner is deliberately distinct from all API roles and is never
-- granted to them. It does not own entries/scales/scale_entries or bypass RLS;
-- it owns only its private request-receipt table.
DO $owner_role$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'diarium_entry_sync_owner'
  ) THEN
    EXECUTE 'CREATE ROLE diarium_entry_sync_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS';
  END IF;
END;
$owner_role$;

DO $owner_role_check$
DECLARE
  v_role pg_catalog.pg_roles%ROWTYPE;
BEGIN
  SELECT * INTO v_role
    FROM pg_catalog.pg_roles
   WHERE rolname = 'diarium_entry_sync_owner';
  IF v_role.rolcanlogin OR v_role.rolsuper OR v_role.rolcreatedb
     OR v_role.rolcreaterole OR v_role.rolinherit OR v_role.rolreplication
     OR v_role.rolbypassrls THEN
    RAISE EXCEPTION 'diarium_entry_sync_owner exists with unsafe role attributes';
  END IF;
  IF pg_catalog.pg_has_role('anon'::name, 'diarium_entry_sync_owner'::name, 'MEMBER')
     OR pg_catalog.pg_has_role('authenticated'::name, 'diarium_entry_sync_owner'::name, 'MEMBER')
     OR pg_catalog.pg_has_role('service_role'::name, 'diarium_entry_sync_owner'::name, 'MEMBER') THEN
    RAISE EXCEPTION 'an API role can assume diarium_entry_sync_owner';
  END IF;
  IF NOT EXISTS (
    SELECT 1
      FROM pg_catalog.pg_roles r
     WHERE r.rolname = current_user
       AND (r.rolsuper OR pg_catalog.pg_has_role(current_user, 'diarium_entry_sync_owner', 'MEMBER'))
  ) THEN
    RAISE EXCEPTION 'migration role must be superuser or a member of diarium_entry_sync_owner to transfer function ownership';
  END IF;
END;
$owner_role_check$;

-- A constant, non-volatile default is metadata-only on PostgreSQL 11+; do not
-- rewrite every diary row to backfill this token. The brief ACCESS EXCLUSIVE
-- lock is bounded by the operator guard's transaction-local lock_timeout.
DO $revision_version$
BEGIN
  IF pg_catalog.current_setting('server_version_num')::integer < 110000 THEN
    RAISE EXCEPTION 'conditional-save candidate requires PostgreSQL 11+ fast constant defaults';
  END IF;
END;
$revision_version$;

-- If a revision column predates this candidate, accept it only when the
-- candidate's positive-value constraint is already present. This makes a
-- partial/unknown rollout stop for manual review instead of scanning/updating
-- a potentially large entries table.
DO $revision_existing_shape$
BEGIN
  IF EXISTS (
    SELECT 1 FROM pg_catalog.pg_attribute a
     WHERE a.attrelid = 'public.entries'::pg_catalog.regclass
       AND a.attname = 'revision'
       AND a.attnum > 0
       AND NOT a.attisdropped
  ) AND NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_constraint c
     WHERE c.conrelid = 'public.entries'::pg_catalog.regclass
       AND c.conname = 'entries_revision_v2_positive'
       AND c.contype = 'c'
       AND pg_catalog.lower(pg_catalog.pg_get_constraintdef(c.oid)) LIKE '%revision >= 1%'
  ) THEN
    RAISE EXCEPTION 'an unverified entries.revision column exists; inspect and remediate it separately before applying this candidate';
  END IF;
END;
$revision_existing_shape$;

ALTER TABLE public.entries
  ADD COLUMN IF NOT EXISTS revision bigint NOT NULL DEFAULT 1;
ALTER TABLE public.entries
  ALTER COLUMN revision SET DEFAULT 1;

DO $revision_type$
DECLARE
  v_type oid;
  v_not_null boolean;
BEGIN
  SELECT a.atttypid, a.attnotnull
    INTO v_type, v_not_null
    FROM pg_catalog.pg_attribute a
   WHERE a.attrelid = 'public.entries'::pg_catalog.regclass
     AND a.attname = 'revision'
     AND a.attnum > 0
     AND NOT a.attisdropped;
  IF v_type IS DISTINCT FROM 'pg_catalog.int8'::pg_catalog.regtype
     OR v_not_null IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'public.entries.revision must be bigint NOT NULL';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_attrdef d
     WHERE d.adrelid = 'public.entries'::pg_catalog.regclass
       AND d.adnum = (
         SELECT a.attnum FROM pg_catalog.pg_attribute a
          WHERE a.attrelid = 'public.entries'::pg_catalog.regclass
            AND a.attname = 'revision' AND a.attnum > 0 AND NOT a.attisdropped
       )
  ) THEN
    RAISE EXCEPTION 'public.entries.revision must retain its constant default';
  END IF;
END;
$revision_type$;

DO $revision_constraint$
DECLARE
  v_definition text;
BEGIN
  SELECT pg_catalog.pg_get_constraintdef(c.oid)
    INTO v_definition
    FROM pg_catalog.pg_constraint c
   WHERE c.conrelid = 'public.entries'::pg_catalog.regclass
     AND c.conname = 'entries_revision_v2_positive';
  IF v_definition IS NULL THEN
    EXECUTE 'ALTER TABLE public.entries ADD CONSTRAINT entries_revision_v2_positive CHECK (revision >= 1) NOT VALID';
  ELSIF pg_catalog.lower(v_definition) NOT LIKE '%revision >= 1%' THEN
    RAISE EXCEPTION 'entries_revision_v2_positive exists with a different check definition';
  END IF;
  -- Intentionally do not VALIDATE here: the newly-added column's missing
  -- value is 1, while validation would scan the full entries table. Existing
  -- pre-candidate revisions are accepted only when this constraint existed.
END;
$revision_constraint$;

CREATE OR REPLACE FUNCTION public.diarium_entry_revision_v2()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = ''
AS $revision$
BEGIN
  IF TG_OP = 'INSERT' THEN
    NEW.revision := 1;
  ELSE
    NEW.revision := OLD.revision + 1;
  END IF;
  RETURN NEW;
END;
$revision$;

DO $revision_trigger$
DECLARE
  v_function oid;
BEGIN
  SELECT p.oid INTO v_function
    FROM pg_catalog.pg_proc p
    JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
   WHERE n.nspname = 'public'
     AND p.proname = 'diarium_entry_revision_v2'
     AND p.prorettype = 'pg_catalog.trigger'::pg_catalog.regtype;

  IF EXISTS (
    SELECT 1 FROM pg_catalog.pg_trigger t
     WHERE t.tgrelid = 'public.entries'::pg_catalog.regclass
       AND t.tgname = 'trg_entries_revision_v2'
       AND NOT t.tgisinternal
       AND (t.tgfoid <> v_function OR t.tgtype <> 23 OR t.tgenabled = 'D')
  ) THEN
    RAISE EXCEPTION 'trg_entries_revision_v2 exists with a different/disabled trigger definition';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_trigger t
     WHERE t.tgrelid = 'public.entries'::pg_catalog.regclass
       AND t.tgname = 'trg_entries_revision_v2'
       AND NOT t.tgisinternal
  ) THEN
    EXECUTE 'CREATE TRIGGER trg_entries_revision_v2 BEFORE INSERT OR UPDATE ON public.entries FOR EACH ROW EXECUTE FUNCTION public.diarium_entry_revision_v2()';
  END IF;
END;
$revision_trigger$;

COMMENT ON COLUMN public.entries.revision IS
  'Server-owned monotonic row revision for additive conditional-save v2; legacy writes still remain possible until a separately approved cutoff.';
COMMENT ON FUNCTION public.diarium_entry_revision_v2() IS
  'For INSERT forces revision=1; for every UPDATE forces OLD.revision+1.';

-- Permanent idempotency tombstones: no entry FK, cascade, or expiration. The
-- only stored request detail is a SHA-256 fingerprint and a small terminal
-- outcome/token envelope; journal/payload text is never copied here.
CREATE TABLE IF NOT EXISTS public.entry_save_v2_receipts (
  user_id uuid NOT NULL,
  request_id uuid NOT NULL,
  fingerprint bytea NOT NULL CHECK (pg_catalog.octet_length(fingerprint) = 32),
  outcome jsonb,
  created_at timestamptz NOT NULL DEFAULT pg_catalog.now(),
  PRIMARY KEY (user_id, request_id),
  CHECK (outcome IS NULL OR pg_catalog.jsonb_typeof(outcome) = 'object')
);
DO $receipt_shape$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM (VALUES
        ('user_id'::name, 'pg_catalog.uuid'::pg_catalog.regtype),
        ('request_id'::name, 'pg_catalog.uuid'::pg_catalog.regtype),
        ('fingerprint'::name, 'pg_catalog.bytea'::pg_catalog.regtype),
        ('outcome'::name, 'pg_catalog.jsonb'::pg_catalog.regtype),
        ('created_at'::name, 'pg_catalog.timestamptz'::pg_catalog.regtype)
      ) AS expected(column_name, type_oid)
      LEFT JOIN pg_catalog.pg_attribute a
        ON a.attrelid = 'public.entry_save_v2_receipts'::pg_catalog.regclass
       AND a.attname = expected.column_name
       AND a.attnum > 0
       AND NOT a.attisdropped
     WHERE a.attname IS NULL OR a.atttypid <> expected.type_oid::pg_catalog.oid
  ) THEN
    RAISE EXCEPTION 'entry_save_v2_receipts has an incompatible column definition';
  END IF;
  IF NOT EXISTS (
    SELECT 1
      FROM pg_catalog.pg_constraint c
     WHERE c.conrelid = 'public.entry_save_v2_receipts'::pg_catalog.regclass
       AND c.contype = 'p'
       AND c.conkey = ARRAY[
         (SELECT a.attnum FROM pg_catalog.pg_attribute a WHERE a.attrelid = 'public.entry_save_v2_receipts'::pg_catalog.regclass AND a.attname = 'user_id'),
         (SELECT a.attnum FROM pg_catalog.pg_attribute a WHERE a.attrelid = 'public.entry_save_v2_receipts'::pg_catalog.regclass AND a.attname = 'request_id')
       ]::smallint[]
  ) THEN
    RAISE EXCEPTION 'entry_save_v2_receipts requires PRIMARY KEY (user_id, request_id)';
  END IF;
  IF EXISTS (
    SELECT 1 FROM pg_catalog.pg_constraint c
     WHERE c.conrelid = 'public.entry_save_v2_receipts'::pg_catalog.regclass
       AND c.contype = 'f'
  ) THEN
    RAISE EXCEPTION 'entry_save_v2_receipts must not cascade with entries or users';
  END IF;
END;
$receipt_shape$;
GRANT CREATE ON SCHEMA public TO diarium_entry_sync_owner;
ALTER TABLE public.entry_save_v2_receipts OWNER TO diarium_entry_sync_owner;
REVOKE CREATE ON SCHEMA public FROM diarium_entry_sync_owner;
ALTER TABLE public.entry_save_v2_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.entry_save_v2_receipts FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS entry_save_v2_receipt_owner ON public.entry_save_v2_receipts;
CREATE POLICY entry_save_v2_receipt_owner
  ON public.entry_save_v2_receipts
  FOR ALL TO diarium_entry_sync_owner
  USING (user_id = public.diarium_auth_uid())
  WITH CHECK (user_id = public.diarium_auth_uid());
REVOKE ALL ON TABLE public.entry_save_v2_receipts FROM PUBLIC, anon, authenticated, service_role;
GRANT SELECT, INSERT, UPDATE ON TABLE public.entry_save_v2_receipts TO diarium_entry_sync_owner;

-- The RPC owner needs schema CREATE only long enough to take ownership of the
-- function. It is revoked immediately after the ownership transfer below.
GRANT CREATE ON SCHEMA public TO diarium_entry_sync_owner;

-- The definer is not a table owner and is NOBYPASSRLS. These narrow policies
-- let it act only for public.diarium_auth_uid(); the function also fixes v_uid to that actor
-- and filters every entry/mirror operation by owner. Do not grant this role to
-- an API role or use it as a login.
GRANT USAGE ON SCHEMA public, auth, extensions TO diarium_entry_sync_owner;
GRANT EXECUTE ON FUNCTION public.diarium_auth_uid() TO diarium_entry_sync_owner;
GRANT EXECUTE ON FUNCTION public.diarium_auth_jwt() TO diarium_entry_sync_owner;
GRANT EXECUTE ON FUNCTION extensions.digest(bytea, text) TO diarium_entry_sync_owner;
GRANT SELECT, INSERT, UPDATE ON TABLE public.entries TO diarium_entry_sync_owner;
GRANT SELECT ON TABLE public.scales TO diarium_entry_sync_owner;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.scale_entries TO diarium_entry_sync_owner;

DROP POLICY IF EXISTS entries_v2_sync_owner ON public.entries;
CREATE POLICY entries_v2_sync_owner
  ON public.entries
  FOR ALL TO diarium_entry_sync_owner
  USING (user_id = public.diarium_auth_uid())
  WITH CHECK (user_id = public.diarium_auth_uid());
DROP POLICY IF EXISTS scales_v2_sync_owner ON public.scales;
CREATE POLICY scales_v2_sync_owner
  ON public.scales
  FOR SELECT TO diarium_entry_sync_owner
  USING (user_id = public.diarium_auth_uid());
DROP POLICY IF EXISTS scale_entries_v2_sync_owner ON public.scale_entries;
CREATE POLICY scale_entries_v2_sync_owner
  ON public.scale_entries
  FOR ALL TO diarium_entry_sync_owner
  USING (user_id = public.diarium_auth_uid())
  WITH CHECK (user_id = public.diarium_auth_uid());

CREATE OR REPLACE FUNCTION public.save_daily_entry_v2(
  p_payload jsonb,
  p_expected_exists boolean,
  p_expected_id uuid,
  p_expected_revision bigint,
  p_request_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
SET row_security = on
AS $save$
DECLARE
  v_actor uuid;
  v_uid uuid;
  v_uid_txt text;
  v_date_txt text;
  v_date date;
  v_row public.entries;
  v_current public.entries;
  v_row_found boolean := false;

  v_fingerprint bytea;
  v_existing_fingerprint bytea;
  v_existing_outcome jsonb;
  v_outcome jsonb;
  v_inserted boolean := false;

  v_p_mood boolean;
  v_p_emoji boolean;
  v_p_sleep boolean;
  v_p_stress boolean;
  v_p_activities boolean;
  v_p_habits boolean;
  v_p_gratitude boolean;
  v_p_note boolean;
  v_p_weather boolean;
  v_p_photo boolean;
  v_p_screen boolean;
  v_p_unlocks boolean;
  v_p_apps boolean;
  v_p_reflection boolean;
  v_p_scaleval boolean;
  v_clear_photo boolean := false;

  v_mood smallint;
  v_emoji text;
  v_sleep smallint;
  v_stress smallint;
  v_activities jsonb;
  v_habits jsonb;
  v_gratitude jsonb;
  v_note text;
  v_weather jsonb;
  v_photo text;
  v_screen integer;
  v_unlocks integer;
  v_apps jsonb;
  v_reflection text;
  v_scaleval jsonb;

  v_clear_scale_ids text[] := ARRAY[]::text[];
  v_clear_scale_id text;
BEGIN
  ---------------------------------------------------------------------------
  -- Authentication and input contract. A null public.diarium_auth_uid() or service-role JWT
  -- is never treated as a trusted server call. The v2 RPC is client-auth only.
  ---------------------------------------------------------------------------
  v_actor := public.diarium_auth_uid();
  IF v_actor IS NULL OR public.diarium_auth_jwt() ->> 'role' IS DISTINCT FROM 'authenticated' THEN
    RAISE EXCEPTION 'save_daily_entry_v2 requires an authenticated user JWT'
      USING ERRCODE = '42501';
  END IF;
  IF p_payload IS NULL OR pg_catalog.jsonb_typeof(p_payload) <> 'object' THEN
    RAISE EXCEPTION 'save_daily_entry_v2: payload must be a JSON object'
      USING ERRCODE = '22023';
  END IF;
  IF p_expected_exists IS NULL OR p_request_id IS NULL THEN
    RAISE EXCEPTION 'save_daily_entry_v2: expected existence and request ID are required'
      USING ERRCODE = '22023';
  END IF;
  IF p_expected_exists AND (p_expected_id IS NULL OR p_expected_revision IS NULL OR p_expected_revision < 1) THEN
    RAISE EXCEPTION 'save_daily_entry_v2: an existing baseline requires a positive revision and row ID'
      USING ERRCODE = '22023';
  END IF;
  IF NOT p_expected_exists AND (p_expected_id IS NOT NULL OR p_expected_revision IS NOT NULL) THEN
    RAISE EXCEPTION 'save_daily_entry_v2: an absent baseline must not include a row ID or revision'
      USING ERRCODE = '22023';
  END IF;

  v_uid_txt := pg_catalog.btrim(p_payload ->> 'user_id');
  v_date_txt := p_payload ->> 'date';
  IF v_uid_txt IS NULL OR v_uid_txt = '' OR v_date_txt IS NULL THEN
    RAISE EXCEPTION 'save_daily_entry_v2: user_id and date are required'
      USING ERRCODE = '22023';
  END IF;
  BEGIN
    v_uid := v_uid_txt::uuid;
  EXCEPTION WHEN invalid_text_representation THEN
    RAISE EXCEPTION 'save_daily_entry_v2: user_id must be a UUID'
      USING ERRCODE = '22023';
  END;
  IF v_uid <> v_actor THEN
    RAISE EXCEPTION 'Forbidden: user_id mismatch' USING ERRCODE = '42501';
  END IF;
  IF v_date_txt !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' THEN
    RAISE EXCEPTION 'save_daily_entry_v2: date must be a real YYYY-MM-DD date'
      USING ERRCODE = '22023';
  END IF;
  BEGIN
    v_date := v_date_txt::date;
  EXCEPTION WHEN invalid_text_representation OR datetime_field_overflow THEN
    RAISE EXCEPTION 'save_daily_entry_v2: date must be a real YYYY-MM-DD date'
      USING ERRCODE = '22023';
  END;
  IF pg_catalog.to_char(v_date, 'YYYY-MM-DD') <> v_date_txt THEN
    RAISE EXCEPTION 'save_daily_entry_v2: date must be a real YYYY-MM-DD date'
      USING ERRCODE = '22023';
  END IF;
  IF v_date > (pg_catalog.now() AT TIME ZONE 'Europe/Prague')::date THEN
    RAISE EXCEPTION 'save_daily_entry_v2: future dates are not allowed'
      USING ERRCODE = '22023';
  END IF;

  ---------------------------------------------------------------------------
  -- Preserve save_daily_entry(jsonb)'s partial-column and value semantics.
  -- The only added wire intents are clear_photo=true and clear_scale_ids.
  ---------------------------------------------------------------------------
  v_p_mood       := p_payload ? 'mood';
  v_p_emoji      := p_payload ? 'mood_emoji';
  v_p_stress     := p_payload ? 'stress';
  v_p_activities := p_payload ? 'activities';
  v_p_habits     := p_payload ? 'habits';
  v_p_gratitude  := p_payload ? 'gratitude';
  v_p_note       := p_payload ? 'note';
  v_p_weather    := p_payload ? 'weather';
  v_p_photo      := p_payload ? 'photo_path';
  v_p_screen     := p_payload ? 'phone_screen_time';
  v_p_unlocks    := p_payload ? 'phone_unlocks';
  v_p_apps       := p_payload ? 'phone_top_apps';
  v_p_reflection := p_payload ? 'ai_reflection';
  v_p_scaleval   := p_payload ? 'scale_values';
  IF v_p_scaleval AND pg_catalog.jsonb_typeof(p_payload -> 'scale_values') = 'null' THEN
    RAISE EXCEPTION 'save_daily_entry_v2: scale_values must not be null; omit it to leave scale state unchanged or use {} to clear it'
      USING ERRCODE = '22023';
  END IF;

  IF p_payload ? 'clear_photo' THEN
    IF pg_catalog.jsonb_typeof(p_payload -> 'clear_photo') <> 'boolean' THEN
      RAISE EXCEPTION 'save_daily_entry_v2: clear_photo must be boolean'
        USING ERRCODE = '22023';
    END IF;
    v_clear_photo := (p_payload ->> 'clear_photo')::boolean;
  END IF;

  IF v_p_mood AND (p_payload ->> 'mood') ~ '^-?[0-9]+(\.[0-9]+)?$' THEN
    IF (p_payload ->> 'mood')::numeric > 0 THEN
      v_mood := (p_payload ->> 'mood')::numeric::smallint;
    END IF;
  END IF;
  IF p_payload ? 'stress' AND (p_payload ->> 'stress') ~ '^-?[0-9]+(\.[0-9]+)?$' THEN
    IF (p_payload ->> 'stress')::numeric > 0 THEN
      v_stress := (p_payload ->> 'stress')::numeric::smallint;
    END IF;
  END IF;

  v_emoji := CASE
    WHEN pg_catalog.jsonb_typeof(p_payload -> 'mood_emoji') = 'string'
     AND (p_payload ->> 'mood_emoji') <> ''
      THEN p_payload ->> 'mood_emoji'
    ELSE ''
  END;

  -- sleep_quality is written only when present and > 0; 0/null is ignored.
  v_p_sleep := (p_payload ? 'sleep_quality')
    AND (p_payload ->> 'sleep_quality') ~ '^-?[0-9]+(\.[0-9]+)?$'
    AND (p_payload ->> 'sleep_quality')::numeric > 0;
  IF v_p_sleep THEN
    v_sleep := (p_payload ->> 'sleep_quality')::numeric::smallint;
  END IF;

  v_activities := CASE WHEN pg_catalog.jsonb_typeof(p_payload -> 'activities') = 'null'
                       THEN '[]'::jsonb ELSE p_payload -> 'activities' END;
  v_habits := CASE WHEN pg_catalog.jsonb_typeof(p_payload -> 'habits') = 'null'
                    THEN '{}'::jsonb ELSE p_payload -> 'habits' END;
  v_gratitude := CASE WHEN pg_catalog.jsonb_typeof(p_payload -> 'gratitude') = 'null'
                      THEN '[]'::jsonb ELSE p_payload -> 'gratitude' END;
  v_weather := CASE WHEN pg_catalog.jsonb_typeof(p_payload -> 'weather') = 'null'
                    THEN '[]'::jsonb ELSE p_payload -> 'weather' END;
  v_note := CASE
    WHEN pg_catalog.jsonb_typeof(p_payload -> 'note') = 'string'
      THEN p_payload ->> 'note'
    WHEN pg_catalog.jsonb_typeof(p_payload -> 'note') IN ('number', 'boolean')
      THEN p_payload ->> 'note'
    ELSE ''
  END;

  -- Legacy photo behavior is retained: omitted/null/empty photo_path does not
  -- clear an old value. Clear is an explicit, mutually exclusive intent.
  v_p_photo := v_p_photo
    AND pg_catalog.jsonb_typeof(p_payload -> 'photo_path') = 'string'
    AND (p_payload ->> 'photo_path') <> '';
  v_photo := CASE WHEN v_p_photo THEN p_payload ->> 'photo_path' ELSE NULL END;
  IF v_clear_photo AND v_p_photo THEN
    RAISE EXCEPTION 'save_daily_entry_v2: clear_photo cannot accompany a photo_path'
      USING ERRCODE = '22023';
  END IF;

  -- ai_reflection follows the legacy truthy rule: missing, null, and empty
  -- values are ignored on UPDATE instead of clearing the stored reflection.
  v_p_reflection := v_p_reflection
    AND pg_catalog.jsonb_typeof(p_payload -> 'ai_reflection') = 'string'
    AND (p_payload ->> 'ai_reflection') <> '';
  v_reflection := CASE WHEN v_p_reflection THEN p_payload ->> 'ai_reflection' ELSE NULL END;

  IF v_p_screen THEN
    IF pg_catalog.jsonb_typeof(p_payload -> 'phone_screen_time') = 'null' THEN
      v_screen := NULL;
    ELSIF (p_payload ->> 'phone_screen_time') ~ '^-?[0-9]+$' THEN
      v_screen := (p_payload ->> 'phone_screen_time')::integer;
    ELSE
      RAISE EXCEPTION 'save_daily_entry_v2: phone_screen_time must be an integer'
        USING ERRCODE = '22023';
    END IF;
  END IF;
  IF v_p_unlocks THEN
    IF pg_catalog.jsonb_typeof(p_payload -> 'phone_unlocks') = 'null' THEN
      v_unlocks := NULL;
    ELSIF (p_payload ->> 'phone_unlocks') ~ '^-?[0-9]+$' THEN
      v_unlocks := (p_payload ->> 'phone_unlocks')::integer;
    ELSE
      RAISE EXCEPTION 'save_daily_entry_v2: phone_unlocks must be an integer'
        USING ERRCODE = '22023';
    END IF;
  END IF;

  -- Match save_daily_entry's top-app array/string conversion exactly.
  CASE pg_catalog.jsonb_typeof(p_payload -> 'phone_top_apps')
    WHEN 'array' THEN
      v_apps := p_payload -> 'phone_top_apps';
    WHEN 'string' THEN
      IF (p_payload ->> 'phone_top_apps') <> '' THEN
        SELECT COALESCE(
                 pg_catalog.jsonb_agg(
                   pg_catalog.jsonb_build_object('app', a.app, 'time_sec', a.secs)
                 ),
                 '[]'::jsonb
               )
          INTO v_apps
          FROM (
            SELECT pg_catalog.btrim(pg_catalog.split_part(pg_catalog.btrim(item), ':', 1)) AS app,
                   CASE
                     WHEN pg_catalog.btrim(pg_catalog.split_part(pg_catalog.btrim(item), ':', 2)) ~ '^[+-]?[0-9]+'
                       THEN pg_catalog.regexp_replace(
                              pg_catalog.btrim(pg_catalog.split_part(pg_catalog.btrim(item), ':', 2)),
                              '^([+-]?[0-9]+).*$', '\1'
                            )::integer * 60
                     ELSE 0
                   END AS secs
              FROM pg_catalog.unnest(pg_catalog.string_to_array(p_payload ->> 'phone_top_apps', ',')) AS item
          ) AS a
         WHERE a.app <> '' AND a.secs > 0;
      ELSE
        v_p_apps := false;
      END IF;
    ELSE
      v_p_apps := false;
  END CASE;

  -- A missing key leaves scale state alone; explicit JSON null was rejected
  -- above so it cannot diverge from the scale_entries mirror.
  v_scaleval := p_payload -> 'scale_values';

  IF p_payload ? 'clear_scale_ids' THEN
    IF pg_catalog.jsonb_typeof(p_payload -> 'clear_scale_ids') <> 'array' THEN
      RAISE EXCEPTION 'save_daily_entry_v2: clear_scale_ids must be an array of UUID strings'
        USING ERRCODE = '22023';
    END IF;
    IF EXISTS (
      SELECT 1
        FROM pg_catalog.jsonb_array_elements(p_payload -> 'clear_scale_ids') AS item(value)
       WHERE pg_catalog.jsonb_typeof(item.value) <> 'string'
          OR (item.value #>> '{}') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ) THEN
      RAISE EXCEPTION 'save_daily_entry_v2: clear_scale_ids must contain only UUID strings'
        USING ERRCODE = '22023';
    END IF;
    SELECT COALESCE(
             pg_catalog.array_agg(DISTINCT pg_catalog.lower(item.value #>> '{}') ORDER BY pg_catalog.lower(item.value #>> '{}')),
             ARRAY[]::text[]
           )
      INTO v_clear_scale_ids
      FROM pg_catalog.jsonb_array_elements(p_payload -> 'clear_scale_ids') AS item(value);
  END IF;

  -- For object maps, scale_values are stored verbatim. A present zero or negative
  -- value does not delete its mirror under the legacy RPC's semantics. Explicit
  -- clear_scale_ids removes the named JSON key and mirror; replacing the object
  -- also removes mirrors for owned keys no longer present. Positive, owned IDs
  -- are upserted below; invalid/foreign keys and non-positive values are skipped.
  IF v_p_scaleval AND pg_catalog.jsonb_typeof(p_payload -> 'scale_values') = 'object' THEN
    IF EXISTS (
      SELECT 1
        FROM pg_catalog.jsonb_each(p_payload -> 'scale_values') AS kv(key, value)
       WHERE kv.key = ANY(v_clear_scale_ids)
         AND pg_catalog.jsonb_typeof(kv.value) = 'number'
         AND (kv.value::text)::numeric > 0
    ) THEN
      RAISE EXCEPTION 'save_daily_entry_v2: a scale cannot be both cleared and assigned a positive value'
        USING ERRCODE = '22023';
    END IF;
  END IF;

  IF v_p_scaleval
     AND pg_catalog.jsonb_typeof(v_scaleval) = 'object'
     AND pg_catalog.cardinality(v_clear_scale_ids) > 0 THEN
    v_scaleval := v_scaleval - v_clear_scale_ids;
  END IF;

  ---------------------------------------------------------------------------
  -- Idempotency claim comes before the date-key lock. A unique-key conflict
  -- waits for an identical in-flight request to commit/rollback. Conflicts are
  -- terminal receipts too, so replaying a stale intent can never become a write.
  ---------------------------------------------------------------------------
  v_fingerprint := extensions.digest(
    pg_catalog.convert_to(
      pg_catalog.jsonb_build_object(
        'contract', 'save_daily_entry_v2',
        'payload', p_payload,
        'expected_exists', p_expected_exists,
        'expected_id', p_expected_id,
        'expected_revision', p_expected_revision
      )::text,
      'UTF8'
    ),
    'sha256'
  );

  INSERT INTO public.entry_save_v2_receipts (user_id, request_id, fingerprint)
  VALUES (v_actor, p_request_id, v_fingerprint)
  ON CONFLICT (user_id, request_id) DO NOTHING
  RETURNING true INTO v_inserted;
  v_inserted := COALESCE(v_inserted, false);

  IF NOT v_inserted THEN
    SELECT r.fingerprint, r.outcome
      INTO v_existing_fingerprint, v_existing_outcome
      FROM public.entry_save_v2_receipts AS r
     WHERE r.user_id = v_actor
       AND r.request_id = p_request_id
     FOR UPDATE;

    IF NOT FOUND THEN
      RAISE EXCEPTION 'save_daily_entry_v2: request receipt disappeared during claim'
        USING ERRCODE = 'XX000';
    END IF;
    IF v_existing_fingerprint <> v_fingerprint THEN
      RAISE EXCEPTION 'save_daily_entry_v2: request_id was already used for a different request'
        USING ERRCODE = '22023';
    END IF;
    IF v_existing_outcome IS NULL THEN
      RAISE EXCEPTION 'save_daily_entry_v2: committed request receipt has no outcome'
        USING ERRCODE = 'XX000';
    END IF;
    RETURN v_existing_outcome || pg_catalog.jsonb_build_object('replayed', true);
  END IF;

  ---------------------------------------------------------------------------
  -- Serialize both existing rows and absent (user,date) keys. hash collisions
  -- can only over-serialize unrelated keys. The unique constraint remains the
  -- final guard for writers that do not take this advisory lock.
  ---------------------------------------------------------------------------
  PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(
      v_actor::text || ':' || pg_catalog.to_char(v_date, 'YYYY-MM-DD'),
      0
    )
  );

  SELECT e.* INTO v_current
    FROM public.entries AS e
   WHERE e.user_id = v_actor
     AND e.date = v_date
   FOR UPDATE;
  v_row_found := FOUND;

  IF p_expected_exists AND NOT v_row_found THEN
    v_outcome := pg_catalog.jsonb_build_object(
      'status', 'conflict', 'reason', 'row_missing', 'date', v_date
    );
    UPDATE public.entry_save_v2_receipts
       SET outcome = v_outcome
     WHERE user_id = v_actor AND request_id = p_request_id;
    RETURN v_outcome || pg_catalog.jsonb_build_object('replayed', false);
  END IF;

  IF NOT p_expected_exists AND v_row_found THEN
    v_outcome := pg_catalog.jsonb_build_object(
      'status', 'conflict', 'reason', 'row_exists', 'date', v_date,
      'current_id', v_current.id, 'current_revision', v_current.revision
    );
    UPDATE public.entry_save_v2_receipts
       SET outcome = v_outcome
     WHERE user_id = v_actor AND request_id = p_request_id;
    RETURN v_outcome || pg_catalog.jsonb_build_object('replayed', false);
  END IF;

  IF p_expected_exists AND v_row_found
     AND (v_current.id <> p_expected_id OR v_current.revision <> p_expected_revision) THEN
    v_outcome := pg_catalog.jsonb_build_object(
      'status', 'conflict', 'reason', 'stale_revision', 'date', v_date,
      'current_id', v_current.id, 'current_revision', v_current.revision
    );
    UPDATE public.entry_save_v2_receipts
       SET outcome = v_outcome
     WHERE user_id = v_actor AND request_id = p_request_id;
    RETURN v_outcome || pg_catalog.jsonb_build_object('replayed', false);
  END IF;

  IF v_row_found THEN
    UPDATE public.entries AS t SET
      mood              = CASE WHEN v_p_mood       THEN v_mood             ELSE t.mood             END,
      mood_emoji        = CASE WHEN v_p_emoji      THEN v_emoji            ELSE t.mood_emoji       END,
      sleep_quality     = CASE WHEN v_p_sleep      THEN v_sleep            ELSE t.sleep_quality    END,
      stress            = CASE WHEN v_p_stress     THEN v_stress           ELSE t.stress           END,
      activities        = CASE WHEN v_p_activities THEN v_activities       ELSE t.activities       END,
      habits            = CASE WHEN v_p_habits     THEN v_habits            ELSE t.habits            END,
      gratitude         = CASE WHEN v_p_gratitude  THEN v_gratitude         ELSE t.gratitude         END,
      note              = CASE WHEN v_p_note       THEN v_note              ELSE t.note              END,
      weather           = CASE WHEN v_p_weather    THEN v_weather           ELSE t.weather           END,
      photo_path        = CASE WHEN v_clear_photo  THEN NULL
                               WHEN v_p_photo      THEN v_photo
                               ELSE t.photo_path END,
      phone_screen_time = CASE WHEN v_p_screen     THEN v_screen            ELSE t.phone_screen_time END,
      phone_unlocks     = CASE WHEN v_p_unlocks    THEN v_unlocks           ELSE t.phone_unlocks     END,
      phone_top_apps    = CASE WHEN v_p_apps       THEN COALESCE(v_apps, '[]'::jsonb)
                               ELSE t.phone_top_apps END,
      ai_reflection     = CASE WHEN v_p_reflection THEN v_reflection        ELSE t.ai_reflection     END,
      scale_values      = CASE
                            WHEN v_p_scaleval THEN v_scaleval
                            WHEN pg_catalog.cardinality(v_clear_scale_ids) > 0
                             AND pg_catalog.jsonb_typeof(t.scale_values) = 'object'
                              THEN t.scale_values - v_clear_scale_ids
                            ELSE t.scale_values
                          END
     WHERE t.id = v_current.id
       AND t.user_id = v_actor
       AND t.date = v_date
       AND t.revision = v_current.revision
    RETURNING t.* INTO v_row;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'save_daily_entry_v2: locked row could not be updated'
        USING ERRCODE = '40001';
    END IF;
  ELSE
    -- Never use ON CONFLICT DO UPDATE for entries: a competing creation becomes
    -- a conflict receipt, never an overwrite. Defaults match save_daily_entry.
    BEGIN
      INSERT INTO public.entries (
        user_id, date, mood, mood_emoji, sleep_quality, stress,
        activities, habits, gratitude, note, weather, photo_path,
        phone_screen_time, phone_unlocks, phone_top_apps, ai_reflection, scale_values
      ) VALUES (
        v_actor, v_date, v_mood,
        CASE WHEN v_p_emoji THEN v_emoji ELSE NULL END,
        v_sleep, v_stress,
        CASE WHEN v_p_activities THEN v_activities ELSE '[]'::jsonb END,
        CASE WHEN v_p_habits THEN v_habits ELSE '{}'::jsonb END,
        CASE WHEN v_p_gratitude THEN v_gratitude ELSE '[]'::jsonb END,
        CASE WHEN v_p_note THEN v_note ELSE ''::text END,
        CASE WHEN v_p_weather THEN v_weather ELSE '[]'::jsonb END,
        CASE WHEN v_clear_photo THEN NULL WHEN v_p_photo THEN v_photo ELSE NULL::text END,
        v_screen, v_unlocks,
        CASE WHEN v_p_apps THEN COALESCE(v_apps, '[]'::jsonb) ELSE '[]'::jsonb END,
        v_reflection,
        CASE WHEN v_p_scaleval THEN COALESCE(v_scaleval, '{}'::jsonb) ELSE '{}'::jsonb END
      ) RETURNING * INTO v_row;
    EXCEPTION WHEN unique_violation THEN
      -- A non-v2 writer may have won the unique key without taking our lock.
      -- Convert only this insert conflict to a terminal conflict; never retry as
      -- an upsert or overwrite its row.
      SELECT e.* INTO v_current
        FROM public.entries AS e
       WHERE e.user_id = v_actor AND e.date = v_date
       FOR UPDATE;
      v_outcome := pg_catalog.jsonb_build_object(
        'status', 'conflict', 'reason', 'insert_race', 'date', v_date
      );
      IF FOUND THEN
        v_outcome := v_outcome || pg_catalog.jsonb_build_object(
          'current_id', v_current.id, 'current_revision', v_current.revision
        );
      END IF;
      UPDATE public.entry_save_v2_receipts
         SET outcome = v_outcome
       WHERE user_id = v_actor AND request_id = p_request_id;
      RETURN v_outcome || pg_catalog.jsonb_build_object('replayed', false);
    END;
  END IF;

  ---------------------------------------------------------------------------
  -- Mirror scale_values in the same transaction. Omitted means unchanged;
  -- positive owned IDs upsert, zero/negative values retain their legacy mirror,
  -- explicit clears remove exact keys, and object replacement removes stale
  -- owned mirrors. All mutations remain part of the entry-save transaction.
  -- Unlike the beta5 RPC's warning-only catch, a v2 mirror failure rolls the
  -- entire new save back so entries and statistics cannot diverge silently.
  ---------------------------------------------------------------------------
  IF pg_catalog.cardinality(v_clear_scale_ids) > 0 THEN
    DELETE FROM public.scale_entries AS se
     USING public.scales AS s
     WHERE se.user_id = v_actor
       AND se.date = v_date
       AND s.id = se.scale_id
       AND s.user_id = v_actor
       AND s.id::text = ANY(v_clear_scale_ids);
  END IF;

  IF v_p_scaleval AND pg_catalog.jsonb_typeof(v_scaleval) = 'object' THEN
    DELETE FROM public.scale_entries AS se
     USING public.scales AS s
     WHERE se.user_id = v_actor
       AND se.date = v_date
       AND s.id = se.scale_id
       AND s.user_id = v_actor
       AND NOT (v_scaleval ? s.id::text);

    INSERT INTO public.scale_entries (user_id, scale_id, date, value)
    SELECT v_actor, s.id, v_date,
           pg_catalog.round((kv.value::text)::numeric)::integer
      FROM pg_catalog.jsonb_each(v_scaleval) AS kv(key, value)
      JOIN public.scales AS s
        ON s.user_id = v_actor
       AND s.id::text = kv.key
     WHERE pg_catalog.jsonb_typeof(kv.value) = 'number'
       AND (kv.value::text)::numeric > 0
    ON CONFLICT (user_id, scale_id, date)
    DO UPDATE SET value = EXCLUDED.value;
  END IF;

  v_outcome := pg_catalog.jsonb_build_object(
    'status', 'saved', 'entry_id', v_row.id, 'date', v_date,
    'revision', v_row.revision
  );
  UPDATE public.entry_save_v2_receipts
     SET outcome = v_outcome
   WHERE user_id = v_actor AND request_id = p_request_id;

  RETURN v_outcome || pg_catalog.jsonb_build_object(
    'replayed', false, 'entry', pg_catalog.to_jsonb(v_row)
  );
END;
$save$;

ALTER FUNCTION public.save_daily_entry_v2(jsonb, boolean, uuid, bigint, uuid)
  OWNER TO diarium_entry_sync_owner;
REVOKE CREATE ON SCHEMA public FROM diarium_entry_sync_owner;
REVOKE ALL ON FUNCTION public.save_daily_entry_v2(jsonb, boolean, uuid, bigint, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.save_daily_entry_v2(jsonb, boolean, uuid, bigint, uuid) FROM anon;
REVOKE ALL ON FUNCTION public.save_daily_entry_v2(jsonb, boolean, uuid, bigint, uuid) FROM service_role;
REVOKE ALL ON FUNCTION public.save_daily_entry_v2(jsonb, boolean, uuid, bigint, uuid) FROM authenticated;
GRANT EXECUTE ON FUNCTION public.save_daily_entry_v2(jsonb, boolean, uuid, bigint, uuid) TO authenticated;

REVOKE ALL ON FUNCTION public.diarium_entry_revision_v2() FROM PUBLIC, anon, authenticated, service_role;

COMMENT ON FUNCTION public.save_daily_entry_v2(jsonb, boolean, uuid, bigint, uuid) IS
  'Authenticated-only conditional save with absent-key serialization, (id,revision) CAS, immutable request receipts, partial-field parity with save_daily_entry(jsonb), and transactional scale/photo-column mutations. Does not disable legacy writes or guarantee global cross-device safety until all legacy paths are cut off.';
