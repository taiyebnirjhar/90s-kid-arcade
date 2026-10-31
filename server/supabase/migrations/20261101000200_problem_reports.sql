-- Problem reports (the owner asked, 2026-10-31: players never send them):
-- when joining fails, a room is lost or one can't be made, the phone sends
-- its connection log here by itself. The owner reads them in Supabase's
-- dashboard (Table Editor, problem_reports); the app never reads them back.
--
-- Within the free plan (D8): at most 20 a day from one player, 20,000 in
-- the last 30 days in all (more are dropped, quietly), each log at most
-- 32 KB, and anything older than 30 days deleted every night.

create table public.problem_reports (
  id bigint generated always as identity primary key,
  reporter uuid references auth.users (id) on delete set null,
  -- What went wrong: 'join', 'host', 'lost', 'ended'.
  kind text not null check (kind in ('join', 'host', 'lost', 'ended')),
  -- The app's emulator build, the phone (maker, model, Android), the room's code or key.
  app text check (char_length(app) <= 64),
  phone text check (char_length(phone) <= 128),
  room text check (char_length(room) <= 32),
  error text check (char_length(error) <= 1000),
  log text check (char_length(log) <= 32768),
  created_at timestamptz not null default now()
);
create index problem_reports_by_time on public.problem_reports (created_at);
create index problem_reports_by_reporter on public.problem_reports (reporter, created_at);
-- No policies: only send_problem_report writes, and nothing in the app reads them.
alter table public.problem_reports enable row level security;

create function public.send_problem_report(
  kind text, app text default null, phone text default null, room text default null,
  error text default null, log text default null
) returns void
language plpgsql volatile security definer set search_path = '' as $$
declare
  me uuid := (select auth.uid());
begin
  if me is null then raise exception 'Sign in first' using errcode = '28000'; end if;
  if (select count(*) from public.problem_reports r where r.reporter = me and r.created_at > now() - interval '1 day') >= 20 then
    return;
  end if;
  if (select count(*) from public.problem_reports r where r.created_at > now() - interval '30 days') >= 20000 then
    return;
  end if;
  insert into public.problem_reports (reporter, kind, app, phone, room, error, log)
  values (me, send_problem_report.kind, left(send_problem_report.app, 64), left(send_problem_report.phone, 128),
          left(send_problem_report.room, 32), left(send_problem_report.error, 1000), right(send_problem_report.log, 32768));
end
$$;

create function public.clear_old_problem_reports() returns void
language sql volatile security definer set search_path = '' as $$
  delete from public.problem_reports r where r.created_at < now() - interval '30 days'
$$;

do $$
begin
  if exists (select 1 from pg_available_extensions where name = 'pg_cron') then
    create extension if not exists pg_cron with schema pg_catalog;
    perform cron.schedule('clear-old-problem-reports', '43 3 * * *', 'select public.clear_old_problem_reports()');
  else
    raise notice 'pg_cron not available: clear_old_problem_reports is not scheduled';
  end if;
end
$$;

revoke all on function public.send_problem_report, public.clear_old_problem_reports from public, anon;
grant execute on function public.send_problem_report to authenticated;
