# 90s Kid Arcade

An Android app for a handful of famous arcade games (Neo Geo, Capcom's
Cadillacs and Dinosaurs, Marvel vs Capcom and Street Fighter III, Konami's
Mystic Warriors, Sunset Riders and Wild West C.O.W.-Boys of Moo Mesa, Toaplan's Snow Bros., Athena's J.J.
Squawkers and Subsino's Penguin Brothers), with
touch controls built for each one: one-tap specials and supers for every
fighter in KOF, Garou, The Last Blade 1 and 2, Samurai Shodown II, Double
Dragon, Street Fighter III and Marvel vs Capcom, big
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
| Penguin Brothers (Subsino, 2000) | `penbros` (or `ablast`, Hong Tian Lei) |
| Double Dragon (Neo Geo, 1995) | `doubledr` |
| Garou - Mark of the Wolves | `garou` (or `garouh`, `garouha`, `garoup`...) |
| The Last Blade | `lastblad` (or `lastblada`, `lastbladh`, `lastsold`...) |
| The Last Blade 2 | `lastbld2` |
| Samurai Shodown II | `samsho2` (or `samsho2k`, `samsho2ka`...) |
| Marvel vs Capcom (Capcom CPS-2) | `mvsc` (or `mvscu`, `mvscur1`, `mvscj`, `mvsca`...): the zip must include its `.key` file |
| Street Fighter III 3rd Strike (Capcom CPS-3) | `sfiii3n` (or `sfiii3nr1`, `sfiii3`, `sfiii3r1`) |
| Mystic Warriors (Konami, 1993) | `mystwarr` (or `mystwarru`, `mystwarrj`, `mystwarra`, `mystwarraa`) |
| Sunset Riders (Konami, 1991), the 4-player version | `ssriders` (or `ssridersuda`, `ssridersjad`... any "4 Players" one) |
| Snow Bros. - Nick & Tom (Toaplan, 1990) | `snowbros` (or `snowbrosa`... `snowbrosj`) |
| J.J. Squawkers (Athena, 1993) | `jjsquawk` (or `jjsquawko`) |
| Wild West C.O.W.-Boys of Moo Mesa (Konami, 1992) | `moomesa` (or `moomesaaab`, `moomesauab`, `moomesauac`) |

The Neo Geo games also need the Neo Geo BIOS, `neogeo.zip`; the others
don't. No ROMs or BIOS are included: use dumps
of games you own.

File names don't matter. The app checks each zip against the files and CRCs
FBNeo expects (`romsets.json`, made from FBNeo's driver by `tools/romsets.py`),
works out which version of the game it is (`sengoku3a`, `kof97h`, ...) and
stores it under that name. If files are missing it says which ones, instead of
the game failing to start. Older Street Fighter III dumps, which keep each
flash SIMM as one file (`10`, `20`, `30`, `31`...), are converted to FBNeo's
chip files on import.

## Install

The website, [taiyebnirjhar.github.io/90s-kid-arcade](https://taiyebnirjhar.github.io/90s-kid-arcade/)
(`site/`, published by `.github/workflows/pages.yml`), has the download button and how to install.

Each push to `main` builds the app and publishes it as the
[latest release](../../releases/latest) (the **Releases** box on the repo page). On your phone, download
`90s-Kid-Arcade.apk` from there and open it (allow installing from your browser
when Android asks). New builds install over the old one and keep your ROMs
and settings.

## Playing

1. The first time, the home screen asks for the Neo Geo BIOS: tap
   **Add BIOS** and pick your `neogeo.zip` (once; Capcom games don't need it).
2. Tap a game's card. The first time it asks for that game's zip: pick it
   from the phone (any file name is fine) or download it from your library
   (below). Cards of games you haven't added are grey and say **Add ROM**; long-press a
   game to remove its ROM.
3. **COIN**, then **START**.

The home screen opens on the game you played last, with **Continue**, and
lists every game by series as picture cards. **Online**,
**Library** and your **Profile** are at the top. The pictures (each game's title and play
screens) ship inside the app: they show offline, from the first launch.

In a game, the pause button opens the pause menu: Resume, save and load
state, *Edit <name>'s moves*, **Sound** on/off, **Settings**, **Button layout** (drag any
control where your thumbs want it, tap one to resize it; the gear slides in the
stick style, buttons on the left, button size and opacity for every control),
**Start over** and Quit.

Playing alone, a game goes on where you left it: it's kept as you leave
(and every minute), and **Continue** picks it up. **Start over** in the
pause menu begins it afresh. Multiplayer games always start from the host's.

**KOF:** the app reads the game's memory to see who you picked, who is
fighting and which way they face, so the move ring always matches your
fighter. The fighter (name, face, move ring, *Edit moves*) shows once a
round is on: on the select screen the picks are still changing. Each fighter's face
comes from the game itself: the first time they're on screen the app takes
the face the game draws by the life bar (Double Dragon: on its select
screen) and shows it from then on on the team buttons, the pause menu and
the select screen; the app ships no character art. The ring around
A B C D shows that fighter's desperation moves and specials, each drawn as
its stick motion; one tap plays the whole input, frame-exact. **MAX** is one
tap (A+B+C in '97, B+C in 2002), as are Roll and Blowback; they keep the stick's direction, as on the
arcade, so back + Roll rolls back. **Block** (above the stick) holds back for as long as
you hold it (slide down for a low block), even as the fighters swap sides. Pause →
*Edit <name>'s moves* to change any move.

The controls start small and in the corners (Button size 85%), so the game
stays in view, and shrink with a smaller screen so it gets the same layout.

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

**Garou - Mark of the Wolves:** one fighter, picked up automatically. LP LK
HP HK; **Evade** (A+B), **T.O.P.** (C+D, while your life is in the T.O.P.
area) and **Feint** (2+A+C). Grant and Kain are picked with the arcade's
codes on the select screen.

**The Last Blade 1 and 2:** Weak and Strong slash, Kick and **Repel** (D
deflects a high attack, **Low repel** 2+D a low one; in 2, **Throw** C+D and
**Unblockable** B+C too). Power or Speed (and EX in 2) is
picked before each fight; each move's note says which style it needs.

**Samurai Shodown II:** light and medium slash and kick; A+B is the strong
slash, C+D the strong kick and B+C a hop. Rolls (tap 3 or 1 twice) and lying
down (2 twice) are on the stick. The Weapon Destruction moves come out with
the POW gauge full.

**Street Fighter III 3rd Strike:** six buttons, LP MP HP over LK MK HK, plus
Throw and Overhead. The app reads which Super Art you picked and shows only
that one on the ring, with the fighter's specials.

**Metal Slug:** Shoot, Jump and Bomb. Hold Shoot to keep firing (rapid
fire; switch it off in Pause → Settings → Controls). Slug Attack on 4 and 5, Slide on 5.

**Marvel vs Capcom:** six buttons like Street Fighter III, plus **Tag**
(HP+HK) and **Partner** (MP+MK, your Special Partner). The app follows
whichever of your two characters is on screen, through tags, and shows
their Hyper Combos and specials. Zips made before current FBNeo lack the
`.key` file the game's code is decrypted with, and won't start.

**Nightmare in the Dark:** Fire and Jump; hold Fire to keep throwing
flames (the same rapid fire switch as Metal Slug).

**Penguin Brothers:** Bomb and Jump, for one or two. Jump takes you up to
the floor above. One bomb is out at a time (the next comes once it goes
off), so Bomb has no rapid fire.

**Sengoku 3:** Weapon, Punch, Jump, Shuriken, and the Ninja Arts every
character shares: 22+A, 22+B, 28+B while holding an enemy (Kagetsura's
Shrike Drop into the sky, Kongoh's slam), the Ultimate (28+AB, gauge full)
and **Log escape** (A+B+C: you swap places with a log, for some life).

**Cadillacs and Dinosaurs:** Attack and Jump; **Special** is both at once
(the spinning attack). Run with the Run button or by pushing the stick to the
edge.

**Mystic Warriors:** Attack and Jump, for up to four (hold Attack to keep
throwing). The app sets the cabinet up for four players, a coin slot each.

**Sunset Riders:** Shoot and Jump, for up to four (hold Shoot to keep
shooting). This cabinet has no START button: you join with Shoot, so START
does that.

**Wild West C.O.W.-Boys of Moo Mesa:** Shoot and Jump, for up to four (hold
Shoot to keep shooting), a coin slot each.

**Snow Bros.:** Snow and Jump, for one or two, with rapid fire on Snow.

**J.J. Squawkers:** Attack and Jump, for one or two, with rapid fire on
Attack.

Pause → **Settings**, in four tabs: **Screen** (picture width, detection
readout), **Controls** (stick style: floating, fixed or
hidden; button size; opacity; buttons on the left; vibration; rapid fire),
**Moves** (move names under the buttons, move timing) and **Sound** (on or
off, volume up and down in steps). The phone's volume buttons set the
game's volume too.
Changes show on the game as you make them. Pause → **Performance** shows
how the game runs (frames a second, late frames made up, sound gaps), with
*Send report* to share it.

### Smooth frames, clean sound

- **Frames go with the screen** when its rate is the game's or a multiple
  (60 Hz: a frame a tick; 120 Hz: a frame every other tick), so every frame
  shows for the same time. On 90 and 144 Hz screens the app asks Android
  for 60 or 120 Hz (where the phone allows), and otherwise times frames by
  the clock. Phones change their screen's rate as they please (between 60
  and 90 or 120 Hz); frames follow.
- **A frame that runs late is made up** in the next tick (two run), so the
  game keeps its pace, and its sound with it. Before, it was lost: on a 60
  Hz screen each one cost the sound a frame's worth, and a few close
  together ran it dry (gaps you hear, not see).
- **The sound** waits in a small buffer (about 30 ms) between the game and
  the speaker, kept there by playing it a hair faster or slower. That
  adjustment follows the buffer's average, not each frame's ups and downs
  (which wobbled the pitch 60 times a second), and the sound is resampled
  with a cubic curve carried from block to block. If the game holds up for
  a moment, the sound stretches over it or fades out and back in: never a
  hard gap or a click. Its rate also matches the frames' (a Neo Geo game
  runs at 60 on a 60 Hz screen, a hair over its own 59.19).
- The macros and rapid fire run on the emulator's thread straight after
  each frame, so a move's every step lands on its frame, however busy the
  screen is.
- `android/libretrodroid/src/test/cpp/playback_test.cpp` (run in CI) plays
  a minute on 60, 90 and 120 Hz screens with late frames and hold-ups, and
  compares with the sound path as it was: on a 60 Hz screen with 3% of
  frames late, 106 sound gaps and 279 clicks a minute before, none now.

## Online and nearby play

One phone per player, as many as the game takes: 2 for most games, 3 for
Cadillacs and Dinosaurs, 4 for Mystic Warriors, Sunset Riders and Moo Mesa; up to 8
phones in a room, the rest watching. Every phone needs the same game zip
(and the BIOS for Neo Geo games; the library has them). Everything is on
Home → **Online**.

- **With internet: Create a room.** Pick **Open** (listed under **Rooms
  online** for anyone with the game, one tap to join; its connections go
  through the relay, so nobody learns a player's address) or **Locked**
  (listed, but only its code gets in), then the game, and it starts. The
  host can lock an open room, and open it again, from the room sheet. The room sheet shows its code (like
  `K7Q-2MX`) with a QR code, **Copy** and **Share**. Friends on any network
  type the code under **Join with a code**, scan the QR code, or tap the
  shared link (it opens the app straight into the room). It needs a free
  sign-in (your profile, made the first time). How it connects is hidden:
  phones on the same Wi-Fi connect there, else directly over the internet,
  else through the relay (only when a direct way is blocked). Only button
  presses travel, phone to phone, encrypted.
- **Without internet (or to use no data): Create nearby room.** All phones
  on the same Wi-Fi, or on one phone's hotspot: no internet, no server, zero
  lag. The room's key (like `K7Q-2M`) holds the host phone's address, so
  typing it connects straight there; rooms nearby also show up by
  themselves, first in the list.
- **Join**: tap a room under **Rooms online** (a locked one asks for its
  code), type a code or key under **Join with a code** (it formats itself
  as you type) or tap a room nearby (each shows its game, host and seats).
  Nobody has to let you in: you watch the game as it stands (up to 8 phones
  in a room), and watching never slows the players down. A problem says
  what to do, with a button for it (get the game, update the app).
- **In the game**, a pill at the top right shows the room: its key, a dot
  per seat (gold: taken), how many watch and, in an online room, bars for
  the connection, with **WATCHING** in front while you watch, "Seat free ·
  press COIN" when there's room, and your place when you're in line. Tap it
  for the room sheet: who sits where, who watches, **Take a seat** or
  **Watch instead**, and Leave. News (someone joined, left, took a seat)
  slides in at the top; getting a seat says "You're player 2!".
- **Take a seat**: while watching, press **COIN** or **START** (or Take a
  seat). You get a free seat, or the seat of a player whose game is over,
  as on the cabinet: the game's own continue countdown ran out (say, the
  challenger who lost). They go back to watching. The app reads this from
  the game itself, in every game (see
  [docs/adding-a-game.md](docs/adding-a-game.md)); it adds no timer of its
  own. No seat to take: you're in line ("You're 2nd in line", **Leave
  line** to just watch), and the next seat that opens is yours in turn. The press goes in once you're seated, so in KOF COIN
  then START brings you in as the challenger. The host follows the same
  rule, and anyone can give their seat up with **Watch instead**.
- **Chat**: the speech bubble by the room pill opens the chat down the
  right side (the game goes on): quick lines ("GG", "Rematch?", "Nice!"…)
  in one tap, or type one. What's said shows for a few seconds at the top
  left, under the pause button, where no control is. Tap someone's line to
  mute them (kept for every room), or, in an internet room, to add them
  as a friend, report them (pick why) or block them. A word filter stars
  out swear words (Settings → Chat turns it, and the lines over the game,
  off). The host passes each line on; a phone that says more than 5 in
  10 seconds is told to slow down.
- **Voice** (internet rooms): the mic button by the chat is off when you
  join (crossed out); tap it to talk (the app asks for the microphone the
  first time; nothing is recorded before). Settings → Chat: the mic open
  or push to talk (hold the button), voices on or off and their own
  volume. Who's speaking shows at the top left. The host can mute someone
  for the room, or let watchers only listen, from the room sheet. Voice
  goes the same way as the game (direct, else the relay) and uses roughly
  10-20 MB an hour.
- **Friends** (Online screen): your 8-letter friend code to share, adding
  a friend by theirs (or from a room's chat), requests to accept or
  decline, and your friends online first with what they're playing: **Join**
  goes straight into their internet room, open or locked. From a room's
  sheet, invite friends who are online; an invite shows at the top of
  their Online screen. **Block** works both ways: a blocked player can't
  find you, ask you or invite you.
- **Problem reports**: when a join fails or a room is lost, the phone
  sends its connection log to the app's maker by itself (only once you've
  been online; Settings → Screen turns it off). It holds what the
  connection tried, the phone's model and the room's code, nothing else.
- **Quick match** (Online screen): into an open room of a game you have
  (one with a free seat first); with none, yours opens for anyone.
- **Play from a saved state**: the host or any player picks **Load state
  for everyone** in the pause menu (and confirms). The host's phone checks
  the save fits this game (a save of another game or another version of it
  is refused, and the game goes on), then everyone carries on from it
  together, in the seats they have now; watchers too. Two at once: the
  first counts. **Save state** works in a room too: it saves the newest
  moment every phone agrees on.
- **Leave any time**: tapping **Leave** empties your seat at once and the
  game goes on for the others. If the host leaves, the next phone takes the
  room over (and the key changes to its address).
- **Dropped out? Your seat waits 60 seconds; the others play on.** A phone
  that goes quiet (Wi-Fi blip, app closed by accident) is treated as gone
  after a few seconds, but its seat is kept for it. Everyone waits 5
  seconds ("Waiting for Rafi to reconnect…"), as most blips are over by
  then, then plays on: Rafi's fighter stands still and the seat stays his
  ("Reconnecting… seat kept 0:42" in the room sheet). Watchers who press
  COIN are told the seat is held. The host can tap **Free seat** to give it
  away; after 60 seconds it frees itself.
- **Back by itself.** The phone that dropped shows "Connection lost.
  Reconnecting…" and tries again every second, and at once when its Wi-Fi
  comes back; when it's in, it sits straight back down (the same phone, by
  its own id). Nobody types the key again. It also listens for the host's
  beacon, so it finds the room even if the host came back with another
  address.
- **Stays on the Wi-Fi.** When Wi-Fi gets weak, Android moves a phone's
  traffic to mobile data and can leave it there long after; the room's
  phones can't be reached that way. So during a game (and while joining)
  the app's traffic is tied to the room's Wi-Fi (`LanLink`), and tied
  again the moment that Wi-Fi comes back.
- **If only your connection broke** (the host is fine), you go straight back
  to the host: nobody takes the room over, nobody else notices. Phones that
  still hear the host refuse a would-be new host.
- **If the host drops**, the next phone takes the room over and holds the
  host's seat the same way; the host's phone, its Wi-Fi back, finds the room
  where it went and rejoins into its seat. A phone that can't reach anyone
  never opens a room of its own: it reconnects instead.
- **If the network splits** and two phones end up running the room, they
  find each other once it's back: the one with fewer phones (on a tie, the
  later one) moves into the other's room first, then its phones follow. It
  gives its room up only once it's in the other, so the room is never left
  with nobody running it.
