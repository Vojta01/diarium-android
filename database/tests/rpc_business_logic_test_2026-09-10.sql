-- =====================================================================
-- Diarium — verification for database/rpc_business_logic_2026-09-10.sql
--
-- Runs EVERYTHING inside one transaction and ROLLS BACK at the end, so it
-- cannot touch production data. Verified: nothing in this file commits.
--
--   psql "$SUPABASE_DB_URL" -f database/tests/rpc_business_logic_test_2026-09-10.sql
--
-- What it proves:
--   A. check_achievements() agrees with the progress the TypeScript
--      implementation already persisted (strongest available oracle) and with
--      a literal, loop-by-loop transcription of the JS (independent of the
--      set-based SQL being tested).
--   B. save_daily_entry() reproduces the partial-upsert contract, the
--      normalisation rules, the scale mirror and the authorization rules.
-- =====================================================================
\set ON_ERROR_STOP on

begin;

\echo '### applying migration (inside the transaction)'
\i /root/diarium/database/rpc_business_logic_2026-09-10.sql

-- the account with the most entries = the single real user; never hardcode it
select user_id as uid, count(*) as n_entries
  from public.entries group by user_id order by 2 desc limit 1 \gset
\echo '-- parity subject:' :uid '(entries:' :n_entries ')'

\echo ''
\echo '### objects created'
select p.proname                        as function,
       pg_get_userbyid(p.proowner)      as owner,
       p.prosecdef                      as security_definer,
       coalesce(array_to_string(p.proconfig, ','), '(none)') as config
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public'
   and p.proname in ('save_daily_entry', 'check_achievements', 'set_updated_at')
 order by p.proname;

select tgname as trigger, tgrelid::regclass as tbl, tgenabled as enabled
  from pg_trigger where not tgisinternal and tgname like 'trg\_%\_updated\_at' escape '\'
 order by 2;

select grantee, privilege_type
  from information_schema.routine_privileges
 where routine_schema = 'public'
   and routine_name in ('save_daily_entry', 'check_achievements')
 order by 1, 2;

\echo ''
\echo '### auth.uid() plumbing (sanity: we can impersonate a user)'
select set_config('request.jwt.claims',
                  json_build_object('sub', :'uid', 'role', 'authenticated')::text,
                  true) is not null as claims_set;
select auth.uid() as auth_uid_should_be_the_user;
select set_config('request.jwt.claims', '', true) is not null as claims_cleared;
-- ---------------------------------------------------------------------
-- PARITY ORACLE 1: a literal, loop-by-loop transcription of the JavaScript
-- in src/lib/achievements.ts. Deliberately implemented with *different* SQL
-- constructs than the function under test (plpgsql loops + array scans instead
-- of set-based CTEs, jsonb_array_elements_text instead of the `?` operator,
-- `#>> '{}'` + a strict numeric lexeme test instead of a regex on
-- jsonb_each_text), so a shared bug is unlikely.
-- ---------------------------------------------------------------------
create or replace function pg_temp.test_js_progress(p_uid uuid)
returns jsonb
language plpgsql
as $fn$
declare
  v_n       int;
  v_dates   date[];
  v_moods   smallint[];
  v_created timestamptz[];
  v_photos  text[];
  v_sv      jsonb[];
  v_acts    jsonb[];
  v_res     jsonb := '{}'::jsonb;
  v_uniq    date[];
  v_max     int;
  v_cur     int;
  v_runs    int;
  v_last7   int[];
  v_distinct int;
  v_ok      boolean;
  v_g       record;
  v_gdates  date[];
  v_w       int;
  v_cnt     int;
  v_best    int;
  i         int;
  j         int;
  v_flag    boolean;
