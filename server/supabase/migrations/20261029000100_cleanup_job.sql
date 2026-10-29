-- Rooms nobody touched for 10 minutes close, every minute (pg_cron, which
-- Supabase has; the tests' plain Postgres doesn't, and skips this).
do $$
begin
  if exists (select 1 from pg_available_extensions where name = 'pg_cron') then
    create extension if not exists pg_cron with schema pg_catalog;
    perform cron.schedule('close-quiet-rooms', '* * * * *', 'select public.close_quiet_rooms()');
  else
    raise notice 'pg_cron not available: close_quiet_rooms is not scheduled';
  end if;
end
$$;
