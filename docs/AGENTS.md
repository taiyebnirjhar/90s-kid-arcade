# Handbook for AI agents (and anyone picking this up)

Read this first, then [decisions.md](decisions.md) (every decision, dated
in Dhaka time, with why). Together they're meant to be enough to carry on without any
earlier conversation. **Keep them true:** each change that decides
something adds a dated entry to `decisions.md`, and anything here that a
change makes wrong is fixed in the same pull request.

Last full review of this handbook: 2026-11-03 02:57 Dhaka.

---

## 1. What this is

**90s Kid Arcade**: an Android app that plays arcade classics (Neo Geo and
Capcom: KOF, Metal Slug, Cadillacs and Dinosaurs, Marvel vs Capcom, 27
games) through FBNeo, with touch controls made for each game (one-tap
specials in fighters), multiplayer on the same Wi-Fi or hotspot (no
internet), and online play (rooms by code, open rooms, quick match,
friends, chat, voice). ROMs are never shipped: players add their own zips,
or download them from the owner's private library (a Cloudflare R2 bucket
behind a key) inside the app.

- **Owner:** Taiyeb Nirjhor (GitHub `taiyebnirjhar`). The app's onboarding
  says "Taiyeb Nirjhor invited you" (`INVITED_BY` in `Onboarding.kt`).
- **Repo:** `taiyebnirjhar/90s-kid-arcade` (public; renamed from
  `kof-arcade`: use the new name in every GitHub call; a local clone may sit
  in a folder named `kof-arcade`).
- **Website:** https://arcade.taiyebnirjhor.com (GitHub Pages from `site/`,
  DNS on Cloudflare; the old https://taiyebnirjhar.github.io/90s-kid-arcade/
  redirects).
- **APK:** https://github.com/taiyebnirjhar/90s-kid-arcade/releases/download/latest/90s-Kid-Arcade.apk
  (also served by the website). Each build has its own release `v1.0.<run>`.

## 2. How work flows (the owner's rules)

- **Branch:** develop on `claude/kof-mobile-emulator-supers-pherts` (or the
  branch the session names). Never push to `main`.
- **Pull requests:** open them against `main`; **the owner merges them
  himself (squash).** Don't merge. Tell him when it's ready: CI green on
  the head and mergeable.
- **After each merge:** bring main back into the branch:
  `git fetch origin main && git merge origin/main -m "Merge main (#N squashed) into the branch"` and push.
- **Before pushing:** `git remote set-url origin https://github.com/taiyebnirjhar/90s-kid-arcade.git`
  (an old remote URL only redirects, with a warning).
