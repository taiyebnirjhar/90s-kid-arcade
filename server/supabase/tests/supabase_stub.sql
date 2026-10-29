-- For the tests only: what a Supabase project has before our migrations,
-- in plain Postgres. auth.uid() is the signed-in user, set by the tests
-- as Supabase sets it from the request's token.
create role anon nologin;
create role authenticated nologin;
create role service_role nologin bypassrls;
create schema auth;
create table auth.users (id uuid primary key);
create function auth.uid() returns uuid language sql stable as $$
  select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$$;
grant usage on schema auth to anon, authenticated, service_role;
grant execute on function auth.uid() to anon, authenticated, service_role;
create schema realtime;
create table realtime.messages (id bigserial primary key, topic text not null, payload jsonb);
alter table realtime.messages enable row level security;
create function realtime.topic() returns text language sql stable as $$
  select nullif(current_setting('realtime.topic', true), '')
$$;
grant usage on schema realtime to authenticated;
grant select, insert on realtime.messages to authenticated;
grant usage on sequence realtime.messages_id_seq to authenticated;
grant usage on schema public to anon, authenticated, service_role;
-- Like the project (Data API: new tables aren't exposed automatically):
-- no table is open to any role, the server's own included, unless granted.
