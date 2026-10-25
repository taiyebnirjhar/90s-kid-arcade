# 90s Kid Arcade

An Android app for a handful of famous arcade games (Neo Geo, and Capcom's
Cadillacs and Dinosaurs, Marvel vs Capcom and Street Fighter III), with
touch controls built for each one: one-tap specials and supers for every
fighter in KOF, Double Dragon, Street Fighter III and Marvel vs Capcom, big
Shoot / Jump / Bomb buttons with rapid fire for Metal Slug and Nightmare in
the Dark, and multiplayer with one phone per player over Wi-Fi or a hotspot.

| Game | ROM set (zip) |
| --- | --- |
| The King of Fighters '97 | `kof97` |
| The King of Fighters '97 Plus (Orochi playable) | `kof97pls` |
| The King of Fighters 2002 | `kof2002` |
| The King of Fighters 2002 Magic Plus | `kf2k2mp`, `kf2k2mpl` or `kf2k2mp2` |
| "KOF 2005" (10th Anniversary hacks of 2002) | `kf2k5uni`, `kof10th` or `kf10thep` |
| Metal Slug, 2, X, 3, 4, 5 | `mslug` … `mslug5` |
| Sengoku 3 | `sengoku3` |
| Cadillacs and Dinosaurs ("Mostofa", Capcom CPS-1) | `dino` |
| Nightmare in the Dark | `nitd` |
| Double Dragon (Neo Geo, 1995) | `doubledr` |
| Marvel vs Capcom (Capcom CPS-2) | `mvsc` (or `mvscu`, `mvscur1`, `mvscj`, `mvsca`...): the zip must include its `.key` file |
| Street Fighter III 3rd Strike (Capcom CPS-3) | `sfiii3n` (or `sfiii3nr1`, `sfiii3`, `sfiii3r1`) |

The Neo Geo games also need the Neo Geo BIOS, `neogeo.zip`; the Capcom
games don't. No ROMs or BIOS are included: use dumps of games you own.