- **Never force-push** (it's refused), never rewrite history on the branch.
- **Commit trailers** (exactly):
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: <the session's link>
  ```
  No model names anywhere else in pushed files.
- **PR body ends with:** `🤖 Generated with [Claude Code](https://claude.com/claude-code)` and the session link.
- **The owner wants to be told** when an APK is ready (with its version and
  link) and when to merge. Answer short; he reads on a phone.
- **Online smoothness, every release:** look for anything that makes online
  play smoother (netcode delay and rollbacks, relay vs direct, the relay's
  reach, start-up time) and take what helps, within the free plans.
  DNS doesn't change in-game smoothness (see decisions.md, 2026-11-03).
- **Everything committed and pushed** before a turn ends (a hook checks).
- **Commit messages and docs:** plain words, say why. The codebase's
  comments read as plain English sentences; keep that voice.

## 3. Hard rules (never break)

- **No ROMs or game files in the repo**, ever. **No SNK/Capcom character
  art.** Game pictures (libretro thumbnails) are fetched at build/deploy
  time (`fetchGameArt` for the APK; the website's share card is rendered
  by its workflow), never committed.
- **No money** while the app uses FBNeo: no payments, ads, subscriptions,
  donation links (FBNeo's license). Plan for later: `online-plan.md`.
- **Never past a free allowance:** every online service runs on a free plan
  with a hard stop below the allowance (relay: no new credentials at 900 GB
  of Cloudflare's 1,000 GB, live ones revoked at 950 GB). See D8 in
  `online-plan.md`.
- **Seats follow the game's own countdown**, no timer of ours (CLAUDE.md;
  `Game.seats`, checked by `GamesTest`).
- **Secrets never in files, commits, logs or public CI output.** Allowed in
  the repo: the Supabase project URL and its publishable key. Never: the
  TURN token, service-role key, access tokens, DB password, the library
  key, the problem-reports reader's password. The repo is public: never
  print private data (problem reports) in workflow logs.
- **The ROM library stays private** behind its key (the owner decided not to
  open it).
- **Online play follows `online-plan.md`** (principles, connection order
  nearby → direct → relay, settled decisions). Record new decisions there
  and in `decisions.md`.
- **Every download keeps going in the background.** Any file the app
  downloads (games, the app's update, anything added later) runs in a
  foreground service (`dataSync`, with a notification, wake and Wi-Fi
  locks: `DownloadService` for games, `UpdateService` for the update),
  never in a screen's thread, and carries on from where it stopped after a
  dropped connection (a Range request, a few tries) rather than failing or
  starting over. A download tied to a screen stops when the player leaves
  the app (the update did, 2026-11-03: "Software caused connection abort").
- **Recording matches follows `recording-plan.md`** (off by default, saved
  on the phone only, videos made by replaying the saved inputs, phases
  R1-R5). Record new decisions there and in `decisions.md`.

## 4. Map of the code

| Where | What |
| --- | --- |
| `android/app/src/main/java/com/kofarcade/` | The Android app (Kotlin, Jetpack Compose + `HudView`, a custom View for in-game controls). |
| `MainActivity.kt` | Home, Library, Online, Profile pages; onboarding gate; friends' news banner; room links. |
| `GameActivity.kt` | One game: emulator, HUD, rooms (Wi-Fi and online), voice, chat, presence and friends' news in-game; replays (`EXTRA_REPLAY`: a saved match run as a room's watcher). |
| `GameDialogs.kt`, `GameUi.kt` | Pause menu, settings, room sheet, sheets over the game. |
| `TwoPlayerDialog.kt` | The Online screen (hub), joining/hosting, quick match, challenges, friend chat page, QR scan. |
| `FriendsUi.kt`, `Chat.kt`, `RoomUi.kt` | Friends list/chat/banner; room chat, mic/speaker buttons; room sheet. |
| `LibraryDialog.kt`, `Library.kt`, `LibraryDownloads.kt`, `DownloadService.kt` | My Library: the R2 library client (whole-file downloads, resume), the app-wide download queue, the foreground service. |
| `Onboarding.kt` | First-run welcome (photo with pixel shades, key, BIOS, save matches?, favourite games). |
| `GameLog.kt` | Every game's session log (Settings → Game log tab, pause menu → Game log): `SpeedMeter` (frames per second, stalls), `GameLog` (header, speed timeline, stalls, events, a room's `NetLog`), `GameLogs` (saved per game in `logs/`, copy, send). |
| `PspActivity.kt`, `PspGameActivity.kt`, `PspGames.kt` | PSP games (docs/psp-plan.md): the list and "Add a game" (a .7z/.zip download unpacked, or .iso/.cso/.chd), the game screen running PPSSPP with the fast settings, the arcade controls (`HudView`, KOF 2002 layout) with each game's `PspProfile` on top (God of War, Assassin's Creed: names, pills, Block, ring moves; A-D = □ ✕ △ ○, E/F = L/R, COIN = SELECT) and gamepad, the arcade's pause menu, Settings and layout editor (shared settings; layouts per game and hand), Graphics & speed per game (`PspStore`), the speed counter (off by default) and the game log (pause menu or Settings → Game log; a Log button per game in the list). Kept apart from `GameActivity`: the arcade games are untouched. | Home's PSP row: `PspTitle` (PspGames.kt) and `PspCard` (Home.kt); pictures listed in `app/psp-art.txt`.
| `Replays.kt` | The Replays page (home chip): saved matches, star, delete, watch; the saving switch. |
| `VideoMaker.kt`, `Videos.kt` | A match's video: the emulator's capture encoded to MP4 (H.264 + AAC, sharp pixels); into the gallery and the share buttons (WhatsApp, Messenger, Facebook, More). |
| `Matches.kt` | Saving matches: `MatchStore` (files in `filesDir/matches`, newest 20 + starred), `MatchTaker` (starts and keeps recordings while a game runs; see recording-plan.md). |
| `Updates.kt`, `UpdateService.kt` | Updates inside the app: `version.json` checked, the APK downloaded in a foreground service (resumes after a drop), Android's installer. |
| `Theme.kt` | Palette, the app-wide UI scale (`uiScale`, `DESIGN_SHORT_DP`), `Breakpoints`, text cap. |
| `Storage.kt` | `RomStore` (zips, checks), `Prefs` (every setting). |
| `Online.kt` | Supabase account, rooms, friends, problem reports, room links (`joinLink`, `codeFromLink`, `scannedRoomCode`). |
| `android/core/` | Plain Kotlin (JVM), tested without a phone: games list and moves (`Games.kt`), ROM checks, rooms and netcode (`core/net`: `Session`, `Room`, `WebRtcTransport`, rollback, chat), online (`core/online`: Supabase client, `OnlineRooms`, `RealtimeSignaling`, friends), saved matches (`core/replay/Match.kt`: the emulator's block, the `.match` file format, what's kept). |
| `android/libretrodroid/` | Fork of LibretroDroid (native C++): frame pacing (`fpssync`), sound (`audiopipe`, adaptive target), match recording (`recorder`, fed by `step`/`stepNetplay`), video capture (`setCapture`), FBNeo core host. |
| `server/supabase/migrations/` | Database: rooms, members, signaling rules (Realtime private channels), profiles, friends, friend chat, reports, problem reports, quotas. Applied to the live project automatically on merge to main. |
| `server/supabase/tests/*.test.sql` | Server tests on plain Postgres with a Supabase stub. |
| `server/supabase/functions/turn-credentials/` | Edge function: Cloudflare TURN credentials with the 900/950 GB stops. |
| `server/worker.js` | Cloudflare Worker for the ROM library (R2). **Deployed by hand** in the Cloudflare dashboard. |
| `site/` | Website (`index.html`, `join.html` for room links, `share-card.html` → `img/share.png` at deploy, `.well-known/assetlinks.json` for app links). |
| `.github/workflows/` | `android.yml` (tests, APK, release), `pages.yml` (website), `server.yml` (server tests), `server-deploy.yml` (migrations to the live project), `keep-awake.yml` (Supabase free project stays awake). |
| `docs/` | This handbook, the decision log, the online plan, setup and testing notes, adding a game, KOF RAM notes. |

## 5. Build, test, release

All from `android/` unless said.

| What | Command | Notes |
| --- | --- | --- |
| Core tests (rooms, netcode, online, games) | `./gradlew -p core test` | ~10 min. Results in `core/build/test-results/test/`. |
| One core test class | `./gradlew -p core test --tests 'com.kofarcade.core.online.OnlineRoomsTest'` | |
| App unit + snapshot tests | `./gradlew :app:testDebugUnitTest` | ~10 min. Paparazzi snapshots, settings, downloads, UI scale. |
| Re-record snapshots | `./gradlew :app:recordPaparazziDebug --tests 'com.kofarcade.UiSnapshots'` | Images in `app/src/test/snapshots/images/` (not committed; CI publishes them to a `ui-snapshots-<branch>` branch). Look at them after UI changes. |
| Compile only | `./gradlew :app:compileDebugKotlin` | Fast check. If the Gradle daemon dies on start, run it again. |
| Native pacing/sound tests | `g++ -std=c++17 -O2 -Ilibretrodroid/src/test/cpp/stub -Ilibretrodroid/src/main/cpp libretrodroid/src/test/cpp/playback_test.cpp libretrodroid/src/main/cpp/fpssync.cpp libretrodroid/src/main/cpp/audiopipe.cpp -o /tmp/pb && /tmp/pb` | CI runs the same. |
| Server tests | from the repo root: `PGURL="postgresql://postgres@localhost:5433/postgres" server/test.sh` | Needs Postgres 16. In the cloud sandbox: `su postgres -c "/usr/lib/postgresql/16/bin/pg_ctl -D /var/tmp/pg/data -o '-p 5433 -k /tmp' -l /var/tmp/pg/log start"` (create with `initdb` if missing). |
| Live online end to end | `LIVE_SUPABASE_URL=https://ysbskjtvccdbhnfuccso.supabase.co LIVE_SUPABASE_KEY=sb_publishable_ZdWmdg-2_f5_rvnzhiviYQ_VSUnZPIE ./gradlew -q -p core test --tests 'com.kofarcade.core.net.LiveOnlineTest' --rerun-tasks -i` | Two phones in one process on the real server and real WebRTC (locked room: play, chat, voice, friends). The open-room case needs `LIVE_OPEN=1` and a network with UDP to the relay (not the sandbox). |
| Run a PSP game here | `Xvfb :99 &` then `DISPLAY=:99 python3` with `tools/pspemu.py` (`from pspemu import Psp`) | PPSSPP with the app's settings on a hidden OpenGL window (Mesa llvmpipe); frames, the app's buttons and stick, screenshots, save states, sound samples (a game running fast shows as more than 44,100 a second). Core, assets and the game in `emu/psp/` (never committed). |
| Website preview | Playwright (preinstalled) on `site/index.html`; the share card: Chrome `--headless=new --screenshot --window-size=1200,630 file://…/site/share-card.html` | |

**Release** (automatic): every merge to main → `android.yml` builds the
APK with `versionCode = run number`, `versionName = 1.0.<run>` (branches:
`1.0.<run>-test`; local: `1.0-dev`), publishes release `v1.0.<run>` with
"What's new" (main's commits since the last `v1.*` tag) and moves `latest`
to it. `pages.yml` runs after it and serves that APK from the website. Server
migrations deploy on merge (`server-deploy.yml`). The Worker does not.

**Checking CI without the GitHub tools:** the public API works with curl,
e.g. `curl -s "https://api.github.com/repos/taiyebnirjhar/90s-kid-arcade/actions/runs?branch=<branch>&per_page=5"`.
Wait for a run in the background (a loop on `.../actions/runs/<id>`), not
with a sleeping foreground command.

## 6. Services and accounts (no secrets here)

- **Supabase** (free): project `ysbskjtvccdbhnfuccso`. Auth (guest
  accounts), Postgres, Realtime (signaling on private channels
  `room:<CODE>`, allowed for room members only), edge function for TURN.
  Kept awake by `keep-awake.yml`. Setup: `docs/online-setup.md`.
- **Cloudflare**: TURN relay (Calls), R2 bucket for the ROM library with the
  Worker `arcade-roms.taiyebnirjhor.workers.dev`, DNS for
  `taiyebnirjhor.com` (`arcade` CNAME → GitHub Pages).
- **Problem reports:** phones send their connection log when a join/room
  fails (`Online.reportProblem`; players can turn it off), and game logs
  (kind `game`: Game log → Send to us, or by itself after a freeze of 2 s
  or more; `Online.sendGameLog`). A reader account
  (in table `problem_readers`) reads them with `read_problem_reports`; its
  login is with the owner, never in the repo. Read them over the REST API
  from the sandbox when asked; never print them in public CI logs.
- **GitHub Actions secrets** hold the Supabase access token / DB password for
  migrations (names in `server-deploy.yml`).

## 7. How the app works (the parts that bite)

- **UI scale:** every Compose screen is drawn at a density scaled to the
  screen's short side: 470 design units across a 411 dp phone
  (`DESIGN_SHORT_DP`), clamped 0.7–2.2×, long side ≥ 640 units. Dialog
  windows re-apply it (`TextScaleCap`). Use `Breakpoints` (sm 360, md 560,
  lg 760, xl 1100) for layout switches, never raw dp numbers. Owner's ask:
  compact, the same feel on every phone and tablet.
- **Rooms:** one `Room`/`Session` logic for Wi-Fi and online; online uses
  `WebRtcTransport` with signaling over Supabase Realtime. Open rooms hide
  public addresses (private + relay only: "kept back" in logs); locked rooms
  and challenges may connect directly. A phone holds a claim per room code
  (`RoomClaims`) so a late "left" never removes a newer join.
- **Online play never pauses** (no cheating by pausing); the pause button is
  a gear (menu while the game goes on).
- **Voice:** WebRTC audio; the game ducks to 25% while someone talks
  (setting "Game quieter while people talk"); voices ×2.5. Per-player mute
  on your phone; the host can mute for the room.
- **Friends:** requests, chat (`friend_messages`, limits in the migration),
  challenges (a locked room made for a friend + an invite with
  `challenge = true`), `friend_news` polled every 15 s while the app is open
  (20 s in a game). No push notifications (needs Firebase from the owner).
- **Library downloads:** one app-wide queue + foreground service; a file
  counts only when whole (size checked), resumed with Range when the Worker
  supports it, else restarted; 3 tries per file.
- **Sound:** `AudioPipe` keeps a target buffer, adaptive (grows after
  run-outs, up to 2×; back down after 20 s calm).
- **In-app updates:** the website's `version.json` (written by `pages.yml`
  from the newest `v1.0.*` release) is compared with `BuildConfig.VERSION_CODE`;
  `AppUpdates` downloads the APK to `cache/updates` and opens the installer
  through the FileProvider (`${applicationId}.files`). Asked on opening or
  coming back to the app (at most once a minute) and every 2 minutes while
  it's open. If `pages.yml` fails
  after a build (GitHub Pages can time out; its deploy waits 30 minutes, the
  site carries the ~100 MB APK), the app never hears of the build: a re-run
  of the same commit fails (the artifact's there twice, or the deployment
  was cancelled), so push a change that runs it again.
- **Deep links:** `https://arcade.taiyebnirjhor.com/join.html?code=XXXXXX`
  (verified App Link via `assetlinks.json`, signed by the fixed debug key in
  `android/app/debug.keystore`), `kidarcade://join?code=…`, and the in-app
  QR scanner (Play services code scanner).

## 8. Gotchas met so far

- The GitHub MCP tools sometimes time out or disconnect: use the public REST
  API with curl to read runs/PRs, and push with git.
- `rm -rf` on the snapshots folder is refused by the sandbox: re-record
  instead of deleting.
- Android unit tests (app module) can't use `com.sun.net.httpserver` (hidden
  by android.jar) and see stubbed `org.json`: write tiny socket servers, and
  keep logic Android-free where it needs tests.
