-- 在 Supabase 的 SQL Editor 里运行一次：建云同步用的表，并保证每个人只能读写自己的那一行。
create table public.user_data (
  user_id uuid primary key references auth.users(id) on delete cascade,
  data jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);
alter table public.user_data enable row level security;
create policy "own row" on public.user_data
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
