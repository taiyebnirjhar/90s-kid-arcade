-- Friends, more alive (docs/online-plan.md, Step 2 follow-up): chat with a
-- friend outside a room, see what's new at a glance (requests, unread
-- messages, invites: one small call the app makes while it's open), "last
-- seen" for friends offline, and challenges (an invite to a room made for
-- that friend, shown as a challenge).
--
-- Free plan: messages are short (300 characters), limited per player (20 a
-- minute, 300 a day), and kept 30 days, at most 200 per pair; the app asks
-- for what's new every 15 seconds while it's open and only then. No
-- notifications (the app closed hears nothing).

-- ----- Messages between friends -----

create table public.friend_messages (
  id bigint generated always as identity primary key,
  sender uuid not null references auth.users (id) on delete cascade,
  receiver uuid not null references auth.users (id) on delete cascade,
  body text not null check (char_length(body) between 1 and 300),
  created_at timestamptz not null default now(),
  read_at timestamptz,
  check (sender <> receiver)
);
create index friend_messages_pair on public.friend_messages (least(sender, receiver), greatest(sender, receiver), id);
create index friend_messages_unread on public.friend_messages (receiver) where read_at is null;
create index friend_messages_by_sender on public.friend_messages (sender, created_at);
alter table public.friend_messages enable row level security;
-- (No policies: the functions below are the only way in.)

-- Says [body] to [player], a friend. Returns the message's id.
create function public.send_friend_message(player uuid, body text) returns bigint
language plpgsql volatile security definer set search_path = '' as $$
declare
  me uuid := (select auth.uid());
  said text := btrim(regexp_replace(coalesce(body, ''), '\s+', ' ', 'g'));
  new_id bigint;
begin
  if me is null then raise exception 'Sign in first' using errcode = '28000'; end if;
  if not public.are_friends(me, player) or public.blocked_between(me, player) then
    raise exception 'Only friends can be messaged' using errcode = '42501';
  end if;
  if said = '' then raise exception 'Say something first' using errcode = '22023'; end if;
  said := left(said, 300);
  if (select count(*) from public.friend_messages m where m.sender = me and m.created_at > now() - interval '1 minute') >= 20
    or (select count(*) from public.friend_messages m where m.sender = me and m.created_at > now() - interval '1 day') >= 300 then
    raise exception 'Too many messages: wait a little' using errcode = '54000';
  end if;
  insert into public.friend_messages (sender, receiver, body) values (me, player, said) returning id into new_id;
  -- At most 200 kept per pair: the oldest go.
  delete from public.friend_messages m
  where least(m.sender, m.receiver) = least(me, player) and greatest(m.sender, m.receiver) = greatest(me, player)
    and m.id < (
      select k.id from public.friend_messages k
      where least(k.sender, k.receiver) = least(me, player) and greatest(k.sender, k.receiver) = greatest(me, player)
      order by k.id desc offset 199 limit 1
    );
  return new_id;
end
$$;

-- The chat with [player]: messages after [after_id] (0: the last 100), oldest
-- first; theirs to you are marked read.
create function public.friend_chat(player uuid, after_id bigint default 0)
returns table (id bigint, mine boolean, body text, created_at timestamptz, seen boolean)
language plpgsql volatile security definer set search_path = '' as $$
declare me uuid := (select auth.uid());
begin
  if me is null then raise exception 'Sign in first' using errcode = '28000'; end if;
  update public.friend_messages m set read_at = now()
  where m.receiver = me and m.sender = player and m.read_at is null and m.id > after_id;
  return query
    select x.id, x.sender = me, x.body, x.created_at, x.read_at is not null
    from (
      select m.* from public.friend_messages m
      where ((m.sender = me and m.receiver = player) or (m.sender = player and m.receiver = me)) and m.id > after_id
      order by m.id desc limit 100
    ) x
    order by x.id;
end
$$;

-- ----- Presence keeps "last seen" -----

-- When the player was last about (online or not); last_seen stays "online until".
alter table public.presence add column active_at timestamptz not null default now();

create or replace function public.set_presence(game_set text default null, room_code text default null) returns void
language plpgsql volatile security definer set search_path = '' as $$
declare
  me uuid := (select auth.uid());
  c text := nullif(public.clean_code(room_code), '');
begin
  if me is null then raise exception 'Sign in first' using errcode = '28000'; end if;
  if c is not null and not public.in_room(c) then c := null; end if;
  insert into public.presence (id, last_seen, active_at, game_set, room) values (me, now(), now(), left(set_presence.game_set, 32), c)
  on conflict (id) do update set last_seen = now(), active_at = now(), game_set = excluded.game_set, room = excluded.room;
end
$$;

-- The app closed (or went to the background): offline now, "last seen" kept.
create or replace function public.go_offline() returns void
language sql volatile security definer set search_path = '' as $$
  update public.presence p set last_seen = '-infinity', game_set = null, room = null, active_at = now()
  where p.id = (select auth.uid())
$$;

