-- Reading problem reports (20261101000300_problem_readers.sql): a listed
-- reader reads them; a player can't.
\set ON_ERROR_STOP on
\o /dev/null
begin;
insert into auth.users (id) values
  ('00000000-0000-0000-0000-00000000000a'), -- a player
  ('00000000-0000-0000-0000-00000000000f'); -- the reader
insert into public.problem_readers (id) values ('00000000-0000-0000-0000-00000000000f');
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
select public.send_problem_report('join', 'b', 'p', 'K7Q2MX', 'connect timed out', 'rtc: way out: relay');
do $$ begin
  perform public.read_problem_reports();
  raise exception 'FAIL: a player read the reports';
exception when insufficient_privilege then null;
end $$;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000f', true);
do $$ begin
  if (select count(*) from public.read_problem_reports(1)) <> 1 then raise exception 'FAIL: the reader sees no report'; end if;
  if (select log from public.read_problem_reports()) <> 'rtc: way out: relay' then raise exception 'FAIL: wrong log'; end if;
  begin
    perform count(*) from public.problem_readers;
    raise exception 'FAIL: read the readers table';
  exception when insufficient_privilege then null;
  end;
end $$;
rollback;
\o
\echo 'problem readers: all checks passed'