- **If everyone drops at once** (the hotspot phone or the router went), the
  host keeps the room and everyone's seats; when the network is back, every
  phone finds it again and the game goes on.
- **Connection report.** Pause → Connection details → *Send connection
  report* shares what happened (drops, reconnects, errors, and every 10
  seconds that weren't smooth: rollbacks, stalls, waits, input delay, the
  slowest frame, and any Wi-Fi hiccup: "nothing from Rafi for 240 ms"),
  with the phone's model and screen rate, to report a problem.

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
  Inputs go marked for Wi-Fi's quickest lane (DSCP EF, WMM voice/video), so
  a busy router sends them ahead of downloads and videos.
  When a guess was wrong, the phone loads the state from that frame and
  replays to now in a few milliseconds, silently. You see the corrected
  game, not a delay. Only frames run on a guess are saved to go back to,
  so on a good connection most frames save nothing and run quicker; and
  Android is told how long each frame's work takes (performance hints,
  Android 12 on), so it speeds the CPU up before a replay runs late.
- Every phone in a room runs at the same pace: 60 frames a second, with
  the screen on 60 and 120 Hz phones and by the clock on 90/144 Hz ones.
  Otherwise the faster phone would keep having to wait for the others.
- A phone that still gets ahead of a partner (screens are never exactly
  alike) waits so the other catches up (time sync), but only for a real
  lead: a frame or more on average over a second, per partner. A single
  measurement jumps by a frame or two with each packet's timing; waiting
  on those (as it used to) held a frame on screen about once a second on a
  router's Wi-Fi. On 90/120 Hz phones a wait is spread over 20 frames,
  unseen. A lead of two frames or more is acted on within a quarter of a
  second, so a phone that can't keep up (an old one in a heavy scene) is
  followed by the others rather than left behind. A phone stops only if a
  partner's inputs are 12 frames (a fifth of a second) late, longer than
  most Wi-Fi hiccups.
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
  COIN and START. *Restart for everyone* (in the pause menu)
  is only for a game stuck somewhere.
