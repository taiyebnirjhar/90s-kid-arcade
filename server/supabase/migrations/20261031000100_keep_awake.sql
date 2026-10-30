-- Keeps the free project from pausing (Supabase pauses one after a week
-- with no activity): the "Keep Supabase awake" workflow calls this every
-- few days with the app's public key. It touches the database and says
-- nothing about it (no rows, no counts).
create function public.keep_awake() returns int
language plpgsql stable security definer set search_path = '' as $$
begin
  perform 1 from public.rooms limit 1;
  return 1;
end
$$;

revoke all on function public.keep_awake from public;
grant execute on function public.keep_awake to anon, authenticated;
