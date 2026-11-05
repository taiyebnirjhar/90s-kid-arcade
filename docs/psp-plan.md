# PSP games: God of War and Assassin's Creed

The owner asked (2026-11-05): "Lets add gow and assassin creed … the game
should never lags and hangs". For himself only, on his Galaxy M14 4G
(Snapdragon 680, Adreno 610 graphics, 6 GB). Not for sale, not on a store;
licences are his. The arcade games stay as they are.

**The games:** God of War: Chains of Olympus, God of War: Ghost of Sparta,
Assassin's Creed: Bloodlines.

## The rule: never lags, never hangs

A game ships in the app only when, on the owner's phone, it meets all of:

- **Full speed:** the game runs at 100% of its own speed in at least 99% of
  seconds over a 30-minute session, at the PSP's own resolution (1x).
- **Clean sound:** no gaps or crackles (sound gaps counted, as for the
  arcade games).
- **No hangs:** no freeze longer than a second; loading screens no longer
  than on a real PSP.
- **Nothing lost:** if anything does go wrong, at most a minute of play is
  lost (an automatic save state every minute, kept in the background).

What it takes, in order, until the rule holds: the fastest settings at 1x
(below); then frame skipping as a safety net (the picture skips a frame,
the game never slows); then dropping that game. No game ships slow.

A Snapdragon 680 is at the edge for God of War: it will need the fast
settings, and Ghost of Sparta (the heavier one) may not pass. That's what
P0 finds out before anything is built.

## Settled

- **Emulator:** PPSSPP's libretro core (`ppsspp_libretro_android.so` from the
  libretro buildbot, like FBNeo; about 8 MB). It draws with the GPU (OpenGL
  ES), which LibretroDroid supports. It needs its asset folder (fonts,
  shaders) in the core's system directory.
- **Games:** the owner's own `.cso` files (compressed ISOs, smaller and read
  as fast). The three come to about 2.5 GB, so they fit in the existing
  library's free 10 GB (0.96 GB used); no second Cloudflare account needed.
  Files over 300 MB go up with `rclone`, not the dashboard.
- **Offline only.** All three are single-player.
- **Fast settings for this phone,** the starting point P0 checks: 1x
  resolution, no texture scaling or filtering, fast memory, lazy texture
  caching, GPU readbacks skipped, low spline quality, threaded file loading,
  auto frame skipping off until needed. Per game, kept in the app.
- **A controller is recommended** (Bluetooth gamepad): 3D action plays far
  better than on touch, and helps the owner's motion sickness. Touch
  controls are there too.

## Phases (one PR each, the arcade games and their tests kept working)

- **P0. Measure first** (no code). The owner plays each game 15 minutes in
  the official PPSSPP app with the fast settings and its speed counter on,
  and reports: smooth, small dips, or slow. Games that fail at 1x even with
  frame skipping are dropped before any work.
- **P1. A PSP game runs.** CI puts the PPSSPP core and assets in the APK; the
  game screen runs a GPU-drawn core; a game picked from the phone's files
  starts with its fast settings. A speed readout (frames, emulation %)
  for checking.
- **P2. Never lags, never hangs.** Measured on the phone against the rule
  above: frame pacing with the screen (as the arcade's), sound through the
  same gap-free path, the CPU kept fast (performance hints), frame skip as
  the safety net, the save every minute, a watchdog that saves and reports
  if the game stops responding.
- **P3. In the app.** The Library takes `.cso`; a PSP row on Home with the
  three games' pictures; downloads of a GB or more resuming after drops.
- **P4. Controls and saves.** A PSP layout (stick, D-pad, the four action
  buttons, L and R, START and SELECT) following the button layout rules;
  gamepads; the game's own saves and quick save/load.

## Testing

The cloud sandbox has no GPU: P1 is checked with PPSSPP's software renderer
where it can be; speed is only real on the owner's phone, so P2 ships with
the speed readout and he reports what it shows. Test games come from the
owner's library (downloaded with its key, never committed).

## Where things stand

- 2026-11-05: plan written. Next: P0 on the owner's phone, and the three
  games uploaded as `.cso`.
