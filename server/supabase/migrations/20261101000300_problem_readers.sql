-- Who may read problem reports from outside the dashboard: the accounts
-- listed here (the owner adds one by its user id, see docs/online-setup.md).
-- Such an account can read problem_reports through read_problem_reports and
-- nothing else; a player's own account is never in it.

create table public.problem_readers (
  id uuid primary key references auth.users (id) on delete cascade,
  added_at timestamptz not null default now()
);
-- No policies: nobody reads or changes this through the API.
alter table public.problem_readers enable row level security;

-- The problem reports of the last [days] days (at most 30), newest first,
-- 200 at most: for a listed reader only.
create function public.read_problem_reports(days int default 3)
returns table (id bigint, created_at timestamptz, kind text, app text, phone text, room text, error text, log text)
language plpgsql stable security definer set search_path = '' as $$
begin
  if not exists (select 1 from public.problem_readers r where r.id = (select auth.uid())) then
    raise exception 'Not a reader' using errcode = '42501';
  end if;
  return query
    select p.id, p.created_at, p.kind, p.app, p.phone, p.room, p.error, p.log
    from public.problem_reports p
    where p.created_at > now() - make_interval(days => least(greatest(coalesce(days, 3), 1), 30))
    order by p.id desc
    limit 200;
end
$$;

revoke all on function public.read_problem_reports from public, anon;
grant execute on function public.read_problem_reports to authenticated;
