# Decision log

Every decision about the app, newest last, with when (Dhaka time, UTC+6), who asked, what
was decided and why, and where it lives. Read [AGENTS.md](AGENTS.md) first
for how the project works. **Add an entry for every decision, in the same
pull request that makes it** (format below). "Owner" is Taiyeb Nirjhor.

```
### YYYY-MM-DD HH:MM Dhaka · Short title (#PR)
- Asked: what the owner asked, in his words where it matters.
- Decided: what was built or chosen, and the alternatives turned down.
- Why: the reason (a report, a rule, a cost).
- Where: files, settings, docs.
```

All times are Dhaka time (Asia/Dhaka, UTC+6), the owner's. For pull requests
up to #66 they're the merge times (from git); later ones are when the
decision was made in the conversation. Get the time now with
`TZ=Asia/Dhaka date '+%Y-%m-%d %H:%M'`.

---

## Foundations (2026-10-25 to 2026-10-28)

### 2026-10-25 · The app: FBNeo on Android, controls made per game (#1–#23)
- Decided: Kotlin app on LibretroDroid with the FBNeo core (downloaded in
  CI, never committed); our own HUD (`HudView`) with one-tap specials for
  fighters from sourced move data; automatic fighter detection from game
  RAM (`docs/kof-ram.md`); ROMs only from the player (import, or the private
  library). APK built by GitHub Actions and published as the `latest` release.
- Why: arcade classics playable on a phone without a stick's skills.

### 2026-10-26 · Same-Wi-Fi multiplayer that heals (#24–#35)
- Decided: rooms on the local network (hotspot works), drop-in/drop-out,
  watchers, host migration, rejoin after drops, everyone binds to the
  room's Wi-Fi. Game art bundled into the APK at build time (no network at
  runtime). Sound on/off and volume.
- Decided (and dropped): Kotlin Multiplatform core for an iPhone version,
  put on hold the same day (#35: "Drop the iPhone groundwork").

### 2026-10-26 / 27 · Smoothness (#36, #37)
- Decided: a shared 60 fps pace across phones, frames locked to the screen,
  late frames made up, crisp sound (AudioPipe: rate control on the averaged
  fill, cubic resampler, gap-free underruns). Native tests in CI.

### 2026-10-28 · Online play, money and licensing planned (#40)
- Decided: the plan in `docs/online-plan.md` (steps 1–4, connection order
  nearby → direct → relay, free plans only with hard stops, no money while
  FBNeo is used; licensing path).

## Online play built (2026-10-29 to 2026-10-31)

### 2026-10-29 18:57 Dhaka · Seats follow the game's countdown (#41)
- Decided: a watcher's COIN/START takes a free seat, or one the game shows
  out (never started, or continue countdown over). No timer of ours. Each
  game declares `Game.seats`; `GamesTest` enforces it. Rule in CLAUDE.md.

### 2026-10-29 23:58 → 2026-10-30 05:05 Dhaka · Online phases 1.1–1.9 (#42, #44–#47)
- Decided: transport seam (1.1); Supabase server: rooms, members, Realtime
  signaling rules, TURN credentials with the 900/950 GB stop (1.2); guest
  sign-in and profile (1.3); WebRTC transport (1.4); rooms by code,
  smoothness online, a line for seats, one Online screen (1.5–1.9).

### 2026-10-30 01:44 Dhaka · More games; load a state for everyone (#43)
- Mystic Warriors, Sunset Riders, Snow Bros., J.J. Squawkers.

### 2026-10-30 13:04 → 19:17 Dhaka · Review, open/locked rooms, sizes (#48–#57)
- Decided: open rooms (listed, anyone joins; relay only, addresses hidden)
  and locked rooms (code only); every screen and text size; online games no
  longer double speed; library retries server hiccups; Supabase kept awake
  (a call every 3 days, `keep-awake.yml`); open rooms let phones on one
  Wi-Fi connect directly while still hiding internet addresses.

### 2026-10-31 13:47 Dhaka · Garou, Last Blade 1 & 2, Samurai Shodown II, Moo Mesa (#58)
- Note: Bust-A-Move 2 wanted, but the owner's file was the N64 game, not the
  arcade one; needs `pbobbl2n.zip`.

### 2026-10-31 19:05 Dhaka · Button layouts with room to press (#59)

## Social and polish (2026-10-31 to 2026-11-02)

### 2026-11-01 01:10 Dhaka · Chat, voice, friends, quick match, reports (#60)
- Asked: "Complete online setup, like voice, chat", in one PR.
- Decided: room chat (quick lines, typed, filter, 5 per 10 s, 120 chars),
  voice (WebRTC audio, open mic or push to talk, host rules), friends
  (codes, requests, presence, invites, block), quick match, player reports.
- Also decided: **automatic problem reports** (a failed join/room sends its
  connection log; players can turn it off), read by a dedicated reader
  account in `problem_readers` (the owner made it; its login is not in the
  repo). Publishing reports in public CI logs was refused (public repo).

### 2026-11-01 01:46 Dhaka · No pausing online; a live test (#61)
- Asked: online games can't be paused (cheating, griefing); a gear replaces
  pause; full test pass; online smoothness first; keep logs.
- Decided: the menu opens while the game goes on online; `LiveOnlineTest`
  (real Supabase + WebRTC); settings test; pause snapshots.
- Finding: open rooms can't connect from the cloud sandbox (public IP, no
  UDP to the relay): the open-room live test is behind `LIVE_OPEN=1`.

