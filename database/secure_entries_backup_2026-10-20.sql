-- Secure the 2026-10-14 data-repair backup table.
--
-- Supabase security advisor flagged `entries_backup_20261014` as
-- "Critical: Table publicly accessible" (rls_disabled_in_public):
-- the table had RLS disabled AND full grants to anon/authenticated,
-- so anyone holding the public anon key could read/update/delete all
-- 1249 diary rows (verified live 2026-10-20 with `set local role anon`).
--
-- Safety copy from the Oct 14 activities/weather jsonb repair; the repair
-- itself is verified, keep the data but deny client access.
-- service_role keeps its grants (BYPASSRLS) and `postgres` owns the table,
-- so both can still read it for any future restore.

alter table public.entries_backup_20261014 enable row level security;
revoke all on table public.entries_backup_20261014 from anon, authenticated;

-- Applied to production 2026-10-20. Re-running is a no-op (idempotent).
