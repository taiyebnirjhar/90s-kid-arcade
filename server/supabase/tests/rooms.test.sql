-- Phase 1.2's rules, checked as each kind of user (server/test.sh runs
-- this on a stub of Supabase). Rafi hosts, Mim joins, Tanu is a stranger.
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

-- Profiles: your own only; everyone's readable.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
insert into public.profiles (id, name) values ('00000000-0000-0000-0000-00000000000a', 'Rafi');
do $$ begin
  insert into public.profiles (id, name) values ('00000000-0000-0000-0000-00000000000b', 'Not Mim');
  raise exception 'FAIL: made someone else''s profile';
exception when insufficient_privilege then null; end $$;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000b', true);
insert into public.profiles (id, name) values ('00000000-0000-0000-0000-00000000000b', 'Mim');
do $$ declare n int; begin
  update public.profiles set name = 'Hacked' where id = '00000000-0000-0000-0000-00000000000a';
  get diagnostics n = row_count;
  if n <> 0 then raise exception 'FAIL: changed someone else''s profile'; end if;
  if (select count(*) from public.profiles) <> 2 then raise exception 'FAIL: profiles not readable'; end if;
end $$;
do $$ begin
  update public.profiles set friend_code = 'AAAAAAAA' where id = '00000000-0000-0000-0000-00000000000b';
  raise exception 'FAIL: changed a friend code';
exception when insufficient_privilege then null; end $$;

-- Rooms: Rafi opens one; a code like the room keys.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
insert into t select 'code', (public.create_room('kof97', 123, 456, 2)).code;
do $$ declare c text := (select v from t where k = 'code'); begin
  if c !~ '^[2-9A-HJ-NP-Z]{6}$' then raise exception 'FAIL: room code %', c; end if;
  if (select count(*) from public.rooms) <> 1 then raise exception 'FAIL: the owner can''t see their room'; end if;
end $$;
-- Rooms can't be made or changed straight in the table.
do $$ begin
  insert into public.rooms (code, owner, game_set, rom, max_players)
    values ('ABCDEF', '00000000-0000-0000-0000-00000000000a', 'kof97', 1, 2);
  raise exception 'FAIL: inserted a room directly';
exception when insufficient_privilege then null; end $$;
do $$ begin
  update public.rooms set max_players = 4;
  raise exception 'FAIL: changed a room directly';
exception when insufficient_privilege then null; end $$;

-- Tanu, a stranger: sees no rooms, but finds one by its code (any case, dashes).
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000c', true);
do $$ declare c text := (select v from t where k = 'code'); r record; begin
  if (select count(*) from public.rooms) <> 0 then raise exception 'FAIL: a stranger sees rooms'; end if;
  if (select count(*) from public.room_members) <> 0 then raise exception 'FAIL: a stranger sees members'; end if;
  select * into r from public.find_room(lower(substr(c, 1, 3)) || '-' || substr(c, 4));
  if r.game_set <> 'kof97' or r.rom <> 123 or r.owner_name <> 'Rafi' or r.members <> 1 then
    raise exception 'FAIL: find_room gave %', r;
  end if;
  if exists (select 1 from public.find_room('ZZZZZZ')) then raise exception 'FAIL: found a room that isn''t'; end if;
end $$;

-- Mim joins: sees the room and who's in; joining again is fine.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000b', true);
select public.join_room((select v from t where k = 'code'));
select public.join_room((select v from t where k = 'code'));
do $$ begin
  if (select count(*) from public.rooms) <> 1 then raise exception 'FAIL: a member can''t see the room'; end if;
  if (select count(*) from public.room_members) <> 2 then raise exception 'FAIL: members: %', (select count(*) from public.room_members); end if;
end $$;

-- Signaling: members hear and talk on "room:CODE"; Tanu can't.
select set_config('realtime.topic', 'room:' || (select v from t where k = 'code'), true);
insert into realtime.messages (topic, payload) values (current_setting('realtime.topic'), '{"offer": 1}');
do $$ begin
  if (select count(*) from realtime.messages) <> 1 then raise exception 'FAIL: a member can''t hear the room'; end if;