### 2026-11-01 02:19 Dhaka · An Online screen a 10-year-old understands (#62)
- Decided: big choices (Quick match, Make a room), a "Got a code?" card,
  friends, "Rooms you can join", same Wi-Fi as a small line.

### 2026-11-01 02:22 Dhaka · Website on arcade.taiyebnirjhor.com (#63)
- Decided: a subdomain of the owner's domain (Cloudflare DNS CNAME → GitHub
  Pages); room links use it; the old github.io links still work.
- Owner chose to keep the ROM library private behind its key.

### 2026-11-01 04:09 Dhaka · Friends chat and challenges, compact UI, GAME ON, QR, mutes (#64)
- Asked (several messages): a meaningful loading screen with the logo and
  "GAME ON"; website text as a marquee centred on the phone; landscape
  never cut; "keep the UI compact on every screen… same ratio, same feel…
  breakpoints like Tailwind… no mercy"; then smaller still with more gaps;
  mute speaker; mute on-screen messages; QR codes that work; friends with
  chat, online/offline, challenge/instant room (no notifications for now).
- Decided:
  - GAME ON loading screen (website font Barlow Condensed, OFL), Cancel.
  - One UI scale by the short side; `Breakpoints` sm/md/lg/xl; final scale
    470 design units across a 411 dp phone, text ×0.93, buttons 44/38.
  - Speaker button (all voices off), per-player mute for everyone, chat
    bubbles ON SCREEN/HIDDEN switch.
  - App Links (`assetlinks.json`, `autoVerify`) so a camera scan opens the
    app; an in-app QR scanner (Play services, no camera permission).
  - Friend chat (300 chars, 20/min, 300/day via `message_quota`, 30 days,
    200 per pair), `friend_news` (requests, unread, invites; also presence)
    every 15 s, banner + red count, challenges, last seen.
  - Link previews: a 1200×630 share card rendered at deploy from
    `site/share-card.html` (game pictures fetched then, never committed).
- Turned down: push notifications (need a Firebase project from the owner).

### 2026-11-01 12:55 Dhaka · Versions and releases (#65)
- Asked: "Are we managing tags and version on GitHub?" → build it.
- Decided: `versionCode` = run number, `versionName` `1.0.<run>`; a release
  `v1.0.<run>` per main build with "What's new"; `latest` follows. The
  version shows in Settings and Profile and goes with problem reports.
- First numbered release: v1.0.233.

