# Tournaments: up to 8 players

The owner asked (2026-11-07): "For arcade challenger game like kof, we need
tournament. Upto 8 player. We want that feature. Lets plan".

## What it is

A host opens a **Tournament** room (online or nearby) for a two-player
fighting game (KOF and the other fighters). Up to 8 people join. The app
draws a bracket, puts the next two players in the seats, everyone else
watches live, and the bracket moves on by itself after each match until
there's a champion. No money and no prizes (FBNeo's licence; see CLAUDE.md).

## Built on what's there

- **Rooms**: up to 8 phones (players and watchers); internet and nearby.
- **The watchers' line** (phase 1.7): a seat goes to the next in line. A
  tournament fills that line from the bracket instead of by arrival.
- **Host migration and rejoin**: a host who drops is replaced; a player who
  drops gets their seat back for a while.
- **Chat, voice, Watching**: the six waiting their turn watch and talk.
- **Seats follow the game** (`Game.seats`): who's in and who's out, read in
  each game's RAM.

## The phases (one PR each; the rooms keep working as they do)

- **T1. The bracket.** Single elimination: 3 to 8 entrants, seeded at
  random, byes for the top seeds when it isn't 4 or 8. 8 players means 7
  matches. Plain Kotlin in `core`, tested: draw, byes, who plays next,
  the winner going on, someone leaving (their opponent goes through).
- **T2. Who won, from the game.** For each fighting game, where its RAM
  keeps rounds won and the end of a match (found and checked in the game,
  like fighter detection: tools/emu.py). KOF first (2002 Magic Plus, '97,
  '98), then the other fighters one by one. A match is best of 1 (the
  game's own rounds) or best of 3 games, as the host picks.
- **T3. The fallback: the players say.** A game without T2's detection,
  or a result the app isn't sure of: both players tap who won; if they
  disagree, the host decides. Every result shows to the room.
- **T4. Seats from the bracket.** When a match ends, the room's seats go to
  the next pair (the existing seat moves), the game restarts from the
  room's start state, and the next pair sees "You're up" with a few
  seconds to get ready. A player who doesn't show in 60 s loses that
  match (their opponent goes through).
- **T5. The tournament screen.** In the room sheet: the bracket (who beat
  whom, who's playing now, who's next), "Start tournament" for the host
  (3 to 8 in), the champion at the end, shared in chat. A small bar over
  the game: "Quarter-final 2 of 4 · Rafi vs Tanu".
  **The look** (the owner, 2026-11-07: "animation for tournament like fifa
  world cup time ... an virtual trophy", with a World Cup bracket picture):
  a dark, two-sided bracket, the halves meeting at the final in the middle,
  each player a card with their avatar; after a match the winner's card
  slides along the line into the next round; before a match a "versus"
  screen (both cards rush in, the round's name, a count to the fight); the
  draw itself animated (cards dealt into their places). The champion gets
  our own trophy, drawn by us (gold, the app's style; not FIFA's), lifted
  with confetti and a fanfare, shared in chat and kept on the profile
  (T6). Smooth at 60 fps on the owner's phone, and short (skippable).
- **T6. Kept on the server.** The bracket and results in Supabase (tiny
  rows, far inside the free plan, with a stop like the others), so a host
  who drops doesn't lose the tournament; tournament wins on the profile.
- **Later (not planned yet):** double elimination, round robin, open
  tournaments anyone can join from the Online screen, a set start time,
  friends invited from the friends list.

## Bottlenecks

- **Knowing who won**: each game keeps it somewhere else in its RAM; it has
  to be found and checked game by game (T2). Until then, the players say
  (T3).
- **Time**: a full 8-player bracket is 7 matches, about 30-45 minutes of
  KOF; byes and best-of-3 change that.
- **One match at a time**: a room plays one pair at a time (8 phones in
  one room); parallel matches would need several rooms (later).
- **Connections**: everyone's phone in the room carries the watchers'
  feeds; the room's limits stay as they are (D8 and the relay's stops).
- **Fairness**: a dropped player, a sore loser closing the app: the rules
  above (60 s, opponent goes through; host decides disputes).

## Where it stands

All six phases are built (2026-11-07 21:06; the owner: "Close 1 to 3 for now ... Make
a proper plan and implement at once"), checked as below; what's left is
the owner's phones.

- **T1** `core/tournament/Bracket.kt`, tested.
- **T2** each fighter's match-win count per side, checked in the game with
  two players each winning (docs/kof-ram.md; tools/emu.py): the five KOFs,
  Garou, Last Blade 1 and 2, Samurai Shodown II, SF III 3rd Strike, Marvel
  vs Capcom (`Game.matchWins`). A draw moves neither. Marvel vs Capcom
  also counts the computer's wins on an empty side, so a win only counts
  with both seats played by people. `WinWatch`, `MatchScore`.
- **T3** the two playing tap "I won" / "<name> won" in the room sheet;
  both the same is the result, different and the host decides (the host can
  settle any game). Every result shows to the room (news, the bracket).
- **T4** the host's phone calls each pair: the two to seats 0 and 1, the
  rest watch, the game from the room's start state; "You're up!" for the
  two; a best of 3 replays with the same seats. A player of the pair not
  there (left, dropped, away) for 60 s loses the match; one who leaves the
  room loses their next one. A new host carries on (every phone keeps the
  tournament the host shares: `Msg.TOURNAMENT`; players' word: `Msg.REPORT`).
- **T5** `Tournaments.kt`: the room sheet's Tournament part (start: one
  match or best of 3; the bracket World Cup style, two halves meeting at the
  final with the trophy under it, each winner's line drawn in gold as it
  happens, the match on now glowing; report and settle buttons; call off),
  the bar over the game, the VS intro before each match, a result flash,
  and the champion's screen: our own trophy (drawn in code, not FIFA's)
  rising, light rays, confetti. The host shares the champion in the chat.
  Sized to the screen (the owner: "Make sure the ui is reponsive, have
  proper spacing and completely optimise"): the bracket's cards take the
  width there is (76-130 dp, sideways scroll only if even the smallest
  don't fit), grow with the phone's text size, buttons wrap, the bar is at
  most half the screen, the VS names and the champion's trophy scale to the
  screen (a short landscape phone keeps the trophy clear of the name).
  Pictures at a small phone, small landscape, landscape, tablet and big
  text (UiSnapshots). Smooth: the bracket's layout is worked out once per
  bracket (and the bracket's matches once, not on every look), and the
  glow, lines and confetti move in the drawing only, never rebuilding the
  cards.
- **T6** tournaments won per game on the champion's phone, synced like the
  games played (`record_tournament_wins`, migration
  20261107010000_tournament_wins.sql: one small row per player and game, a
  count only goes up) and shown on the profile. The bracket in play lives
  on every phone of the room, not the server (a new host carries on), so
  nothing of it needs the free database.

## To settle with the owner

- Format: single elimination first (the plan), or round robin / double?
- Match length: best of 1 (the game's own rounds) or best of 3 by default?
- Online and nearby both, or online first?
- First game: KOF 2002 Magic Plus?
