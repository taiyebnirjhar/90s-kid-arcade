-- The server's own access (service role), for the turn-credentials function.
-- The project doesn't open new tables to any role by itself (Data API: new
-- tables not exposed automatically), the service role included, so without
-- these it couldn't check room membership or keep the relay's month total:
-- it gave out no relay servers ("You're not in that room").
grant usage on schema public to service_role;
grant select on public.rooms, public.room_members to service_role;
grant select, insert, update on public.relay_usage to service_role;
grant select, insert, update on public.relay_credentials to service_role;