### 2026-11-03 01:38 Dhaka · Background downloads, onboarding, rejoin fix, voices over the game (#66)
- Asked: library downloads stop when leaving and sometimes look done but
  aren't; "it should keep downloading on bg"; onboarding as if referred by
  Taiyeb (welcome → key → Neo Geo BIOS first → favourite game); a join
  report (Samsung M14, room S3S-TGV: "connect timed out"); game sound
  drowns voices and leaks into the mic.
- Decided:
  - One app-wide download queue + a foreground service (dataSync) with a
    notification; whole files only (size checked; Range resume where the
    Worker allows, else restart; 3 tries); Stop; Waiting.
  - `server/worker.js` answers Range (206) once redeployed by hand.
  - Onboarding, four steps, skippable, once, only on a fresh phone.
  - Rejoin bug: a late "left" from the previous visit removed the new
    membership, so Realtime refused the channel → per-player room claims
    (`RoomClaims`), and a refused channel gets back in (3 times max).
  - Sound gaps on slow phones: adaptive AudioPipe target (halved run-outs in
    a simulation; long network stalls still pause the sound by design).
  - Voices: the game ducks to 25% while anyone talks (mic level for your
    own talk), voices ×2.5, setting "Game quieter while people talk".
- Not done: making open rooms connect directly (they stay relay-only to hide
  addresses: a locked room or a challenge is faster).
- Release: v1.0.238.

### 2026-11-03 02:35 Dhaka · Welcome uses the owner's photo with pixel shades (#67)
- Asked: "Use my image as avatar instead of TN", then "swag sunglasses on
  top of my glasses… fit it well".
- Decided: `res/drawable-nodpi/invited_by.jpg` (head and shoulders,
  384 px) in the gold ring; pixel "deal with it" shades drawn on a 21×6
  grid over his glasses (44% wide, from 32.5% down), dropping in 0.5 s
  after the screen opens.
- A lower, fuller version (7 rows, filled bridge) was tried at 02:37 and
  **reverted** at 02:41 at the owner's request ("doesn't look good, back to like
  image attached").
- Note: the repo is public, so the photo is public on GitHub.

### 2026-11-03 02:45 Dhaka · Play from My Library (#67)
- Asked: a player downloaded a game and didn't know how to start it.
- Decided: games on the phone show **Play** on their Library card (Neo Geo
  games once the BIOS is there); tapping the card plays.

### 2026-11-03 02:57 Dhaka · Docs for agents (#67)
- Asked: "keep docs for every single decision with date and time and
  complete context… so a new session can continue without friction… a
  comprehensive doc for AI agents."
- Decided: `docs/AGENTS.md` (how everything works and is done) and this
  log; CLAUDE.md points to both and requires an entry per decision.

### 2026-11-03 02:58 Dhaka · Times in the docs are Dhaka time (#67)
- Asked: "Use dhaka time zone."
- Decided: every date and time in these docs is Asia/Dhaka (UTC+6); the
  earlier UTC entries were converted. New entries take the time from
  `TZ=Asia/Dhaka date '+%Y-%m-%d %H:%M'`.

### 2026-11-03 03:11 Dhaka · A download count on the website
- Asked: "we want active user/download count on website", then "only
  download counts fine, no need others".
- Decided: the website shows "N downloads" in its stats row: every tap on
  a Download button counts one on the server (`note_download`, table
  `site_counters`, read with `download_count`, both callable without
  signing in), plus the release downloads GitHub counts. Hidden until the
  number has come. Counting starts now (earlier website downloads weren't
  counted).
- Turned down (owner): player counts (daily anonymous pings from the app,
  players this week, online now). Built and removed before committing.
- Where: `server/supabase/migrations/20261103000000_download_count.sql`,
  its test, `site/index.html`.

### 2026-11-03 03:17 Dhaka · Updates inside the app
- Asked: "alert / badge on app, with button to on click update, when new
  version comes, so they don't need to download from website again and
  again. Keep it UI/UX friendly."
