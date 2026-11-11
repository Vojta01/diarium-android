-- Diarium Android: native FCM push tokens
-- Run in Supabase SQL Editor.
create table if not exists push_tokens (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  token text not null unique,
  platform text not null default 'android', -- 'android' (FCM)
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists push_tokens_user_idx on push_tokens (user_id);

-- RLS: users manage their own tokens
alter table push_tokens enable row level security;

create policy "push_tokens_select_own" on push_tokens
  for select using (auth.uid() = user_id);

create policy "push_tokens_insert_own" on push_tokens
  for insert with check (auth.uid() = user_id);

create policy "push_tokens_update_own" on push_tokens
  for update using (auth.uid() = user_id);

create policy "push_tokens_delete_own" on push_tokens
  for delete using (auth.uid() = user_id);

-- Service role (cron/push/send) bypasses RLS via service_role key.
-- Purging stale tokens happens from the API route with the service key.