- `File.createTempFile` needs a prefix of 3+ characters.
- `withContext(NonCancellable)` still throws on resume if the caller was
  cancelled (prompt cancellation): keep what was made aside and close it in
  `catch (CancellationException)` (see `host()` / joins in `TwoPlayerDialog`).
- Supabase Realtime re-checks channel rights when a token refreshes: a phone
  removed from `room_members` gets `Unauthorized … read from this Channel
  topic` (that's how the rejoin bug showed).
- Paparazzi renders `LocalInspectionMode`: animations are drawn still there
  (`LocalStill` too).
- The Supabase CLI runs at `latest` in `server-deploy.yml`: its defaults can
  change (2026-11-03 `db push` began targeting a local stack, then asking
  to confirm: now `db push --remote --yes`). After a server PR merges, check that "Server deploy" passed.
- GitHub expressions have no real ternary: `cond && '' || 'x'` is always
  `'x'` (an empty string is falsy). That made every main build
  `1.0.<run>-test` until 2026-11-03; write `!cond && 'x' || ''`.
- Releases 403 ("Resource not accessible by integration"): the repo's
  Settings → Actions → General → Workflow permissions must be "Read and write".
- `cancel-in-progress` on branch builds: a newer push cancels the older run,
  so a "cancelled" run is normal.

## 9. Where things stand

See the end of [decisions.md](decisions.md) for the latest state (open PRs,
what's waiting on the owner, next ideas). Update it whenever that changes.
