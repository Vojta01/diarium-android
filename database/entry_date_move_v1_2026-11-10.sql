-- PRODUCTION CANDIDATE — review and preflight required; not applied to production.
-- This file is a migration fragment, not a safe standalone entry point. Apply it
-- only through database/apply_conditional_save_move_candidate.sh with the other
-- two candidate files in its explicit transaction.
-- Requires the candidate's entries.revision trigger, public.diarium_auth_uid()/public.diarium_auth_jwt(),
-- extensions.digest(), unique entry/scale keys, and Supabase API roles.
-- Adds a separate RPC and receipt table; beta5 save_daily_entry and CAS v2 are
-- untouched. SQL moves only the photo_path pointer; Storage cleanup is outside
-- the transaction and remains a pending, non-authorizing handoff.
--
-- Non-test databases require the operator-created temporary approval table
-- described in database/CONDITIONAL_SAVE_MOVE_CANDIDATE_2026-11-11.md, created
-- with ON COMMIT DROP inside the explicit all-migrations transaction. The
-- diarium_test_* guard remains for disposable CI fixtures. A transaction-local
-- 5-second lock_timeout is set before DDL.

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
  SELECT pg_catalog.coalesce(
    pg_catalog.nullif(pg_catalog.current_setting('request.jwt.claim', true), ''),
    pg_catalog.nullif(pg_catalog.current_setting('request.jwt.claims', true), ''),
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
  SELECT pg_catalog.coalesce(
    pg_catalog.nullif(pg_catalog.current_setting('request.jwt.claim.sub', true), ''),
    public.diarium_auth_jwt() ->> 'sub'
  )::uuid
$auth_uid$;

REVOKE ALL ON FUNCTION public.diarium_auth_jwt() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.diarium_auth_uid() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.diarium_auth_jwt() TO anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.diarium_auth_uid() TO anon, authenticated, service_role;

DO $preflight$
DECLARE
  v_revision_type oid;
BEGIN
  IF pg_catalog.to_regclass('public.entries') IS NULL
     OR pg_catalog.to_regclass('public.scale_entries') IS NULL
     OR pg_catalog.to_regclass('public.scales') IS NULL
     OR pg_catalog.to_regprocedure('public.save_daily_entry_v2(jsonb,boolean,uuid,bigint,uuid)') IS NULL
     OR pg_catalog.to_regprocedure('public.diarium_auth_uid()') IS NULL
     OR pg_catalog.to_regprocedure('public.diarium_auth_jwt()') IS NULL
     OR pg_catalog.to_regprocedure('extensions.digest(bytea,text)') IS NULL THEN
    RAISE EXCEPTION 'date-move foundation requires the CAS fixture and save_daily_entry_v2';
  END IF;

  SELECT a.atttypid INTO v_revision_type
    FROM pg_catalog.pg_attribute AS a
   WHERE a.attrelid = 'public.entries'::pg_catalog.regclass
     AND a.attname = 'revision'
     AND a.attnum > 0
     AND NOT a.attisdropped;
  IF v_revision_type IS DISTINCT FROM 'pg_catalog.int8'::pg_catalog.regtype THEN
    RAISE EXCEPTION 'public.entries.revision must be bigint';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_trigger AS t
     WHERE t.tgrelid = 'public.entries'::pg_catalog.regclass
       AND t.tgname = 'trg_entries_revision_v2'
       AND NOT t.tgisinternal
       AND t.tgenabled <> 'D'
  ) THEN
    RAISE EXCEPTION 'the active entries revision trigger is required';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_constraint AS c
     WHERE c.conrelid = 'public.entries'::pg_catalog.regclass
       AND c.contype = 'u'
       AND c.conkey = ARRAY[
         (SELECT a.attnum FROM pg_catalog.pg_attribute AS a WHERE a.attrelid = 'public.entries'::pg_catalog.regclass AND a.attname = 'user_id'),
         (SELECT a.attnum FROM pg_catalog.pg_attribute AS a WHERE a.attrelid = 'public.entries'::pg_catalog.regclass AND a.attname = 'date')
       ]::smallint[]
  ) THEN
    RAISE EXCEPTION 'public.entries requires UNIQUE (user_id, date)';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_constraint AS c
     WHERE c.conrelid = 'public.scale_entries'::pg_catalog.regclass
       AND c.contype = 'u'
       AND c.conkey = ARRAY[
         (SELECT a.attnum FROM pg_catalog.pg_attribute AS a WHERE a.attrelid = 'public.scale_entries'::pg_catalog.regclass AND a.attname = 'user_id'),
         (SELECT a.attnum FROM pg_catalog.pg_attribute AS a WHERE a.attrelid = 'public.scale_entries'::pg_catalog.regclass AND a.attname = 'scale_id'),
         (SELECT a.attnum FROM pg_catalog.pg_attribute AS a WHERE a.attrelid = 'public.scale_entries'::pg_catalog.regclass AND a.attname = 'date')
       ]::smallint[]
  ) THEN
    RAISE EXCEPTION 'public.scale_entries requires UNIQUE (user_id, scale_id, date)';
  END IF;
