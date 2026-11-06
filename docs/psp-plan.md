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
settings, and Ghost of Sparta (the heavier one) may not pass. P1's speed
readout, in our own app on his phone, finds that out first.

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
- **Fast settings for this phone,** the starting point P1 measures: 1x
  resolution, no texture scaling or filtering, fast memory, lazy texture
  caching, GPU readbacks skipped, low spline quality, threaded file loading,
  auto frame skipping off until needed. Per game, kept in the app.
- **A controller is recommended** (Bluetooth gamepad): 3D action plays far
  better than on touch, and helps the owner's motion sickness. Touch
  controls are there too.

## Phases (one PR each, the arcade games and their tests kept working)

- **P1. A PSP game runs, and its speed is measured.** CI puts the PPSSPP
  core and assets in the APK; the game screen runs a GPU-drawn core; a game
  picked from the phone's files (no upload needed yet) starts with its fast
  settings. A speed readout (frames, emulation %) shows on screen, and the
  owner plays each game 15 minutes and reports it. Games that fail even
  with frame skipping are dropped here, before more work.
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

- **P5. Weaker phones (noted, not started).** The owner (2026-11-06):
  after P2, refine so the PSP games also run on phones weaker than his,
  with minimal graphics if needed (a lowest-settings preset, chosen by the
  phone's speed). He gives the game file first, so it can be measured
  here; starts when he says so.

## Testing

The cloud sandbox has no GPU: P1 is checked with PPSSPP's software renderer
where it can be; speed is only real on the owner's phone, so P2 ships with
the speed readout and he reports what it shows. Test games come from the
owner's library (downloaded with its key, never committed).

## Where things stand

- 2026-11-06: Ghost of Sparta runs in the sandbox (`tools/pspemu.py`). Story
  videos ran ~1.5x fast: `ppsspp_frame_duplication` is now always on. The
  opening story can't be skipped in the game itself.
- 2026-11-06: the top bar's D-PAD switch makes the stick work the D-pad
  (the menus answer only the D-pad); Opacity now reaches faint.
- 2026-11-06: every game (arcade too, alone and online) has the game log now,
  as a Game log tab in Settings.
- 2026-11-06: the game log. Nothing on screen during play (the speed
  counter is off unless switched on); pause menu → Game log shows the
  whole session (phone, settings, speed second by second, stalls, what
  happened) with Copy and Send, saved on quit, and a Log button per game
  in the PSP list. The owner pastes it in the chat after playing.
- 2026-11-06: first report from the phone (Ghost of Sparta, 8 minutes, frame
  skip off): 60 fps, full speed 96% (19 slow seconds of 478), longest stall
  921 ms. Close, not yet the rule. P2 started with: the counter timed on the
  emulator's thread (the main thread's own pauses had counted as stalls),
  frame skip Auto by default (skips a picture only when behind, so the game
  never slows), a stall list with when each happened (a loading screen or
  gameplay), Android's sustained-performance mode, and per-game settings:
  frame skip, sharpness 1x/2x, picture (Sharp, Smooth, Enhanced edges), faster
  effects. Controls (P4 begun, the owner's ask): kept to the screen's edges,
  movable, size and see-through settings, and God of War's own labels (□
  Light, △ Heavy, ○ Grab, ✕ Jump, R Block), a Roll button (L+R, steered with
  the stick), SELECT hidden.
- 2026-11-06: the owner didn't like PSP-looking controls: "Buttons and layout should
  exactly like kof 2002 layout … We show based layout, based on game we show
  dedicated buttons". Now every PSP game uses the arcade controls
  (`HudView`, the KOF 2002 layout): stick, A B C D, SELECT and START on top,
  the same menu and layout editor; each game's profile (`PspProfile`) adds
  its names and dedicated buttons, as a fighter adds its moves. The PSP's
  buttons on the arcade keys: A = square, B = cross, C = triangle, D =
  circle, E = L, F = R, COIN = SELECT; the stick goes to the analog stick.
  God of War: A Light, B Jump, C Heavy, D Grab, Block (holds R), pills Roll
  (L+R) and L. Its combos go on the ring once the owner sends the game's own
  move list (no reliable list was reachable from here). The face buttons
  show the PSP's symbols (□ ✕ △ ○) with the game's names under them, since
  the games say "press ○"; buttons a game never uses are hidden (God of
  War: SELECT, later shown again: the owner's move list says it opens a
  menu). God of War's ring and pills follow the owner's move list:
  Hyperion Charge, Spirit of Hercules, Cyclone of Chaos, three magics;
  Roll, Weapon, Fire; Block holds L. To check in the game.
- 2026-11-06: P1 built for Ghost of Sparta first (the owner: "We will
  first check and stable ghost of Sparta"). Home → PSP → Add a game takes
  his `.7z` download (unpacked on the phone) or a plain image; the game
  screen runs PPSSPP with the fast settings, touch controls (stick, D-pad,
  the four action buttons, L/R, SELECT/START) or a gamepad, and a speed
  counter (fps, % of seconds at full speed, slow seconds, longest stall).
  The menu switches frame skip (Off, 1, Auto) and restarts the count.
  Waiting for his report from the phone.
- 2026-11-05: plan written. The owner won't use the PPSSPP app ("It felt
  complecated. And the ui is mess. Thats why we are adding on our app"), so
  measuring moved into our app (P1). Next: his go on P1; the three games on
  his phone as `.cso` (the library upload can wait until P3).
