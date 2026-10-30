-- Open and locked rooms (20261031000000_room_list.sql), as each kind of
-- user. Rafi makes an open room and a locked one, Tanu (a stranger) sees
-- both listed but gets only the open one's code, Mim joins.
\set ON_ERROR_STOP on
\o /dev/null
begin;
insert into auth.users (id) values
  ('00000000-0000-0000-0000-00000000000a'), -- Rafi
  ('00000000-0000-0000-0000-00000000000b'), -- Mim
  ('00000000-0000-0000-0000-00000000000c'); -- Tanu
create temp table t (k text primary key, v text);
grant all on t to authenticated, service_role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
insert into public.profiles (id, name, icon) values ('00000000-0000-0000-0000-00000000000a', 'Rafi', 5);
insert into t select 'open', (public.create_room('kof97', 1, 2, 2, true)).code;
insert into t select 'locked', (public.create_room('dino', 3, 0, 3, false)).code;
-- An app from before open rooms: four arguments, a locked room.
insert into t select 'old', (public.create_room('kof98', 4, 2, 2)).code;
do $$ begin
  if not (select open and relay_only from public.rooms where code = (select v from t where k = 'open')) then
    raise exception 'FAIL: an open room isn''t open and relay-only';
  end if;
  if (select open or relay_only from public.rooms where code = (select v from t where k = 'old')) then
    raise exception 'FAIL: an old app''s room is open';
  end if;
  -- Your own rooms aren't suggested to you.
  if exists (select 1 from public.list_rooms()) then raise exception 'FAIL: listed my own rooms'; end if;
end $$;

-- Tanu: all three listed, open first; only the open one's code.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000c', true);
do $$ declare r record; n int := 0; begin
  for r in select * from public.list_rooms() loop
    n := n + 1;
    if n = 1 and not r.open then raise exception 'FAIL: open rooms aren''t first'; end if;
    if r.open and r.code <> (select v from t where k = 'open') then raise exception 'FAIL: open room''s code %', r.code; end if;
    if not r.open and r.code is not null then raise exception 'FAIL: a locked room''s code was listed'; end if;
    if r.owner_name <> 'Rafi' or r.owner_icon <> 5 or r.members <> 1 then raise exception 'FAIL: listed %', r; end if;
  end loop;
  if n <> 3 then raise exception 'FAIL: listed % rooms', n; end if;
  if not (select f.open and f.relay_only from public.find_room((select v from t where k = 'open')) f) then
    raise exception 'FAIL: find_room doesn''t say the room is open and relay-only';
  end if;
  -- Not in the room: can't open or lock it.
  perform public.set_room_open((select v from t where k = 'open'), false);
  raise exception 'FAIL: a stranger locked a room';
exception when sqlstate '42501' then null; end $$;

-- Mim joins the open room: it's no longer suggested to Mim; Mim (any phone in it) can lock it.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000b', true);
select public.join_room((select v from t where k = 'open'));
do $$ begin
  if exists (select 1 from public.list_rooms() where open) then raise exception 'FAIL: suggested the room I''m in'; end if;
  perform public.set_room_open((select v from t where k = 'open'), false);
end $$;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000c', true);
do $$ begin
  if exists (select 1 from public.list_rooms() where code is not null) then raise exception 'FAIL: a locked room''s code listed after locking'; end if;
end $$;

-- Rafi opens it again (made open: fine); the room made locked stays locked.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
select public.set_room_open((select v from t where k = 'open'), true);
do $$ begin
  perform public.set_room_open((select v from t where k = 'locked'), true);
  raise exception 'FAIL: opened a room made locked';
exception when sqlstate '22023' then null; end $$;

-- Quiet rooms (no phone touched them for 3 minutes) and closed ones aren't listed.
reset role;
update public.rooms set last_seen = now() - interval '4 minutes' where code = (select v from t where k = 'old');
set local role authenticated;
select public.leave_room((select v from t where k = 'locked'));
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000c', true);
do $$ begin
  if (select count(*) from public.list_rooms()) <> 1 then raise exception 'FAIL: listed %', (select array_agg(game_set) from public.list_rooms()); end if;
end $$;

-- Not signed in: nothing.
set local role anon;
do $$ begin
  perform public.list_rooms();
  raise exception 'FAIL: anon listed rooms';
exception when insufficient_privilege then null; end $$;

rollback;
\o
\echo 'room list: all checks passed'
