-- Phase 1.3 (docs/online-plan.md): the app saves your profile, and the
-- games you've played, counted on the phone and synced here.

-- Makes or changes your profile in one call, and gives it back with its
-- friend code. (The app can't send PATCH: Android's HTTP client has none.)
create function public.save_profile(name text, icon int) returns public.profiles
language plpgsql volatile security definer set search_path = '' as $$
declare
  me uuid := (select auth.uid());
  saved public.profiles;
begin
  if me is null then raise exception 'Sign in first' using errcode = '28000'; end if;
  insert into public.profiles as p (id, name, icon) values (me, btrim(save_profile.name), save_profile.icon)
  on conflict (id) do update set name = excluded.name, icon = excluded.icon
  returning * into saved;
  return saved;
end
$$;
revoke all on function public.save_profile from public, anon;
grant execute on function public.save_profile to authenticated;

-- ----- Games played -----

create table public.plays (
  player uuid not null references auth.users (id) on delete cascade,
  game text not null check (game ~ '^[a-z0-9_]{1,32}$'),
  count int not null check (count between 0 and 1000000),
  updated_at timestamptz not null default now(),
  primary key (player, game)
);
alter table public.plays enable row level security;

-- Anyone signed in sees them (a profile shows its games). Written only
-- through record_plays.
create policy "plays are public to the signed in" on public.plays
  for select to authenticated using (true);
grant select on public.plays to authenticated;

-- The phone's counts, as {"kof97": 12, ...}. A count only goes up: an
-- older phone copy (or a reinstall) never lowers what's here.
create function public.record_plays(counts jsonb) returns void
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
  insert into public.plays as p (player, game, count)
    select me, e.key, (e.value #>> '{}')::int from jsonb_each(counts) e
  on conflict (player, game) do update
    set count = greatest(p.count, excluded.count), updated_at = now()
    where excluded.count > p.count;
end
$$;

revoke all on function public.record_plays from public, anon;
grant execute on function public.record_plays to authenticated;
