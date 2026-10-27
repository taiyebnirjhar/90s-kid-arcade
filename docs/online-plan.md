# Online play: the plan

**Status: not started (parked, 2026-10-27).** Online play isn't being built
now. If it is, it follows this plan. Today's multiplayer (same Wi-Fi or
hotspot) is unchanged and stays.

## Where things are today

- Rooms on the same Wi-Fi or hotspot. The room key is the owner's local
  address and port (`RoomKey.kt`). Phones find rooms with a beacon, and
  control messages go over TCP to the owner (`Session.kt`).
- Rollback netcode (`Lockstep.kt`, LibretroDroid `stepNetplay`):
  - inputs over UDP;
  - a guessed frame is rolled back up to 12 frames;
  - `TimeSync` waits only for a real lead;
  - `DelayTuner` sets the input delay (1 to 3 frames).
- Joining mid-game, watchers (COIN or START asks for a seat), rematches,
  host handover and reconnects already work.

## Principles

1. **Offline stays as it is.** Solo play, the library and controls are
   unchanged, and the app works fully without internet.
2. **One "Online" entry.** The app picks the best connection itself; players
   never choose between Wi-Fi and internet.
3. **Smoothness first.** Voice, chat and watching never slow the game down.
4. **Real people first, bots as the fallback**, so nobody waits.
5. **Bring your own ROM.** Both players need the same ROM (checked, as
   today). Avatars never use game character art.

## How it connects (hidden from the player)

For every room or invite, in this order:

1. **Nearby (same Wi-Fi or hotspot):** zero lag, no data used.
2. **Direct over the internet**, phone to phone (UDP hole punching, with the
   address found through STUN).
3. **Relay** (Cloudflare TURN) when a direct path is blocked. Public matches
   with strangers use the relay by default, so a player's internet address
   stays private. Friends and nearby play connect directly.

Players see only ping bars. The connection report records which path was
used.

## Nearby play as a feature

- **No internet:** the Online screen shows a gold card instead of an error:
  "Nearby play: no internet needed. Play with friends on the same Wi-Fi or
  hotspot. Zero lag, no data used." Nearby rooms are listed automatically,
  with a big **Create nearby room** button.
- **With internet:** nearby players appear at the top with a "Nearby ·
  fastest" badge.
- **Home screen:** "Play together anywhere, even without internet."

## Step 1: Foundation

- **Sign-in:** guest with one tap; link Google later so the profile
  survives a phone change.
- **Profile:** name, an icon, a friend code (`TAIYEB#4821`), and games and
  wins for each game.
- **Online rooms:** create a room and share the code. Joining mid-game,
  rematches and host handover work as on Wi-Fi.
- **Watch and queue** (the existing watchers, brought online):
  - A full room makes you a watcher: "You're 2nd in line".
  - When a seat frees up, the next in line takes it.
  - You can leave the line and keep watching. Players see "3 watching".
  - Watchers receive button presses, not video, and never slow the
    players down.
- **Input delay set from the measured ping.** The current `DelayTuner`
  stayed at 1 frame at 70 ms ping, which means more corrections.
- **Room security:** a secret per room, so outsiders can't send inputs.
- **Control messages over the internet:** they move off direct TCP, through
  the server or a reliable channel on the same link. This is the largest
  piece of the step.
- **Tests:**
  - `SmoothnessTest` at internet pings;
  - the fault tests;
  - two real phones on different networks.

**Done when:** two friends on different networks play a smooth game, and the
same flow works on one Wi-Fi with no internet.

## Step 2: Friends

- Add a friend by code or after a match. Requests are accepted or declined.
- **Friends list:** online, playing (which game), or offline.
- **Invite:** "Taiyeb invites you to KOF '97", one tap to join. Join or
  watch a friend's game in progress.
- Notifications when the app is closed (Firebase Cloud Messaging).
- Block.

## Step 3: Public play and bots

- **Game lobby:** players online for each game, nearest first.
- **Live now:** games being played; watch, or queue for a seat.
- **Challenges, open rooms, and Quick match.**
- **Seat rotation**, set by the host:
  - **Winner stays:** arcade style. For fighters the app reads the winner
    from the game's memory.
  - **Everyone rotates.**
  - **Next in line:** for co-op games, when a seat frees up.
- **Report players**, and limits on how often someone can challenge.

### Bots

- **Real players first:** Quick match searches for about 15 to 20 seconds,
  widening the ping range. Only then does a bot take the seat.
- **Looks like a player:** a name, an icon and a ping.
- **Runs on the phone**, so a bot match has no lag.
- **Search continues during the bot match.** Afterwards: "A player is
  ready. Play them next?"
