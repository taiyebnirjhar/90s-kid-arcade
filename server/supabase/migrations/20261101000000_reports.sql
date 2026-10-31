-- Reports (docs/online-plan.md, phases 4.1 and 3.3): a player reports
-- another for what they said in chat or voice, their name, or how they
-- played. The owner reads them in Supabase's dashboard; the app never reads
-- them back, and the reported player isn't told who.

create table public.reports (
  id bigint generated always as identity primary key,
  reporter uuid not null references auth.users (id) on delete cascade,
  reported uuid not null references auth.users (id) on delete cascade,
  room text,
  reason text not null check (reason in ('chat', 'voice', 'name', 'play', 'other')),
  -- What they said (the last lines of chat), for the owner to judge.
  said text check (char_length(said) <= 2000),
  created_at timestamptz not null default now()
);
create index reports_by_reporter on public.reports (reporter, created_at);
create index reports_by_reported on public.reports (reported, created_at);
-- No policies: only report_player writes them, and nothing in the app reads them.
alter table public.reports enable row level security;

create function public.max_reports_a_day() returns int language sql immutable as $$ select 20 $$;

-- Reports [player] for [reason]. The same report of the same player again
-- within a day does nothing; more than max_reports_a_day() a day is refused.
create function public.report_player(player uuid, reason text, room_code text default null, said text default null)
returns void
language plpgsql volatile security definer set search_path = '' as $$
declare
  me uuid := (select auth.uid());
begin
  if me is null then raise exception 'Sign in first' using errcode = '28000'; end if;
  if player = me then raise exception 'You can''t report yourself' using errcode = '22023'; end if;
  if not exists (select 1 from auth.users u where u.id = player) then
    raise exception 'No such player' using errcode = '22023';
  end if;
  if exists (
    select 1 from public.reports r
    where r.reporter = me and r.reported = player and r.reason = report_player.reason and r.created_at > now() - interval '1 day'
  ) then
    return;
  end if;
  if (select count(*) from public.reports r where r.reporter = me and r.created_at > now() - interval '1 day') >= public.max_reports_a_day() then
    raise exception 'That''s a lot of reports today: try again tomorrow' using errcode = '53400';
  end if;
  insert into public.reports (reporter, reported, room, reason, said)
    values (me, player, nullif(public.clean_code(room_code), ''), report_player.reason, left(said, 2000));
end
$$;

revoke all on function public.report_player from public, anon;
grant execute on function public.report_player to authenticated;