begin
  select array_agg(x.date), array_agg(x.mood), array_agg(x.created_at),
         array_agg(x.photo_path), array_agg(x.scale_values), array_agg(x.activities)
    into v_dates, v_moods, v_created, v_photos, v_sv, v_acts
    from (select * from public.entries where user_id = p_uid
           order by date asc limit 10000) x;

  v_n := coalesce(array_length(v_dates, 1), 0);

  -- counts
  v_res := v_res || jsonb_build_object('first_entry', case when v_n >= 1 then 1 else 0 end);
  v_res := v_res || jsonb_build_object('entries_10',  least(v_n, 10));
  v_res := v_res || jsonb_build_object('entries_100', least(v_n, 100));
  v_res := v_res || jsonb_build_object('entries_365', least(v_n, 365));

  -- computeMaxStreak(): consecutive calendar days among the (unique, sorted) dates
  v_max := 0;
  v_cur := 0;
  if v_n > 0 then
    v_max := 1;
    v_cur := 1;
    for i in 2 .. v_n loop
      if (v_dates[i] - v_dates[i - 1]) = 1 then
        v_cur := v_cur + 1;
        if v_cur > v_max then v_max := v_cur; end if;
      else
        v_cur := 1;
      end if;
    end loop;
  end if;
  v_res := v_res || jsonb_build_object('streak_7',   least(v_max, 7));
  v_res := v_res || jsonb_build_object('streak_30',  least(v_max, 30));
  v_res := v_res || jsonb_build_object('streak_100', least(v_max, 100));
  v_res := v_res || jsonb_build_object('streak_365', least(v_max, 365));

  -- hasPerfectWeek(): run of >= 7 consecutive days with mood === 5
  v_uniq := '{}';
  for i in 1 .. v_n loop
    if v_moods[i] = 5 then v_uniq := v_uniq || v_dates[i]; end if;
  end loop;
  v_ok := false;
  if coalesce(array_length(v_uniq, 1), 0) >= 7 then
    v_runs := 1;
    for i in 2 .. array_length(v_uniq, 1) loop
      if (v_uniq[i] - v_uniq[i - 1]) = 1 then
        v_runs := v_runs + 1;
        if v_runs >= 7 then v_ok := true; exit; end if;
      else
        v_runs := 1;
      end if;
    end loop;
  end if;
  v_res := v_res || jsonb_build_object('perfect_week', case when v_ok then 1 else 0 end);

  -- all_moods_week: the LAST 7 ENTRIES, distinct moods inside 1..5, need >= 3
  v_last7 := '{}';
  for i in greatest(1, v_n - 6) .. v_n loop
    v_last7 := v_last7 || v_moods[i]::int;
  end loop;
  select count(distinct m) into v_distinct from unnest(v_last7) m where m between 1 and 5;
  v_res := v_res || jsonb_build_object('all_moods_week',
             case when coalesce(v_distinct, 0) >= 3 then 1 else 0 end);

  -- add_photo: any entry with a truthy photo_path
  v_ok := false;
  for i in 1 .. v_n loop
    if v_photos[i] is not null and v_photos[i] <> '' then v_ok := true; exit; end if;
  end loop;
  v_res := v_res || jsonb_build_object('add_photo', case when v_ok then 1 else 0 end);

  -- use_scale: any entry whose scale_values object holds a value with Number(v) > 0
  -- (JS Number() semantics: numbers pass, numeric strings pass, true -> 1)
  v_ok := false;
  <<svloop>>
  for i in 1 .. v_n loop
    if jsonb_typeof(v_sv[i]) = 'object' then
      for v_g in select value from jsonb_each(v_sv[i]) loop
        -- NB: plpgsql cannot parse `IF case ... THEN ...` inside an IF condition --
        -- it takes the inner THEN as the end of the condition (verified on PG 17).
        -- So the CASE is always evaluated into a variable first.
        v_flag := case jsonb_typeof(v_g.value)
             when 'number' then (v_g.value #>> '{}')::numeric > 0
             when 'string' then case
                 when trim(v_g.value #>> '{}') ~ '^[+-]?([0-9]+(\.[0-9]+)?|\.[0-9]+)([eE][+-]?[0-9]+)?$'
                   then trim(v_g.value #>> '{}')::numeric > 0
                 else false end
             when 'boolean' then (v_g.value #>> '{}') = 'true'
             else false
           end;
        if v_flag then v_ok := true; exit svloop; end if;
      end loop;
    end if;
  end loop;
  v_res := v_res || jsonb_build_object('use_scale', case when v_ok then 1 else 0 end);

  -- create_goal / goalEverCompleted() -> complete_goal
  v_ok := false;
  for v_g in select activity_key, target_count, frequency
              from public.goals where user_id = p_uid loop
    v_gdates := '{}';
    for i in 1 .. v_n loop
      if jsonb_typeof(v_acts[i]) = 'array'
         and exists (select 1 from jsonb_array_elements_text(v_acts[i]) el
                      where el = v_g.activity_key) then
        v_gdates := v_gdates || v_dates[i];
      end if;
    end loop;
    v_w := case v_g.frequency when 'daily' then 1 when 'weekly' then 7 else 30 end;
    v_best := 0;                                  -- maxCountInWindow()
    for i in 1 .. coalesce(array_length(v_gdates, 1), 0) loop
      v_cnt := 0;
      for j in i .. array_length(v_gdates, 1) loop
        if (v_gdates[j] - v_gdates[i]) < v_w then v_cnt := v_cnt + 1; else exit; end if;
      end loop;
      if v_cnt > v_best then v_best := v_cnt; end if;
    end loop;
    if v_best >= coalesce(v_g.target_count, 0) then v_ok := true; exit; end if;
  end loop;
  v_res := v_res || jsonb_build_object('create_goal',
             case when (select count(*) from public.goals where user_id = p_uid) >= 1
                  then 1 else 0 end);
  v_res := v_res || jsonb_build_object('complete_goal', case when v_ok then 1 else 0 end);

  -- hasWeekendPair(): a Saturday whose immediately following day also has an entry
  v_ok := false;
  for i in 1 .. v_n loop
    if extract(dow from v_dates[i]) = 6 and (v_dates[i] + 1) = any(v_dates) then
      v_ok := true; exit;
    end if;
  end loop;
  v_res := v_res || jsonb_build_object('weekend_warrior', case when v_ok then 1 else 0 end);

  -- early_bird / night_owl: local wall-clock hour (Europe/Prague in SQL)
  v_ok := false;
  for i in 1 .. v_n loop
    if v_created[i] is not null
       and extract(hour from (v_created[i] at time zone 'Europe/Prague')) < 9 then
      v_ok := true; exit;
    end if;
  end loop;
  v_res := v_res || jsonb_build_object('early_bird', case when v_ok then 1 else 0 end);

  v_ok := false;
  for i in 1 .. v_n loop
    if v_created[i] is not null
       and extract(hour from (v_created[i] at time zone 'Europe/Prague')) >= 23 then
      v_ok := true; exit;
    end if;
  end loop;
  v_res := v_res || jsonb_build_object('night_owl', case when v_ok then 1 else 0 end);

  return v_res;
end;
$fn$;

select auth.uid() as auth_uid_should_be_null;

\echo ''
\echo '### A1. parity: set-based function vs literal JS transcription vs stored (TS-written) rows'
with fn as (
  select k.key, (k.value)::int as fn_progress
    from jsonb_each_text(public.check_achievements(:'uid'::uuid) -> 'progress') k
),
js as (
  select k.key, (k.value)::int as js_progress
    from jsonb_each_text(pg_temp.test_js_progress(:'uid'::uuid)) k
)
select coalesce(fn.key, js.key) as achievement,
       fn.fn_progress,
       js.js_progress,
       a.progress as stored_ts,
       case when fn.fn_progress is not distinct from js.js_progress
            then 'OK' else 'DIFF <<<' end as fn_vs_js,
       case when a.progress is null then 'no row'
            when fn.fn_progress >= a.progress then 'OK'
            else 'DIFF <<<' end as fn_vs_stored
  from fn full join js on js.key = fn.key
  left join public.achievements a
         on a.user_id = :'uid'::uuid
        and a.achievement_key = coalesce(fn.key, js.key)
 order by 1;

\echo ''
\echo '### A2. totals (expect 17 rows, 0 DIFF)'
with fn as (
  select k.key, (k.value)::int as p
    from jsonb_each_text(public.check_achievements(:'uid'::uuid) -> 'progress') k
),
js as (
  select k.key, (k.value)::int as p
    from jsonb_each_text(pg_temp.test_js_progress(:'uid'::uuid)) k
)
select count(*) as keys,
       count(*) filter (where fn.p is distinct from js.p) as diffs_fn_vs_js
  from fn full join js on js.key = fn.key;

\echo ''
\echo '### B. save_daily_entry(): partial upsert, normalisation, scale mirror, authz'
select (select count(*) from public.entries)      as entries_before,
       (select count(*) from public.scale_entries) as scale_entries_before;

-- resolve the real scale ids instead of hardcoding (order by name: Energie, Produktivita)
select s.id as scale1_id from public.scales s where s.user_id = :'uid'::uuid order by s.name asc limit 1 \gset
select s.id as scale2_id from public.scales s where s.user_id = :'uid'::uuid order by s.name asc offset 1 limit 1 \gset
\echo '-- scale ids resolved:' :scale1_id :scale2_id

\echo ''
\echo '### B1. authenticated full save on a virgin date (1999-01-01)'
select set_config('request.jwt.claims',
                  json_build_object('sub', :'uid', 'role', 'authenticated')::text,
                  true) is not null as jwt_set;
set local role authenticated;

select r.date as returned_date, r.mood as returned_mood, r.mood_emoji as returned_emoji
  from public.save_daily_entry(jsonb_build_object(
          'user_id',          :'uid',
          'date',             '1999-01-01',
          'mood',             4,
          'mood_emoji',       'x',
          'sleep_quality',    3,
          'stress',           2,
          'activities',       jsonb_build_array('sport', 'cteni'),
          'habits',           jsonb_build_object('koureni', false),
          'gratitude',        jsonb_build_array('rodina'),
          'note',             'test note',
          'weather',          '[]'::jsonb,
          'photo_path',       'diary-photos/x/1999-01-01.jpg',
          'phone_screen_time', 123,
          'phone_unlocks',     45,
          'phone_top_apps',   'Instagram:12,WhatsApp:3,Broken:,NoTime',
          'ai_reflection',    'reflection text',
          'scale_values',     jsonb_build_object(
                                :'scale1_id', 5,
                                :'scale2_id', 2,
                                '00000000-0000-0000-0000-000000000000', 4)
        )) r;

reset role;
select mood, mood_emoji, sleep_quality, stress, activities, note, photo_path,
       phone_screen_time, phone_unlocks, phone_top_apps, ai_reflection, scale_values
  from public.entries
 where user_id = :'uid'::uuid and date = '1999-01-01';

\echo '-- B1 scale mirror: expect exactly 2 rows (bogus uuid skipped, 0/negative skipped)'
select s.name, se.date, se.value
  from public.scale_entries se join public.scales s on s.id = se.scale_id
 where se.user_id = :'uid'::uuid and se.date = '1999-01-01'
 order by s.name;

\echo ''
\echo '### B2. partial update: only ai_reflection sent -> every other column must survive'
-- now() is fixed for the whole transaction, so backdate updated_at first to prove
-- the BEFORE UPDATE trigger really moves it forward (and that it is wired up at all)
reset role;
update public.entries set updated_at = now() - interval '1 day'
 where user_id = :'uid'::uuid and date = '1999-01-01';
set local role authenticated;
select r.ai_reflection as returned_reflection, r.mood as returned_mood, r.sleep_quality as returned_sleep
  from public.save_daily_entry(jsonb_build_object(
          'user_id', :'uid', 'date', '1999-01-01', 'ai_reflection', 'SECOND PASS')) r;
reset role;
select mood, sleep_quality, note, photo_path, phone_top_apps, activities, ai_reflection,
       updated_at > now() - interval '1 hour' as updated_at_bumped_by_trigger
  from public.entries
 where user_id = :'uid'::uuid and date = '1999-01-01';

\echo ''
\echo '### B3. asymmetry check: mood 0 -> NULL, but sleep_quality 0 -> column left alone'
set local role authenticated;
select r.mood as returned_mood_must_be_null, r.sleep_quality as returned_sleep_must_be_3
  from public.save_daily_entry(jsonb_build_object(
          'user_id', :'uid', 'date', '1999-01-01', 'mood', 0, 'sleep_quality', 0)) r;
reset role;
select mood as mood_must_be_null, sleep_quality as sleep_must_still_be_3, note as note_untouched
  from public.entries
 where user_id = :'uid'::uuid and date = '1999-01-01';

\echo ''
\echo '### B4. authz: an authenticated caller may not write someone else''s user_id'
select set_config('request.jwt.claims',
                  json_build_object('sub', :'uid', 'role', 'authenticated')::text,
                  true) is not null as jwt_set;
set local role authenticated;
do $$
begin
  perform public.save_daily_entry(
    jsonb_build_object('user_id', '00000000-0000-0000-0000-000000000001',
                       'date', '1999-01-02'));
  raise notice 'B4 FAIL: user_id mismatch was accepted';
exception when insufficient_privilege then
  raise notice 'B4 PASS: rejected with sqlstate % (%)', sqlstate, sqlerrm;
end $$;
reset role;

\echo ''
\echo '### B5. server-side call (auth.uid() null): persists, but scale mirror must be SKIPPED'
select set_config('request.jwt.claims', '', true) is not null as claims_cleared;
select auth.uid() is null as actor_is_null;
select r.date as returned_date, r.mood as returned_mood, r.scale_values as returned_scale_values
  from public.save_daily_entry(jsonb_build_object(
          'user_id', :'uid',
          'date', '1999-01-03',
          'mood', 5,
          'scale_values', jsonb_build_object(:'scale1_id', 4))) r;
select (select scale_values from public.entries
         where user_id = :'uid'::uuid and date = '1999-01-03') as b5_entries_scale_values,
       (select count(*) from public.scale_entries
         where user_id = :'uid'::uuid and date = '1999-01-03') as b5_mirrored_must_be_0;

\echo ''
\echo '### B6. anon must have no EXECUTE on the new functions'
set local role anon;
do $$
begin
  perform public.save_daily_entry('{"user_id":"00000000-0000-0000-0000-000000000001","date":"1999-01-02"}'::jsonb);
  raise notice 'B6 FAIL: anon could call save_daily_entry';
exception when insufficient_privilege then
  raise notice 'B6 PASS: anon blocked on save_daily_entry (sqlstate %)', sqlstate;
end $$;
do $$
begin
  perform public.check_achievements('00000000-0000-0000-0000-000000000001'::uuid);
  raise notice 'B6 FAIL: anon could call check_achievements';
exception when insufficient_privilege then
  raise notice 'B6 PASS: anon blocked on check_achievements (sqlstate %)', sqlstate;
end $$;
reset role;

\echo ''
\echo '### B7. check_achievements authz + happy path'
select set_config('request.jwt.claims',
                  json_build_object('sub', :'uid', 'role', 'authenticated')::text,
                  true) is not null as jwt_set;
set local role authenticated;
do $$
begin
  perform public.check_achievements('00000000-0000-0000-0000-000000000001'::uuid);
  raise notice 'B7 FAIL: cross-user read was accepted';
exception when insufficient_privilege then
  raise notice 'B7 PASS: cross-user rejected (sqlstate %)', sqlstate;
end $$;
select public.check_achievements() -> 'progress' ->> 'first_entry' as b7_own_user_default_arg,
       public.check_achievements(:'uid'::uuid) -> 'progress' ->> 'streak_7' as b7_streak7;
reset role;

\echo ''
\echo '### C. ROLLBACK — production must be byte-for-byte untouched'
rollback;

select (select count(*) from public.entries)       as entries_after_rollback,
       (select count(*) from public.scale_entries) as scale_entries_after_rollback,
       (select count(*) from public.achievements)  as achievements_after_rollback;

select count(*) as new_functions_left_behind
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public'
   and p.proname in ('save_daily_entry', 'check_achievements', 'set_updated_at');

select count(*) as new_triggers_left_behind
  from pg_trigger t where not t.tgisinternal and t.tgname = 'trg_set_updated_at';

