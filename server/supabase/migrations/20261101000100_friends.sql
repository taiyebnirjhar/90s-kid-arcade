-- Friends (docs/online-plan.md, Step 2): add a player by their friend code
-- (or from a room you played in), accept or decline, see what your friends
-- are doing (online, the game, the room they're in), invite them to your
-- room, and block anyone.
--
-- Nothing here is readable straight from the tables: every read and write
-- goes through the functions below, which only ever show you your own
-- friends, requests, invites and blocks.

-- ----- Friendships and requests -----

-- One row per pair (a < b): a request until accepted.
create table public.friendships (
  a uuid not null references auth.users (id) on delete cascade,
  b uuid not null references auth.users (id) on delete cascade,
  status text not null default 'pending' check (status in ('pending', 'friends')),
  -- Who asked (a request waits on the other).
  asked_by uuid not null references auth.users (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (a, b),
  check (a < b),
  check (asked_by = a or asked_by = b)
);
create index friendships_by_b on public.friendships (b);
alter table public.friendships enable row level security;

create table public.blocks (
  blocker uuid not null references auth.users (id) on delete cascade,
  blocked uuid not null references auth.users (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker, blocked),
  check (blocker <> blocked)
);
create index blocks_by_blocked on public.blocks (blocked);
alter table public.blocks enable row level security;

-- What a player is doing: seen by their friends only (list_friends).
create table public.presence (
  id uuid primary key references auth.users (id) on delete cascade,
  last_seen timestamptz not null default now(),
  -- The game they're playing (null: on the menus), and their internet room (null: none).
  game_set text check (char_length(game_set) <= 32),
  room text
);
alter table public.presence enable row level security;

-- Invites to a room, for a friend: shown for 10 minutes.
create table public.invites (
  id bigint generated always as identity primary key,
  sender uuid not null references auth.users (id) on delete cascade,
  receiver uuid not null references auth.users (id) on delete cascade,
  room text not null,
  game_set text not null,
  created_at timestamptz not null default now()
);
create index invites_by_receiver on public.invites (receiver, created_at);
alter table public.invites enable row level security;
-- (No policies on any of these: the functions below are the only way in.)

/** Friends and waiting requests one player may have. */
create function public.max_friends() returns int language sql immutable as $$ select 200 $$;
/** A player counts as online this long after their app last said so. */
create function public.online_for() returns interval language sql immutable as $$ select interval '2 minutes' $$;

-- Either has blocked the other.
create function public.blocked_between(x uuid, y uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.blocks k where (k.blocker = x and k.blocked = y) or (k.blocker = y and k.blocked = x))
$$;

create function public.are_friends(x uuid, y uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.friendships f
    where f.a = least(x, y) and f.b = greatest(x, y) and f.status = 'friends'
  )
$$;

-- Asks [player] to be friends; if they'd asked you, you're friends now.
-- Says which: 'asked', 'friends' (now or already).
create function public.befriend(player uuid) returns text
language plpgsql volatile security definer set search_path = '' as $$
declare
  me uuid := (select auth.uid());
  row public.friendships;
begin
  if me is null then raise exception 'Sign in first' using errcode = '28000'; end if;
  if player is null or player = me then raise exception 'That''s your own code' using errcode = '22023'; end if;
  -- Blocked either way: as if there were no such player.
  if public.blocked_between(me, player) or not exists (select 1 from public.profiles p where p.id = player) then
    raise exception 'No player has that code' using errcode = 'P0002';
  end if;
  select * into row from public.friendships f where f.a = least(me, player) and f.b = greatest(me, player) for update;
  if found then
    if row.status = 'friends' then return 'friends'; end if;
    if row.asked_by = me then return 'asked'; end if;
    update public.friendships f set status = 'friends' where f.a = row.a and f.b = row.b;
    return 'friends';
  end if;
  if (select count(*) from public.friendships f where f.a = me or f.b = me) >= public.max_friends() then
    raise exception 'You have % friends and requests: remove some first', public.max_friends() using errcode = '53400';
  end if;
  insert into public.friendships (a, b, asked_by) values (least(me, player), greatest(me, player), me);
  return 'asked';
end
$$;

-- By their friend code ("K7Q2MX8P", any case, dashes and spaces ignored).
create function public.request_friend(friend_code text) returns text
language plpgsql volatile security definer set search_path = '' as $$
declare
  them uuid;
begin
  select p.id into them from public.profiles p where p.friend_code = public.clean_code(request_friend.friend_code);
  if them is null then raise exception 'No player has that code' using errcode = 'P0002'; end if;
  return public.befriend(them);
end
$$;

-- From a room: someone you played with (their player id).
create function public.request_friend_by_id(player uuid) returns text
language sql volatile security definer set search_path = '' as $$
  select public.befriend(player)
$$;

-- Accepts or declines [player]'s request.
create function public.answer_friend(player uuid, accept boolean) returns void
language plpgsql volatile security definer set search_path = '' as $$
declare
  me uuid := (select auth.uid());
begin
  if me is null then raise exception 'Sign in first' using errcode = '28000'; end if;
  if accept then
    update public.friendships f set status = 'friends'
      where f.a = least(me, player) and f.b = greatest(me, player) and f.status = 'pending' and f.asked_by = player;
  else
    delete from public.friendships f
      where f.a = least(me, player) and f.b = greatest(me, player) and f.status = 'pending' and f.asked_by = player;
  end if;
end
$$;

-- No longer friends (or a request taken back).
create function public.remove_friend(player uuid) returns void
language sql volatile security definer set search_path = '' as $$
  delete from public.friendships f where f.a = least((select auth.uid()), player) and f.b = greatest((select auth.uid()), player)
$$;

-- Blocks [player]: no friendship, requests or invites either way, and they
-- can't find you by code. (The app also mutes them in rooms.)
create function public.block_player(player uuid) returns void
language plpgsql volatile security definer set search_path = '' as $$
declare
  me uuid := (select auth.uid());
begin
  if me is null then raise exception 'Sign in first' using errcode = '28000'; end if;
  if player = me then raise exception 'You can''t block yourself' using errcode = '22023'; end if;
  insert into public.blocks (blocker, blocked) values (me, player) on conflict do nothing;
  delete from public.friendships f where f.a = least(me, player) and f.b = greatest(me, player);
  delete from public.invites i where (i.sender = me and i.receiver = player) or (i.sender = player and i.receiver = me);
end
$$;

create function public.unblock_player(player uuid) returns void
language sql volatile security definer set search_path = '' as $$
  delete from public.blocks k where k.blocker = (select auth.uid()) and k.blocked = player
$$;

-- Players you've blocked, for unblocking.
create function public.list_blocked()
returns table (id uuid, name text, icon smallint)
language sql stable security definer set search_path = '' as $$
  select k.blocked, p.name, p.icon
  from public.blocks k left join public.profiles p on p.id = k.blocked
  where k.blocker = (select auth.uid())
  order by k.created_at desc
$$;

-- ----- Presence -----

-- The app says what it's doing every minute while it's open: the game
-- (null: menus) and its internet room (null: none). Only friends see it.
create function public.set_presence(game_set text default null, room_code text default null) returns void
language plpgsql volatile security definer set search_path = '' as $$
declare
  me uuid := (select auth.uid());
  c text := nullif(public.clean_code(room_code), '');
begin
  if me is null then raise exception 'Sign in first' using errcode = '28000'; end if;
  -- Only a room you're really in.
  if c is not null and not public.in_room(c) then c := null; end if;
  insert into public.presence (id, last_seen, game_set, room) values (me, now(), left(set_presence.game_set, 32), c)
  on conflict (id) do update set last_seen = now(), game_set = excluded.game_set, room = excluded.room;
end
$$;

-- The app closed (or went to the background): offline now.
create function public.go_offline() returns void
language sql volatile security definer set search_path = '' as $$
  delete from public.presence p where p.id = (select auth.uid())
$$;

-- Your friends and requests: who, and what each friend is doing. A friend's
-- room comes with its code while they're in it (friends join any room,
-- locked or open); requests show only who.
create function public.list_friends()
returns table (id uuid, name text, icon smallint, status text, online boolean, game_set text, room text, room_space boolean)
language sql stable security definer set search_path = '' as $$
  with me as (select (select auth.uid()) as id),
  mine as (
    select case when f.a = me.id then f.b else f.a end as other, f.status, f.asked_by, f.created_at
    from public.friendships f, me where f.a = me.id or f.b = me.id
  )
  select m.other, p.name, p.icon,
    case when m.status = 'friends' then 'friend' when m.asked_by = (select id from me) then 'outgoing' else 'incoming' end,
    m.status = 'friends' and coalesce(s.last_seen > now() - public.online_for(), false),
    case when m.status = 'friends' and s.last_seen > now() - public.online_for() then s.game_set end,
    r.code,
    case when r.code is not null then
      (select count(*) from public.room_members rm where rm.room = r.code) < public.max_members()
    end
  from mine m
  left join public.profiles p on p.id = m.other
  left join public.presence s on s.id = m.other
  left join public.rooms r on m.status = 'friends' and s.last_seen > now() - public.online_for()
    and r.code = s.room and r.status = 'open'
  order by (m.status = 'pending' and m.asked_by <> (select id from me)) desc,
    (s.last_seen > now() - public.online_for()) desc nulls last, p.name
$$;

-- ----- Invites -----

-- Invites [player] (a friend) to your internet room. Once a minute per friend.
create function public.invite_friend(player uuid, room_code text) returns void
language plpgsql volatile security definer set search_path = '' as $$
declare
  me uuid := (select auth.uid());
  c text := public.clean_code(room_code);
  game text;
begin
  if me is null then raise exception 'Sign in first' using errcode = '28000'; end if;
  if not public.are_friends(me, player) or public.blocked_between(me, player) then
    raise exception 'Only friends can be invited' using errcode = '42501';
  end if;
  if not public.in_room(c) then raise exception 'You''re not in room %', c using errcode = '42501'; end if;
  if exists (select 1 from public.invites i where i.sender = me and i.receiver = player and i.created_at > now() - interval '1 minute') then
    return;
  end if;
  select r.game_set into game from public.rooms r where r.code = c;
  insert into public.invites (sender, receiver, room, game_set) values (me, player, c, game);
end
$$;

-- Invites to you from the last 10 minutes, to rooms still open, newest first.
create function public.my_invites()
returns table (id bigint, sender uuid, name text, icon smallint, room text, game_set text, created_at timestamptz)
language sql stable security definer set search_path = '' as $$
  select i.id, i.sender, p.name, p.icon, i.room, i.game_set, i.created_at
  from public.invites i
  left join public.profiles p on p.id = i.sender
  join public.rooms r on r.code = i.room and r.status = 'open'
  where i.receiver = (select auth.uid()) and i.created_at > now() - interval '10 minutes'
    and not public.blocked_between(i.sender, i.receiver)
  order by i.created_at desc
  limit 20
$$;

-- Seen (joined, or turned down).
create function public.dismiss_invite(invite bigint) returns void
language sql volatile security definer set search_path = '' as $$
  delete from public.invites i where i.id = invite and i.receiver = (select auth.uid())
$$;

-- Old invites and presence go, with the quiet rooms (close_quiet_rooms runs every minute).
create function public.clear_old_friend_rows() returns void
language sql volatile security definer set search_path = '' as $$
  delete from public.invites i where i.created_at < now() - interval '1 hour';
  delete from public.presence p where p.last_seen < now() - interval '1 day';
$$;

do $$
begin
  if exists (select 1 from pg_available_extensions where name = 'pg_cron') then
    create extension if not exists pg_cron with schema pg_catalog;
    perform cron.schedule('clear-old-friend-rows', '17 * * * *', 'select public.clear_old_friend_rows()');
  else
    raise notice 'pg_cron not available: clear_old_friend_rows is not scheduled';
  end if;
end
$$;

revoke all on function public.blocked_between, public.are_friends, public.befriend, public.request_friend,
  public.request_friend_by_id, public.answer_friend, public.remove_friend, public.block_player, public.unblock_player,
  public.list_blocked, public.set_presence, public.go_offline, public.list_friends, public.invite_friend,
  public.my_invites, public.dismiss_invite, public.clear_old_friend_rows from public, anon;
grant execute on function public.request_friend, public.request_friend_by_id, public.answer_friend, public.remove_friend,
  public.block_player, public.unblock_player, public.list_blocked, public.set_presence, public.go_offline,
  public.list_friends, public.invite_friend, public.my_invites, public.dismiss_invite to authenticated;
