-- Diarium: bump entries.revision only when diary content changes.
--
-- Why: the Android screen-time worker updates phone_screen_time, phone_unlocks
-- and phone_top_apps for the last days on every app start. The old trigger
-- bumped revision on any UPDATE, so a phone-only write made every open draft
-- look stale and blocked its save with a false conflict.
--
-- Contract after this migration:
--   INSERT                      -> revision = 1
--   UPDATE of content columns   -> revision = OLD.revision + 1
--   UPDATE of phone columns only-> revision unchanged
--   UPDATE with identical values-> revision unchanged
--
-- Content columns are the ones a person edits in the diary. Phone columns are
-- device measurements; losing a race on them is harmless, so they must not
-- invalidate a user's draft.

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
  ELSIF (NEW.mood, NEW.mood_emoji, NEW.sleep_quality, NEW.stress,
         NEW.activities, NEW.habits, NEW.gratitude, NEW.note,
         NEW.weather, NEW.photo_path, NEW.ai_reflection, NEW.scale_values)
        IS DISTINCT FROM
        (OLD.mood, OLD.mood_emoji, OLD.sleep_quality, OLD.stress,
         OLD.activities, OLD.habits, OLD.gratitude, OLD.note,
         OLD.weather, OLD.photo_path, OLD.ai_reflection, OLD.scale_values)
  THEN
    NEW.revision := OLD.revision + 1;
  ELSE
    NEW.revision := OLD.revision;
  END IF;
  RETURN NEW;
END;
$revision$;

COMMIT;
