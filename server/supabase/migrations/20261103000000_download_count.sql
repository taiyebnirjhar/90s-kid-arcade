-- The website's download count (2026-11-03, owner's ask: a download count on
-- the website; players not counted). The Download buttons add one; the site
-- shows it with the release downloads GitHub counts. Nothing personal kept.

create table public.site_counters (
  name text primary key,
  n bigint not null default 0
);
insert into public.site_counters (name) values ('downloads');
alter table public.site_counters enable row level security;
-- (No policies: the functions below are the only way in.)

-- The website's Download button was pressed.
create function public.note_download() returns void
language sql volatile security definer set search_path = '' as $$
  update public.site_counters c set n = c.n + 1 where c.name = 'downloads'
$$;

-- Downloads from the website so far.
create function public.download_count() returns bigint
language sql stable security definer set search_path = '' as $$
  select c.n from public.site_counters c where c.name = 'downloads'
$$;

revoke all on function public.note_download, public.download_count from public;
grant execute on function public.note_download, public.download_count to anon, authenticated;
