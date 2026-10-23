# KOF Arcade

A mobile web emulator made only for the Neo Geo King of Fighters games
('94 to 2003, 2002 Magic Plus, 10th Anniversary / 2005 Unique).
It replaces the usual emulator clutter with arcade-style touch controls and
**one-tap buttons for specials, supers and hidden supers.**

It runs in the phone's browser and can be installed to the home screen as a
full-screen app. Emulation is done by [EmulatorJS](https://github.com/EmulatorJS/EmulatorJS)
running the [FBNeo](https://github.com/finalburnneo/FBNeo) arcade core in WebAssembly.

## Playing

1. Open the app and tap **Add ROM sets**. Pick your game zips (`kof97.zip`,
   `kof2002.zip`, `kf2k2mp.zip`, …) and the Neo Geo BIOS `neogeo.zip`. You only
   do this once: the files stay in the browser's storage on your phone.
2. Tap a game. Hold the phone sideways.
3. **COIN**, then **START**.

| Control | What it does |
| --- | --- |
| Left half of the screen | Floating stick: put your thumb down anywhere and it appears under it |
| A B C D | Normal buttons. Slide a finger between them for chains |
| Gold buttons | One tap does the whole motion and button, frame-exact |
| Roll / Blowback / MAX | AB, CD and the game's MAX/power-up input in one tap |
| Facing pill | Gold moves are written for a fighter facing right. When you cross sides, tap it and every move mirrors |
| ⚙ | Pick a character's move list or write your own; adjust timing, size, opacity, vibration, left-handed layout |
| Save / Load | Quick save state, slot 1 |

Keyboard (tablet with a keyboard, or desktop): arrows/WASD, J K U I = A B C D,
1–6 = gold buttons, F = flip facing, Enter = start, Backspace = coin.

## Writing your own moves

Moves use numpad notation, written as if your fighter faces right:

```
7 8 9     236        quarter circle forward   ↓↘→
4 5 6     214        quarter circle back      ↓↙←
1 2 3     623        dragon punch             →↓↘
          63214      half circle back         →↘↓↙←
          2363214+A  Iori's Ya Otome          ↓↘→↘↓↙← A
          2141236+A  Kyo's Orochinagi         ↓↙←↙↓↘→ A
          [2]8+A     charge down, up + A
          BC         2002 MAX activation
```

Up to six gold buttons per game. The ▶ button next to a move in the editor
fires it right away so you can check it in training.

Starter lists are included for Kyo, Iori, Terry, Ryo, Robert, Mai and K'.
They are the common commands from '97/'98/2002, so check them against the
game's own command list (and edit A↔C or B↔D for the other strength).

## How it works

- `js/macro.js` parses a command into a per-frame timeline: each direction is
  held 2 frames, then the buttons 4 frames, then everything is released
  (tunable in ⚙). Facing left swaps 4↔6, 1↔3, 7↔9.
- `js/input.js` plays the timeline against the emulator's own frame counter,
  one step per emulated frame and never skipping one, so a slow phone frame
  can't drop a direction from the motion. While a move plays it owns the stick
  and buttons; your finger's input comes back the frame it ends.
- Inputs go to FBNeo through EmulatorJS's `simulateInput`, using FBNeo's
  default Neo Geo layout (A, B, C, D on RetroPad B, A, Y, X).
- The BIOS is written into the emulator's file system next to the game, which
  is where FBNeo looks for `neogeo.zip`.

## Running it yourself

It is a static site: no build step.

```sh
npm install        # EmulatorJS + FBNeo core (optional, for offline/self-hosting)
npm run vendor     # copies them into vendor/ ; without this the EmulatorJS CDN is used
npm start          # http://localhost:8080
npm test           # unit tests for the motion engine and input layer
```

To play on a phone, host the folder on any HTTPS static host (GitHub Pages,
Netlify, Cloudflare Pages), open it, and use "Add to Home screen". After the
first game load the service worker caches everything for offline play.

## ROMs

No game or BIOS files are included or downloaded by this app. Use dumps of
games you own. FBNeo is strict about ROM sets: it needs sets that match its
current version (the error screen lists what is missing).

## Ideas for later

- Auto facing: read each fighter's X position from game RAM so the facing
  pill flips by itself.
- Android/iOS app wrapper (Capacitor or a Trusted Web Activity) for store
  installs and lower input latency.
- Two-player over Bluetooth controllers or EmulatorJS netplay.

## License

GPL-3.0-or-later, same as EmulatorJS. FBNeo has its own non-commercial license.
