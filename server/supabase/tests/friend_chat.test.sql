-- Friend chat, news, last seen, challenges (20261102000000_friend_chat.sql):
-- Rafi and Mim are friends, Tanu isn't anyone's.
\set ON_ERROR_STOP on
\o /dev/null
begin;
insert into auth.users (id) values
  ('00000000-0000-0000-0000-00000000001a'), -- Rafi
  ('00000000-0000-0000-0000-00000000001b'), -- Mim
  ('00000000-0000-0000-0000-00000000001c'); -- Tanu
insert into public.profiles (id, name, icon, friend_code) values
  ('00000000-0000-0000-0000-00000000001a', 'Rafi', 1, 'RAFX2345'),
  ('00000000-0000-0000-0000-00000000001b', 'Mim', 2, 'MIMX2345'),
  ('00000000-0000-0000-0000-00000000001c', 'Tanu', 3, 'TANX2345');
create temp table t (k text primary key, v text);
grant all on t to authenticated;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000001a', true);
select public.request_friend('MIMX2345');
-- Mim sees a request in her news, and accepts.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000001b', true);
do $$ begin
  if (select requests from public.friend_news()) <> 1 then raise exception 'FAIL: no request in Mim''s news'; end if;
  perform public.request_friend_by_id('00000000-0000-0000-0000-00000000001a');
  if (select requests from public.friend_news()) <> 0 then raise exception 'FAIL: request still in news'; end if;
end $$;

-- Rafi messages Mim; strangers can't; empty and spam are refused.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000001a', true);
do $$ begin
  perform public.send_friend_message('00000000-0000-0000-0000-00000000001b', '  rematch?   tonight  ');
  perform public.send_friend_message('00000000-0000-0000-0000-00000000001b', repeat('x', 400));
  begin
    perform public.send_friend_message('00000000-0000-0000-0000-00000000001b', '   ');
    raise exception 'FAIL: sent nothing';
  exception when sqlstate '22023' then null;
  end;
  begin
    perform public.send_friend_message('00000000-0000-0000-0000-00000000001c', 'hi');
    raise exception 'FAIL: messaged a stranger';
  exception when insufficient_privilege then null;
  end;
  begin
    perform count(*) from public.friend_messages;
    raise exception 'FAIL: read the messages table';
  exception when insufficient_privilege then null;
  end;
  if (select count(*) from public.friend_chat('00000000-0000-0000-0000-00000000001b')) <> 2 then raise exception 'FAIL: Rafi''s chat'; end if;
  if (select body from public.friend_chat('00000000-0000-0000-0000-00000000001b') order by id limit 1) <> 'rematch? tonight' then raise exception 'FAIL: not tidied'; end if;
  if (select char_length(body) from public.friend_chat('00000000-0000-0000-0000-00000000001b') order by id desc limit 1) <> 300 then raise exception 'FAIL: not cut to 300'; end if;
end $$;

-- Mim: 2 unread, the newest in her news (for the banner) and on Rafi's row; reading clears them.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000001b', true);
do $$
declare n record; f record;
begin
  select * into n from public.friend_news();
  if n.unread <> 2 or n.message_name <> 'Rafi' or n.message_body <> repeat('x', 300) then raise exception 'FAIL: news %', n; end if;
  select * into f from public.list_friends() where name = 'Rafi';
  if f.unread <> 2 or f.last_message_mine then raise exception 'FAIL: Rafi''s row %', f; end if;
  if (select count(*) from public.friend_chat('00000000-0000-0000-0000-00000000001a') where not mine) <> 2 then raise exception 'FAIL: Mim''s chat'; end if;
  if (select unread from public.friend_news()) <> 0 then raise exception 'FAIL: still unread after reading'; end if;
  -- Only what's new after the last one she has.
  perform public.send_friend_message('00000000-0000-0000-0000-00000000001a', 'yes!');
  if (select count(*) from public.friend_chat('00000000-0000-0000-0000-00000000001a',
      (select max(id) from public.friend_chat('00000000-0000-0000-0000-00000000001a') where not mine))) <> 1 then
    raise exception 'FAIL: after_id';
  end if;
end $$;

-- Rafi sees his messages read; spam stops at 20 a minute.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000001a', true);
do $$ begin
  if exists (select 1 from public.friend_chat('00000000-0000-0000-0000-00000000001b') where mine and not seen) then raise exception 'FAIL: not seen'; end if;
  for i in 1..18 loop perform public.send_friend_message('00000000-0000-0000-0000-00000000001b', 'spam ' || i); end loop;
  begin
    perform public.send_friend_message('00000000-0000-0000-0000-00000000001b', 'one too many');
    raise exception 'FAIL: no rate limit';
  exception when sqlstate '54000' then null;
  end;
end $$;

-- A challenge: Rafi makes a room for Mim and challenges her; she sees it in her news, as a challenge.
insert into t select 'room', (public.create_room('kof97', 1, 2, 2, false)).code;
select public.invite_friend('00000000-0000-0000-0000-00000000001b', (select v from t where k = 'room'), challenge => true);
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000001b', true);
do $$
declare n record;
begin
  select * into n from public.friend_news();
  if n.invites <> 1 or n.invite_name <> 'Rafi' or not n.invite_challenge or n.invite_set <> 'kof97'
    or n.invite_room <> (select v from t where k = 'room') then raise exception 'FAIL: challenge news %', n; end if;
  if not (select challenge from public.my_invites()) then raise exception 'FAIL: not a challenge'; end if;
end $$;

-- Mim goes offline: Rafi sees her offline, with when she was last seen.
select public.go_offline();
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000001a', true);
do $$
declare f record;
begin
  select * into f from public.list_friends() where name = 'Mim';
  if f.online or f.game_set is not null or f.last_seen is null or f.last_seen < now() - interval '1 minute' then raise exception 'FAIL: Mim offline %', f; end if;
end $$;

-- Blocked: no more messages either way, and none shown.
select public.block_player('00000000-0000-0000-0000-00000000001b');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000001b', true);
do $$ begin
  begin
    perform public.send_friend_message('00000000-0000-0000-0000-00000000001a', 'hey');
    raise exception 'FAIL: messaged after a block';
  exception when insufficient_privilege then null;
  end;
  if exists (select 1 from public.friend_chat('00000000-0000-0000-0000-00000000001a')) then raise exception 'FAIL: chat shown after a block'; end if;
end $$;
-- The daily limit counts sends, not what's kept: trimming old messages gives no more.
reset role;
insert into public.message_quota (id, day, sent) values ('00000000-0000-0000-0000-00000000001c', current_date, 300);
insert into public.friendships (a, b, status, asked_by) values
  ('00000000-0000-0000-0000-00000000001b', '00000000-0000-0000-0000-00000000001c', 'friends', '00000000-0000-0000-0000-00000000001b');
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000001c', true);
do $$ begin
  begin
    perform public.send_friend_message('00000000-0000-0000-0000-00000000001b', 'one more');
    raise exception 'FAIL: no daily limit';
  exception when sqlstate '54000' then null;
  end;
end $$;
rollback;
\o
\echo 'friend_chat: ok'
