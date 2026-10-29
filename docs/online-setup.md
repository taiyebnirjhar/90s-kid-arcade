# Online play: one-time setup

What the owner sets up once for online play (Step 1 of
[the plan](online-plan.md)): a Supabase project and a Cloudflare TURN key.
Each takes a few minutes. Both are free at our size.

**Never paste a secret** (API token, service-role key, TURN token) into a
chat, an issue or the repo. Secrets go only into the Supabase dashboard.

**Two things are public and safe to share:** the project URL and the
**publishable** (anon) key. They go into the app, and the database rules
(row-level security) decide what anyone can do with them.

## 1. Supabase project

1. Go to [supabase.com](https://supabase.com) and sign in (GitHub sign-in is
   fine). Click **New project**.
2. Fill in:
   - **Name:** `90s-kid-arcade`.
   - **Database password:** generate one and keep it in your password
     manager. The app never needs it.
   - **Region:** **South Asia (Mumbai)**, the closest to Bangladesh.
   - **Plan:** Free.
3. When it's ready, go to **Authentication → Sign In / Providers** and turn
   on **Allow anonymous sign-ins**. This is the one-tap guest sign-in; Google
   comes later, keeping the same profile.
4. Go to **Project Settings → API Keys** and copy two things to send me:
   - the **Project URL** (`https://xxxx.supabase.co`);
   - the **publishable key** (or the **anon** key, if your project shows
     that name).

   Don't send the **secret** or **service_role** key.

**Good to know:** a free project **pauses after a week with no activity**.
While we're building, open the dashboard and click **Restore** if it pauses.
Once people play regularly it won't pause; if it ever matters, the Pro plan
removes the pause.

## 2. Cloudflare TURN key (the relay)

This is the same Cloudflare account as the ROM library.

1. In the [Cloudflare dashboard](https://dash.cloudflare.com), open
   **Realtime → TURN Server** and click **Create**. Name it
   `90s-kid-arcade`.
2. It shows a **Turn Token ID** and an **API Token** once. Don't send them
   to anyone; they go straight into the next step.
3. In Supabase, open **Project Settings → Edge Functions → Secrets** (or
   **Edge Functions → Secrets**) and add:
   - `CF_TURN_KEY_ID`: the Turn Token ID;
   - `CF_TURN_KEY_TOKEN`: the API Token.

4. **For the usage stop** (the relay switches off before 1,000 GB a month;
   see D8 in the plan), the server needs to read the relay's use:
   - In Cloudflare, **Manage account → Account API tokens → Create token →
     Custom token**. Name it `90s-kid-arcade-usage`, permission **Account →
     Account Analytics → Read**, this account only. Create it and copy it
     once.
   - In Supabase secrets, add:
     - `CF_ANALYTICS_TOKEN`: that token;
     - `CF_ACCOUNT_ID`: the long id in the Cloudflare address bar, after
       `dash.cloudflare.com/` (not secret, but it lives with the token).

The app never sees these. A small server function (phase 1.2) uses them to
hand each phone relay credentials that expire, as Cloudflare recommends.

## 3. Putting the server parts on the project

The database (tables, rules, room codes, the clean-up job) and the relay
function live in the repo, in `server/supabase/`. Two ways to put them on
the project; the automatic one also keeps it up to date.

**Automatic (recommended).** Add three secrets to the GitHub repo
(**Settings → Secrets and variables → Actions → New repository secret**):

- `SUPABASE_ACCESS_TOKEN`: in Supabase, your avatar (top right) →
  **Account preferences → Access Tokens → Generate new token**, named
  `90s-kid-arcade-deploy`;
- `SUPABASE_PROJECT_REF`: `ysbskjtvccdbhnfuccso` (not secret, but kept with
  the others);
- `SUPABASE_DB_PASSWORD`: the database password you saved when you made the
  project.

Then **Actions → Server deploy → Run workflow** (and after that, on its
own, whenever the server changes on main). Without the secrets the
workflow says so and does nothing.

**By hand.**

1. **Database:** in Supabase, **SQL Editor → New query**. Paste the whole of
   `server/supabase/migrations/20261029000000_online.sql`, then **Run**;
   then the same for each later file in that folder, in name order
   (`20261029000100_cleanup_job.sql`, `20261030000000_profile_and_plays.sql`,
   ...). Run each once.
2. **Function:** **Edge Functions → Deploy a new function → Via Editor**,
   named `turn-credentials`. Add the two files from
   `server/supabase/functions/turn-credentials/` (`index.ts` and
   `relay.ts`, same names), then **Deploy**. Leave "Verify JWT" on.

**Check it worked:** **Table Editor** shows `profiles`, `plays`, `rooms`,
`room_members`, `relay_usage` and `relay_credentials`; **Integrations →
Cron** shows `close-quiet-rooms` every minute; **Edge Functions** shows
`turn-credentials`.

## The project

Public values only; they're in the app (`android/app/build.gradle.kts`) since phase 1.3.

- **Project URL:** `https://ysbskjtvccdbhnfuccso.supabase.co`
- **Publishable key:** `sb_publishable_ZdWmdg-2_f5_rvnzhiviYQ_VSUnZPIE`
- **Settings:** Data API on; new tables not exposed automatically; automatic
  RLS on.

## Checklist

- [x] Supabase project created (Mumbai, Free)
- [x] Anonymous sign-ins on (captcha off until the app can solve one)
- [x] Project URL and publishable key sent
- [x] Cloudflare TURN key created (2026-10-29)
- [x] `CF_TURN_KEY_ID` and `CF_TURN_KEY_TOKEN` saved as Supabase secrets
- [x] `CF_ANALYTICS_TOKEN` (Account Analytics: Read) and `CF_ACCOUNT_ID` saved as Supabase secrets
- [x] Server parts on the project: GitHub secrets for automatic deploys (`SUPABASE_ACCESS_TOKEN`, `SUPABASE_PROJECT_REF`, `SUPABASE_DB_PASSWORD`); first deployed 2026-10-29