- Checked against the real games with `tools/emu.py`: rollback with
  random 0-7 frame delays lands on the same game as a perfect run, with two
  players in KOF and three in Cadillacs and Dinosaurs.
- Each frame's inputs are read on the emulator's own thread, straight after
  the frame, so a busy screen (drawing, garbage collection) never leaves
  the game waiting for them.
- Native side: `android/libretrodroid` (LibretroDroid::setNetplay).
  Rooms, protocol and tests: `android/core/.../net` (`Room` in `Session.kt`).
- Battle tests: `ChaosTest` runs rooms of two and four phones through bad
  Wi-Fi (drops of a moment to 12 s, connections stalled or destroyed as
  Android does, packet loss, delay, reordering) with the app's timings, and
  checks every phone ends up back in the one room, in its seat, in step.
  `KOF_SOAK_SEEDS=1,2,3,… KOF_SOAK_SECONDS=60` runs longer soaks.
- Smoothness tests: `SmoothnessTest` plays minutes of two and three phone
  games in simulated time (screens at slightly different rates, Wi-Fi
  latency, jitter, loss and hiccups of up to a third of a second) through
  the game's own input bookkeeping and the emulator's rollback rules, and
  counts what a player would see: frames held, frames stalled, rollbacks.
  Before the fixes above: 5-7 held frames a minute on clean Wi-Fi, 50-70 on
  a router's with hiccups or with a 120 Hz phone; now 0-1 (the drift
  between the screens needs no more). With a phone that manages only 56
  frames a second, the other used to run 12-16 frames ahead, replaying
  thousands of frames and stopping at the limit; now it stays within 1-2.

