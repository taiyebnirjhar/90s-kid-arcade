# KOF Arcade

A mobile emulator for a handful of famous Neo Geo games, with touch controls
built for each one: one-tap specials and supers for every King of Fighters
character, big Shoot / Jump / Bomb buttons and rapid fire for Metal Slug.

Runs in the phone's browser and installs to the home screen as a full-screen
app. Emulation is [EmulatorJS](https://github.com/EmulatorJS/EmulatorJS)
running the [FBNeo](https://github.com/finalburnneo/FBNeo) arcade core.

## Games

| Game | ROM set (zip) | Layout |
| --- | --- | --- |
| The King of Fighters '97 | `kof97` | Fighter: stick, A B C D, MAX (A+B+C), character moves |
| The King of Fighters 2002 | `kof2002` | Fighter: MAX is B+C |
| "KOF 2005" (10th Anniversary hacks of 2002) | `kf2k5uni`, `kof10th` or `kf10thep` | Fighter, 2002 move lists |
| Metal Slug, 2, X, 3, 4, 5 | `mslug` … `mslug5` | Shoot, Jump, Bomb, rapid fire; Slug Attack (D) on 4 and 5; Slide on 5 |
| Sengoku 3 | `sengoku3` | Weapon, Punch, Jump, Throw, plus the shared special inputs |

Every game also needs the Neo Geo BIOS, `neogeo.zip`.

## Playing

1. Open the app. Tap **Neo Geo BIOS** and pick your `neogeo.zip` (once).
2. Tap a game. The first time, it asks for that game's zip. A differently
   named file is fine: it is stored under the name the emulator expects.
3. **COIN**, then **START**.

ROMs are kept in the browser's storage on your phone. Nothing is uploaded.
Long-press a game card to remove its ROM.

**For your own copy of the app:** zips placed in a `roms/` folder next to
`index.html` are used directly, without adding them on the phone. `roms/` is
git-ignored so it never ends up in the repository. Don't do this on a public
host: anyone with the link could download the files.

### Fighters (KOF)

- **Team bar (top left):** tap a slot and pick a fighter, or tap a team name
  to fill all three. During the match tap whoever is fighting this round:
  the move buttons switch to that character.
- **Move ring:** that character's desperation moves and specials, each drawn
  as its stick motion. Desperation moves have an orange ring. One tap plays
  the whole input, frame-exact.
- **MAX:** one tap for the power-up (A+B+C in '97, B+C in 2002).
- **Roll / Blowback:** A+B and C+D.
- **Facing:** moves are written for a fighter facing right. When you cross
  sides, tap it and every move mirrors.
- **❚❚ → Edit <name>'s moves:** change or add moves (numpad notation, e.g.
  `2363214+A`), with ▶ to try one.

The move lists (`data/rosters/`) come from the MAME `command.dat` move-list
file. Mash moves ("tap A rapidly") are left out because one tap can't do
them.

### Metal Slug

Shoot, Jump and Bomb are bigger buttons. **Rapid** turns on auto-fire while
you hold Shoot.

### Controls for every game

❚❚ → **Controls**: stick style (floating, fixed or hidden), size, opacity,
move names, vibration, left-handed layout, and move timing.

Keyboard: arrows/WASD, J K U I = A B C D, 1–6 = move buttons, F = flip
facing, Enter = start, Backspace = coin.

## How it works

- `js/games.js` lists the games and their layouts.
- `js/macro.js` turns a command into a per-frame timeline (2 frames per
  direction, buttons held 4), mirrored when facing left.
- `js/input.js` plays it against the emulator's frame counter, one step per
  emulated frame, never skipping one; it also runs rapid fire.
- `js/glyph.js` draws each move's stick motion as an icon.
- Inputs reach FBNeo through EmulatorJS's `simulateInput` (A, B, C, D on
  RetroPad B, A, Y, X). The BIOS is written next to the game in the
  emulator's file system, where FBNeo looks for it.

## Running it

A static site with no build step.

```sh
npm install        # EmulatorJS + FBNeo core, for offline/self-hosting
npm run vendor     # copies them into vendor/; without it the EmulatorJS CDN is used
npm start          # http://localhost:8080
npm test
```

## License

GPL-3.0-or-later, as EmulatorJS. FBNeo has its own non-commercial license.
No game or BIOS files are included.