- **Skill matched** to the player, with human-like reaction times and
  mistakes.
- **Fighters first.** The app already knows who's fighting, which way they
  face, and their moves (`Detect.kt`, the move lists). Co-op games start
  solo and stay open for real players to drop in. A co-op bot partner comes
  later.

**Guardrails** (to keep trust if players find out):

- Bot wins don't count on public leaderboards.
- Bots never send friend requests, never use text chat or voice, and can't
  be friended. Quick-chat "GG" is fine.
- One line in About/FAQ: "When few players are online, you may be matched
  with AI players."

## Step 4: Chat and voice

- **Quick chat:** "GG", "Rematch?", "Nice combo!" as small bubbles that
  never cover the game.
- **Text chat:** with friends and in rooms, with filter, block and report.
- **Voice**, including with strangers:
  - Mute mic; push-to-talk or open mic.
  - **The mic is off when you join**; you turn it on yourself.
  - Voice volume and mute all voices, separate from game volume.
  - Mute one player. The host can mute someone for the room, or make
    watchers listen-only.
  - Speaking indicator, echo cancellation and noise suppression.
  - Voice drops its quality first on a weak connection; the game comes
    first.
  - Report voice abuse.

## Smoothness

**Simulated** (`SmoothnessTest` at internet pings): two phones, 5 minutes,
3 runs each, with 1% loss and drop-outs of up to 0.2 s:

| Ping | Freezes | Input delay | Corrections |
| --- | --- | --- | --- |
| Same Wi-Fi | none | 1 frame | a few a minute |
| ~35 ms | none | 1 frame | now and then, 1 frame |
| ~70 ms | none | 1-2 frames | a few a second, 1 frame |
| ~110 ms | none | 2-3 frames | a few a second, 1 frame |
| ~160 ms | none | 3 frames | ~7 a second, 1-2 frames |
| ~250 ms | none | 3 frames | ~8 a second, ~4 frames |

The simulated player changes buttons about 8 times a second, busier than
most real play. To be confirmed on real phones.

- **Match by ping**; nearby players first.
- **No assembly.** FBNeo is native C/C++; a frame takes about 1-3 ms of
  16.7 ms. Speed work goes only where measuring shows a need: most likely
  rollback replays of the heavy games (SF3, Penguin Brothers: about 2× a
  KOF frame) on slower phones.
- Watching, voice and chat never hold the game back.

## Servers and cost

Game inputs never pass through Supabase: they go phone to phone, or through
the relay.

| Part | Service | Free allowance (as of 2026-10) |
| --- | --- | --- |
| Sign-in, profiles, friends, stats, chat history | Supabase Auth + Postgres | 50,000 users a month, 500 MB |
| Who's online, lobbies, invites, chat delivery, connection setup | Supabase Realtime | ~200 at once, 2M messages a month |
| Matchmaking, bot fallback, relay keys, notifications | Supabase Edge Functions | 500,000 calls a month |
| Game relay, voice | Cloudflare TURN / SFU | 1,000 GB a month, then $0.05/GB |
| Notifications | Firebase Cloud Messaging | free |
| ROM library | Cloudflare R2 (as now) | unchanged |

- **Supabase's free plan pauses a project after a week with no activity.**
  The Pro plan removes the pause; check the price on supabase.com/pricing
  when it's needed.
- **Security:**
  - Row-level security on every table.
  - Only the public (anon) key in the app; the service key stays server
    side.
  - The TURN key stays on the server, which hands out short-lived
    credentials.
- **Data use:** about 40 MB an hour for a two-player game; voice adds
  roughly 10-20 MB an hour.
- **Owner's setup**, once, with step-by-step instructions:
  - a Supabase project with Google sign-in, plus the SQL to run;
  - a Cloudflare TURN key;
  - a Firebase project for notifications.

## Settled decisions

- Guest sign-in first, Google later.
- Public matches use the relay by default; friends and nearby play connect
  directly.
- Voice is allowed everywhere, with the mic off when you join.
- Published on GitHub for now, built so it could meet the Play Store's
  safety rules.

## Order

Steps 1 → 2 → 3 → 4, each shipped and tested on its own (one PR or a few).

- Step 1 is the biggest: multiplayer moves from Wi-Fi-only to hybrid.
- In Step 3, the fighter bot is the largest piece.

## Sources

- [Supabase pricing](https://supabase.com/pricing)
- [Cloudflare Realtime TURN/SFU pricing](https://developers.cloudflare.com/realtime/sfu/platform/pricing/)
- [GGPO](https://en.wikipedia.org/wiki/GGPO),
  [Fightcade](https://en.wikipedia.org/wiki/Fightcade): the rollback
  approach this follows.
