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

The app never sees these. A small server function (phase 1.2) uses them to
hand each phone relay credentials that expire, as Cloudflare recommends.

## 3. Deploying the server parts (phase 1.2, later)

The database tables, rules and functions will live in the repo
(`server/supabase/`). To put them on the project, one of:

- **Automatic (recommended):** add two GitHub secrets to the repo
  (**Settings → Secrets and variables → Actions**):
  - `SUPABASE_ACCESS_TOKEN`: from Supabase **Account → Access Tokens**;
  - `SUPABASE_PROJECT_REF`: the `xxxx` part of the project URL.

  A workflow deploys on each merge to main.
- **By hand:** paste the SQL into Supabase's **SQL Editor** and create the
  functions in the dashboard. Step-by-step instructions come with that PR.

## The project

Public values only; they go into the app in phase 1.2.

- **Project URL:** `https://ysbskjtvccdbhnfuccso.supabase.co`
- **Publishable key:** `sb_publishable_ZdWmdg-2_f5_rvnzhiviYQ_VSUnZPIE`
- **Settings:** Data API on; new tables not exposed automatically; automatic
  RLS on.

## Checklist

- [x] Supabase project created (Mumbai, Free)
- [x] Anonymous sign-ins on (captcha off until the app can solve one)
- [x] Project URL and publishable key sent
- [ ] Cloudflare TURN key created
- [ ] `CF_TURN_KEY_ID` and `CF_TURN_KEY_TOKEN` saved as Supabase secrets
- [ ] (For phase 1.2) GitHub secrets for automatic deploys, or by hand