File names don't matter. The app checks each zip against the files and CRCs
FBNeo expects (`romsets.json`, made from FBNeo's driver by `tools/romsets.py`),
works out which version of the game it is (`sengoku3a`, `kof97h`, ...) and
stores it under that name. If files are missing it says which ones, instead of
the game failing to start. Older Street Fighter III dumps, which keep each
flash SIMM as one file (`10`, `20`, `30`, `31`...), are converted to FBNeo's
chip files on import.

## Install

Each push to `main` builds the app and publishes it as the
[latest release](../../releases/latest) (the **Releases** box on the repo page). On your phone, download
`90s-Kid-Arcade.apk` from there and open it (allow installing from your browser
when Android asks). New builds install over the old one and keep your ROMs
and settings.

## Playing

1. Tap **Add BIOS** and pick your `neogeo.zip` (once).
2. Tap a game. The first time it asks for that game's zip: pick it from the
   phone (any file name is fine) or download it from your library (below).
   Long-press a game to remove its ROM.
3. **COIN**, then **START**.

**KOF:** the app reads the game's memory to see who you picked, who is
fighting and which way they face, so the move ring always matches your
fighter (turn this off in Controls to pick by hand). The ring around
A B C D shows that fighter's desperation moves and specials, each drawn as
its stick motion; one tap plays the whole input, frame-exact. **MAX** is one
tap (A+B+C in '97, B+C in 2002), as are Roll and Blowback. Pause →
*Edit <name>'s moves* to change any move.

Every move in the lists was checked in the real games (see
[Move lists](#move-lists)), and the buttons play inputs the way a real stick
moves: rolling through the diagonals (214214), tapping a direction twice
(22), only passing through up mid-motion so the fighter doesn't jump
(Mary's 1236982), and pressing "~" inputs back to back (K''s 214+C~A).
Button-mash specials (Joe's Bakuretsu Ken) play as five quick taps.

**Double Dragon:** one fighter, picked up automatically like KOF. A to D
are one attack at four strengths. When the life bar shows **CHARGE**, the
supers (Charge Moves) on the ring come out; Billy and Jimmy's A+B+C+D
turns them into their Double Dragon form for the round.

**Street Fighter III 3rd Strike:** six buttons, LP MP HP over LK MK HK, plus
Throw and Overhead. The app reads which Super Art you picked and shows only
that one on the ring, with the fighter's specials.

**Metal Slug:** Shoot, Jump and Bomb. Hold Shoot to keep firing (rapid
fire; switch it off in Controls). Slug Attack on 4 and 5, Slide on 5.

**Marvel vs Capcom:** six buttons like Street Fighter III, plus **Tag**
(HP+HK) and **Partner** (MP+MK, your Special Partner). The app follows
whichever of your two characters is on screen, through tags, and shows
their Hyper Combos and specials. Zips made before current FBNeo lack the
`.key` file the game's code is decrypted with, and won't start.

**Nightmare in the Dark:** Fire and Jump; hold Fire to keep throwing
flames (the same rapid fire switch as Metal Slug).

**Sengoku 3:** Weapon, Punch, Jump, Shuriken, and the Ninja Arts every
character shares: 22+A, 22+B, 28+B while holding an enemy (Kagetsura's
Shrike Drop into the sky, Kongoh's slam), the Ultimate (28+AB, gauge full)
and **Log escape** (A+B+C: you swap places with a log, for some life).

**Cadillacs and Dinosaurs:** Attack and Jump; **Special** is both at once
(the spinning attack). Run with the Run button or by pushing the stick to the
edge.

Pause → **Controls**: stick style (floating, fixed or hidden), size,
opacity, move names, vibration, left-handed layout, move timing.

## Multiplayer

One phone per player, as many as the game takes: 2 for most games, 3 for
Cadillacs and Dinosaurs. All phones on the same Wi-Fi, or on one phone's
hotspot (no internet needed). Every phone needs the same game zip (and the
BIOS for Neo Geo games; the library has them).

- **Open a room** (Home → **Multiplayer**): pick the game and it starts. The
  room's key (like `K7Q-2M`) shows as it opens, with a **Copy** button, and
  stays in the pause menu. It holds the host phone's address on the Wi-Fi or
  hotspot, so typing it connects straight there: no server, no internet, no
  searching.
- **Join a room**: type the key, or tap the room in the list of rooms nearby.
  Nobody has to let you in. You come in watching the game as it stands
  (up to 8 phones in a room), and watching never slows the players down.
- **Take a seat**: while watching, press **COIN** or **START**. You get a free
  seat, or the seat of a player who has pressed nothing for 20 seconds (say,
  the challenger who lost): they go back to watching. If two ask at once, the
  first request to reach the host wins; the other is told. The press goes in
  once you're seated, so in KOF COIN then START brings you in as the
  challenger. The host follows the same rule.
- **Leave any time**: your seat empties and the game goes on for the others.
  If the host leaves, the next phone takes the room over (and the key
  changes to its address). A phone that goes quiet for 5-10 seconds is
  treated as gone.

How it works, the way GGPO / Fightcade do it (rollback netcode):
- Every player's phone runs the game, sending only its button states over
  UDP, straight to the other players. Pause, taking a seat, restarting and
  leaving go through the host.
- Watchers never hold the players up: the host gives a newcomer the game at
  the newest frame every input is in for (from the emulator's rollback
  states, without stopping it), then passes on the players' inputs from
  there, over that watcher's own connection and thread. Watchers run only
  frames whose inputs are all in, so they never guess; one that falls
  behind or out of step gets the game again, and nobody else notices.
- Your own presses take effect after one frame. The partner's input is
  guessed (their last one) until it arrives, ~1-2 frames later on Wi-Fi;
  each partner's separately.
  On a slower connection (a hotspot, a far router) each phone raises its
  own input delay to 2-3 frames so the partner guesses less, and drops back
  to 1 when the connection allows.
- Wi-Fi stays awake during a game (a low-latency Wi-Fi lock): phones
  otherwise let the radio nap between packets, which shows up as hiccups.
  When a guess was wrong, the phone loads the state from that frame and
  replays to now in a few milliseconds, silently. You see the corrected
  game, not a delay.
- A phone that runs ahead of a partner waits a frame now and then (time
  sync). A phone stops only if a partner falls 8 frames behind.
- Phones play the same game because whenever who plays changes (someone
  takes a seat, or a player leaves), the room's owner stops the game for a
  moment and sends everyone its emulator state (compressed) and the new
  seats; all carry on from that state. An empty seat plays no input and
  nobody waits for it.
- A RAM checksum of confirmed frames every 2 seconds catches drift; if two
  phones ever differ, the owner re-sends its game to everyone in the same
  way, and play goes on from there (nothing restarts). A game stuck for 3
  seconds with nobody paused asks for the same. The pause menu shows how
  the connection is doing (each phone's inputs and when it was last heard).
- A player's pause menu pauses every phone (a watcher's pauses nobody). For a rematch, use the game's own:
  in KOF the other player presses COIN and START mid-fight ("Here comes a
  new challenger!"), and after a versus fight the loser continues with
  COIN and START. *Restart the game for everyone* (bottom of the pause menu)
  is only for a game stuck somewhere.
- Checked against the real games with `tools/emu.py`: rollback with
  random 0-7 frame delays lands on the same game as a perfect run, with two
  players in KOF and three in Cadillacs and Dinosaurs.
- Native side: `android/libretrodroid` (LibretroDroid::setNetplay).
  Rooms, protocol and tests: `android/core/.../net` (`Room` in `Session.kt`).

## Move lists

`android/core/src/main/resources/rosters/` has one list per game: `kof97`,
`kof97plus`, `kof2002`, `kof2002mp` (Magic Plus), `kof2005`, `doubledr`,
`sfiii3` and `mvsc`.

- **Sources.** '97 and 2002 come from MAME's `command.dat`, cross-checked
  against StrategyWiki, SuperCombo and SNK Fandom. 2005 Unique has no
  `command.dat` entry: its list is 2002's with the inputs the hack changed,
  from Chinese move lists written for it (ddooo.com, kofcn.org).
- **Checked in the games.** Each move was played in FBNeo exactly as the
  app plays it, and counted as working only if the fighter really did it:
  a super has to use power or flash the screen, a special has to start an
  animation no normal attack or simpler listed move makes. Where the
  sources and the game disagreed, the game won. For example, Benimaru's
  Elec-Trigger in '97 only comes out as 632146 632146, and in 2005 Terry's
  Rising Tackle is 623+A.
- **2005's changed moves** were found by their animation: the same
  animation as the 2002 move, under the new input.
- Moves left out on purpose: inputs no source or test could bring out in
  that game.

- **Double Dragon, Street Fighter III and Marvel vs Capcom** come from `command.dat`,
  StrategyWiki and SuperCombo (and the 3rd Strike training scripts), and
  were checked the same way: every Double Dragon special and Charge Move,
  every 3rd Strike special and all three Super Arts of each fighter, and
  every Marvel vs Capcom Hyper Combo and special of all 21 characters.

`tools/fix_rosters.py` holds every KOF change with the reason;
`tools/doubledr_roster.py`, `tools/sfiii3_roster.py` and
`tools/mvsc_roster.py` write the other lists, and `tools/movetest.py` is the tester (it needs `tools/emu.py`'s setup).

## Your ROM library (optional)

Keep your zips in a private Cloudflare R2 bucket and download them on any
phone with one tap (home screen → **Library**). The library address is built
into the app (`Library.DEFAULT_URL`); it asks only for the key, once.

1. Create an R2 bucket (public access off) and upload your zips.
2. Create a Worker with the code in `server/worker.js`.
3. In the Worker's settings, add an R2 binding named `ROMS` to the bucket and
   a secret named `ARCADE_KEY` (the key the app asks for).

Without the key the Worker answers "Wrong key". The key can only download;
change the secret to lock out a lost phone.

## Project

- `android/core` — plain Kotlin: the move engine (numpad commands, played
  back one emulated frame at a time), motion icons, the game list, fighter
  detection, the ROM checks and the move lists (see [Move lists](#move-lists)). Tested without the
  Android SDK: `cd android/core && gradle test`.
- `android/app` — the Android app: [LibretroDroid](https://github.com/Swordfish90/LibretroDroid)
  running the [FBNeo](https://github.com/libretro/FBNeo) libretro core, a
  native touch HUD, and Compose screens.
- `server/worker.js` — the ROM library Worker.
- `tools/romsets.py` — rebuilds the ROM check data from FBNeo's Neo Geo,
  CPS-1, CPS-2 and CPS-3 drivers.
- `tools/emu.py`, `tools/movetest.py` — a headless FBNeo front end, and the
  move tester built on it (bring your own core and ROMs).
- `docs/kof-ram.md` — the KOF RAM map fighter detection reads (the other
  games' addresses are documented in `Detect.kt`).
- `.github/workflows/android.yml` — downloads the FBNeo core from the
  libretro buildbot and builds the APK.

Build locally with Android Studio, or: download the FBNeo core into
`android/app/src/main/jniLibs/<abi>/libfbneo_libretro_android.so` (see the
workflow), then `cd android && ./gradlew :app:assembleDebug`.

## License

GPL-3.0-or-later (LibretroDroid is GPL-3.0). FBNeo has its own
non-commercial license.
