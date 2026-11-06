-- Game logs (the owner asked, 2026-11-07: "By send, cant we save it directly
-- on our server, to check log our self directly?"): a game's log (Settings,
-- Game log, Send to us; or by itself after a session with a bad freeze)
-- goes into problem_reports as kind 'game', read with read_problem_reports
-- like the others.
--
-- Within the free plan (D8): a game log is at most 200 KB; at most 20 a day
-- from one player (apart from the connection reports' 20); and nothing more
-- is kept once the logs stored reach 200 MB (of the free 500 MB database),
-- until the nightly clean-up (older than 30 days) makes room.

alter table public.problem_reports drop constraint problem_reports_kind_check;
alter table public.problem_reports add constraint problem_reports_kind_check
  check (kind in ('join', 'host', 'lost', 'ended', 'game'));
alter table public.problem_reports drop constraint problem_reports_log_check;
alter table public.problem_reports add constraint problem_reports_log_check
  check (char_length(log) <= 204800);

create or replace function public.send_problem_report(
  kind text, app text default null, phone text default null, room text default null,
  error text default null, log text default null
) returns void
language plpgsql volatile security definer set search_path = '' as $$
declare
  me uuid := (select auth.uid());
  game boolean := send_problem_report.kind = 'game';
begin
  if me is null then raise exception 'Sign in first' using errcode = '28000'; end if;
  if (select count(*) from public.problem_reports r
      where r.reporter = me and r.created_at > now() - interval '1 day'
        and (r.kind = 'game') = game) >= 20 then
    return;
  end if;
  if (select count(*) from public.problem_reports r where r.created_at > now() - interval '30 days') >= 20000 then
    return;
  end if;
  if (select coalesce(sum(octet_length(r.log)), 0) from public.problem_reports r) >= 200000000 then
    return;
  end if;
  insert into public.problem_reports (reporter, kind, app, phone, room, error, log)
  values (me, send_problem_report.kind, left(send_problem_report.app, 64), left(send_problem_report.phone, 128),
          left(send_problem_report.room, 32), left(send_problem_report.error, 1000),
          right(send_problem_report.log, case when game then 204800 else 32768 end));
end
$$;

revoke all on function public.send_problem_report from public, anon;
grant execute on function public.send_problem_report to authenticated;
