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

## God of War as part of the app (the owner, 2026-11-06)

"We need to make the gow game and controller first party native support
instead of thirdparty. Like its part of our app … improve layout, combos,
buttons, max ux, graphics and butter smooth game play. Goal it to improve
and maximize gaming exp on touch screen phone and play native smooth."

Everything below is checked in the game itself: it runs in the sandbox
(`tools/pspemu.py`), and every move, menu and fix is tried there before it
ships, as the arcade's moves are (`tools/movetest.py`). One PR per step;
each starts when the owner says so.

- **G1. Saves that just work.** Leaving the game keeps it where it was, and
  opening it goes on from there (as the arcade games do), every time.
  Quick save and load checked in the game (a load takes a few frames to
  show: the core applies it at the next frame). The game's own saves stay
  on the phone and are kept when the app updates.
- **G2. Every move, checked.** God of War: Ghost of Sparta's full move list
  (attacks, grabs, L+□ / L+△ / L+○ / L+✕, magic, items, Spartan Rage),
  each tried in the game and given its place: face buttons and pills for
  what's pressed often, the ring for combos; anything that doesn't work in
  the game is fixed or left out. Helpers for touch: a held button that
  mashes for the game's "press ○ quickly" and "alternate L and R"
  prompts, and a swipe to roll.
- **G3. A God of War layout.** Built for this game on the KOF 2002 base:
  sizes and places tuned for the thumbs, magic as one row of pills, PSP
  symbols with the game's names, nothing it doesn't use, D-pad only when
  it's needed (menus), the layout editor kept.
- **G4. Butter smooth.** One game frame per screen frame (story videos at
  their own speed: done), the pacing and sound paths as the arcade's,
  frame skip only as a safety net, loading stalls measured from the game
  log and cut, sharper picture (2x, Smooth) where the phone keeps full
  speed.
- **G5. On Home like every game.** A God of War card in the games rows,
  with its picture, Play and Continue. Until the library has it, "Add your
  copy" picks the file once; later it downloads from the library (P3).
- **G6. The library.** The ROM library moves to the new Cloudflare account
  (planned, waiting on the owner); the PSP games go in its `psp/` folder and
  download with resume like the arcade's.

Google Drive as the library: it can work (the Worker reads the files from
Drive with a service account and keeps the key check, so the files stay
private, downloads resume), but R2 is faster, made for this, and has no
download quotas; Drive is the fallback if R2 isn't possible.

## Testing

The cloud sandbox has no GPU: P1 is checked with PPSSPP's software renderer
where it can be; speed is only real on the owner's phone, so P2 ships with
the speed readout and he reports what it shows. Test games come from the
owner's library (downloaded with its key, never committed).

## Where things stand

- 2026-11-07: Assassin's Creed: Bloodlines set up (profile from its own
  tutorial and button panel, a Sprint pill, the Home card); checked here.
- 2026-11-07: the D-PAD switch is manual again for God of War: its "menu"
  byte also read 0 in play later in the game (the owner's log).
- 2026-11-07: the D-PAD button is gone for God of War: the stick follows
  the game's own "being played" byte (0x08C5D8D1 = 4).
- 2026-11-07: G2: moves checked in the game (Hyperion Charge is L, then ○);
  G5: the God of War card on Home (library later).
- 2026-11-07: G1 done (goes on from where it was left); G4: speed count
  holds while paused, PSP sound buffer 12 frames; G2 begun: Mash (L/R),
  checked in the game. Next: every move tried close up.
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
