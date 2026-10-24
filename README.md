# 90s Kid Arcade

An Android app for a handful of famous Neo Geo games, with touch controls
built for each one: one-tap specials and supers for every King of Fighters
character, big Shoot / Jump / Bomb buttons and rapid fire for Metal Slug.

| Game | ROM set (zip) |
| --- | --- |
| The King of Fighters '97 | `kof97` |
| The King of Fighters 2002 | `kof2002` |
| "KOF 2005" (10th Anniversary hacks of 2002) | `kf2k5uni`, `kof10th` or `kf10thep` |
| Metal Slug, 2, X, 3, 4, 5 | `mslug` … `mslug5` |
| Sengoku 3 | `sengoku3` |

Every game also needs the Neo Geo BIOS, `neogeo.zip`. No ROMs or BIOS are
included: use dumps of games you own.

File names don't matter. The app checks each zip against the files and CRCs
FBNeo expects (`romsets.json`, made from FBNeo's driver by `tools/romsets.py`),
works out which version of the game it is (`sengoku3a`, `kof97h`, ...) and
stores it under that name. If files are missing it says which ones, instead of
the game failing to start.

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

**KOF:** pick your three fighters in the team bar (top left) — tap a slot,
or a team name to fill all three — and tap whoever is fighting this round.
The move ring around A B C D shows only that fighter's desperation moves and
specials, each drawn as its stick motion; one tap plays the whole input,
frame-exact. **MAX** is one tap (A+B+C in '97, B+C in 2002), as are Roll and
Blowback. Moves are written for a fighter facing right: when you cross
sides, tap **FACING** and they mirror. Pause → *Edit <name>'s moves* to
change any move.

**Metal Slug:** Shoot, Jump and Bomb; **Rapid** auto-fires while you hold
Shoot. Slug Attack on 4 and 5, Slide on 5.

**Sengoku 3:** Weapon, Punch, Jump, Throw, plus the shared special inputs.

Pause → **Controls**: stick style (floating, fixed or hidden), size,
opacity, move names, vibration, left-handed layout, move timing.

## Two players

Two phones on the same Wi-Fi, or one phone's hotspot with the other connected
to it. Home → **2P**: one phone hosts a game (player 1), the other joins
(player 2); it finds the host by itself, or type the address the host shows.
Both phones need the same game zip and BIOS (the library has them).

How it works:
- Both phones run the game, exchanging only button states over UDP.
  Every frame runs with both players' inputs on both phones: delay-based
  lockstep, 3 frames (~50 ms) of input delay.
- Phones start identical because the host sends its emulator state when the
  game opens. Without that, the Neo Geo clock, which starts from each
  phone's time, would differ.
- A RAM checksum every 2 seconds catches the games drifting apart.
- Native side: `android/libretrodroid` lockstep mode. Protocol and tests:
  `android/core/.../net`.

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
  KOF '97 / 2002 move lists (from MAME's `command.dat`). Tested without the
  Android SDK: `cd android/core && gradle test`.
- `android/app` — the Android app: [LibretroDroid](https://github.com/Swordfish90/LibretroDroid)
  running the [FBNeo](https://github.com/libretro/FBNeo) libretro core, a
  native touch HUD, and Compose screens.
- `server/worker.js` — the ROM library Worker.
- `tools/romsets.py` — rebuilds the ROM check data from FBNeo's Neo Geo driver.
- `.github/workflows/android.yml` — downloads the FBNeo core from the
  libretro buildbot and builds the APK.

Build locally with Android Studio, or: download the FBNeo core into
`android/app/src/main/jniLibs/<abi>/libfbneo_libretro_android.so` (see the
workflow), then `cd android && ./gradlew :app:assembleDebug`.

## License

GPL-3.0-or-later (LibretroDroid is GPL-3.0). FBNeo has its own
non-commercial license.
