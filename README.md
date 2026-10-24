# 90s Kid Arcade

An Android app for a handful of famous arcade games (Neo Geo, and Capcom's
Cadillacs and Dinosaurs and Street Fighter III), with touch controls
built for each one: one-tap specials and supers for every fighter in KOF,
Double Dragon and Street Fighter III, and big Shoot / Jump / Bomb buttons
with rapid fire for Metal Slug.

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

1. Tap **Neo Geo BIOS** and pick your `neogeo.zip` (once).
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

**Nightmare in the Dark:** Fire and Jump; hold Fire to keep throwing
flames (the same rapid fire switch as Metal Slug).

**Sengoku 3:** Weapon, Punch, Jump, Throw, plus the shared special inputs.

**Cadillacs and Dinosaurs:** Attack and Jump; **Special** is both at once
(the spinning attack). Run with the Run button or by pushing the stick to the
edge.

Pause → **Controls**: stick style (floating, fixed or hidden), size,
opacity, move names, vibration, left-handed layout, move timing.

## Multiplayer

One phone per player, as many as the game takes: 2 for most games, 3 for
Cadillacs and Dinosaurs. All phones on the same Wi-Fi, or on one phone's
hotspot. Home → **Multiplayer**: one phone hosts a game (player 1), the others
join; they find the host by themselves, or type the address it shows. The
host accepts each player, and the game starts when it's full or when the host
taps **Start**. Every phone needs the same game zip (and the BIOS for Neo Geo
games; the library has them).

How it works, the way GGPO / Fightcade do it (rollback netcode):
- Every phone runs the game, sending only its button states over UDP,
  straight to every other phone. Pause, rematch and leaving go through the
  host.
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
- Phones start identical because the host sends its emulator state to each
  when the game opens. Without that, the Neo Geo clock, which starts from each
  phone's time, would differ.
- A RAM checksum of confirmed frames every 2 seconds catches drift.
- The pause menu pauses every phone; **Rematch** restarts them all from the
  same start.
- Checked against the real games with `tools/emu.py`: rollback with
  random 0-7 frame delays lands on the same game as a perfect run, with two
  players in KOF and three in Cadillacs and Dinosaurs.
- Native side: `android/libretrodroid` (LibretroDroid::setNetplay).
  Protocol and tests: `android/core/.../net`.

## Move lists

`android/core/src/main/resources/rosters/` has one list per game: `kof97`,
`kof97plus`, `kof2002`, `kof2002mp` (Magic Plus), `kof2005`, `doubledr`
and `sfiii3`.

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

- **Double Dragon and Street Fighter III** come from `command.dat`,
  StrategyWiki and SuperCombo (and the 3rd Strike training scripts), and
  were checked the same way: every Double Dragon special and Charge Move,
  and every 3rd Strike special and all three Super Arts of each fighter.

`tools/fix_rosters.py` holds every KOF change with the reason;
`tools/doubledr_roster.py` and `tools/sfiii3_roster.py` write the other two
lists, and `tools/movetest.py` is the tester (it needs `tools/emu.py`'s setup).

## Your ROM library (optional)

Keep your zips in a private Cloudflare R2 bucket and download them on any
phone with one tap (home screen → **LIBRARY**). The library address is built
into the app (`Library.DEFAULT_URL`); it asks only for the key, once.

1. Create an R2 bucket (public access off) and upload your zips.
2. Create a Worker with the code in `server/worker.js`.
3. In the Worker's settings, add an R2 binding named `ROMS` to the bucket and
   a secret named `ARCADE_KEY` (the key the app asks for).

Without the key the Worker answers "Wrong key". The key can only download;
change the secret to lock out a lost phone.

## Project

- `android/core` — plain Kotlin: the move engine (numpad commands, played
  back one emulated frame at a time), motion icons, the game list and the
  KOF move lists (see [Move lists](#move-lists)). Tested without the
  Android SDK: `cd android/core && gradle test`.
- `android/app` — the Android app: [LibretroDroid](https://github.com/Swordfish90/LibretroDroid)
  running the [FBNeo](https://github.com/libretro/FBNeo) libretro core, a
  native touch HUD, and Compose screens.
- `server/worker.js` — the ROM library Worker.
- `tools/romsets.py` — rebuilds the ROM check data from FBNeo's Neo Geo and CPS-1 drivers.
- `tools/emu.py`, `tools/movetest.py` — a headless FBNeo front end, and the
  move tester built on it (bring your own core and ROMs).
- `docs/kof-ram.md` — the KOF RAM map fighter detection reads.
- `.github/workflows/android.yml` — downloads the FBNeo core from the
  libretro buildbot and builds the APK.

Build locally with Android Studio, or: download the FBNeo core into
`android/app/src/main/jniLibs/<abi>/libfbneo_libretro_android.so` (see the
workflow), then `cd android && ./gradlew :app:assembleDebug`.

## License

GPL-3.0-or-later (LibretroDroid is GPL-3.0). FBNeo has its own
non-commercial license.
