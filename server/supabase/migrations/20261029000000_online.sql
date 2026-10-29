-- Online play, Step 1 (docs/online-plan.md, phase 1.2): profiles, rooms and
-- who's in them, and what the relay has used this month (D8).
--
-- Nothing here is open to the app unless granted below: the project doesn't
-- expose new tables by itself, and every table has row-level security.

-- Codes people read out: no 0/O or 1/I, as in today's room keys.
create function public.new_code(len int) returns text
language sql volatile set search_path = '' as $$
  select string_agg(substr('23456789ABCDEFGHJKLMNPQRSTUVWXYZ', 1 + floor(random() * 32)::int, 1), '')
  from generate_series(1, len)
$$;

-- "k7q-2m " or "K7Q2M" -> "K7Q2M".
create function public.clean_code(code text) returns text
language sql immutable set search_path = '' as $$
  select upper(regexp_replace(coalesce(code, ''), '[^A-Za-z0-9]', '', 'g'))
$$;

-- ----- Profiles -----

create table public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  name text not null check (char_length(name) between 1 and 24),
  icon smallint not null default 0 check (icon between 0 and 63),
  -- For friends (Step 2): given once, never changes.
  friend_code text not null unique default public.new_code(8),
  created_at timestamptz not null default now()
);
alter table public.profiles enable row level security;

-- Anyone signed in sees names and icons (a room shows who's in it).
create policy "profiles are public to the signed in" on public.profiles
  for select to authenticated using (true);
create policy "you make your own profile" on public.profiles
  for insert to authenticated with check (id = (select auth.uid()));
create policy "you change your own profile" on public.profiles
  for update to authenticated using (id = (select auth.uid())) with check (id = (select auth.uid()));

grant select on public.profiles to authenticated;
grant insert (id, name, icon), update (name, icon) on public.profiles to authenticated;

-- ----- Rooms -----

create table public.rooms (
  code text primary key check (code ~ '^[2-9A-HJ-NP-Z]{6}$'),
  owner uuid not null references auth.users (id) on delete cascade,
  -- The game: the same set, ROM and BIOS on every phone, as on Wi-Fi.
  game_set text not null check (char_length(game_set) between 1 and 32),
  rom bigint not null,
  bios bigint not null default 0,
  max_players smallint not null check (max_players between 1 and 4),
  status text not null default 'open' check (status in ('open', 'closed')),
  created_at timestamptz not null default now(),
  last_seen timestamptz not null default now()
);
create index rooms_open_by_owner on public.rooms (owner) where status = 'open';
create index rooms_open_by_last_seen on public.rooms (last_seen) where status = 'open';
alter table public.rooms enable row level security;

create table public.room_members (
  room text not null references public.rooms (code) on delete cascade,
  member uuid not null references auth.users (id) on delete cascade,
  joined_at timestamptz not null default now(),
  last_seen timestamptz not null default now(),
  primary key (room, member)
);
create index room_members_by_member on public.room_members (member);
alter table public.room_members enable row level security;

-- Whether you're in a room (for policies; it reads past them).
create function public.in_room(room_code text) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.room_members m join public.rooms r on r.code = m.room
    where m.room = room_code and m.member = (select auth.uid()) and r.status = 'open'
  )
$$;

-- You see the rooms you're in and who's in them. Finding a room by its
-- code goes through find_room, which shows only that one.
create policy "members see their room" on public.rooms
  for select to authenticated using (public.in_room(code));
create policy "members see who's in" on public.room_members
  for select to authenticated using (public.in_room(room));
grant select on public.rooms, public.room_members to authenticated;
-- Everything else goes through the functions below.

/** Open rooms one person may run at once. */
create function public.max_open_rooms() returns int language sql immutable as $$ select 3 $$;
/** Phones in a room at most (as MAX_MEMBERS in the app). */
create function public.max_members() returns int language sql immutable as $$ select 8 $$;

-- A new room for the game, run by you: its code, unique among rooms.
create function public.create_room(game_set text, rom bigint, bios bigint, max_players int)
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
      insert into public.rooms (code, owner, game_set, rom, bios, max_players)
        values (public.new_code(6), me, create_room.game_set, create_room.rom, create_room.bios, create_room.max_players)
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

