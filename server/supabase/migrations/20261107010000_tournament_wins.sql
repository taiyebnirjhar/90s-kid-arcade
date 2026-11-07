-- Tournaments T6 (docs/tournament-plan.md): tournaments won, per game, for
-- the profile. Counted on the champion's phone and synced here like the
-- games played (record_plays): a count only goes up.
--
-- Within the free plan (D8): one small row per player and game (at most 64
-- games a player), nothing else kept. The bracket itself lives on every phone
-- in the room (the host shares it; a new host carries on), so nothing of a
-- tournament in play needs the server.

create table public.tournament_wins (
  player uuid not null references auth.users (id) on delete cascade,
  game text not null check (game ~ '^[a-z0-9_]{1,32}$'),
  count int not null check (count between 0 and 100000),
  updated_at timestamptz not null default now(),
  primary key (player, game)
);
alter table public.tournament_wins enable row level security;

create policy "tournament wins are public to the signed in" on public.tournament_wins
  for select to authenticated using (true);
grant select on public.tournament_wins to authenticated;

create function public.record_tournament_wins(counts jsonb) returns void
language plpgsql volatile security definer set search_path = '' as $$
declare
  me uuid := (select auth.uid());
begin
  if me is null then raise exception 'Sign in first' using errcode = '28000'; end if;
  if jsonb_typeof(counts) <> 'object' then
    raise exception 'Counts are an object' using errcode = '22023';
  end if;
  if (select count(*) from jsonb_object_keys(counts)) > 64 then
    raise exception 'Too many games' using errcode = '54000';
  end if;
  insert into public.tournament_wins as w (player, game, count)
    select me, e.key, least((e.value #>> '{}')::int, 100000) from jsonb_each(counts) e
  on conflict (player, game) do update
    set count = greatest(w.count, excluded.count), updated_at = now()
    where excluded.count > w.count;
end
$$;

revoke all on function public.record_tournament_wins from public, anon;
grant execute on function public.record_tournament_wins to authenticated;
