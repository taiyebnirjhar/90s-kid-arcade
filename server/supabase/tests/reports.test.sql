-- Reports (20261101000000_reports.sql): Rafi reports Mim's chat; the same
-- report again does nothing; nobody reports themselves or reads reports.
\set ON_ERROR_STOP on
\o /dev/null
begin;
insert into auth.users (id) values
  ('00000000-0000-0000-0000-00000000000a'), -- Rafi
  ('00000000-0000-0000-0000-00000000000b'); -- Mim

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
select public.report_player('00000000-0000-0000-0000-00000000000b', 'chat', 'k7q-2mx', 'Mim: something rude');
select public.report_player('00000000-0000-0000-0000-00000000000b', 'chat', 'K7Q2MX', 'Mim: again');
do $$ begin
  begin
    perform public.report_player('00000000-0000-0000-0000-00000000000a', 'chat');
    raise exception 'FAIL: reported myself';
  exception when sqlstate '22023' then null;
  end;
  begin
    perform public.report_player('00000000-0000-0000-0000-00000000000b', 'swearing');
    raise exception 'FAIL: a reason that isn''t one';
  exception when check_violation then null;
  end;
  begin
    perform count(*) from public.reports;
    raise exception 'FAIL: read the reports';
  exception when insufficient_privilege then null;
  end;
end $$;

reset role;
do $$ begin
  if (select count(*) from public.reports) <> 1 then raise exception 'FAIL: the same report twice made two'; end if;
  if (select room from public.reports) <> 'K7Q2MX' then raise exception 'FAIL: room code not cleaned'; end if;
end $$;

-- Twenty reports a day at most.
insert into auth.users (id) select gen_random_uuid() from generate_series(1, 20);
insert into public.reports (reporter, reported, reason)
  select '00000000-0000-0000-0000-00000000000a', u.id, 'play' from auth.users u
  where u.id not in ('00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-00000000000b') limit 19;
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
do $$ begin
  perform public.report_player('00000000-0000-0000-0000-00000000000b', 'voice');
  raise exception 'FAIL: a 21st report today';
exception when sqlstate '53400' then null;
end $$;
rollback;
\o
\echo 'reports: all checks passed'
