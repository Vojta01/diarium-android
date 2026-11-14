-- Contract test for entries_revision_content_only_2026-11-14.sql.
-- Run after the migration on a disposable PostgreSQL instance.
-- Fails closed: any mismatch raises and psql exits non-zero.

\set ON_ERROR_STOP on

DROP TABLE IF EXISTS public.entries CASCADE;

CREATE TABLE public.entries (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  date date NOT NULL,
  mood smallint,
  mood_emoji text,
  sleep_quality smallint,
  stress smallint,
  activities jsonb,
  habits jsonb,
  gratitude jsonb,
  note text,
  weather jsonb,
  photo_path text,
  phone_screen_time integer,
  phone_unlocks integer,
  phone_top_apps jsonb,
  ai_reflection text,
  scale_values jsonb,
  revision bigint NOT NULL DEFAULT 1
);

CREATE TRIGGER entries_revision_v2
BEFORE INSERT OR UPDATE ON public.entries
FOR EACH ROW EXECUTE FUNCTION public.diarium_entry_revision_v2();

INSERT INTO public.entries (user_id, date, mood, note)
VALUES ('00000000-0000-0000-0000-000000000001', '2026-11-13', 2, 'prvni zapis');

DO $$
DECLARE
  v_revision bigint;
BEGIN
  SELECT revision INTO v_revision FROM public.entries WHERE date = '2026-11-13';
  IF v_revision <> 1 THEN
    RAISE EXCEPTION 'insert must set revision 1, got %', v_revision;
  END IF;
END;
$$;

-- A phone-only write must not invalidate a draft.
UPDATE public.entries
   SET phone_screen_time = 15083, phone_unlocks = 42, phone_top_apps = '[]'::jsonb
 WHERE date = '2026-11-13';

DO $$
DECLARE
  v_revision bigint;
BEGIN
  SELECT revision INTO v_revision FROM public.entries WHERE date = '2026-11-13';
  IF v_revision <> 1 THEN
    RAISE EXCEPTION 'phone-only update must keep revision 1, got %', v_revision;
  END IF;
END;
$$;

-- A content write must bump the revision.
UPDATE public.entries SET note = 'opraveno' WHERE date = '2026-11-13';

DO $$
DECLARE
  v_revision bigint;
BEGIN
  SELECT revision INTO v_revision FROM public.entries WHERE date = '2026-11-13';
  IF v_revision <> 2 THEN
    RAISE EXCEPTION 'content update must set revision 2, got %', v_revision;
  END IF;
END;
$$;

-- Writing the same content again is not a new version.
UPDATE public.entries SET note = 'opraveno' WHERE date = '2026-11-13';

DO $$
DECLARE
  v_revision bigint;
BEGIN
  SELECT revision INTO v_revision FROM public.entries WHERE date = '2026-11-13';
  IF v_revision <> 2 THEN
    RAISE EXCEPTION 'identical content update must keep revision 2, got %', v_revision;
  END IF;
END;
$$;

-- Mixed write counts as content.
UPDATE public.entries
   SET phone_screen_time = 1, mood = 5
 WHERE date = '2026-11-13';

DO $$
DECLARE
  v_revision bigint;
BEGIN
  SELECT revision INTO v_revision FROM public.entries WHERE date = '2026-11-13';
  IF v_revision <> 3 THEN
    RAISE EXCEPTION 'mixed update must set revision 3, got %', v_revision;
  END IF;
END;
$$;

-- JSONB content counts too.
UPDATE public.entries
   SET gratitude = '["rodina"]'::jsonb
 WHERE date = '2026-11-13';

DO $$
DECLARE
  v_revision bigint;
BEGIN
  SELECT revision INTO v_revision FROM public.entries WHERE date = '2026-11-13';
  IF v_revision <> 4 THEN
    RAISE EXCEPTION 'jsonb content update must set revision 4, got %', v_revision;
  END IF;
END;
$$;

SELECT 'entries revision content-only contract: OK' AS result;