end $$;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000c', true);
do $$ begin
  if (select count(*) from realtime.messages) <> 0 then raise exception 'FAIL: a stranger hears the room'; end if;
  insert into realtime.messages (topic, payload) values (current_setting('realtime.topic'), '{"evil": 1}');
  raise exception 'FAIL: a stranger talked in the room';
exception when insufficient_privilege then null; end $$;

-- The relay's tables are the server's alone.
do $$ begin
  perform 1 from public.relay_usage;
  raise exception 'FAIL: read the relay''s use';
exception when insufficient_privilege then null; end $$;
do $$ begin
  perform 1 from public.relay_credentials;
  raise exception 'FAIL: read relay credentials';
exception when insufficient_privilege then null; end $$;

-- At most 3 rooms open each.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
select public.create_room('kof97', 1, 1, 2);
select public.create_room('kof97', 1, 1, 2);
do $$ begin
  perform public.create_room('kof97', 1, 1, 2);
  raise exception 'FAIL: a fourth room opened';
exception when sqlstate '53400' then null; end $$;

-- Leaving: the last one out closes the room, and its code says so.
select public.leave_room((select v from t where k = 'code'));
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000b', true);
select public.leave_room((select v from t where k = 'code'));
do $$ begin
  if exists (select 1 from public.find_room((select v from t where k = 'code'))) then raise exception 'FAIL: an empty room is still open'; end if;
  perform public.join_room((select v from t where k = 'code'));
  raise exception 'FAIL: joined a closed room';
exception when sqlstate 'P0002' then null; end $$;

-- A full room (8 phones) turns the ninth away.
reset role;
insert into auth.users (id) select ('00000000-0000-0000-0000-0000000001' || lpad(i::text, 2, '0'))::uuid from generate_series(1, 9) i;
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000101', true);
insert into t select 'full', (public.create_room('dino', 1, 0, 3)).code;
do $$ begin
  for i in 2..8 loop
    perform set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-0000000001' || lpad(i::text, 2, '0'), true);
    perform public.join_room((select v from t where k = 'full'));
  end loop;
  perform set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000109', true);
  perform public.join_room((select v from t where k = 'full'));
  raise exception 'FAIL: a ninth phone got in';
exception when sqlstate '53400' then null; end $$;

-- Rooms nobody touched for 10 minutes close.
reset role;
update public.rooms set last_seen = now() - interval '11 minutes' where code = (select v from t where k = 'full');
do $$ begin
  if public.close_quiet_rooms() < 1 then raise exception 'FAIL: a quiet room stayed open'; end if;
  if (select status from public.rooms where code = (select v from t where k = 'full')) <> 'closed' then raise exception 'FAIL: not closed'; end if;
  if exists (select 1 from public.room_members where room = (select v from t where k = 'full')) then raise exception 'FAIL: members kept'; end if;
end $$;

-- The server (the relay function, as the service role) reads rooms and who's
-- in them, and keeps the relay's month and credentials: granted, since
-- the project grants nothing by itself.
reset role;
insert into t select 'open', (select code from public.rooms where status = 'open' limit 1);
set local role service_role;
do $$ begin
  if not exists (select 1 from public.rooms r join public.room_members m on m.room = r.code
                 where r.code = (select v from t where k = 'open') and r.status = 'open') then
    raise exception 'FAIL: the server can''t see an open room''s members';
  end if;
  insert into public.relay_usage (month, bytes, checked_at) values ('2026-10-01', 5, now())
    on conflict (month) do update set bytes = excluded.bytes;
  update public.relay_usage set bytes = 6 where month = '2026-10-01';
  insert into public.relay_credentials (username, member, room, expires_at)
    values ('u1', '00000000-0000-0000-0000-00000000000a', 'ABCDEF', now() + interval '1 hour');
  update public.relay_credentials set revoked = true where username = 'u1';
  perform count(*) from public.relay_credentials where not revoked;
end $$;

-- Strangers who aren't signed in get nothing.
set local role anon;
do $$ begin
  perform public.find_room('ABCDEF');
  raise exception 'FAIL: anon called find_room';
exception when insufficient_privilege then null; end $$;

rollback;
\o
\echo 'rooms: all checks passed'