## Profile

Home → **Profile** (the chip with your icon): a name, one of 16 icons and
a friend code, for online play. It's a guest profile: the first time you
open it the app signs in as a guest (it never does at start), and it
works offline from the phone's copy, saving changes once you're back
online. It also counts the games you start, per game. Rooms show your
profile's name.

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
- **Garou, The Last Blade 1 and 2 and Samurai Shodown II** come from
  `command.dat` and the FBNeo cheat files, checked the same way: every
  listed move of every fighter (137, 123, 166 and 99), supers in each
  style or with the gauge they need. Moves the app's buttons can't play
  (hold and release, after a Just Defend) are left out.

`tools/fix_rosters.py` holds every KOF change with the reason;
`tools/doubledr_roster.py`, `tools/sfiii3_roster.py` and
`tools/mvsc_roster.py`, `tools/garou_roster.py`, `tools/lastblad_roster.py`,
`tools/lastbld2_roster.py` and `tools/samsho2_roster.py` write the other lists, and `tools/movetest.py` is the tester (it needs `tools/emu.py`'s setup).

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
  CPS-1, CPS-2, CPS-3 and Seta 2 drivers.
- `tools/emu.py`, `tools/movetest.py` — a headless FBNeo front end, and the
  move tester built on it (bring your own core and ROMs).
- `docs/kof-ram.md` — the KOF RAM map fighter detection reads (the other
  games' addresses are documented in `Detect.kt`).
- `docs/adding-a-game.md` — what every new game needs, including the seat
  rule (seats follow the game's own countdown) and how to find a game's
  player status bytes.
- `docs/online-plan.md` — the plan for online play, built phase by phase
  (the profile is in; internet rooms are next), and `docs/online-setup.md`,
  the server's one-time setup.
- `server/supabase/` — the online server: the database's tables and rules,
  and the relay function (`server/test.sh` tests them).
- `.github/workflows/android.yml` — downloads the FBNeo core from the
  libretro buildbot and builds the APK.
- The games' pictures (`android/app/art.txt`, kept in step with the game
  list by a test) are fetched from libretro's thumbnail collection when the
  app is built (`fetchGameArt`, into `build/`) and packed inside the APK;
  they're never committed, and the app never downloads them.

Build locally with Android Studio, or: download the FBNeo core into
`android/app/src/main/jniLibs/<abi>/libfbneo_libretro_android.so` (see the
workflow), then `cd android && ./gradlew :app:assembleDebug`.

## License

GPL-3.0-or-later (LibretroDroid is GPL-3.0). FBNeo has its own
non-commercial license.
