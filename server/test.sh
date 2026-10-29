#!/usr/bin/env bash
# The server's tests: the database's rules on a stub of Supabase in plain
# Postgres ($PGURL, a database these tests may fill and empty), then the
# relay function's logic in Node.
set -euo pipefail
cd "$(dirname "$0")"
: "${PGURL:?set PGURL to an empty Postgres 16 database}"
export PGOPTIONS="-c client_min_messages=warning"
psql "$PGURL" -q -v ON_ERROR_STOP=1 -c "drop schema if exists public cascade; drop schema if exists auth cascade; drop schema if exists realtime cascade; create schema public;" \
  -c "drop role if exists anon; drop role if exists authenticated; drop role if exists service_role;" >/dev/null
psql "$PGURL" -q -v ON_ERROR_STOP=1 -f supabase/tests/supabase_stub.sql
for f in supabase/migrations/*.sql; do psql "$PGURL" -q -v ON_ERROR_STOP=1 -f "$f"; done
for f in supabase/tests/*.test.sql; do psql "$PGURL" -q -v ON_ERROR_STOP=1 -f "$f"; done
node --experimental-strip-types --no-warnings --test supabase/functions/turn-credentials/*.test.ts
