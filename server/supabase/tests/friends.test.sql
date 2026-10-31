-- Friends (20261101000100_friends.sql): Rafi asks Mim by code, Mim asks
-- back (friends), Mim plays in a room and invites Rafi, Tanu blocks Rafi.
\set ON_ERROR_STOP on
\o /dev/null
begin;
insert into auth.users (id) values
  ('00000000-0000-0000-0000-00000000000a'), -- Rafi
  ('00000000-0000-0000-0000-00000000000b'), -- Mim
  ('00000000-0000-0000-0000-00000000000c'); -- Tanu
insert into public.profiles (id, name, icon, friend_code) values
  ('00000000-0000-0000-0000-00000000000a', 'Rafi', 1, 'RAFI2345'),
  ('00000000-0000-0000-0000-00000000000b', 'Mim', 2, 'MIMM2345'),
  ('00000000-0000-0000-0000-00000000000c', 'Tanu', 3, 'TANU2345');
create temp table t (k text primary key, v text);
grant all on t to authenticated;

set local role authenticated;
-- Rafi asks Mim, by her code typed loosely.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
do $$ begin
  if public.request_friend('mimm-2345') <> 'asked' then raise exception 'FAIL: not asked'; end if;
  if public.request_friend('MIMM2345') <> 'asked' then raise exception 'FAIL: asking again'; end if;
  if (select status from public.list_friends() where name = 'Mim') <> 'outgoing' then raise exception 'FAIL: Rafi sees no request'; end if;
  begin
    perform public.request_friend('RAFI2345');
    raise exception 'FAIL: befriended myself';
  exception when sqlstate '22023' then null;
  end;
  begin
    perform public.request_friend('NOPE2345');
    raise exception 'FAIL: a code nobody has';
  exception when sqlstate 'P0002' then null;
  end;
  begin
    perform count(*) from public.friendships;
    raise exception 'FAIL: read the friendships table';
  exception when insufficient_privilege then null;
  end;
end $$;

-- Mim sees the request and accepts by asking back.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000b', true);
do $$ begin
  if (select status from public.list_friends() where name = 'Rafi') <> 'incoming' then raise exception 'FAIL: Mim sees no request'; end if;
  if public.request_friend_by_id('00000000-0000-0000-0000-00000000000a') <> 'friends' then raise exception 'FAIL: not friends'; end if;
end $$;
-- Mim plays KOF '97 in her room.
insert into t select 'room', (public.create_room('kof97', 1, 2, 2, false)).code;
select public.set_presence('kof97', (select v from t where k = 'room'));
select public.invite_friend('00000000-0000-0000-0000-00000000000a', (select v from t where k = 'room'));
select public.invite_friend('00000000-0000-0000-0000-00000000000a', (select v from t where k = 'room'));
do $$ begin
  begin
    perform public.invite_friend('00000000-0000-0000-0000-00000000000c', (select v from t where k = 'room'));
    raise exception 'FAIL: invited a stranger';
  exception when insufficient_privilege then null;
  end;
end $$;

-- Rafi sees Mim online, in her room (with its code), and her invite, once.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
do $$
declare f record;
begin
  select * into f from public.list_friends() where name = 'Mim';
  if f.status <> 'friend' or not f.online or f.game_set <> 'kof97' then raise exception 'FAIL: Mim not shown playing: %', f; end if;
  if f.room <> (select v from t where k = 'room') or not f.room_space then raise exception 'FAIL: Mim''s room not shown: %', f; end if;
  if (select count(*) from public.my_invites()) <> 1 then raise exception 'FAIL: % invites', (select count(*) from public.my_invites()); end if;
  if (select name from public.my_invites()) <> 'Mim' then raise exception 'FAIL: invite not from Mim'; end if;
  perform public.dismiss_invite((select id from public.my_invites()));
  if exists (select 1 from public.my_invites()) then raise exception 'FAIL: dismissed invite still there'; end if;
  -- Rafi isn't in that room: he can't say he is.
  perform public.set_presence('kof97', (select v from t where k = 'room'));
end $$;

-- Mim goes offline: no game, no room shown.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000b', true);
select public.go_offline();
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
do $$
declare f record;
begin
  select * into f from public.list_friends() where name = 'Mim';
  if f.online or f.game_set is not null or f.room is not null then raise exception 'FAIL: Mim still shown online: %', f; end if;
end $$;

-- Tanu blocks Rafi: he can't find her by code, nor she him.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000c', true);
select public.request_friend('RAFI2345');
select public.block_player('00000000-0000-0000-0000-00000000000a');
do $$ begin
  if exists (select 1 from public.list_friends()) then raise exception 'FAIL: the request survived the block'; end if;
  if (select name from public.list_blocked()) <> 'Rafi' then raise exception 'FAIL: Rafi not listed as blocked'; end if;
end $$;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
do $$ begin
  perform public.request_friend('TANU2345');
  raise exception 'FAIL: found someone who blocked me';
exception when sqlstate 'P0002' then null;
end $$;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000c', true);
select public.unblock_player('00000000-0000-0000-0000-00000000000a');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
do $$ begin
  if public.request_friend('TANU2345') <> 'asked' then raise exception 'FAIL: unblocked, still not found'; end if;
  -- And removing a friend.
  perform public.remove_friend('00000000-0000-0000-0000-00000000000b');
  if exists (select 1 from public.list_friends() where name = 'Mim') then raise exception 'FAIL: Mim still a friend'; end if;
end $$;
rollback;
\o
\echo 'friends: all checks passed'
