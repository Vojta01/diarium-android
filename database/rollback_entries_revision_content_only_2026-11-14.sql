-- Rollback for entries_revision_content_only_2026-11-14.sql.
-- Restores the previous contract: every UPDATE bumps revision.
-- Use only when the content-only contract causes a verified regression.

BEGIN;

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

COMMIT;
