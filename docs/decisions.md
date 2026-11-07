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
- Decided: every game's controls placed by its kind and button count, no
  two controls closer than 4 dp, checked on three screens, three sizes and
  left-handed (`HudLayoutTest`). The rules: `docs/adding-a-game.md`,
  "Button layouts".

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
- First made with our own artwork only (a pixel arcade stick). The owner
  then chose the link card's look for the banner ("I like this as banner
  image better": game title screens beside "Game on") and the logo as
  lettering only, no controller. Game pictures on the page are his call;
  they're fetched when rendered, never kept in the repo. Sources in
  `marketing/facebook/` (HTML, rendered to PNG); the text says free and no
  ads, and asks for no money.
- Then (owner's screenshots): on the phone app the cover was cut on both
  sides and the profile picture covered it, and it looked blurry. Now the
  words are centred in the top half, title screens fill both sides in
  sharp pixels, and it's uploaded as PNG.
- Then: "This description and post looks ai generated for emojies and its
  tone… mimic my writing style". Rewritten in his voice: short, plain,
  first person ("So I built it"), no emojis.

### 2026-11-03 15:58 Dhaka · Recording matches: planned (docs/recording-plan.md)
- Asked: a video of a real KOF 2002 Magic Plus match to post; then
  streaming to YouTube ("one click"), then: "I need to record our game play.
  That's why i asked about streaming", platforms that can't block us first.
- Decided: record in the app, no platform: a match is saved as its start
  state plus every input and the 2 s RAM checksums; **Save video** replays
  it off screen into a 1080p60 MP4 in the gallery. Nothing runs during
  play, nothing is hosted by us. Off by default (owner: "by default the
  record needs to be off"), offered in onboarding; when on, the last 20
  matches are kept, starred ones for good; **Replays** on home.
- Streaming (YouTube sign-in: Google's review and a 100-tester limit until
  then; Twitch the easiest) comes later on the same encoder.
- Phases R1-R5 in the plan, one PR each.

### 2026-11-03 16:11 Dhaka · Recording R1: matches saved (off by default)
- Asked: go ahead with recording; off by default, offered in onboarding;
  share to WhatsApp, Messenger and Facebook (added to R3).
- Done: the emulator records every match's start state, inputs and
  checksums (native `MatchRecorder`); the app keeps each one 10 s or longer
  in `filesDir/matches` (`.match`: info, then deflated runs of inputs,
  sums, state), the newest 20 plus starred. Settings → Save my matches,
  and onboarding step 4 of 5 ("Save your matches?": yes / not now).
- Found: online play's RAM checksum starts from 1469598103934665603, not
  FNV's own 14695981039346656037 (a digit short). Harmless, and changing it
  would split phones of different versions: kept, and pinned by a test.
- The proof that a replay is the match moves to R2, where matches can be
  watched (it needs the watcher's replay path); the plan says so.

### 2026-11-03 16:17 Dhaka · Recording R2: Replays (watch a saved match)
- Asked: "Yes start R2, tell me when to merge".
- Done: a **Replays** chip on home (shown once saving is on or a match is
  saved) opens the list: game, players, length, date; star to keep, delete,
  the saving switch, space used. Tapping one plays it in the game screen
  as a room's watcher: the saved state, then every seat's inputs handed in
  4 frames ahead (`REPLAY_LEAD`; more and a watcher races to catch up).
  Each 2 s checksum is compared with the saved one (`ReplayCheck`); at the
  end it says "the same as the match ✓" or when it went different.
- Not yet proven on a phone: that needs the owner to play and watch one.

### 2026-11-03 17:26 Dhaka · Recording R3: Save video and share
- Asked: "How to use this replay? … upload somewhere or share as video with
  friends", then "complete the phase end to end".
- Done: The **download** icon on each match in Replays plays it through once while the
  phone's encoders make a 1080p60 MP4 (sharp pixels, dark bars, game sound,
  720p if 1080p isn't possible), into the gallery album "90s Kid Arcade"
  (before Android 10: the app's Movies folder). Then **Share it**: WhatsApp,
  Messenger, Facebook (the ones installed; `<queries>` in the manifest),
  More… (Android's list). Each opens that app with the video attached.
- Decided: made in real time on screen (the replay at its own speed, a
  progress line), not off screen faster: it reuses the watcher's replay
  path, which works; faster can come later. Leaving while it's made drops it.
- Small: space shows KB under a megabyte (it said "0.0 MB").
- Then (owner): "Instead of video button, download icon does make better
  sense?" Yes: a download icon (an arrow into a tray) beside the star, in
  the same grey as the star and delete icons (owner: "Not yellow. The
  gray. But like others"); the
  screen says "Saving the video… N%".
- Not yet proven on a phone: the owner makes one and shares it.

### 2026-11-03 20:23 Dhaka · Match videos: as the game looks and runs on the phone
- Owner (first video, 1.0.256, VLC): "The video and sound didn't match. The
  frame rates looked odd… it should be exact as when we saw the real app
  game play. There is a graphical look difference."
- Found: (1) the video ran at the game's own 59.19 frames a second, while
  the app shows every game at 60 (rooms by the clock, alone with a 60 Hz
  screen): played on a phone, a judder about once a second. (2) Frames and
  sound were dropped whenever the encoder fell behind (the capture queue
  was capped), so picture and sound came apart. (3) The game screen draws
  smooth (its default shader is plain bilinear) and as wide as the Screen
  setting (16:9 by default); the video was 4:3 with bars and hard pixels.
- Decided: the video at exactly 60, its sound fitted to that as the app
  plays it (`AudioStretch`, by the game's rate / 60, tested: the right
  count, no click between blocks); the replay holds its next inputs while
  more than 6 frames wait for the encoder (`captureBacklog`), so nothing
  is dropped; the picture smooth and the player's Screen shape.
- Then: a 26-minute match's video was 2.5 GB ("Damn the file size is 2.5
  GB"). The bitrate goes from 12 to 8 Mbps at 1080p (5 at 720p): about 60
  MB a minute, still clean for pixel art. Short clips need the trim (R5),
  next.

### 2026-11-03 21:42 Dhaka · The update downloads in the background too
- Asked: (screenshots: 1.0.259's update stopped at 33%, "The download
  stopped (Software caused connection abort)") "Download drops if move out
  of app. We had same issue on game download and fixed it… all download
  should be support bg. Remember on doc so same issue never appears."
- Found: the update downloaded on a plain thread of the app; leaving the
  app let Android cut its connection. Game downloads already ran in
  `DownloadService` (a foreground service).
- Decided: `UpdateService`, a foreground service like `DownloadService`
  (notification with progress, wake and Wi-Fi locks), runs the update's
  download; a dropped connection is tried again up to 5 times (2-16 s
  apart), carrying on from the bytes it has (Range; a 416 starts over),
  and "Try again" carries on too. Done with the app on screen: the
  installer opens; in the background: a notification "Update ready to
  install". A hard rule in AGENTS.md: every download runs this way.
- Note: phones on 1.0.259 or older still have the old download; this fix
  helps from the next update on (keep the app open for this one).

### 2026-11-03 21:56 Dhaka · Match videos: each frame carries its own sound
- The owner sent 1.0.259's video (Drive). Measured (ffprobe): picture
  1920x1080, exactly 60 fps, 10,780 frames, 179.7 s, no gap between frames;
  the look right (16:9, smooth). The sound: 195.6 s, 8.9% long, exactly
  48000/44100: KOF 2002 Magic Plus says its sound is 44100 a second and
  makes 48000, so the sound fell 16 s behind the picture by the end.
- Decided: never trust the reported rate. The emulator hands over each
  frame with the sound made with it; `FrameSound` makes each frame's sound
  exactly its 1/60 of a second (800 samples at 48000; tested: 812 or 800
  in, 800 out, no click). Picture and sound can't come apart, for any game.
  `AudioStretch` (by the reported rate) is gone.

### 2026-11-03 23:27 Dhaka · Gameplay videos: story first (marketing/video-playbook.md)
- Owner on draft 1 (a "one more round" edit of a 3-minute match): "it has
  no point. No story, nothing… research first for viral game play style /
  game play video plots… analysis each to understand what works."
- Decided: a playbook in `marketing/video-playbook.md`: rules (one story
  said in the first second, an open loop with a late payoff, under 25 s,
  relatable arcade in-jokes), seven story formats to try, and a section of
  breakdowns of viral examples the owner sends (TikTok/Reels pages can't be
  read from here; downloaded files can be analysed frame by frame).

### 2026-11-05 02:04 Dhaka · Spin Master, In the Hunt and Stone Ball
- Owner sent twelve zips: "I want to add these games… Yes go ahead with
  Stone Ball and Spin Master", then "Check these as well".
- Decided: add the three that boot and play in FBNeo: Spin Master (Neo
  Geo), In the Hunt (Irem M92) and Stone Ball (Art & Magic, 4 players).
  Only the versions checked are listed: `inthunt` (World) and `stonebal`
  (4 players); the 2-player Stone Balls would leave two seats empty.
- Seats: Spin Master uses the Neo Geo BIOS bytes (checked: 1, 2, then 0).
  In the Hunt shows each player's state in their corner of the score line
  (raw 0x3E50, 0x3EB0: 0 playing, 32 countdown, 84 out), with raw 0x11284
  saying a game is on. Stone Ball keeps one byte of flags for all four
  players (raw 0x1C3F on the join screen, then 0x1C6B), so `SeatStatus` got
  `flagBytes`: each seat's bit instead of an offset; raw 0x17041 is 239
  while a game is on. All three replay a savestate exactly.
- Not added, and why: Knights of Valour, KOV 2 Plus and The Gladiator need
  the newer PGM BIOS (`pgm.zip` with `ddp3_bios.u37` and `bios.u42`; the
  one sent is older); KOV2 106 also lacks `pgm_a1201.u4` and
  `pgm_a1204.u10`; Alien vs Predator (`avspu`) lacks `avspu.key` and four of
  its graphics files are other dumps. Vampire Savior (`vsav`, `vsavu`)
  works and can be added next as a six-button fighter.

### 2026-11-05 09:51 Dhaka · Vampire Savior
- Owner: "Add vampire saviour. And tell me which files to upload on r2".
  Upload `vsav.zip` and `vsavu.zip` (`qsound_hle.zip` isn't used by FBNeo).
- Decided: add it as a six-button fighter like Marvel vs Capcom: one
  fighter each, the move ring and detection, and Dark Force (LP+LK) as the
  system move. Versions listed: the six 970519 ones (Europe, USA, Japan,
  Asia, Brazil, Hispanic); not the bootleg or the 2026 hack.
- Found in the game (tools/emu.py): fighter blocks at 0xFF8400 and 0xFF8800;
  character +0x382 (15 fighters, IDs 0-15 without 11), X +0x10, facing
  +0x0B, 0 on the floor at +0x38, gauge level +0x109; 0xFF8200 is 1 on the
  stage (the attract demo too); each player's state at +0x384 (0 out, 6
  playing and through the countdown, 10 when it ran out), which is also
  the seat byte.
- Moves from command.dat, every one tried in the game
  (tools/vsav_roster.py, tools/movetest.py). Left out: moves that only come
  out as a reversal, and the 360 grabs: the stick passes up on the way
  round, so the fighter jumps first (the app's input does the same).

### 2026-11-05 21:52 Dhaka · Every game's button layout checked
- Owner: "Check every single games layout. I checked the recent one, those
  layout was not aligned properly. We supposed to have doc for this
  decision of prev."
- Found, looking at all 31 games' layout pictures: Stone Ball's three
  buttons sat as a square with a corner missing (C over A, nothing over B),
  and a fighter's lone system move (Vampire Savior's Dark Force, The Last
  Blade's Low repel) sat to one side. The #59 entry had no rules written.
- Decided: three big buttons make a triangle, C centred over A and B; a
  lone system move is centred over the buttons. The layout rules are now
  written down (`docs/adding-a-game.md`, "Button layouts"), and adding a
  game includes looking at its layout pictures beside a game like it.

### 2026-11-06 00:49 Dhaka · More consoles (PSP, PS1, Dreamcast): looked at, dropped
- Owner wanted better-looking games than the arcade's, for himself only.
  Looked at which consoles could run inside the app (as libretro cores):
  PSP, PS1, Dreamcast, Naomi, N64 on most phones; GameCube, Wii, 3DS on a
  strong chip; not PS2 and later, Xbox, Wii U, Switch. His phone, a Galaxy
  M14 4G (Snapdragon 680), runs PS1 well, most PSP games at 1x, Dreamcast
  with slowdowns, GameCube not at all. A PSP plan (PPSSPP core, a second
  Cloudflare account as its own library) was written.
- Owner, after watching gameplay videos: "Drop the idea". Decided: the app
  stays arcade (FBNeo) only; the PSP plan is removed. If it comes back, the
  findings above are the starting point.

### 2026-11-06 02:37 Dhaka · PSP back, for three games: God of War and Assassin's Creed
- Owner: "Lets add gow and assassin creed. Make a plan … the game should
  never lags and hangs -- we will try our best to manage and adjust". Asked
  after the more-consoles idea was dropped; the arcade games stay as they
  are.
- Decided: God of War: Chains of Olympus, Ghost of Sparta and Assassin's
  Creed: Bloodlines through PPSSPP's libretro core, offline. A game ships
  only if it meets "never lags, never hangs" on his Galaxy M14 4G (full
  speed in 99% of seconds over 30 minutes at 1x, no sound gaps, no freeze
  over a second, a save every minute); otherwise it's dropped, never
  shipped slow. Measured in our own app (P1's speed readout): he won't use
  the PPSSPP app ("It felt complecated. And the ui is mess. Thats why we
  are adding on our app at the first place").
- The three fit in the existing library (about 2.5 GB as CSO), so no second
  Cloudflare account. Plan: `docs/psp-plan.md`.

### 2026-11-06 03:14 Dhaka · PSP P1: Ghost of Sparta first
- Owner: "We will first check and stable ghost of Sparta. So go accordingly"
  (his download: a 950 MB `.7z`, USA).
- Built: a PSP section of its own (Home → PSP), apart from the arcade game
  screen. "Add a game" unpacks a `.7z`/`.zip` download on the phone (or takes
  an `.iso`/`.cso`/`.chd`); the game runs with PPSSPP's fast settings for his
  Snapdragon 680; a speed counter on screen measures the plan's rule; frame
  skip (Off, 1, Auto) from the menu. The PPSSPP core and its fonts/shaders
  come from the libretro buildbot in CI, like FBNeo.
- Can't be measured here (the sandbox has no GPU): his report from the
  phone decides what P2 tunes.

### 2026-11-06 15:05 Dhaka · PSP: Ghost of Sparta's first run, and P2 begun
- Owner's screenshot: 60 fps, full speed 96%, 19 slow seconds of 478,
  longest stall 921 ms (frame skip off). Asked: improve the layout per game
  ("combination of buttons, quick combo… keep control as far possible from
  the screen"), settings, graphics, and optimise as much as possible.
- Decided: frame skip Auto by default (a skipped picture, never a slower
  game); the counter on the emulator's thread with a stall list; per-game
  settings (sharpness 1x/2x, picture Sharp/Smooth/Enhanced edges, faster
  effects, controls size and see-through), saved per game; controls at the
  edges, movable, with God of War's labels, a Roll (L+R) combo, SELECT
  hidden. 2x sharpness stays a choice: on a Snapdragon 680 it likely costs
  full speed, so the phone decides.
- R2: not needed yet (games are picked from the phone until P3).

### 2026-11-06 15:10 Dhaka · PSP games use the arcade (KOF 2002) controls
- Owner: "Didnt like the layout. Select and start should be on top like
  arcade. Buttons and layout should exactly like kof 2002 layout … We show
  based layout, based on game we show dedicated buttons. Dont want psp
  looking buttons. Button Name / label can be as game suggest."
- Decided: the PSP screen uses `HudView` itself (the arcade layout, editor,
  size and see-through); the PSP-style pad is removed. A game's
  `PspProfile` gives its button names, pills, Block and ring moves. The
  top bar's COIN pill can be renamed (`coinLabel`), "SELECT" for PSP.
- God of War's ring combos wait for the game's own move list from the
  owner, rather than a guessed list.
- Then: "remove unwanted button which that game dont use … we can use psp
  labels like o, square, x … But layout should like ours". Decided: the
  face buttons show the PSP's symbols (□ ✕ △ ○, upright) with the game's
  names under them; a profile hides what its game never uses (God of War:
  SELECT).

### 2026-11-06 15:22 Dhaka · God of War's own buttons, from the owner's move list
- Owner sent a move list for Ghost of Sparta (with GameFAQs and manual
  links). It says L held blocks (tap parries), R + stick rolls, R held is
  Thera's Bane, L+○ Hyperion Charge, L+✕ casts the magic picked with the
  D-pad, D-pad ↓ swaps weapons, SELECT opens a menu.
- Decided: God of War's profile follows it: Block holds L; pills Roll (R),
  Weapon (D-pad ↓), Fire (R held while pressed); the ring: Hyperion Charge,
  Spirit of Hercules (□□△), Cyclone of Chaos (□ held), Atlas Spark, Erinys
  Void, Boreas Freeze (each picks its magic, then casts). SELECT shown.
  PSP-only steps (D-pad taps, held buttons) play from `PspSteps`. The list
  reads partly machine-written, so every input gets checked in the game
  (the owner is sending the game for that).
- Then the owner's corrected list (against the game's manual): Roll is
  L+R with the stick; R held is Thera's Bane, or aims the spear with the
  Arms of Sparta. Roll's pill now presses L+R; Fire is "Fire / Aim".
- Then: "I dont see L+R. Or sepetate L and R if needed". Decided: every PSP
  game has L and R pills that stay pressed while held (`SysButton.holdKey`),
  named for what they do (God of War: L Block, R Fire / Aim), beside Roll
  (L+R) and Weapon. The fighter layout gets a fourth pill spot (beside C);
  `PspHudTest` fails if any PSP controls overlap.

### 2026-11-06 15:48 Dhaka · The PSP screen dressed like the arcade games
- Owner: "Make sure u polish and clean the ui. Like other games. We have
  aleady decision for those, settings ui, responsiveness, layout button
  responsive ness, customisation etc."
- Decided: the PSP game screen uses the arcade's own pieces, no plain
  lists: the pause menu (`PauseContent`, with "Graphics & speed" and
  "Speed report" added through its new `extra` items), Settings
  (`ControlsPanel`, the same saved settings as the arcade games: stick,
  size, see-through, left-handed, vibration, sound, move timing), the layout
  editor bar with its size slider and gear (`EditBarContent`,
  `EditAllSheet`), layouts kept per game and per hand. Graphics & speed is
  a panel in the same style, kept per game. Leaving the app opens the
  pause menu. `Overlay`, `PickRow`, `StepRow` and `ToggleRow` are shared
  (internal) for this.

### 2026-11-06 16:08 Dhaka · The PSP game log, off the game screen
- Owner: "Where hv u set the logs. It should not visible on game play. But
  under setting keep a section where i just copy and paste complete log of
  game play so u understand and can improve based on it".
- Decided: the speed counter is off during play by default (Graphics &
  speed can switch it on). The pause menu's **Game log** (also a button in
  Graphics & speed) shows the whole session: phone, app version, game,
  settings and controls; full-speed summary; speed second by second; every
  stall and when; and what happened (paused, saved, settings changed, left
  the app, errors). **Copy** puts it on the clipboard for the chat; **Send**
  shares it. It is saved on pause and quit (`psp/logs/<game>.txt`), and the
  PSP list has a **Log** button per game to copy the last one (`PspLog`).

### 2026-11-06 16:13 Dhaka · A game log for every game, alone and online
- Owner: "Make for all games. So i can just copy and give u logs for both
  online and offline plays".
- Decided: every game's Settings has a **Game log** tab (and the pause menu
  a "Game log" item that opens it), arcade and PSP, alone, in a room or
  watching: phone and app, the game and how it's played (alone, nearby or
  online room, host or player), the room's status and ping, controls,
  speed second by second (frames counted on the emulator's thread; under
  52 fps is slow for an arcade board, under 57 for the PSP), stalls, late
  frames and sound gaps, what was done, and the room's connection log
  (`NetLog`). **Copy** and **Send**. The last session per game is saved
  (`logs/<game>.txt`) on leaving the app, quitting, an error or a room
  ending. One shared piece: `GameLog`, `SpeedMeter`, `GameLogs`
  (`GameLog.kt`) and the tab in `ControlsPanel`.

### 2026-11-06 16:21 Dhaka · Next after PSP P2: weaker phones
- Owner: "After that we will refine, so it supports weaker phone than mine
  means other phones as well. If needed with mininal graphics an all. For
  that i will give u the file first. Keep that in note".
- Noted as P5 in docs/psp-plan.md: once P2 holds on his phone, the PSP
  games also run on weaker phones, with minimal graphics where needed.
  He gives the game file first; starts when he says so.

### 2026-11-06 20:23 Dhaka · Opacity that shows, and the D-pad on PSP
- Owner (1.0.279, Ghost of Sparta): "i tried to reduce opacity. Didnt
  worked. Check the complete settings, control and all other things" and
  "we removed up down left right button. Now i cant go down and check
  Control on the page".
- Found: Opacity's lowest (30%) still drew the controls at 69% against 76%
  by default, so lowering it hardly showed (every game). The game's menus
  answer only the D-pad, and the stick works the analog stick.
- Decided: Opacity goes from 10% (faint) to 100% (solid); the default
  looks as before (`viewAlpha`). A PSP game's top bar has a **D-PAD**
  switch (where a fighter's FACING sits): on, the stick works the D-pad
  (menus, a game's D-pad picks); off, the analog stick. PSP Settings shows
  only what applies (Controls, Moves, Sound, Game log; the picture is in
  Graphics & speed), and Cancel puts back what's saved.

### 2026-11-06 20:28 Dhaka · PSP story scenes feel like fast-forward; a skip button
- Owner: "we want our game feels native. So no lag is priority. But fast
  forward felt on story Disturb exp when i want to see the story. We can
  add skip story button. Again game should play smooth and native. Like
  video is running. No lag."
- Not found from here yet (the game isn't in the sandbox). Suspects: frame
  skip Auto showing a story scene in jumps, the pacing making up late
  frames two at a time, or a story scene's own frame rate. Decided: the
  game log now shows it: seconds faster than the game's own speed
  ("fast: 90 fps"), and for PSP too the playback line (frames a second,
  paced with the screen or by the clock, the screen's Hz), late frames
  made up and sound gaps each second they happen.
- The skip button waits for the game file: the game's own skip input is
  checked in the game first, not guessed.

### 2026-11-06 21:54 Dhaka · The update that didn't show
- Owner: "I dont see update option app. Havent it released? Please check"
  (1.0.279 on the phone, 1.0.282 released).
- Found: the website's deploy after the 1.0.282 build timed out at GitHub
  Pages, so `version.json` still said 1.0.279; re-run. And the app asked
  at most every 3 hours while it stayed open in the background.
- Decided: the app asks every 15 minutes at most (opening or coming back
  to it); a failed website deploy after a build gets re-run (AGENTS.md).

### 2026-11-06 23:55 Dhaka · Ghost of Sparta played here: the story's speed fixed
- The owner sent the game (a Google Drive link), so it runs here now
  (`tools/pspemu.py`: PPSSPP with the app's settings, Xvfb + Mesa).
- Found: story videos ran about 1.5x fast. During them the core gave 67,000
  sound samples per second of frames instead of 44,100: each frame the app
  asked for ran on to the game's next picture (the video shows ~30 a
  second). PPSSPP's `ppsspp_frame_duplication` ("Render Duplicate Frames to
  60 Hz") keeps one game frame per screen frame: with it, 44,100 and the
  video's own 30 pictures a second. The app never set it, and the core
  took it as off. Decided: always on. Gameplay and menus measure the same
  either way (44,100; story videos cost ~1.6 ms a frame here, gameplay ~17).
- Also checked in the game: the title menu answers only the D-pad, not the
  stick (the D-PAD switch was right); every other option the app sets is a
  real option and value of this core.
- Skip story: the opening story can't be skipped by any button, tapped or
  held (START, SELECT, ✕, ○, △): the game doesn't allow it there. Later
  scenes to be tried as the game goes on.

### 2026-11-06 23:57 Dhaka · God of War, first party: the plan
- Owner: "Our save state didnt worked on gow. Not all L + O available. Not
  all combination is here also not all combination worked. We need to make
  the gow game and controller first party native support … improve layout,
  combos, buttons, max ux, graphics and butter smooth game play … Also add
  gow card on app like other app … can we use google drive as our library?"
- Checked here: quick save and load work in the core (a load shows a few
  frames later). A PSP game doesn't yet go on from where it was left, as
  the arcade games do: that's G1.
- Decided: the plan G1-G6 in docs/psp-plan.md (saves, every move checked in
  the game, a God of War layout, smoothness, the Home card, the library).
  Google Drive can be the library behind the Worker; R2 stays first choice.

### 2026-11-07 00:17 Dhaka · God of War: saves, smoother sound, a true speed count, Mash
- Owner: sent an hour's game log (1.0.284) and "Do all step phase by phase.
  I will merge a single pr".
- The log: (1) story videos dropping ~43,000 sound samples a second: the
  1.5x story speed, fixed by frame duplication; (2) minutes away from the
  app counted as stalls (1,488 s) and slow seconds, so "34% full speed" was
  wrong; (3) hundreds to 2,000 sound samples dropped a second in play
  (crackles); (4) no save or load in that session.
- Decided and done:
  - G1: the game is kept whenever it pauses (a menu, leaving the app) and
    opening it goes on from there ("Back where you left off"); Start over
    drops it. Quick save and load as before (a load shows a few frames on).
  - G4: the speed count holds while the game is paused or in the
    background, for PSP and arcade (`SpeedMeter.hold`). PSP sound uses the
    12-frame buffer: PPSSPP hands sound over unevenly (0 to ~2,000 samples
    a frame, backlogs of ~2,000, measured in the game), which overflowed
    the 6-frame one.
  - G2 begun: a **Mash** pill alternates L and R twelve times (the game's
    "alternate L and R quickly"); checked in the game, it escapes the
    boat's first grab. On the bottom row past the ring (the layout test
    found every spot nearer the buttons taken).
- Next (G2): every move tried in the game, close up; L + each face button.

### 2026-11-07 00:33 Dhaka · God of War: moves checked in the game, a card on Home
- Owner: "Except adding it to library. U can add card".
- Moves, checked in the game (one fresh run per move from the same moment,
  two seconds after a load, compared with doing nothing): □, △, ○ attack;
  **Hyperion Charge is L held, then ○** (both at once is just ○: the old
  ring button did a plain grab). With L held, □ △ ✕ do nothing until Kratos
  has magic, so the magic casts now press L first too (to check once the
  game gives magic). New step form "E>D" (first held, then both).
- Home: a "PlayStation Portable" row with a God of War card, the same frame
  as the arcade's, its picture from libretro's PSP thumbnails (packed at
  build time: `app/psp-art.txt`, never committed). On the phone: Play;
  not yet: "Add your copy" opens the PSP games to add it. The library
  stays for later.

### 2026-11-07 00:59 Dhaka · God of War: no D-PAD button, the stick follows the game
- Owner: "i want to remove d pad button. It should auto detect and active /
  deactive. Check if it possible".
- Found in the game's RAM (tools/pspemu.py, 7 menu screens and 5 moments of
  play compared): 0x08C5D8D1 is 4 while Kratos is played (fights, grabs,
  prompts) and 0 on the title, difficulty, pause (SELECT), Power Up (START)
  and "You are dead" screens and in story videos; 2-3 for a moment between.
  It follows a pause and an unpause within a tenth of a second.
- Decided: for God of War the app reads it ten times a second and the stick
  works the analog stick while it's 4, the D-pad otherwise; the D-PAD
  button is gone (`PspProfile.inPlay`). A direction held carries over. A
  game without such a byte keeps the switch. Each change is in the game log.

### 2026-11-07 01:01 Dhaka · The D-PAD switch stays, as a fallback
- Owner: "Have we adjust the controlles for all combos? Dont remove the D
  pad button. Keep it. But make sure it auto turn on and off. Keep d pad
  button as fallback use".
- Decided: the D-PAD switch is back on the top bar. For God of War the
  stick still switches by itself, but only when the game goes between play
  and a menu; a tap on the switch holds until the next such change.
- Combos checked in the game: Spirit of Hercules (□ □ △) and Cyclone of
  Chaos (□ held) each come out as their own combo; Roll (L+R with the
  stick) rolls; with Hyperion Charge (L, then ○), Light, Heavy, Grab and
  Mash before. Not yet: the three magic casts and Weapon (Kratos has no
  magic or second weapon this early).

### 2026-11-07 01:30 Dhaka · Game logs straight to our server; frame skip Off for PSP
- Owner: "By send, cant we save it directly on our server, to check log
  our self directly?" … "Yes go ahead".
- Decided: Game log's **Send to us** uploads the log to problem_reports as
  kind 'game' (migration 20261107000000_game_logs.sql); it's read with
  read_problem_reports (the reader account), like the connection reports.
  A session that had a freeze of 2 s or more sends its log by itself on
  quitting, when "Send problem reports" is on. Copy stays.
- Within the free plan (D8): a game log is at most 200 KB, 20 a day per
  player (apart from the connection reports' 20), nothing more kept once
  200 MB of logs are stored (of the free 500 MB), and all older than 30
  days deleted nightly. Server test: game_logs.test.sql.
- The owner's latest log (1.0.284): with frame skip Off, sound drops fell
  from thousands a second to almost none and play held 58-60 fps. Decided:
  PSP frame skip is Off by default; Auto stays a choice.
- Then: "Make sure it works for all game". Checked: only two screens run
  games, the arcade's (all 31, alone, online and replays) and the PSP's;
  both have Game log → Send to us and send by themselves after a bad
  freeze. The Settings tab row now scrolls to the open tab, so Game log
  (last of six on the arcade's) shows when opened from the pause menu.
  Snapshot: controls_game_log.

### 2026-11-07 03:51 Dhaka · The D-pad stayed on in play
- Owner (1.0.292, a screenshot in a later area, Kratos walking): "D pad didnt
  auto turned off. It should auto turn on and off".
- The byte was 4 in play on the boat (where it was found); elsewhere it may
  read otherwise. Menus and story always read 0. Decided: any value but 0
  is play. Every switch now logs the byte's value ("game byte 5"), so a log
  from a place where it's still wrong shows what the game said there.

### 2026-11-07 03:58 Dhaka · The D-PAD switch is manual again
- Owner (1.0.292 log): "D pad s auto is not working expectedly. In game it
  turned off and auto unexpectedly … Dpad only needed when we pause inside
  game. If this cant be solved. Make dpad manual like before".
- The log: the byte dropped to 0 in the middle of play again and again
  (6:14, 6:19, 7:00, 7:39, 7:49, 7:53), so "0 = a menu" isn't true past the
  boat. Decided: no automatic switch for God of War; the D-PAD switch on
  the top bar is the only way, as before. (`PspProfile.inPlay` stays for a
  game whose byte holds up.)

### 2026-11-07 04:00 Dhaka · Updates show without reopening the app
- Owner: "Why do i close and open again. Cant i get trigger via socket to
  show update state" … (asked whether to check on coming back and every 2
  minutes instead) "Yes".
- Decided: no socket (every open app holding a Supabase Realtime
  connection would eat the free plan's 200, shared with online play).
  Instead version.json (a few hundred bytes on GitHub Pages) is asked on
  every return to the app, at most once a minute, and every 2 minutes
  while it's open; the Update button then shows within about 2 minutes.

### 2026-11-07 05:53 Dhaka · Assassin's Creed: Bloodlines, set up end to end
- Owner: "I want to add assassin cred next. Do a complete setup end to
  end..i will check om the morning".
- Played here from the owner's Drive copy (tools/pspemu.py): it boots, the
  first questions (install data, a save) want the D-pad (the D-PAD switch;
  answer Yes to both on the phone), then the title and the tutorial.
- Controls, from the game's own tutorial and button panel: the stick
  walks; R held High Profile (the stick runs; ✕ then sprints, ○ tackles);
  L held turns the camera with the four buttons, a tap centres it; △
  Target, □ Attack, ✕ held Blend. Profile `ASSASSINS_CREED`: those names
  under □ ✕ △ ○, L "Camera", R "High Profile", a **Sprint** pill holding R
  and ✕ together for one thumb (`SysButton.alsoHold`), Tackle (R, then ○)
  on the ring. Checked: run, sprint (unlike walking), camera, blend.
- Speed: lighter than Ghost of Sparta here (8 ms a frame, 17 for GoS); it
  draws 30 pictures a second; story and play sound at 44,100 a second with
  frame duplication on.
- Home: an Assassin's Creed card beside God of War (libretro's picture).
- The D-PAD switch stays manual (God of War's lesson).

### 2026-11-07 15:31 Dhaka · The website's deploy gets 30 minutes
- 1.0.298 (Assassin's Creed) built, but the website step timed out at
  GitHub Pages ("syncing_files" past 10 minutes), as for 1.0.282: the site
  carries the APK (~100 MB). The app never hears of such a build.
- Decided: deploy-pages waits 30 minutes. Merging this change runs the
  website step again (pages.yml changed), which publishes 1.0.298.

### 2026-11-07 16:41 Dhaka · Mash turns the stick too; God of War saves on leaving at once
- Owner (1.0.298, a chain pull failing, a screenshot): "It saying ti move
  controller stick round round. Also make sure gow auto saves on left like
  other game".
- Decided: the Mash pill presses L, ○, R, ○ (about three seconds) and turns
  the analog stick round (a turn every 16 frames) at the same time, so one
  pill answers "alternate L and R", "press ○" and "rotate the stick"
  prompts. Checked in the game: the boat's grab still escapes with ○ and
  the turning stick added. Mash plays frame by frame (PspSteps), the
  timing checked.
- Leaving the app keeps the game at once (`keepPlaceNow`, off the
  emulator's thread, as the arcade games do), not queued for a thread
  Android stops as the app goes. Checked in the game: a state taken off
  that thread loads back.

---

## Where things stand (update whenever it changes)

As of 2026-11-06 16:13 Dhaka:

- **Open:** PSP P2 (speed, picture and controls for Ghost of Sparta).
- **Merged:** #83 (PSP plan and P1), #82 (more consoles looked at, dropped), #81 (button layouts and their rules), #80 (Spin Master, In the Hunt, Stone Ball, Vampire Savior: 31
  games), #71-#79 (recording matches R1-R3, match videos with their own
  sound, updates downloading in the background).
- **Latest release:** v1.0.269.
- **The PSP games for testing:** in the owner's Google Drive folder (shared by link; the link is with the owner and in the chat, never in the repo): Ghost of Sparta, Chains of Olympus, Assassin's Creed: Bloodlines, Need for Speed: Most Wanted 5-1-0.
- **Waiting on the owner:**
  - Moving the ROM library to his new Cloudflare account (on hold: needs his card for R2). Steps given: bucket `arcade-roms`, zips at the top, Worker `server/worker.js` with binding `ROMS` and the same `ARCADE_KEY`, PSP game in `psp/` with Round Sync (RCX left the Play Store); he sends the new Worker address, then `Library.DEFAULT_URL` changes. The old account stays (TURN, DNS).
  - PSP P2: Ghost of Sparta played again with frame skip Auto; its game log (pause menu → Game log → Copy).
  - Game logs from arcade play, alone and online (Settings → Game log → Copy), when something feels off.
  - Screen recordings for the app tutorial (`marketing/tutorial-script.md`).
  - A current `pgm.zip` (with `ddp3_bios.u37` and `bios.u42`) for Knights
    of Valour, KOV 2 Plus and The Gladiator; `avspu.key` and a current
    `avsp.zip` for Alien vs Predator.
  - `pbobbl2n.zip` (arcade Bust-A-Move 2), to add it.
  - Real-phone feedback on voice ducking (if the game "pumps" with an open
    mic, raise `MIC_SPEAKING_LEVEL` in `GameActivity`).
  - Redeploy `server/worker.js` in Cloudflare to get download resume
    (optional; the app works without it).
  - A Firebase project, if he wants notifications with the app closed.
- **Next for PSP (noted):** P5, weaker phones with minimal graphics; the owner sends the game file first.
- **Ideas not started:** everyone-rotates seats, a lobby, bots (Step 3).
- **Watch:** problem reports (reader account) after the owner's friends
  play online.