-- Presence is kept 30 days (for "last seen"), invites an hour.
create or replace function public.clear_old_friend_rows() returns void
language sql volatile security definer set search_path = '' as $$
  delete from public.invites i where i.created_at < now() - interval '1 hour';
  delete from public.presence p where p.active_at < now() - interval '30 days';
  delete from public.friend_messages m where m.created_at < now() - interval '30 days';
$$;

-- ----- Friends, with their chat and last seen -----

drop function public.list_friends();
create function public.list_friends()
returns table (
  id uuid, name text, icon smallint, status text, online boolean, game_set text, room text, room_space boolean,
  last_seen timestamptz, unread int, last_message text, last_message_mine boolean, last_message_at timestamptz
)
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
    end,
    case when m.status = 'friends' then s.active_at end,
    case when m.status = 'friends' then (
      select count(*)::int from public.friend_messages u
      where u.receiver = (select id from me) and u.sender = m.other and u.read_at is null
    ) else 0 end,
    l.body, l.sender = (select id from me), l.created_at
  from mine m
  left join public.profiles p on p.id = m.other
  left join public.presence s on s.id = m.other
  left join public.rooms r on m.status = 'friends' and s.last_seen > now() - public.online_for()
    and r.code = s.room and r.status = 'open'
  left join lateral (
    select k.body, k.sender, k.created_at from public.friend_messages k
    where m.status = 'friends'
      and ((k.sender = m.other and k.receiver = (select id from me)) or (k.sender = (select id from me) and k.receiver = m.other))
    order by k.id desc limit 1
  ) l on true
  order by (m.status = 'pending' and m.asked_by <> (select id from me)) desc,
    (s.last_seen > now() - public.online_for()) desc nulls last,
    l.created_at desc nulls last, p.name
$$;

-- ----- Challenges -----

alter table public.invites add column challenge boolean not null default false;

drop function public.invite_friend(uuid, text);
-- Invites [player] (a friend) to your internet room; [challenge]: the room was
-- made for them (shown as a challenge). Once a minute per friend.
create function public.invite_friend(player uuid, room_code text, challenge boolean default false) returns void
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
  if exists (select 1 from public.invites i where i.sender = me and i.receiver = player and i.room = c and i.created_at > now() - interval '1 minute') then
    return;
  end if;
  select r.game_set into game from public.rooms r where r.code = c;
  insert into public.invites (sender, receiver, room, game_set, challenge) values (me, player, c, game, invite_friend.challenge);
end
$$;

drop function public.my_invites();
create function public.my_invites()
returns table (id bigint, sender uuid, name text, icon smallint, room text, game_set text, created_at timestamptz, challenge boolean)
language sql stable security definer set search_path = '' as $$
  select i.id, i.sender, p.name, p.icon, i.room, i.game_set, i.created_at, i.challenge
  from public.invites i
  left join public.profiles p on p.id = i.sender
  join public.rooms r on r.code = i.room and r.status = 'open'
  where i.receiver = (select auth.uid()) and i.created_at > now() - interval '10 minutes'
    and not public.blocked_between(i.sender, i.receiver)
  order by i.created_at desc
  limit 20
$$;

-- ----- What's new, in one small call -----

-- Requests waiting on you, unread messages, invites, and the newest message
-- and invite (for the app's banner), while the app's open. Also keeps you
-- online (as set_presence, with what you're doing).
create function public.friend_news(game_set text default null, room_code text default null)
returns table (
  requests int, unread int, invites int,
  message_id bigint, message_from uuid, message_name text, message_icon smallint, message_body text,
  invite_id bigint, invite_from uuid, invite_name text, invite_icon smallint, invite_room text, invite_set text, invite_challenge boolean
)
language plpgsql volatile security definer set search_path = '' as $$
declare me uuid := (select auth.uid());
begin
  if me is null then raise exception 'Sign in first' using errcode = '28000'; end if;
  perform public.set_presence(friend_news.game_set, friend_news.room_code);
  return query
    with msg as (
      select m.id, m.sender, p.name, p.icon, m.body from public.friend_messages m
      left join public.profiles p on p.id = m.sender
      where m.receiver = me and m.read_at is null and not public.blocked_between(m.sender, me)
      order by m.id desc limit 1
    ), inv as (
      select * from public.my_invites() limit 1
    )
    select
      (select count(*)::int from public.friendships f where (f.a = me or f.b = me) and f.status = 'pending' and f.asked_by <> me),
      (select count(*)::int from public.friend_messages m where m.receiver = me and m.read_at is null and not public.blocked_between(m.sender, me)),
      (select count(*)::int from public.my_invites()),
      msg.id, msg.sender, msg.name, msg.icon, msg.body,
      inv.id, inv.sender, inv.name, inv.icon, inv.room, inv.game_set, inv.challenge
    from (select 1) one left join msg on true left join inv on true;
end
$$;

revoke all on function public.send_friend_message, public.friend_chat, public.list_friends, public.invite_friend,
  public.my_invites, public.friend_news from public, anon;
grant execute on function public.send_friend_message, public.friend_chat, public.list_friends, public.invite_friend,
  public.my_invites, public.friend_news to authenticated;
