-- =====================================================================
-- Diarium — business logic in Postgres  (§7.3.1 of the native Android plan)
-- File:   database/rpc_business_logic_2026-09-10.sql
-- Author: assistant, 2026-09-10
--
-- WHY: the daily-entry write path (normalise payload -> upsert entries ->
-- mirror scale_values) and the achievement unlock logic currently live only
-- in TypeScript (src/app/api/save-entry/route.ts + src/lib/achievements.ts).
-- The native Android app needs the same rules, and a second copy of them
-- written in Kotlin would drift. So the rules move here once, and both
-- clients call these functions.
--
-- ADDITIVE BY DESIGN: this migration creates functions and triggers only.
-- Nothing in the running web app calls them yet — switching the callers is a
-- separate, separately verified step (plan §7.3.1). Until then the web path
-- behaves exactly as before.
--
-- Behaviour of both functions was transcribed 1:1 from TypeScript by reading
-- the source, not from memory. Where SQL cannot reproduce JS exactly, the
-- divergence is documented inline with a `PARITY:` note.
--
-- INTENTIONAL DIVERGENCE (check_achievements only — verified, not assumed):
-- the TypeScript reads its inputs with `.order("date",{ascending:true})
-- .limit(10000)`, but Supabase's API-level row cap (db-max-rows, default 1000)
-- silently truncates that to the OLDEST 1000 rows. Measured 2026-09-10 against
-- the real account (1214 entries): early_bird over the whole table = 1 — three
-- entries genuinely exist whose Europe/Prague hour is 0, 2 and 6 — while over
-- the first 1000 rows by date asc = 0, which is exactly the value the TS wrote
-- into the achievements table. check_achievements() reads ALL of the user's
-- entries and therefore reports 1. That is the correct value.
-- Preserving a silent truncation bug is not worth it; if bug-for-bug parity is
-- ever wanted, add `limit 1000` to the entries CTE in §3.2.
--
-- SECURITY: both functions are SECURITY DEFINER (same convention as the
-- existing public.handle_new_user). A DEFINER function bypasses RLS, so RLS
-- must NOT be relied on as the guard — the authorization check is therefore
-- explicit and inside the function (see §2.2 / §3.1). EXECUTE is revoked from
-- PUBLIC/anon and granted only to authenticated + service_role.
--
-- ROLLBACK (functions + triggers only, no data is touched by this file):
--   drop function if exists public.save_daily_entry(jsonb);
--   drop function if exists public.check_achievements(uuid, boolean, boolean);
--   drop trigger if exists trg_entries_updated_at on public.entries;
--   drop trigger if exists trg_profiles_updated_at on public.profiles;
--   drop trigger if exists trg_push_tokens_updated_at on public.push_tokens;
--   drop function if exists public.set_updated_at();
-- =====================================================================


-- =====================================================================
-- §1  set_updated_at() + triggers
-- =====================================================================
-- Currently no table in `public` has a trigger, so every writer has to set
-- updated_at by hand (push/subscribe does; the entries upsert does not).
--
-- PARITY: this is the ONE intentional behaviour change in this file. Before,
-- entries.updated_at was written once at insert and never refreshed. After,
-- it tracks the last write. Verified harmless: a grep of /root/diarium/src
-- shows nothing reads entries.updated_at (only push_tokens.updated_at is
-- written by push/subscribe, and app_settings is read in ai-config.ts).
-- If you ever want the old behaviour back, drop trg_entries_updated_at.
-- =====================================================================
create or replace function public.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

comment on function public.set_updated_at() is
  'BEFORE UPDATE trigger: stamps updated_at = now(). No-op on INSERT (column default already handles it).';

drop trigger if exists trg_entries_updated_at on public.entries;
create trigger trg_entries_updated_at
  before update on public.entries
  for each row execute function public.set_updated_at();

drop trigger if exists trg_profiles_updated_at on public.profiles;
create trigger trg_profiles_updated_at
  before update on public.profiles
  for each row execute function public.set_updated_at();