END;
$preflight$;

DO $owner_role$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'diarium_entry_move_owner'
  ) THEN
    EXECUTE 'CREATE ROLE diarium_entry_move_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS';
  END IF;
END;
$owner_role$;

DO $owner_role_check$
DECLARE
  v_role pg_catalog.pg_roles%ROWTYPE;
BEGIN
  SELECT * INTO v_role
    FROM pg_catalog.pg_roles
   WHERE rolname = 'diarium_entry_move_owner';
  IF v_role.rolcanlogin OR v_role.rolsuper OR v_role.rolcreatedb
     OR v_role.rolcreaterole OR v_role.rolinherit OR v_role.rolreplication
     OR v_role.rolbypassrls THEN
    RAISE EXCEPTION 'diarium_entry_move_owner exists with unsafe role attributes';
  END IF;
  IF pg_catalog.pg_has_role('anon'::name, 'diarium_entry_move_owner'::name, 'MEMBER')
     OR pg_catalog.pg_has_role('authenticated'::name, 'diarium_entry_move_owner'::name, 'MEMBER')
     OR pg_catalog.pg_has_role('service_role'::name, 'diarium_entry_move_owner'::name, 'MEMBER') THEN
    RAISE EXCEPTION 'an API role can assume diarium_entry_move_owner';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_roles AS r
     WHERE r.rolname = current_user
       AND (r.rolsuper OR pg_catalog.pg_has_role(current_user, 'diarium_entry_move_owner', 'MEMBER'))
  ) THEN
    RAISE EXCEPTION 'migration role must be superuser or member of diarium_entry_move_owner';
  END IF;
END;
$owner_role_check$;

CREATE TABLE IF NOT EXISTS public.entry_move_v1_receipts (
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
      LEFT JOIN pg_catalog.pg_attribute AS a
        ON a.attrelid = 'public.entry_move_v1_receipts'::pg_catalog.regclass
       AND a.attname = expected.column_name
       AND a.attnum > 0
       AND NOT a.attisdropped
     WHERE a.attname IS NULL OR a.atttypid <> expected.type_oid::pg_catalog.oid
  ) THEN
    RAISE EXCEPTION 'entry_move_v1_receipts has an incompatible column definition';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_constraint AS c
     WHERE c.conrelid = 'public.entry_move_v1_receipts'::pg_catalog.regclass
       AND c.contype = 'p'
       AND c.conkey = ARRAY[
         (SELECT a.attnum FROM pg_catalog.pg_attribute AS a WHERE a.attrelid = 'public.entry_move_v1_receipts'::pg_catalog.regclass AND a.attname = 'user_id'),
         (SELECT a.attnum FROM pg_catalog.pg_attribute AS a WHERE a.attrelid = 'public.entry_move_v1_receipts'::pg_catalog.regclass AND a.attname = 'request_id')
       ]::smallint[]
  ) THEN
    RAISE EXCEPTION 'entry_move_v1_receipts requires PRIMARY KEY (user_id, request_id)';
  END IF;
  IF EXISTS (
    SELECT 1 FROM pg_catalog.pg_constraint AS c
     WHERE c.conrelid = 'public.entry_move_v1_receipts'::pg_catalog.regclass
       AND c.contype = 'f'
  ) THEN
    RAISE EXCEPTION 'entry_move_v1_receipts must not cascade with entries or users';
  END IF;
