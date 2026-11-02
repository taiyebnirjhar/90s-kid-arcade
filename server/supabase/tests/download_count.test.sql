-- The website's download count (20261103000000_download_count.sql): counted
-- and read without signing in; the table itself isn't open.
\set ON_ERROR_STOP on
\o /dev/null
begin;
set local role anon;
do $$ begin
  if public.download_count() <> 0 then raise exception 'FAIL: not 0 at first'; end if;
  perform public.note_download();
  perform public.note_download();
  if public.download_count() <> 2 then raise exception 'FAIL: % downloads', public.download_count(); end if;
  begin
    update public.site_counters set n = 1000000;
    raise exception 'FAIL: wrote the counter';
  exception when insufficient_privilege then null;
  end;
end $$;
rollback;
\o
\echo 'download count: all checks passed'
