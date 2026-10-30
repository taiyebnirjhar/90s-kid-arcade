-- The keep-awake call (20261031000100_keep_awake.sql): anyone with the
-- app's public key may make it, and it gives nothing away.
\set ON_ERROR_STOP on
\o /dev/null
begin;
set local role anon;
do $$ begin
  if public.keep_awake() <> 1 then raise exception 'FAIL: keep_awake'; end if;
end $$;
rollback;
\o
\echo 'keep awake: all checks passed'
