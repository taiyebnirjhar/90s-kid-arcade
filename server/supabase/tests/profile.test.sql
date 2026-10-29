-- Phase 1.3's rules: your profile, saved in one call, and games played,
-- counted on the phone and synced. Rafi plays; Mim looks; strangers who
-- aren't signed in get nothing.
\set ON_ERROR_STOP on
\o /dev/null
begin;
insert into auth.users (id) values
  ('00000000-0000-0000-0000-00000000000a'), -- Rafi
  ('00000000-0000-0000-0000-00000000000b'); -- Mim

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);

-- save_profile makes it the first time, then changes it; the friend code stays.
create temp table t (k text primary key, v text);
insert into t select 'code', (public.save_profile('  Rafi ', 3)).friend_code;
do $$ declare p public.profiles; begin
  select * into p from public.save_profile('Rafi K', 5);
  if p.name <> 'Rafi K' or p.icon <> 5 then raise exception 'FAIL: saved %', p; end if;
  if p.friend_code <> (select v from t where k = 'code') then raise exception 'FAIL: the friend code changed'; end if;
  if (select name from public.profiles where id = '00000000-0000-0000-0000-00000000000a') <> 'Rafi K' then
    raise exception 'FAIL: not saved';
  end if;
end $$;
do $$ begin
  perform public.save_profile('', 1);
  raise exception 'FAIL: an empty name';
exception when check_violation then null; end $$;
do $$ begin
  perform public.save_profile('Rafi', 64);
  raise exception 'FAIL: an icon that isn''t one';
exception when check_violation then null; end $$;
do $$ begin
  perform public.save_profile(repeat('x', 25), 1);
  raise exception 'FAIL: a 25-letter name';
exception when check_violation then null; end $$;

select public.record_plays('{"kof97": 3, "dino": 1}');
-- A count only goes up: an older copy from the phone doesn't lower it.
select public.record_plays('{"kof97": 2, "mslugx": 5}');
do $$ begin
  if (select count from public.plays where game = 'kof97') <> 3 then raise exception 'FAIL: a count went down'; end if;
  if (select count(*) from public.plays) <> 3 then raise exception 'FAIL: % games', (select count(*) from public.plays); end if;
end $$;
select public.record_plays('{"kof97": 4}');
do $$ begin
  if (select count from public.plays where game = 'kof97') <> 4 then raise exception 'FAIL: a count didn''t go up'; end if;
end $$;

-- Written only through record_plays, and only your own.
do $$ begin
  insert into public.plays (player, game, count) values ('00000000-0000-0000-0000-00000000000b', 'kof97', 99);
  raise exception 'FAIL: wrote plays directly';
exception when insufficient_privilege then null; end $$;
do $$ begin
  update public.plays set count = 0;
  raise exception 'FAIL: changed plays directly';
exception when insufficient_privilege then null; end $$;

-- Bad input changes nothing.
do $$ begin
  perform public.record_plays('{"kof97": -1}');
  raise exception 'FAIL: a negative count';
exception when check_violation then null; end $$;
do $$ begin
  perform public.record_plays('{"Robert''); drop table x; --": 1}');
  raise exception 'FAIL: a game id that isn''t one';
exception when check_violation then null; end $$;
do $$ begin
  perform public.record_plays('[1, 2]');
  raise exception 'FAIL: not an object';
exception when sqlstate '22023' then null; end $$;
do $$ begin
  perform public.record_plays((select jsonb_object_agg('g' || i, 1) from generate_series(1, 65) i));
  raise exception 'FAIL: 65 games at once';
exception when sqlstate '54000' then null; end $$;

-- Mim sees Rafi's games.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000b', true);
do $$ begin
  if (select count(*) from public.plays where player = '00000000-0000-0000-0000-00000000000a') <> 3 then
    raise exception 'FAIL: plays not readable';
  end if;
end $$;

-- Not signed in: nothing.
set local role anon;
do $$ begin
  perform public.save_profile('Nobody', 1);
  raise exception 'FAIL: anon saved a profile';
exception when insufficient_privilege then null; end $$;
do $$ begin
  perform public.record_plays('{"kof97": 1}');
  raise exception 'FAIL: anon recorded plays';
exception when insufficient_privilege then null; end $$;
do $$ begin
  perform count(*) from public.plays;
  raise exception 'FAIL: anon read plays';
exception when insufficient_privilege then null; end $$;

rollback;
\o
\echo 'profile and plays: all checks passed'