END;
$receipt_shape$;

GRANT CREATE ON SCHEMA public TO diarium_entry_move_owner;
ALTER TABLE public.entry_move_v1_receipts OWNER TO diarium_entry_move_owner;
REVOKE CREATE ON SCHEMA public FROM diarium_entry_move_owner;
ALTER TABLE public.entry_move_v1_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.entry_move_v1_receipts FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS entry_move_v1_receipt_owner ON public.entry_move_v1_receipts;
CREATE POLICY entry_move_v1_receipt_owner
  ON public.entry_move_v1_receipts
  FOR ALL TO diarium_entry_move_owner
  USING (user_id = public.diarium_auth_uid())
  WITH CHECK (user_id = public.diarium_auth_uid());
REVOKE ALL ON TABLE public.entry_move_v1_receipts FROM PUBLIC, anon, authenticated, service_role;
GRANT SELECT, INSERT, UPDATE ON TABLE public.entry_move_v1_receipts TO diarium_entry_move_owner;

GRANT USAGE ON SCHEMA public, auth, extensions TO diarium_entry_move_owner;
GRANT EXECUTE ON FUNCTION public.diarium_auth_uid() TO diarium_entry_move_owner;
GRANT EXECUTE ON FUNCTION public.diarium_auth_jwt() TO diarium_entry_move_owner;
GRANT EXECUTE ON FUNCTION extensions.digest(bytea, text) TO diarium_entry_move_owner;
GRANT SELECT, UPDATE, DELETE ON TABLE public.entries TO diarium_entry_move_owner;
GRANT SELECT, UPDATE, DELETE ON TABLE public.scale_entries TO diarium_entry_move_owner;

DROP POLICY IF EXISTS entries_v1_move_owner ON public.entries;
CREATE POLICY entries_v1_move_owner
  ON public.entries
  FOR ALL TO diarium_entry_move_owner
  USING (user_id = public.diarium_auth_uid())
  WITH CHECK (user_id = public.diarium_auth_uid());
DROP POLICY IF EXISTS scale_entries_v1_move_owner ON public.scale_entries;
CREATE POLICY scale_entries_v1_move_owner
  ON public.scale_entries
  FOR ALL TO diarium_entry_move_owner
  USING (user_id = public.diarium_auth_uid())
  WITH CHECK (user_id = public.diarium_auth_uid());

