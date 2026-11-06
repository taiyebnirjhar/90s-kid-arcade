-- Game logs (20261107000000_game_logs.sql): kind 'game', up to 200 KB, 20 a
-- day apart from the connection reports, nothing kept past 200 MB stored.
\set ON_ERROR_STOP on
\o /dev/null
begin;
insert into auth.users (id) values ('00000000-0000-0000-0000-00000000000b');
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000b', true);
select public.send_problem_report('game', '1.0.290', 'samsung SM-M145F', null, 'God of War', repeat('y', 300000));
do $$ begin
  for i in 1..25 loop perform public.send_problem_report('game', '1.0.290'); end loop;
  for i in 1..5 loop perform public.send_problem_report('lost', '1.0.290'); end loop;
end $$;
reset role;
do $$ begin
  if (select count(*) from public.problem_reports where kind = 'game') <> 20 then
    raise exception 'FAIL: % game logs kept, not 20', (select count(*) from public.problem_reports where kind = 'game');
  end if;
  if (select count(*) from public.problem_reports where kind = 'lost') <> 5 then
    raise exception 'FAIL: game logs used up the connection reports'' 20';
  end if;
  if (select max(char_length(log)) from public.problem_reports where kind = 'game') <> 204800 then
    raise exception 'FAIL: game log not cut to 200 KB';
  end if;
end $$;
-- Past 200 MB stored: nothing more is kept.
insert into public.problem_reports (kind, log) select 'lost', repeat('z', 32768) from generate_series(1, 6200);
insert into auth.users (id) values ('00000000-0000-0000-0000-00000000000c');
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000c', true);
select public.send_problem_report('game', '1.0.290', null, null, null, 'one more');
reset role;
do $$ begin
  if exists (select 1 from public.problem_reports where log = 'one more') then
    raise exception 'FAIL: a game log kept past 200 MB stored';
  end if;
end $$;
rollback;
\o
\echo 'game logs: all checks passed'
