-- Problem reports (20261101000200_problem_reports.sql): a phone sends its
-- log; at most 20 a day count; nobody reads them back through the API.
\set ON_ERROR_STOP on
\o /dev/null
begin;
insert into auth.users (id) values ('00000000-0000-0000-0000-00000000000a');
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
select public.send_problem_report('join', 'build-1', 'Samsung SM-A546E, Android 14', 'K7Q2MX', 'connect timed out', repeat('x', 40000));
do $$ begin
  begin
    perform public.send_problem_report('crash');
    raise exception 'FAIL: a kind that isn''t one';
  exception when check_violation then null;
  end;
  begin
    perform count(*) from public.problem_reports;
    raise exception 'FAIL: read the reports';
  exception when insufficient_privilege then null;
  end;
  for i in 1..30 loop
    perform public.send_problem_report('lost', 'build-1');
  end loop;
end $$;
reset role;
do $$ begin
  if (select count(*) from public.problem_reports) <> 20 then
    raise exception 'FAIL: % reports kept, not 20', (select count(*) from public.problem_reports);
  end if;
  if (select max(char_length(log)) from public.problem_reports) <> 32768 then raise exception 'FAIL: log not cut to 32 KB'; end if;
end $$;
rollback;
\o
\echo 'problem reports: all checks passed'
