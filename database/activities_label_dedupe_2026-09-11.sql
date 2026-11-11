-- 2026-09-11 — Activity/habit label hygiene: kill the "Hacking" / "hacking " duplicate
--
-- Symptom (reported from the Android app): the check-in showed the activity
-- "Hacking" twice, once capitalised and once lower-case.
--
-- Root cause (verified in this database):
--   * user_activities held TWO rows for the same activity:
--       key 'hacking'  label 'Hacking'   (from a clean add)
--       key 'hacking_' label 'hacking '  (from an add where the typed label had a
--                                         trailing space: the client slugified
--                                         name.toLowerCase().replace(/\s+/g,"_")
--                                         without trimming -> key 'hacking_',
--                                         so the upsert on (user_id, key) missed
--                                         the existing row and inserted a new one)
--   * entries store activities as a JSON array of LABEL strings; all 8 affected
--     entries use 'Hacking' (capitalised), none use the trailing-space variant,
--     so the duplicate row is safe to delete.
--
-- Fix: (1) drop the duplicate row, (2) make the label unique per user,
-- case- and whitespace-insensitively, so the same mistake can never silently
-- create a second activity again, (3) normalise whitespace on every write.
--
-- The write path was fixed in the same change (src/app/api/manage-activities/route.ts
-- trims and merges case/whitespace variants, src/components/OnePageCheckIn.tsx trims
-- before slugifying).

BEGIN;

-- 1. Remove the duplicate (nothing references its label or key).
DELETE FROM public.user_activities
WHERE key = 'hacking_'
  AND lower(btrim(label)) = 'hacking'
  AND NOT EXISTS (
    SELECT 1 FROM public.entries e
    WHERE e.activities @> to_jsonb(btrim(public.user_activities.label)::text)
       OR e.activities @> to_jsonb(public.user_activities.key::text)
  );

-- 2. Belt and braces: one activity per user per label, ignoring case and
--    surrounding whitespace.
CREATE UNIQUE INDEX IF NOT EXISTS user_activities_label_ci_uq
  ON public.user_activities (user_id, lower(btrim(label)));

-- 3. Trim key/label (and habit labels) on every write, so stray spaces cannot
--    reach the tables in the first place.
CREATE OR REPLACE FUNCTION public.normalize_activity_text()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.key   := btrim(NEW.key);
  NEW.label := btrim(NEW.label);
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_user_activities_normalize ON public.user_activities;
CREATE TRIGGER trg_user_activities_normalize
  BEFORE INSERT OR UPDATE ON public.user_activities
  FOR EACH ROW EXECUTE FUNCTION public.normalize_activity_text();

DROP TRIGGER IF EXISTS trg_user_habits_normalize ON public.user_habits;
CREATE TRIGGER trg_user_habits_normalize
  BEFORE INSERT OR UPDATE ON public.user_habits
  FOR EACH ROW EXECUTE FUNCTION public.normalize_activity_text();

-- 4. Same guarantee for habits (no duplicates today, kept symmetric on purpose).
CREATE UNIQUE INDEX IF NOT EXISTS user_habits_label_ci_uq
  ON public.user_habits (user_id, lower(btrim(label)));

COMMIT;
