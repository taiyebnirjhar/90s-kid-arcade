-- Open and locked rooms, listed on the Online screen.
--
-- Every room is listed (its game, who made it, how many are in). An open
-- room's code comes with it, so a tap joins; a locked room's doesn't, so
-- only those given the code get in.
--
-- A room made open is relay-only (docs/online-plan.md, settled: public
-- matches with strangers use the relay, so a player's internet address
-- stays private). That's fixed when it's made: an open room can be locked
-- and opened again, but a room made locked (its phones connected directly)
-- can't be opened to strangers.

alter table public.rooms add column open boolean not null default false;
alter table public.rooms add column relay_only boolean not null default false;
create index rooms_listed on public.rooms (last_seen desc) where status = 'open';

-- create_room takes whether the room is open (apps from before this make locked ones).
drop function public.create_room(text, bigint, bigint, int);
create function public.create_room(game_set text, rom bigint, bios bigint, max_players int, open boolean default false)
returns public.rooms
language plpgsql volatile security definer set search_path = '' as $$
declare
  me uuid := (select auth.uid());
  made public.rooms;
begin
  if me is null then raise exception 'Sign in first' using errcode = '28000'; end if;
  if (select count(*) from public.rooms r where r.owner = me and r.status = 'open') >= public.max_open_rooms() then
    raise exception 'You have % rooms open already: close one first', public.max_open_rooms() using errcode = '53400';
  end if;
  for i in 1..20 loop
    begin
      insert into public.rooms (code, owner, game_set, rom, bios, max_players, open, relay_only)
        values (public.new_code(6), me, create_room.game_set, create_room.rom, create_room.bios, create_room.max_players,
                create_room.open, create_room.open)
        returning * into made;
      insert into public.room_members (room, member) values (made.code, me);
      return made;
    exception when unique_violation then
      -- That code's taken: another.
    end;
  end loop;
  raise exception 'No free room code: try again' using errcode = '53400';
end
$$;

-- find_room says whether the room is open and relay-only too.
drop function public.find_room(text);
create function public.find_room(room_code text)
returns table (code text, game_set text, rom bigint, bios bigint, max_players smallint, owner uuid, owner_name text, members int,
               open boolean, relay_only boolean)
language sql stable security definer set search_path = '' as $$
  select r.code, r.game_set, r.rom, r.bios, r.max_players, r.owner, p.name,
    (select count(*)::int from public.room_members m where m.room = r.code),
    r.open, r.relay_only
  from public.rooms r left join public.profiles p on p.id = r.owner
  where r.code = public.clean_code(room_code) and r.status = 'open' and (select auth.uid()) is not null
$$;

-- The rooms to show on the Online screen: live ones (touched in the last 3
-- minutes) with room for another phone, not yours, open ones first. A
-- locked room's code is left out.
create function public.list_rooms()
returns table (code text, game_set text, max_players smallint, owner_name text, owner_icon smallint, members int, open boolean)
language sql stable security definer set search_path = '' as $$
  select case when r.open then r.code end, r.game_set, r.max_players, p.name, p.icon, n.members, r.open
  from public.rooms r
  left join public.profiles p on p.id = r.owner
  cross join lateral (select count(*)::int as members from public.room_members m where m.room = r.code) n
  where r.status = 'open'
    and r.last_seen > now() - interval '3 minutes'
    and n.members between 1 and public.max_members() - 1
    and (select auth.uid()) is not null
    and not exists (select 1 from public.room_members m where m.room = r.code and m.member = (select auth.uid()))
  order by r.open desc, r.last_seen desc
  limit 30
$$;

-- Opens or locks a room: any phone in it (whoever runs the room now may
-- not be the one who made it). A room made locked stays locked.
create function public.set_room_open(room_code text, open boolean) returns public.rooms
language plpgsql volatile security definer set search_path = '' as $$
declare
  c text := public.clean_code(room_code);
  target public.rooms;
begin
  if not public.in_room(c) then raise exception 'Not in room %', c using errcode = '42501'; end if;
  select * into target from public.rooms r where r.code = c for update;
  if set_room_open.open and not target.relay_only then
    raise exception 'This room was made locked: make an open room for anyone to join' using errcode = '22023';
  end if;
  update public.rooms r set open = set_room_open.open where r.code = c returning * into target;
  return target;
end
$$;

revoke all on function public.create_room, public.find_room, public.list_rooms, public.set_room_open from public, anon;
grant execute on function public.create_room, public.find_room, public.list_rooms, public.set_room_open to authenticated;
