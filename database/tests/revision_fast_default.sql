\set ON_ERROR_STOP on
BEGIN;
DO $fast_default$
DECLARE
  v_revision bigint;
  v_type regtype;
  v_not_null boolean;
  v_default boolean;
BEGIN
  SELECT e.revision INTO v_revision
    FROM public.entries AS e
   WHERE e.user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
     AND e.date = DATE '1990-01-01';
  IF v_revision IS DISTINCT FROM 1 THEN
    RAISE EXCEPTION 'pre-existing synthetic row must read the metadata fast default 1, got %', v_revision;
  END IF;
  SELECT a.atttypid::pg_catalog.regtype, a.attnotnull,
         EXISTS (
           SELECT 1 FROM pg_catalog.pg_attrdef d
            WHERE d.adrelid = a.attrelid AND d.adnum = a.attnum
         )
    INTO v_type, v_not_null, v_default
    FROM pg_catalog.pg_attribute a
   WHERE a.attrelid = 'public.entries'::pg_catalog.regclass
     AND a.attname = 'revision' AND a.attnum > 0 AND NOT a.attisdropped;
  IF v_type IS DISTINCT FROM 'pg_catalog.int8'::pg_catalog.regtype
     OR v_not_null IS DISTINCT FROM true
     OR v_default IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'revision must remain bigint NOT NULL with a default';
  END IF;
END;
$fast_default$;
ROLLBACK;
\echo 'Existing-row fast-default and NOT NULL assertions passed.'