-- An open room by its code, for joining: its game, who runs it, how full.
create function public.find_room(room_code text)
returns table (code text, game_set text, rom bigint, bios bigint, max_players smallint, owner uuid, owner_name text, members int)
language sql stable security definer set search_path = '' as $$
  select r.code, r.game_set, r.rom, r.bios, r.max_players, r.owner, p.name,
    (select count(*)::int from public.room_members m where m.room = r.code)
  from public.rooms r left join public.profiles p on p.id = r.owner
  where r.code = public.clean_code(room_code) and r.status = 'open' and (select auth.uid()) is not null
$$;

-- Into an open room with space (or still in it: seen again).
create function public.join_room(room_code text) returns public.rooms
language plpgsql volatile security definer set search_path = '' as $$
declare
  me uuid := (select auth.uid());
  c text := public.clean_code(room_code);
  target public.rooms;
begin
  if me is null then raise exception 'Sign in first' using errcode = '28000'; end if;
  select * into target from public.rooms r where r.code = c and r.status = 'open' for update;
  if target is null then raise exception 'No open room %', c using errcode = 'P0002'; end if;
  if not exists (select 1 from public.room_members m where m.room = c and m.member = me)
     and (select count(*) from public.room_members m where m.room = c) >= public.max_members() then
    raise exception 'The room is full: % phones at most', public.max_members() using errcode = '53400';
  end if;
  insert into public.room_members (room, member) values (c, me)
    on conflict (room, member) do update set last_seen = now();
  return target;
end
$$;

-- Still here (every minute or so, from every phone in the room).
create function public.touch_room(room_code text) returns void
language sql volatile security definer set search_path = '' as $$
  with me as (
    update public.room_members m set last_seen = now()
    where m.room = public.clean_code(room_code) and m.member = (select auth.uid())
    returning m.room
  )
  update public.rooms r set last_seen = now() from me where r.code = me.room and r.status = 'open'
$$;

-- Out of a room; the last one out closes it.
create function public.leave_room(room_code text) returns void
language plpgsql volatile security definer set search_path = '' as $$
declare
  c text := public.clean_code(room_code);
begin
  delete from public.room_members m where m.room = c and m.member = (select auth.uid());
  if not exists (select 1 from public.room_members m where m.room = c) then
    update public.rooms r set status = 'closed' where r.code = c;
  end if;
end
$$;

-- Rooms nobody has touched for 10 minutes close (run every minute).
create function public.close_quiet_rooms() returns int
language plpgsql volatile security definer set search_path = '' as $$
declare
  n int;
begin
  update public.rooms r set status = 'closed'
    where r.status = 'open' and r.last_seen < now() - interval '10 minutes';
  get diagnostics n = row_count;
  delete from public.room_members m using public.rooms r
    where r.code = m.room and r.status = 'closed';
  -- Closed codes are kept a day (so an old code says "ended"), then freed.
  delete from public.rooms r where r.status = 'closed' and r.last_seen < now() - interval '1 day';
  return n;
end
$$;

revoke all on function public.create_room, public.find_room, public.join_room, public.touch_room,
  public.leave_room, public.close_quiet_rooms, public.in_room from public, anon;
grant execute on function public.create_room, public.find_room, public.join_room, public.touch_room,
  public.leave_room, public.in_room to authenticated;

-- ----- The relay's use (D8: never past the free 1,000 GB a month) -----
-- Only the turn-credentials function reads and writes these (service role).

create table public.relay_usage (
  -- The month (its 1st), in UTC, as Cloudflare bills it.
  month date primary key,
  bytes bigint not null,
  checked_at timestamptz not null
);
alter table public.relay_usage enable row level security;

-- Every relay credential handed out, to revoke if the month's use runs high.
create table public.relay_credentials (
  username text primary key,
  member uuid references auth.users (id) on delete set null,
  room text,
  expires_at timestamptz not null,
  revoked boolean not null default false
);
create index relay_credentials_live on public.relay_credentials (expires_at) where not revoked;
alter table public.relay_credentials enable row level security;
revoke all on public.relay_usage, public.relay_credentials from anon, authenticated;

-- ----- Signaling: only a room's members join its channel ("room:CODE") -----

create policy "members hear their room" on realtime.messages
  for select to authenticated
  using (realtime.topic() like 'room:%' and public.in_room(substr(realtime.topic(), 6)));
create policy "members talk in their room" on realtime.messages
  for insert to authenticated
  with check (realtime.topic() like 'room:%' and public.in_room(substr(realtime.topic(), 6)));