- Decided:
  - The website publishes `version.json` (build number, name, "What's new"
    from the newest `v1.0.*` release, the APK's address) when it deploys.
  - The app asks for it on opening (every 3 hours at most). A newer build
    shows an **Update** chip (red dot) first in the home header, and its card
    opens by itself once per version: version, what's new, **Update now**.
  - The app downloads the APK itself (progress on the card, size checked),
    then opens Android's installer (FileProvider); the first time, Android's
    "install unknown apps" switch for this app opens, explained on the card,
    and coming back installs. Same signing key, so it installs over with
    games and settings kept.
- Not chosen: Play Store updates (the app isn't on it); silent installs
  (Android doesn't allow them for sideloaded apps).
- Where: `Updates.kt`, `core/AppUpdate.kt` (+ test), `MainActivity.kt`,
  `Home.kt`, the manifest (`REQUEST_INSTALL_PACKAGES`, provider),
  `res/xml/file_paths.xml`, `.github/workflows/pages.yml`.
- Note: the first build with this sees updates only from the next one on.

### 2026-11-03 03:29 Dhaka · Server deploy: push to the linked project
- Found: #68's merge didn't put the download count on the server: the
  Supabase CLI (`version: latest`) now runs `db push` against a local stack
  unless told otherwise ("failed to connect to postgres … 127.0.0.1:54322").
- Decided: `supabase db push --linked` in `server-deploy.yml`. Its merge
  re-runs the deploy (the workflow file is one of its triggers), which
  applies the waiting migration (`20261103000000_download_count.sql`).
- Then (#69's deploy): it reached the project but stopped at "Apply 1
  migration? … Re-run with --yes"; `--linked` is also deprecated. Now
  `supabase db push --remote --yes`, and the functions deploy tries `--yes`
  first.

### 2026-11-03 03:30 Dhaka · Link previews: a small JPEG card, fuller tags
- Asked: the site's link in Messenger shows no card, title or description.
- Found: the page serves every tag and the 1200×630 card to Facebook's
  crawler (checked with its user agent); Messenger keeps what it first saw
  of a link for a long time, so the fix for an old share is Facebook's
  Sharing Debugger ("Scrape Again"). The card was a 507 KB PNG, over the
  ~300 KB some apps (WhatsApp) take.
- Decided: the card is also published as a ~120 KB JPEG (`img/share.jpg`,
  made by `pages.yml`; the pages fall back to the PNG if it can't be made),
  with `og:image:type`, `og:image:secure_url`, `og:site_name`, and
  `twitter:title`/`description` on both pages.

### 2026-11-03 03:36 Dhaka · Download count shown even without GitHub
- Asked: "Where is it" (the count still not on the site, Brave on Android).
- Found: #70's deploy put `download_count` live (it answers 0: no taps
  counted before). The stat stays hidden until the page itself reads a
  total above 0, and the GitHub part is read from the visitor's browser, so
  a browser that can't reach api.github.com (blocked or rate-limited) shows
  nothing.
- Decided: `pages.yml` bakes GitHub's release-download total into the page
  at build time (`data-github`, shown, not hidden); the browser still adds
  the live site taps and a fresher GitHub total when it can.

### 2026-11-03 03:44 Dhaka · Download count starts at 100
- Asked: "Why only 3 downloads… It should start from 100. And increase
  dynamically."
- Found: the website serves its own copy of the APK, so GitHub's release
  counter (3) never saw those downloads, and the site's taps were only
  counted from #70 on. The owner downloaded it ~50 times and friends many more.
- Decided: 100 from before counting began (the owner's estimate,
  `data-base="100"` in `site/index.html`), plus site taps and GitHub's
  release downloads. First shown in hundreds ("100+"); then the owner:
  "Keep the download count visible it shows transparency": now the exact
  number (1,234), shortened only from a million on (1.2M+) so the row
  fits, and a tap on Download adds one on screen.

### 2026-11-03 04:17 Dhaka · Releases: Actions needs "Read and write" permissions
- Found: #68's APK (v1.0.245) built and passed its tests, but publishing its
  release failed twice with "HTTP 403: Resource not accessible by
  integration", although `android.yml` asks for `contents: write` and the
  same step published v1.0.243 an hour before.
- Done: the owner set Settings → Actions → General → Workflow permissions
  to "Read and write permissions"; a fresh main build (workflow_dispatch)
  published v1.0.246 and the website serves it (`version.json` code 246).
  v1.0.245 was never released. If the 403 comes back, check that setting first.

### 2026-11-03 04:31 Dhaka · Main builds no longer say "-test"
- Asked: (screenshot) the profile shows "90s Kid Arcade 1.0.246-test".
- Found: `APP_CHANNEL: ${{ github.ref == 'refs/heads/main' && '' || 'test' }}`
  is always `test`, since GitHub's `&& ||` treats the empty string as false.
  Every main build since versioning began was labelled `-test`; nothing
  else reads the label, and updates compare `versionCode` only.
- Decided: `github.ref != 'refs/heads/main' && 'test' || ''`. Main builds
  are `1.0.<run>`, branch builds `1.0.<run>-test`.

### 2026-11-03 14:10 Dhaka · Smoothest online play, looked at every release; DNS doesn't help
- Asked: "keep looking for smoothest online play experience. On every
  release. If cloudflare dns or any dns make it faster or smoother … go for it."
- Decided: a standing rule in AGENTS.md (§2): every release, look for what
  makes online play smoother and take it, within the free plans.
- Found on DNS: a name is looked up once, when a room connects (a few ms,
  then cached); a match's packets go by address, so no DNS (Cloudflare's
  1.1.1.1 or any other) changes lag or stutter in play. The relay is already
  Cloudflare's own anycast TURN (`turn.cloudflare.com`), so each phone
  reaches its nearest Cloudflare site; inputs go on an unordered,
  no-retransmit channel (`AndroidRtc.kt`), and rollback hides the rest.
- Already in place: the input delay starts from the measured ping and
  adapts during play (`Session.delayFor`, `DelayTuner`); relay credentials
  are cached until near their expiry (`Relay.kt`); locked rooms and
  challenges try a direct connection before the relay. Next checks each
  release: real-phone connection logs (rollbacks, stalls, relay or direct)
  and anything new in WebRTC or Cloudflare's relay that cuts delay.

### 2026-11-03 14:19 Dhaka · Facebook page: logo, cover and text
- Asked: market the app, first a Facebook page: banner, profile picture,
  description.
- Decided: our own artwork only (a pixel arcade stick, the site's gold and
  Barlow Condensed lettering), no game pictures, so the page can't be taken
  down over SNK or Capcom art. Sources in `marketing/facebook/` (HTML,
  rendered to PNG); the text says free and no ads, and asks for no money.

---

## Where things stand (update whenever it changes)

As of 2026-11-03 14:19 Dhaka:

- **Open:** the Facebook page assets PR (`marketing/facebook/`).
- **Merged:** #68-#70 (in-app updates; download count server side), #71,
  #72 and #74 (download count on the site: exact, 1.2M+ past a million),
  #73 (main builds no longer say "-test").
- **Latest release:** v1.0.248 (#73). The website serves it. The owner's
  phone took the in-app update path from 1.0.246.
- **Waiting on the owner:**
  - Redeploy `server/worker.js` in Cloudflare to get download resume
    (optional; the app works without it).
  - Real-phone feedback on voice ducking (if the game "pumps" with an open
    mic, raise `MIC_SPEAKING_LEVEL` in `GameActivity`).
  - A Firebase project, if he wants notifications with the app closed.
  - `pbobbl2n.zip` (arcade Bust-A-Move 2), to add it.
- **Ideas not started:** everyone-rotates seats, a lobby, bots (Step 3),
  making Tailwind-like breakpoints cover any remaining hard-coded sizes.
- **Watch:** problem reports (reader account) after the owner's friends
  play online; the last report (2026-11-03, M14) led to the rejoin fix.