CREATE OR REPLACE FUNCTION public.move_daily_entry_v1(
  p_source_date date,
  p_source_id uuid,
  p_source_revision bigint,
  p_target_date date,
  p_target_expected_exists boolean,
  p_target_expected_id uuid,
  p_target_expected_revision bigint,
  p_confirm_overwrite boolean,
  p_request_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
SET row_security = on
AS $move$
DECLARE
  v_actor uuid;
  v_source public.entries;
  v_target public.entries;
  v_moved public.entries;
  v_source_found boolean := false;
  v_target_found boolean := false;
  v_source_lock bigint;
  v_target_lock bigint;
  v_fingerprint bytea;
  v_existing_fingerprint bytea;
  v_existing_outcome jsonb;
  v_outcome jsonb;
  v_inserted boolean := false;
  v_scale_rows_moved bigint := 0;
BEGIN
  v_actor := public.diarium_auth_uid();
  IF v_actor IS NULL OR public.diarium_auth_jwt() ->> 'role' IS DISTINCT FROM 'authenticated' THEN
    RAISE EXCEPTION 'move_daily_entry_v1 requires an authenticated user JWT'
      USING ERRCODE = '42501';
  END IF;
  IF p_source_date IS NULL OR p_source_id IS NULL OR p_source_revision IS NULL
     OR p_source_revision < 1 OR p_target_date IS NULL
     OR p_target_expected_exists IS NULL OR p_confirm_overwrite IS NULL
     OR p_request_id IS NULL THEN
    RAISE EXCEPTION 'move_daily_entry_v1: source token, target expectation, confirmation, dates, and request ID are required'
      USING ERRCODE = '22023';
  END IF;
  IF p_target_expected_exists
     AND (p_target_expected_id IS NULL OR p_target_expected_revision IS NULL OR p_target_expected_revision < 1) THEN
    RAISE EXCEPTION 'move_daily_entry_v1: occupied target requires a positive revision and row ID'
      USING ERRCODE = '22023';
  END IF;
  IF NOT p_target_expected_exists
     AND (p_target_expected_id IS NOT NULL OR p_target_expected_revision IS NOT NULL) THEN
    RAISE EXCEPTION 'move_daily_entry_v1: absent target must not include a row ID or revision'
      USING ERRCODE = '22023';
  END IF;
  IF p_confirm_overwrite AND NOT p_target_expected_exists THEN
    RAISE EXCEPTION 'move_daily_entry_v1: overwrite confirmation requires an occupied-target token'
      USING ERRCODE = '22023';
  END IF;
  IF p_target_date > (pg_catalog.now() AT TIME ZONE 'Europe/Prague')::date THEN
    RAISE EXCEPTION 'move_daily_entry_v1: future target dates are not allowed'
      USING ERRCODE = '22023';
  END IF;

  -- The receipt is claimed before either date lock. An ambiguous network retry
  -- with the same request ID and complete intent returns the original outcome.
  v_fingerprint := extensions.digest(
    pg_catalog.convert_to(
      pg_catalog.jsonb_build_object(
        'contract', 'move_daily_entry_v1',
        'source_date', p_source_date,
        'source_id', p_source_id,
        'source_revision', p_source_revision,
        'target_date', p_target_date,
        'target_expected_exists', p_target_expected_exists,
        'target_expected_id', p_target_expected_id,
        'target_expected_revision', p_target_expected_revision,
        'confirm_overwrite', p_confirm_overwrite
      )::text,
      'UTF8'
    ),
    'sha256'
  );

  INSERT INTO public.entry_move_v1_receipts (user_id, request_id, fingerprint)
  VALUES (v_actor, p_request_id, v_fingerprint)
  ON CONFLICT (user_id, request_id) DO NOTHING
  RETURNING true INTO v_inserted;
  v_inserted := COALESCE(v_inserted, false);

  IF NOT v_inserted THEN
    SELECT r.fingerprint, r.outcome
      INTO v_existing_fingerprint, v_existing_outcome
      FROM public.entry_move_v1_receipts AS r
     WHERE r.user_id = v_actor
       AND r.request_id = p_request_id
     FOR UPDATE;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'move_daily_entry_v1: request receipt disappeared during claim'
        USING ERRCODE = 'XX000';
    END IF;
    IF v_existing_fingerprint <> v_fingerprint THEN
      RAISE EXCEPTION 'move_daily_entry_v1: request_id was already used for a different request'
        USING ERRCODE = '22023';
    END IF;
    IF v_existing_outcome IS NULL THEN
      RAISE EXCEPTION 'move_daily_entry_v1: committed request receipt has no outcome'
        USING ERRCODE = 'XX000';
    END IF;
    RETURN v_existing_outcome || pg_catalog.jsonb_build_object('replayed', true);
  END IF;

  -- Use the exact date-key hash used by save_daily_entry_v2. Locks are sorted by
  -- hash so opposite-direction moves take the same order; a hash collision only
  -- causes safe over-serialization. Same-date requests take one lock.
  v_source_lock := pg_catalog.hashtextextended(
    v_actor::text || ':' || pg_catalog.to_char(p_source_date, 'YYYY-MM-DD'), 0
  );
  v_target_lock := pg_catalog.hashtextextended(
    v_actor::text || ':' || pg_catalog.to_char(p_target_date, 'YYYY-MM-DD'), 0
  );
  IF v_source_lock = v_target_lock THEN
    PERFORM pg_catalog.pg_advisory_xact_lock(v_source_lock);
  ELSIF v_source_lock < v_target_lock THEN
    PERFORM pg_catalog.pg_advisory_xact_lock(v_source_lock);
    PERFORM pg_catalog.pg_advisory_xact_lock(v_target_lock);
  ELSE
    PERFORM pg_catalog.pg_advisory_xact_lock(v_target_lock);
    PERFORM pg_catalog.pg_advisory_xact_lock(v_source_lock);
  END IF;

  -- Lock present rows in date order too. This complements the advisory locks
  -- and avoids opposite-direction row-lock ordering among move RPC callers.
  IF p_source_date <= p_target_date THEN
    SELECT e.* INTO v_source
      FROM public.entries AS e
     WHERE e.user_id = v_actor AND e.date = p_source_date
     FOR UPDATE;
    v_source_found := FOUND;
    IF p_source_date <> p_target_date THEN
      SELECT e.* INTO v_target
        FROM public.entries AS e
       WHERE e.user_id = v_actor AND e.date = p_target_date
       FOR UPDATE;
      v_target_found := FOUND;
    END IF;
  ELSE
    SELECT e.* INTO v_target
      FROM public.entries AS e
     WHERE e.user_id = v_actor AND e.date = p_target_date
     FOR UPDATE;
    v_target_found := FOUND;
    SELECT e.* INTO v_source
      FROM public.entries AS e
     WHERE e.user_id = v_actor AND e.date = p_source_date
     FOR UPDATE;
    v_source_found := FOUND;
  END IF;

  IF NOT v_source_found THEN
    v_outcome := pg_catalog.jsonb_build_object(
      'status', 'conflict', 'reason', 'source_missing', 'source_date', p_source_date
    );
    UPDATE public.entry_move_v1_receipts SET outcome = v_outcome
     WHERE user_id = v_actor AND request_id = p_request_id;
    RETURN v_outcome || pg_catalog.jsonb_build_object('replayed', false);
  END IF;
  IF v_source.id <> p_source_id OR v_source.revision <> p_source_revision THEN
    v_outcome := pg_catalog.jsonb_build_object(
      'status', 'conflict', 'reason', 'stale_source', 'source_date', p_source_date,
      'current_id', v_source.id, 'current_revision', v_source.revision
    );
    UPDATE public.entry_move_v1_receipts SET outcome = v_outcome
     WHERE user_id = v_actor AND request_id = p_request_id;
    RETURN v_outcome || pg_catalog.jsonb_build_object('replayed', false);
  END IF;

  -- A same-date move is an authenticated, token-checked no-op, matching Android's
  -- picker behavior; it never treats the source row as an overwrite target.
  IF p_source_date = p_target_date THEN
    IF NOT p_target_expected_exists THEN
      v_outcome := pg_catalog.jsonb_build_object(
        'status', 'conflict', 'reason', 'destination_exists', 'target_date', p_target_date,
        'current_id', v_source.id, 'current_revision', v_source.revision
      );
      UPDATE public.entry_move_v1_receipts SET outcome = v_outcome
       WHERE user_id = v_actor AND request_id = p_request_id;
      RETURN v_outcome || pg_catalog.jsonb_build_object('replayed', false);
    END IF;
    IF p_target_expected_id <> v_source.id
       OR p_target_expected_revision <> v_source.revision THEN
      v_outcome := pg_catalog.jsonb_build_object(
        'status', 'conflict', 'reason', 'stale_target', 'target_date', p_target_date,
        'current_id', v_source.id, 'current_revision', v_source.revision
      );
      UPDATE public.entry_move_v1_receipts SET outcome = v_outcome
       WHERE user_id = v_actor AND request_id = p_request_id;
      RETURN v_outcome || pg_catalog.jsonb_build_object('replayed', false);
    END IF;
    IF p_confirm_overwrite THEN
      RAISE EXCEPTION 'move_daily_entry_v1: same-date move cannot confirm overwrite'
        USING ERRCODE = '22023';
    END IF;
    v_outcome := pg_catalog.jsonb_build_object(
      'status', 'unchanged', 'source_date', p_source_date, 'target_date', p_target_date,
      'entry_id', v_source.id, 'revision', v_source.revision, 'entry', pg_catalog.to_jsonb(v_source)
    );
    UPDATE public.entry_move_v1_receipts SET outcome = v_outcome
     WHERE user_id = v_actor AND request_id = p_request_id;
    RETURN v_outcome || pg_catalog.jsonb_build_object('replayed', false);
  END IF;

  IF p_target_expected_exists THEN
    IF NOT v_target_found THEN
      v_outcome := pg_catalog.jsonb_build_object(
        'status', 'conflict', 'reason', 'target_missing', 'target_date', p_target_date
      );
      UPDATE public.entry_move_v1_receipts SET outcome = v_outcome
       WHERE user_id = v_actor AND request_id = p_request_id;
      RETURN v_outcome || pg_catalog.jsonb_build_object('replayed', false);
    END IF;
    IF v_target.id <> p_target_expected_id
       OR v_target.revision <> p_target_expected_revision THEN
      v_outcome := pg_catalog.jsonb_build_object(
        'status', 'conflict', 'reason', 'stale_target', 'target_date', p_target_date,
        'current_id', v_target.id, 'current_revision', v_target.revision
      );
      UPDATE public.entry_move_v1_receipts SET outcome = v_outcome
       WHERE user_id = v_actor AND request_id = p_request_id;
      RETURN v_outcome || pg_catalog.jsonb_build_object('replayed', false);
    END IF;
    IF NOT p_confirm_overwrite THEN
      v_outcome := pg_catalog.jsonb_build_object(
        'status', 'confirmation_required', 'target_date', p_target_date,
        'current_id', v_target.id, 'current_revision', v_target.revision
      );
      UPDATE public.entry_move_v1_receipts SET outcome = v_outcome
       WHERE user_id = v_actor AND request_id = p_request_id;
      RETURN v_outcome || pg_catalog.jsonb_build_object('replayed', false);
    END IF;
  ELSIF v_target_found THEN
    v_outcome := pg_catalog.jsonb_build_object(
      'status', 'conflict', 'reason', 'destination_exists', 'target_date', p_target_date,
      'current_id', v_target.id, 'current_revision', v_target.revision
    );
    UPDATE public.entry_move_v1_receipts SET outcome = v_outcome
     WHERE user_id = v_actor AND request_id = p_request_id;
    RETURN v_outcome || pg_catalog.jsonb_build_object('replayed', false);
  END IF;

  -- Lock source and destination scale rows in stable key order. Scale entries
  -- also have direct legacy writers, so an unoccupied entry date with existing
  -- standalone scale data is refused rather than silently overwriting that data.
  PERFORM se.id
    FROM public.scale_entries AS se
   WHERE se.user_id = v_actor
     AND se.date IN (p_source_date, p_target_date)
   ORDER BY se.date, se.scale_id, se.id
   FOR UPDATE;

  IF NOT v_target_found AND EXISTS (
    SELECT 1 FROM public.scale_entries AS se
     WHERE se.user_id = v_actor AND se.date = p_target_date
  ) THEN
    v_outcome := pg_catalog.jsonb_build_object(
      'status', 'conflict', 'reason', 'destination_scale_data_exists', 'target_date', p_target_date
    );
    UPDATE public.entry_move_v1_receipts SET outcome = v_outcome
     WHERE user_id = v_actor AND request_id = p_request_id;
    RETURN v_outcome || pg_catalog.jsonb_build_object('replayed', false);
  END IF;

  -- The target entry and its mirror values are discarded only after its exact
  -- ID/revision was checked above and the caller explicitly confirmed overwrite.
  IF v_target_found THEN
    DELETE FROM public.entries AS e
     WHERE e.user_id = v_actor AND e.date = p_target_date AND e.id = v_target.id;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'move_daily_entry_v1: locked target row could not be deleted'
        USING ERRCODE = '40001';
    END IF;
    DELETE FROM public.scale_entries AS se
     WHERE se.user_id = v_actor AND se.date = p_target_date;
  END IF;

  -- Moving the source row preserves every entry field (including photo_path and
  -- scale_values); the server revision trigger advances its token. The photo
  -- path is retained exactly; object storage is deliberately outside this SQL txn.
  UPDATE public.entries AS e
     SET date = p_target_date
   WHERE e.user_id = v_actor
     AND e.date = p_source_date
     AND e.id = v_source.id
     AND e.revision = v_source.revision
   RETURNING e.* INTO v_moved;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'move_daily_entry_v1: locked source row could not be moved'
      USING ERRCODE = '40001';
  END IF;

  UPDATE public.scale_entries AS se
     SET date = p_target_date
   WHERE se.user_id = v_actor AND se.date = p_source_date;
  GET DIAGNOSTICS v_scale_rows_moved = ROW_COUNT;

  v_outcome := pg_catalog.jsonb_build_object(
    'status', 'moved', 'source_date', p_source_date, 'target_date', p_target_date,
    'entry_id', v_moved.id, 'revision', v_moved.revision,
    'overwrote', v_target_found, 'scale_rows_moved', v_scale_rows_moved,
    'entry', pg_catalog.to_jsonb(v_moved)
  );
  -- The actor-scoped receipt is the durable cleanup handoff. This is only a
  -- possible object identifier, never deletion authorization: consumers must
  -- independently prove storage ownership and no remaining entry references.
  -- If either validation is unavailable or ambiguous, retain the object.
  IF v_target_found
     AND NULLIF(pg_catalog.btrim(v_target.photo_path), '') IS NOT NULL
     AND v_target.photo_path IS DISTINCT FROM v_moved.photo_path THEN
    v_outcome := v_outcome || pg_catalog.jsonb_build_object(
      'photo_cleanup_candidate', pg_catalog.jsonb_build_object(
        'actor_id', v_actor,
        'photo_path', v_target.photo_path,
        'status', 'pending_validation',
        'safe_to_delete', false,
        'required_checks', pg_catalog.jsonb_build_array(
          'verify_storage_object_owner',
          'verify_no_remaining_entry_references'
        ),
        'uncertain_validation_action', 'retain_object'
      )
    );
  END IF;
  UPDATE public.entry_move_v1_receipts SET outcome = v_outcome
   WHERE user_id = v_actor AND request_id = p_request_id;
  RETURN v_outcome || pg_catalog.jsonb_build_object('replayed', false);
END;
$move$;

GRANT CREATE ON SCHEMA public TO diarium_entry_move_owner;
ALTER FUNCTION public.move_daily_entry_v1(date, uuid, bigint, date, boolean, uuid, bigint, boolean, uuid)
  OWNER TO diarium_entry_move_owner;
REVOKE CREATE ON SCHEMA public FROM diarium_entry_move_owner;
REVOKE ALL ON FUNCTION public.move_daily_entry_v1(date, uuid, bigint, date, boolean, uuid, bigint, boolean, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.move_daily_entry_v1(date, uuid, bigint, date, boolean, uuid, bigint, boolean, uuid) FROM anon;
REVOKE ALL ON FUNCTION public.move_daily_entry_v1(date, uuid, bigint, date, boolean, uuid, bigint, boolean, uuid) FROM service_role;
REVOKE ALL ON FUNCTION public.move_daily_entry_v1(date, uuid, bigint, date, boolean, uuid, bigint, boolean, uuid) FROM authenticated;
GRANT EXECUTE ON FUNCTION public.move_daily_entry_v1(date, uuid, bigint, date, boolean, uuid, bigint, boolean, uuid) TO authenticated;

COMMENT ON FUNCTION public.move_daily_entry_v1(date, uuid, bigint, date, boolean, uuid, bigint, boolean, uuid) IS
  'Test-only atomic date move: authenticates via public.diarium_auth_uid(), checks source and destination CAS tokens, locks both date keys deterministically, moves scale rows and preserves the photo_path pointer, and replays immutable request receipts. An overwritten target photo is only returned as an actor-bound pending candidate when different from the moved photo; callers must independently verify storage ownership and all references, retain on ambiguity, and never treat the candidate as delete authorization. This SQL never deletes Storage. Existing direct legacy writers do not share all locks and must be retired before claiming global cross-device serialization.';