drop trigger if exists trg_push_tokens_updated_at on public.push_tokens;
create trigger trg_push_tokens_updated_at
  before update on public.push_tokens
  for each row execute function public.set_updated_at();


-- =====================================================================
-- §2  save_daily_entry(p_payload jsonb) -> public.entries
-- =====================================================================
-- 1:1 replacement for POST /api/save-entry (src/app/api/save-entry/route.ts).
--
-- The argument is the raw JSON request body, not named parameters: the route's
-- contract is a *partial* upsert ("only keys actually present are written;
-- absent keys are left untouched"), and that presence test is only expressible
-- in SQL if the payload arrives as jsonb (with named params, an absent column
-- and an explicit NULL are indistinguishable).
--
-- Caller contract (transcribe of route.ts:11-32):
--   * authenticated callers (browser / Android with a user JWT): p_payload
--     .user_id MUST equal auth.uid(), otherwise 42501 (route returns 403).
--   * server callers (service_role, cron, backfill): pass .user_id explicitly.
--     auth.uid() is NULL for those, which is how the function tells them apart.
--
-- Error mapping for an HTTP wrapper (not wired up yet):
--   22023 -> 400   (missing/invalid user_id or date, bad numeric column)
--   42501 -> 403   (user_id mismatch)
--   23514 -> 400   (CHECK violation, e.g. mood outside 1..5)
--   others -> 400/500 as today
--
-- Returns the full resulting row, like `.select().single()` did.
-- =====================================================================

create or replace function public.save_daily_entry(p_payload jsonb)
returns public.entries
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_actor   uuid;
  v_uid     uuid;
  v_uid_txt text;
  v_date    date;
  v_row     public.entries;

  -- presence flags: "the key exists in the payload". For most columns the JS
  -- contract is `payload.x || default`, so a JSON null behaves like absent.
  v_p_mood       boolean;
  v_p_emoji      boolean;
  v_p_sleep      boolean;
  v_p_stress     boolean;
  v_p_activities boolean;
  v_p_habits     boolean;
  v_p_gratitude  boolean;
  v_p_note       boolean;
  v_p_weather    boolean;
  v_p_photo      boolean;
  v_p_screen     boolean;
  v_p_unlocks    boolean;
  v_p_apps       boolean;
  v_p_reflection boolean;
  v_p_scaleval   boolean;

  -- normalised values (NULL = "column untouched / DB default" on insert)
  v_mood       smallint;
  v_emoji      text;
  v_sleep      smallint;
  v_stress     smallint;
  v_activities jsonb;
  v_habits     jsonb;
  v_gratitude  jsonb;
  v_note       text;
  v_weather    jsonb;
  v_photo      text;
  v_screen     integer;
  v_unlocks    integer;
  v_apps       jsonb;
  v_reflection text;
  v_scaleval   jsonb;
begin
  ---------------------------------------------------------------------
  -- 1. payload sanity + authorization
  ---------------------------------------------------------------------
  if p_payload is null or jsonb_typeof(p_payload) <> 'object' then
    raise exception 'save_daily_entry: payload must be a JSON object'
      using errcode = '22023';
  end if;

  v_uid_txt := p_payload->>'user_id';
  if v_uid_txt is null or btrim(v_uid_txt) = '' or p_payload->>'date' is null then
    raise exception 'Missing user_id or date' using errcode = '22023';
  end if;

  begin
    v_uid_txt := btrim(v_uid_txt);
    v_uid     := v_uid_txt::uuid;
    v_date    := (p_payload->>'date')::date;
  exception when invalid_text_representation or datetime_field_overflow then
    raise exception 'save_daily_entry: user_id must be a uuid and date a valid date'
      using errcode = '22023';
  end;

  v_actor := auth.uid();
  if v_actor is not null then
    -- authenticated caller: may only write their own rows, regardless of the
    -- user_id they sent (route.ts:30-32 -> 403 on mismatch).
    if v_uid <> v_actor then
      raise exception 'Forbidden: user_id mismatch' using errcode = '42501';
    end if;
    v_uid := v_actor;
  end if;
  -- else: server-side caller (service_role / SQL). EXECUTE is not granted to
  -- anon, and for `authenticated` PostgREST always resolves auth.uid() from the
  -- JWT `sub` claim, so this branch is unreachable for non-service callers.

  ---------------------------------------------------------------------
  -- 2. column normalisation — transcribed from route.ts:36-64
  ---------------------------------------------------------------------
  v_p_mood       := (p_payload ? 'mood');
  v_p_emoji      := (p_payload ? 'mood_emoji');
  v_p_stress     := (p_payload ? 'stress');
  v_p_activities := (p_payload ? 'activities');
  v_p_habits     := (p_payload ? 'habits');
  v_p_gratitude  := (p_payload ? 'gratitude');
  v_p_note       := (p_payload ? 'note');
  v_p_weather    := (p_payload ? 'weather');
  v_p_photo      := (p_payload ? 'photo_path');
  v_p_screen     := (p_payload ? 'phone_screen_time');
  v_p_unlocks    := (p_payload ? 'phone_unlocks');
  v_p_apps       := (p_payload ? 'phone_top_apps');
  v_p_reflection := (p_payload ? 'ai_reflection');
  v_p_scaleval   := (p_payload ? 'scale_values');

  -- mood / stress: `x > 0 ? x : null` (0, negative, null and non-numeric -> NULL).
  -- PARITY: numbers are cast straight to smallint; a fractional mood (4.5)
  -- rounds here, whereas PostgREST would have let the column cast fail.
  if v_p_mood and (p_payload->>'mood') ~ '^-?[0-9]+(\.[0-9]+)?$' then
    if (p_payload->>'mood')::numeric > 0 then
      v_mood := (p_payload->>'mood')::numeric::smallint;
    end if;
  end if;
  if v_p_stress and (p_payload->>'stress') ~ '^-?[0-9]+(\.[0-9]+)?$' then
    if (p_payload->>'stress')::numeric > 0 then
      v_stress := (p_payload->>'stress')::numeric::smallint;
    end if;
  end if;

  -- mood_emoji: `mood_emoji || ''` — keep a non-empty string, else ''.
  v_emoji := case
    when jsonb_typeof(p_payload->'mood_emoji') = 'string'
     and (p_payload->>'mood_emoji') <> ''
      then p_payload->>'mood_emoji'
    else ''
  end;

  -- sleep_quality: written ONLY when present AND > 0 (route.ts:47). A present
  -- 0 is silently ignored, i.e. the existing column is left alone — NOT nulled.
  v_p_sleep := (p_payload ? 'sleep_quality')
               and (p_payload->>'sleep_quality') ~ '^-?[0-9]+(\.[0-9]+)?$'
               and (p_payload->>'sleep_quality')::numeric > 0;
  if v_p_sleep then
    v_sleep := (p_payload->>'sleep_quality')::numeric::smallint;
  end if;

  -- activities / habits / gratitude / weather / note: `x || default`.
  v_activities := case when jsonb_typeof(p_payload->'activities') = 'null'
                       then '[]'::jsonb else p_payload->'activities' end;
  v_habits     := case when jsonb_typeof(p_payload->'habits') = 'null'
                       then '{}'::jsonb else p_payload->'habits' end;
  v_gratitude  := case when jsonb_typeof(p_payload->'gratitude') = 'null'
                       then '[]'::jsonb else p_payload->'gratitude' end;
  v_weather    := case when jsonb_typeof(p_payload->'weather') = 'null'
                       then '[]'::jsonb else p_payload->'weather' end;
  v_note       := case when jsonb_typeof(p_payload->'note') = 'string'
                       then p_payload->>'note'
                       when jsonb_typeof(p_payload->'note') in ('number','boolean')
                       then p_payload->>'note'
                       else '' end;

  -- photo_path: `!== undefined && truthy` -> '' is ignored (column untouched).
  v_p_photo := v_p_photo
    and jsonb_typeof(p_payload->'photo_path') = 'string'
    and (p_payload->>'photo_path') <> '';
  v_photo := case when v_p_photo then p_payload->>'photo_path' else null end;

  -- ai_reflection: same truthy rule (route.ts:63).
  v_p_reflection := v_p_reflection
    and jsonb_typeof(p_payload->'ai_reflection') = 'string'
    and (p_payload->>'ai_reflection') <> '';
  v_reflection := case when v_p_reflection then p_payload->>'ai_reflection' else null end;

  -- phone_screen_time / phone_unlocks: passed through unchanged, NULL included.
  if v_p_screen then
    if jsonb_typeof(p_payload->'phone_screen_time') = 'null' then
      v_screen := null;
    elsif (p_payload->>'phone_screen_time') ~ '^-?[0-9]+$' then
      v_screen := (p_payload->>'phone_screen_time')::integer;
    else
      raise exception 'save_daily_entry: phone_screen_time must be an integer'
        using errcode = '22023';
    end if;
  end if;
  if v_p_unlocks then
    if jsonb_typeof(p_payload->'phone_unlocks') = 'null' then
      v_unlocks := null;
    elsif (p_payload->>'phone_unlocks') ~ '^-?[0-9]+$' then
      v_unlocks := (p_payload->>'phone_unlocks')::integer;
    else
      raise exception 'save_daily_entry: phone_unlocks must be an integer'
        using errcode = '22023';
    end if;
  end if;

  -- phone_top_apps: array -> as-is (even empty); non-empty string -> parsed
  -- "App:minutes,App2:minutes" -> [{app, time_sec}], dropping empty app names
  -- and non-positive times; empty string / number / object / null -> key
  -- omitted, i.e. column untouched (route.ts:52-62).
  case jsonb_typeof(p_payload->'phone_top_apps')
    when 'array' then
      v_apps := p_payload->'phone_top_apps';
    when 'string' then
      if (p_payload->>'phone_top_apps') <> '' then
        select coalesce(jsonb_agg(jsonb_build_object('app', a.app, 'time_sec', a.secs)), '[]'::jsonb)
          into v_apps
        from (
          select btrim(split_part(btrim(item), ':', 1)) as app,
                 case
                   when btrim(split_part(btrim(item), ':', 2)) ~ '^[+-]?[0-9]+'
                     then regexp_replace(btrim(split_part(btrim(item), ':', 2)),
                                         '^([+-]?[0-9]+).*$', '\1')::integer * 60
                   else 0
                 end as secs
          from unnest(string_to_array(p_payload->>'phone_top_apps', ',')) as item
        ) a
        where a.app <> '' and a.secs > 0;
      else
        v_p_apps := false;   -- '' -> key omitted
      end if;
    else
      v_p_apps := false;     -- number/object/null -> key omitted
  end case;

  -- scale_values: stored verbatim when present, explicit null included.
  v_scaleval := case when jsonb_typeof(p_payload->'scale_values') = 'null'
                     then null else p_payload->'scale_values' end;

  ---------------------------------------------------------------------
  -- 3. upsert entries (onConflict: 'user_id,date', returning the full row)
  --    On INSERT, columns whose key was absent take exactly the DB defaults
  --    the old PostgREST upsert used to produce.
  ---------------------------------------------------------------------
  insert into public.entries as t (
    user_id, date, mood, mood_emoji, sleep_quality, stress,
    activities, habits, gratitude, note, weather, photo_path,
    phone_screen_time, phone_unlocks, phone_top_apps, ai_reflection, scale_values
  ) values (
    v_uid, v_date,
    v_mood,
    case when v_p_emoji then v_emoji else null end,
    v_sleep,
    v_stress,
    case when v_p_activities then v_activities else '[]'::jsonb end,
    case when v_p_habits     then v_habits     else '{}'::jsonb end,
    case when v_p_gratitude  then v_gratitude  else '[]'::jsonb end,
    case when v_p_note       then v_note       else ''::text end,
    case when v_p_weather    then v_weather    else '[]'::jsonb end,
    v_photo,
    v_screen,
    v_unlocks,
    case when v_p_apps       then coalesce(v_apps, '[]'::jsonb) else '[]'::jsonb end,
    v_reflection,
    case when v_p_scaleval   then coalesce(v_scaleval, '{}'::jsonb) else '{}'::jsonb end
  )
  on conflict (user_id, date) do update set
    mood             = case when v_p_mood       then excluded.mood             else t.mood             end,
    mood_emoji       = case when v_p_emoji      then excluded.mood_emoji       else t.mood_emoji       end,
    sleep_quality    = case when v_p_sleep      then excluded.sleep_quality    else t.sleep_quality    end,
    stress           = case when v_p_stress     then excluded.stress           else t.stress           end,
    activities       = case when v_p_activities then excluded.activities       else t.activities       end,
    habits           = case when v_p_habits     then excluded.habits           else t.habits           end,
    gratitude        = case when v_p_gratitude  then excluded.gratitude        else t.gratitude        end,
    note             = case when v_p_note       then excluded.note             else t.note             end,
    weather          = case when v_p_weather    then excluded.weather          else t.weather          end,
    photo_path       = case when v_p_photo      then excluded.photo_path       else t.photo_path       end,
    phone_screen_time= case when v_p_screen     then excluded.phone_screen_time else t.phone_screen_time end,
    phone_unlocks    = case when v_p_unlocks    then excluded.phone_unlocks    else t.phone_unlocks    end,
    phone_top_apps   = case when v_p_apps       then excluded.phone_top_apps   else t.phone_top_apps   end,
    ai_reflection    = case when v_p_reflection then excluded.ai_reflection    else t.ai_reflection    end,
    scale_values     = case when v_p_scaleval   then excluded.scale_values     else t.scale_values     end
  returning * into v_row;

  ---------------------------------------------------------------------
  -- 4. mirror scale_values -> scale_entries (route.ts:79-107)
  --    Only for a real authenticated user (never for a cron/server save),
  --    and a mirror failure must NOT fail the save (it was only logged).
  ---------------------------------------------------------------------
  if v_actor is not null
     and v_p_scaleval
     and jsonb_typeof(p_payload->'scale_values') = 'object'
  then
    begin
      insert into public.scale_entries (user_id, scale_id, date, value)
      select v_uid, s.id, v_date, round((kv.value::text)::numeric)::integer
      from jsonb_each(p_payload->'scale_values') as kv(key, value)
      join public.scales s
        on s.user_id = v_uid
       and s.id::text = kv.key          -- text compare: bad keys are skipped, not cast
      where jsonb_typeof(kv.value) = 'number'   -- note: numeric STRINGS are dropped here
        and (kv.value::text)::numeric > 0       -- 0 / negative dropped
      on conflict (user_id, scale_id, date) do update set value = excluded.value;
      -- PARITY: never deletes a row and never clips to scales.min_value/max_value,
      -- exactly like the current route.
    exception when others then
      raise warning 'save_daily_entry: scale mirror failed (save kept): %', sqlerrm;
    end;
  end if;

  return v_row;
end;
$$;

comment on function public.save_daily_entry(jsonb) is
  'Partial upsert of one daily entry + scale_entries mirror. 1:1 of POST /api/save-entry. SECURITY DEFINER with an explicit auth.uid() check. See database/rpc_business_logic_2026-09-10.sql.';


-- =====================================================================
-- §3  check_achievements(p_user_id uuid, p_has_photo bool, p_has_scale bool)
-- =====================================================================
-- 1:1 replacement for syncAchievements() (src/lib/achievements.ts:262-323).
--
-- The 17 definitions are hardcoded in TS (no catalog table), so the keys and
-- targets are hardcoded here too — keep the two in sync if a definition ever
-- changes. Progress is monotonic: GREATEST(computed, stored) is persisted, so
-- deleting entries can never re-lock an achievement.
--
-- p_has_photo / p_has_scale mirror the `live` argument of the TS function: the
-- check-in handler passes whether the just-submitted form carried a photo /
-- a scale value, which the DB cannot see until the row is written. Callers
-- that only want a DB-derived recompute leave them false (a new save may be
-- reflected one call late for these two keys).
--
-- PARITY notes (deliberate, documented):
--   * early_bird / night_owl use the browser's LOCAL hour in TS. SQL uses
--     Europe/Prague; identical for a user in Prague, different elsewhere.
--     No per-user timezone is stored, so exact parity is impossible.
--   * The TS query caps entries at the OLDEST 10000 rows (ascending + limit);
--     this function reads the whole table. Unobservable below 10000 entries.
--   * use_scale counts a value when Number(v) > 0, so a JSON *string* "3"
--     counts in TS and here (regex on the text form), but a JSON `true` counts
--     in TS (Number(true) === 1) and not here. Absurd input, ignored.
--   * all_moods_week is the LAST 7 ENTRIES by date, not a 7-day window — the
--     TS name/description lies, the behaviour is what is replicated.
-- =====================================================================

create or replace function public.check_achievements(
  p_user_id   uuid    default null,
  p_has_photo boolean default false,
  p_has_scale boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_actor uuid;
  v_uid   uuid;
  v_key   text;
  v_prog  integer;
  v_target integer;

  -- existing row (achievements is keyed by UNIQUE(user_id, achievement_key))
  v_ex_id        uuid;
  v_ex_progress  integer;
  v_ex_unlocked  timestamptz;

  v_final      integer;
  v_is_unlocked boolean;
  v_was_unlocked boolean;

  v_newly    text[] := '{}';
  v_progress jsonb  := '{}'::jsonb;
begin
  ---------------------------------------------------------------------
  -- 3.1 authorization
  ---------------------------------------------------------------------
  v_actor := auth.uid();
  if v_actor is not null then
    if p_user_id is not null and p_user_id <> v_actor then
      raise exception 'Forbidden: user_id mismatch' using errcode = '42501';
    end if;
    v_uid := v_actor;
  else
    v_uid := p_user_id;
  end if;
  if v_uid is null then
    raise exception 'check_achievements: user id is required' using errcode = '22023';
  end if;

  ---------------------------------------------------------------------
  -- 3.2 compute the true progress of every achievement from raw data
  --     (set-based equivalent of computeProgress(), achievements.ts:183-235)
  ---------------------------------------------------------------------
  for v_key, v_prog in
    with e as (
      select date, mood, created_at, photo_path, scale_values, activities
      from public.entries
      where user_id = v_uid
      order by date asc
    ),
    cnt as (select count(*)::integer as n from e),
    -- gaps-and-islands: consecutive dates share (date - row_number)
    d as (select distinct date from e),
    runs as (select date - (row_number() over (order by date))::integer as grp from d),
    streak as (select coalesce(max(c), 0)::integer as mx
               from (select count(*)::integer as c from runs group by grp) s),
    -- "perfect week": a run of >= 7 consecutive days all with mood = 5
    m5 as (select distinct date from public.entries where user_id = v_uid and mood = 5),
    m5runs as (select date - (row_number() over (order by date))::integer as grp from m5),
    perfect as (select coalesce(max(c), 0)::integer >= 7 as ok
                from (select count(*)::integer as c from m5runs group by grp) s),
    -- last 7 ENTRIES (date desc), moods restricted to 1..5, need >= 3 distinct
    last7 as (select mood from e order by date desc limit 7),
    moods as (select count(distinct mood)::integer as c from last7 where mood between 1 and 5),
    photo as (select coalesce(bool_or(photo_path is not null and photo_path <> ''), false) as ok from e),
    scale as (
      select coalesce(bool_or(
        exists (
          select 1
          from jsonb_each_text(case when jsonb_typeof(e.scale_values) = 'object'
                                    then e.scale_values else '{}'::jsonb end) kv
          where kv.value ~ '^-?[0-9]+(\.[0-9]+)?$'
            and kv.value::numeric > 0
        )), false) as ok
      from e
    ),
    goal_n as (select count(*)::integer as n from public.goals where user_id = v_uid),
    goal_done as (
      select coalesce(bool_or(x.ok), false) as ok
      from (
        select exists (
          select 1
          from (
            select (
              select count(*)
              from unnest(a.ds) as x
              where x >= d1 and x < d1 + (case go.frequency
                                            when 'daily'  then 1
                                            when 'weekly' then 7
                                            else 30 end)
            ) as c
            from unnest(a.ds) as d1
          ) z
          where z.c >= go.target_count
        ) as ok
        from public.goals go
        cross join lateral (
          select coalesce(array_agg(e2.date order by e2.date), '{}'::date[]) as ds
          from public.entries e2
          where e2.user_id = v_uid
            and jsonb_typeof(e2.activities) = 'array'
            and e2.activities ? go.activity_key
        ) a
        where go.user_id = v_uid
      ) x
    ),
    weekend as (
      select coalesce(bool_or(exists (
               select 1 from d d2 where d2.date = d1.date + 1
             )), false) as ok
      from d d1
      where extract(dow from d1.date) = 6          -- 6 = Saturday, same as getUTCDay()
    ),
    hours as (
      select
        coalesce(bool_or(extract(hour from (created_at at time zone 'Europe/Prague')) < 9),  false) as early,
        coalesce(bool_or(extract(hour from (created_at at time zone 'Europe/Prague')) >= 23), false) as night
      from e
      where created_at is not null
    )
    select k.key, k.prog
    from (
                select 'first_entry'::text      as key, (case when cnt.n >= 1 then 1 else 0 end) as prog from cnt
      union all select 'entries_10',                 least(cnt.n, 10)                            from cnt
      union all select 'entries_100',                least(cnt.n, 100)                           from cnt
      union all select 'entries_365',                least(cnt.n, 365)                           from cnt
      union all select 'streak_7',                   least(streak.mx, 7)                         from streak
      union all select 'streak_30',                  least(streak.mx, 30)                        from streak
      union all select 'streak_100',                 least(streak.mx, 100)                       from streak
      union all select 'streak_365',                 least(streak.mx, 365)                       from streak
      union all select 'use_scale',                  (case when scale.ok or p_has_scale then 1 else 0 end)   from scale
      union all select 'add_photo',                  (case when photo.ok or p_has_photo then 1 else 0 end)   from photo
      union all select 'create_goal',                (case when goal_n.n >= 1 then 1 else 0 end) from goal_n
      union all select 'complete_goal',              (case when goal_done.ok then 1 else 0 end)  from goal_done
      union all select 'all_moods_week',             (case when moods.c >= 3 then 1 else 0 end)  from moods
      union all select 'perfect_week',               (case when perfect.ok then 1 else 0 end)    from perfect
      union all select 'early_bird',                 (case when hours.early then 1 else 0 end)   from hours
      union all select 'night_owl',                  (case when hours.night then 1 else 0 end)   from hours
      union all select 'weekend_warrior',            (case when weekend.ok then 1 else 0 end)    from weekend
    ) k
  loop
    v_target := case v_key
      when 'first_entry' then 1
      when 'entries_10'  then 10
      when 'entries_100' then 100
      when 'entries_365' then 365
      when 'streak_7'    then 7
      when 'streak_30'   then 30
      when 'streak_100'  then 100
      when 'streak_365'  then 365
      else 1                    -- use_scale, add_photo, create_goal, complete_goal,
    end;                        -- all_moods_week, perfect_week, early_bird, night_owl, weekend_warrior

    -------------------------------------------------------------------
    -- 3.3 persist, monotonic and idempotent (achievements.ts:292-320)
    -------------------------------------------------------------------
    v_ex_id := null; v_ex_progress := null; v_ex_unlocked := null;
    select a.id, a.progress, a.unlocked_at
      into v_ex_id, v_ex_progress, v_ex_unlocked
    from public.achievements a
    where a.user_id = v_uid and a.achievement_key = v_key;

    v_final        := greatest(v_prog, coalesce(v_ex_progress, 0));
    v_is_unlocked  := v_final >= v_target;
    v_was_unlocked := v_ex_id is not null
                      and (coalesce(v_ex_progress, 0) >= v_target or v_ex_unlocked is not null);

    if v_ex_id is null then
      insert into public.achievements (user_id, achievement_key, progress, target, unlocked_at)
      values (v_uid, v_key, v_final, v_target,
              case when v_is_unlocked then now() else null end);
      if v_is_unlocked then v_newly := v_newly || v_key; end if;

    elsif v_final <> coalesce(v_ex_progress, 0) then
      update public.achievements
         set progress   = v_final,
             unlocked_at = case when v_is_unlocked and v_ex_unlocked is null
                                then now() else v_ex_unlocked end
       where id = v_ex_id;
      if v_is_unlocked and not v_was_unlocked then
        v_newly := v_newly || v_key;
      end if;
    end if;

    v_progress := v_progress || jsonb_build_object(v_key, v_final);
  end loop;

  -- TS returns the *computed* map; this returns the persisted (monotonic)
  -- values, which is what callers actually want. Nothing reads either yet.
  return jsonb_build_object(
    'newly_unlocked', coalesce(to_jsonb(v_newly), '[]'::jsonb),
    'progress',       v_progress
  );
end;
$$;

comment on function public.check_achievements(uuid, boolean, boolean) is
  'Recompute + persist all 17 achievement progress values. 1:1 of syncAchievements() in src/lib/achievements.ts. Monotonic (GREATEST vs stored). SECURITY DEFINER with an explicit auth.uid() check.';


-- =====================================================================
-- §4  grants — a DEFINER function must not be callable by anon
-- =====================================================================
revoke all on function public.save_daily_entry(jsonb) from public;
revoke all on function public.save_daily_entry(jsonb) from anon;
grant execute on function public.save_daily_entry(jsonb) to authenticated, service_role;

revoke all on function public.check_achievements(uuid, boolean, boolean) from public;
revoke all on function public.check_achievements(uuid, boolean, boolean) from anon;
grant execute on function public.check_achievements(uuid, boolean, boolean) to authenticated, service_role;


-- =====================================================================
-- §5  post-apply verification (read-only, safe to run any time)
-- =====================================================================
-- -- triggers present?
-- select tgname, tgrelid::regclass as tbl, tgenabled
--   from pg_trigger where not tgisinternal and tgname like 'trg_%_updated_at';
--
-- -- both functions exist, owned by a superuser-ish role, search_path pinned?
-- select p.proname, pg_get_userbyid(p.proowner) as owner, p.prosecdef,
--        p.proconfig
--   from pg_proc p join pg_namespace n on n.oid = p.pronamespace
--  where n.nspname = 'public' and p.proname in ('save_daily_entry','check_achievements','set_updated_at');
--
-- -- grants are authenticated+service_role only
-- select grantee, privilege_type
--   from information_schema.routine_privileges
--  where routine_schema = 'public' and routine_name in ('save_daily_entry','check_achievements')
--  order by routine_name, grantee;
--
-- -- parity cross-check against the stored rows is in the test script
-- -- (database/tests/rpc_business_logic_test_2026-09-10.sql): it compares this
-- -- function's output with the progress the TypeScript implementation already
-- -- persisted, which is the strongest oracle available.
