# Adding a game

Every game goes through this list. Each step is checked in the real game in
FBNeo (`tools/emu.py`), never taken from a wiki alone. ROMs are never
committed or placed in the repo; keep them in a scratch folder.

1. **ROM check.** Add the set to `BASE` in `tools/romsets.py` and its FBNeo
   driver file to the usage line and `"source"`, then regenerate
   `romsets.json`. Its clones come with it. Only the new sets may change.
2. **The game** (`core/Games.kt`):
   - its sets, layout, labels and players;
   - `neoGeo` (needs the BIOS or not);
   - `pad` (which emulator button each of A-F is);
   - its libretro thumbnail name in `art`;
   - `dips`, if the cabinet must be set up a certain way (FBNeo's DIP
     switch names, as the core asks for them: Mystic Warriors needs
     `Number_of_Players` 4 and `Coin_Mechanism` Independent, or only two
     players can join);
   - `startWith`, for a cabinet with no START button (Sunset Riders' 4-player
     one: players join with fire).

   Check players 3 and 4 too: their coin and start must work.

   Check every button in the game: what A, B, C, D do, whether holding fires
   once or keeps firing (`rapid`), and the frame rate.
3. **Seats: who's in, from the game itself** (see
   [the seat rule](#the-seat-rule-seats-follow-the-games-own-countdown)).
   `seats` must say where the game keeps each player's status.
   `GamesTest.everyGameSaysWhosInEachSeat` fails without it.
4. **Moves**, if it has a move list: every move tried in the game
   (`tools/movetest.py`). Judge supers by their **animation**, not by power
   spent: some hacks keep the gauge full.
5. **Pictures:** regenerate `app/art.txt` (`KOF_WRITE_ART_LIST=1` with the
   core tests).
6. **Savestates replay exactly** (rollback needs it): load a state, play the
   same inputs twice, and compare.
7. **README and site:** the game list, its controls, and the game count.

## The seat rule: seats follow the game's own countdown

In a room, a watcher's COIN or START takes:

- a free seat; or
- the seat of a player whose game is over, as on the arcade cabinet. That
  means the player never started, or the game's **own continue countdown
  has run out**.

While the countdown runs, the seat stays the player's. **The app adds no timer
of its own.** The host's phone reads each seated player's status byte from
its copy of the game (`RoomListener.seatFree`, `Game.seats`). A player who
just sat down gets 8 seconds to press START before the game's word counts.

`Game.seats` is a `SeatStatus`:

- `offsets`: one raw offset per player into the emulator's system RAM, as
  libretro hands it out (`retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM)`);
- `free`: the values that mean "out".

Known so far:

| Game | Offsets (raw) | Values |
| --- | --- | --- |
| Every Neo Geo game | `0xFDB7` (P1), `0xFDB6` (P2): the BIOS's `BIOS_PLAYER_MOD1/2` at 0x10FDB6/7, bytes swapped in pairs | 0 never played, 1 playing, 2 continue, **3 game over**. Free: 0, 3 |
| Cadillacs and Dinosaurs | `0xB317`, `0xB497`, `0xB617` (68000 0xFFB316, +0x180 a player) | 0 out, 4 choosing, 6 playing, 8 continue. Free: 0 |
| Penguin Brothers | `0x35B2`, `0x362E` | 0 out, 2 playing, 4 continue, 6 game over. Free: 0, 6 |
| Marvel vs. Capcom | `0x32B1`, `0x36B1` | 0 out, 6 playing, 8 continue, 10 or 12 game over. Free: 0, 10, 12 |
| Street Fighter III 3rd Strike | `0x156E1` (P1), `0x156E0` (P2): the in-fight flag | 1 in the fight, 0 out. Free: 0 for 18 s (`outForMs`) |
| Mystic Warriors | `0x20D`, `0x24D`, `0x28D`, `0x2CD` | 0 out, 2 choosing a ninja, 4 playing and counting down. Free: 0 |
| Sunset Riders (4 players) | `0x211`, `0x251`, `0x291`, `0x2D1`; game byte `0x0` | 129 playing, 1 countdown, 0 out. Free: 0, or no game on (`0x0` not 2) |
| J.J. Squawkers | `0x561D`, `0x561C` | 127 out (126 a moment in attract), 0 or 1 playing, 255 countdown. Free: 126, 127 |
| Snow Bros. | `0x40`, `0x4A`; game byte `0x2D` | Not 0 while in, 0 out. Free: 0 (or no game on: `0x2D` not 128) for 17 s (`outForMs`) |

**Neo Geo games get this automatically** (`seats` defaults to the BIOS bytes
when `neoGeo` is true). Every other board needs its own.

**When the attract demo sets the players' bytes too** (Snow Bros., Sunset
Riders), find a byte that says whether a real game is on, and give it as
`game` with the values it has then (`inGame`); while it has any other,
every seat counts as out. Compare the attract demo's stretches with a real
game's.

**When a game has no countdown byte** (Street Fighter III: its flag drops
when the countdown *starts*), set `outForMs` to the countdown's measured
length. The host then samples the byte twice a second (`SeatWatch`) and
frees the seat only once it has read "out" for that long without a break.
Measure the countdown from screenshots (flag drop to GAME OVER or "PRESS
START" coming back) and round up.

### Finding a game's status bytes

1. Record the whole system RAM once a second (`tools/emu.py`, `ctypes.string_at`
   on `retro_get_memory_data(2)`), and screenshots every 10 s, while:
   - nobody plays (attract);
   - P1 inserts a coin and starts;
   - P1 stands idle until game over, through the continue countdown.
2. From the screenshots, note the seconds of playing, of the countdown, and
   just after game over.
3. Look for a byte that:
   - has one value while playing;
   - has another during the countdown;
   - returns to its attract value after game over.
4. Do the same for P2 with P2 alone, or with a versus match where the loser's
   byte changes back while the winner plays on. Players are usually a fixed
   stride apart.
5. Confirm the byte in a two-player game before adding it.

**A fallback exists only so a room never locks up.** For a board whose bytes
aren't found yet, `seats` is null, and a player who pressed nothing for 10
seconds can be replaced. Don't add a game that way: the test requires
`seats`.
