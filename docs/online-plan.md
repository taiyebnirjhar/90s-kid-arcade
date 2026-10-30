# Online play: the plan

**Status: Step 1 built (2026-10-30), waiting on the two-phone checklist ([docs/online-testing.md](online-testing.md)).** The owner asked to build online
play. Step 1 is broken down phase by phase below
([Step 1](#step-1-foundation-work-breakdown)), each phase one PR. Steps 2-4
follow it. Today's multiplayer (same Wi-Fi or hotspot) is unchanged and
stays.

**Making money** (customization, a subscription) comes only after licensing:
FBNeo's license forbids any profit or donations, and the games are SNK's and
Capcom's. See [Making money](#making-money-later-and-only-after-licensing)
and [Licensing the games](#licensing-the-games).

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

## Step 1: Foundation (work breakdown)

**Status: built, waiting on real-phone tests.** Phase 1.0 done (2026-10-28); phases 1.1 and 1.2 merged, the server deployed (2026-10-29); phases 1.3 to 1.9 built (2026-10-30), each its own PR; the two-phone checklist (docs/online-testing.md) is next. The owner asked to build
Step 1. Each phase below is one PR (or a few), merged before the next
starts. Every phase keeps today's same-Wi-Fi play working and its tests
green.

**Done when:** two friends on different networks (Wi-Fi and 4G, or two 4G
phones) play a smooth game from a room code, and the same flow still works on
one Wi-Fi with no internet.

### Notes from the code (why Step 1 is shaped this way)

- **Today's rooms are a star plus a mesh** (`Session.kt`).
  - Control messages go over TCP between each phone and the owner (`Line`):
    joining (`HELLO`/`WELCOME`), seatings, the game state, pause, seat
    requests, watchers' feeds.
  - Inputs go over UDP, from every phone to every other.
- **The room key is the owner's local address and port** (`RoomKey.kt`),
  and phones find rooms with a broadcast beacon. Both only work on one
  network.
- **When the owner leaves, the new owner opens a TCP connection to every
  member** (`TAKEOVER`). Over the internet a phone can't accept incoming
  connections (home routers, and mobile networks' shared addresses), so this
  can't work there.
- **What carries over unchanged:** the rollback and time sync (`Lockstep`,
  `TimeSync`, `DelayTuner`), the room logic (seats, holds, watchers,
  rematch, desync resync), and the ROM fingerprint check. They need a
  different way to carry their messages, not new logic.
- **`core` is plain Kotlin on the JVM**, tested without Android. The room
  logic is tested over real sockets on one machine (`Phones.kt`,
  `RoomTest`, `ChaosTest`, with network faults injected), and
  `SmoothnessTest` simulates timing. Internet transport must keep the room
  logic testable that way.

### Architecture decisions (proposed; the owner confirms in phase 1.0)

- **D1. Internet transport: WebRTC.** One peer connection per pair of
  phones, each with two data channels:
  - a reliable, ordered one for control messages (today's TCP `Line`);
  - an unreliable, unordered one for inputs (today's UDP).

  Why:
  - **The connection order is built in.** WebRTC's ICE tries the local
    network first, then direct over the internet (STUN), then the relay
    (TURN). That's exactly "nearby, then direct, then relay".
  - **Cloudflare's TURN is made for it.**
  - **Everything is encrypted (DTLS).**
  - **Voice (Step 4) runs on the same connections.**

  Library: `io.getstream:stream-webrtc-android`, a maintained prebuilt of
  Google's WebRTC; Google stopped publishing one.
  - *To confirm in 1.0:* how much it adds to the APK (arm64-v8a and
    armeabi-v7a only, like the FBNeo core).
  - *The alternative, if it's too big:* our own STUN, hole punching, a TURN
    client and a reliable channel over UDP. Pure Kotlin and smaller, but much
    more to build and test, and voice would still need WebRTC later.
- **D2. Offline nearby play stays on today's transport.** With no internet
  there's no server to set up WebRTC through. So the beacon, TCP and UDP
  rooms stay exactly as they are and are what the Nearby card uses. When
  online, two phones on one Wi-Fi still connect locally: ICE picks the local
  path.
- **D3. The room logic gets a transport seam.** `Room` talks to two small
  interfaces instead of sockets:
  - `Link`: a reliable message channel to one member;
  - `Datagrams`: inputs to and from members.

  Today's sockets become the LAN implementation and WebRTC the internet one.
  The tests run the room logic over a fake transport that can add internet
  latency, jitter, loss and drop-outs.
- **D4. Host takeover over the internet reuses the mesh.** Every pair
  already has a peer connection for inputs, so a new owner already has a
  reliable channel to each member: no new connections needed.
- **D5. Server: Supabase.**
  - Auth: guest sign-in, Google linked later, keeping the same user id.
  - Postgres: profiles, rooms.
  - Realtime: signaling, meaning the WebRTC offers, answers and candidates
    for each room, on a private channel only that room's members can join.
  - Edge Functions: room codes, and minting short-lived TURN credentials.

  Android client: `supabase-kt`.
  - *To confirm in 1.0:* Realtime's message size and rate limits. Signaling
    messages are a few KB; game states never go through Supabase.
- **D6. Relay: Cloudflare Realtime TURN.**
  - The TURN key stays in a Supabase secret.
  - An Edge Function calls Cloudflare's credentials API and hands each phone
    short-lived `iceServers`, valid 1 hour and renewed while the room
    lasts (D8).
  - *To confirm in 1.0:* the exact endpoint. The docs show
    `.../credentials/generate-ice-servers`; older pages show `.../generate`.
- **D7. Room codes come from the server.** Six characters from the same
  alphabet as today's keys (no 0/O, 1/I), so they're easy to read out.
  Codes expire when the room closes.

- **D8. Never past a free allowance: a hard stop, owner's rule
  (2026-10-29).** A feature that would go past its service's free allowance
  for the month switches itself off until the month resets, with a note in
  the app. Nothing is ever billed.
  - **The relay (1,000 GB a month).** Cloudflare doesn't stop at the free
    amount; it bills $0.05/GB after it. So our server stops it:
    - `turn-credentials` reads this month's relay use from Cloudflare's
      analytics (GraphQL `callsTurnUsageAdaptiveGroups`, egress plus
      ingress bytes), at most every 5 minutes, and keeps it in the database.
    - **At 900 GB** (90%, a margin for the analytics' delay) it gives out no
      new credentials. Credentials last 1 hour, and the app asks for fresh
      ones each hour, so use stops within the hour.
    - **At 950 GB** it revokes every credential still live
      (`.../credentials/{username}/revoke`), and relayed games end.
    - Every credential carries the room as its custom identifier, so one
      room or user using too much shows up and can be cut off on its own.
    - The app: "Online play through the relay is paused until the 1st.
      Nearby play and direct connections still work." Games that connect
      directly don't use the relay and go on.
    - For scale: a two-player game uses about 40 MB an hour, so 900 GB is
      over 20,000 relayed hours a month.
  - **Supabase:** the free plan can't be billed; it stops or slows at its
    limits. The app still checks: when sign-in, room creation or
    signaling fails because a limit was reached, it says online play is
    paused, instead of failing quietly. Room creation also refuses when
    Realtime is near its limit of people at once.
  - **Every new paid service** gets the same kind of stop before it ships.

### Phase 1.0: Decisions and owner setup

**Results (2026-10-28):**

- **D1 confirmed by the owner: WebRTC.** D2-D7 go ahead as written.
- **APK size (1.0.2):** `io.getstream:stream-webrtc-android` 1.3.10 (Sept
  2025):
  - native libraries: 11.5 MB (arm64-v8a) and 6.6 MB (armeabi-v7a) raw;
    5.4 + 3.9 MB compressed;
  - Java code: 0.5 MB.

  The app packs native libraries compressed (`useLegacyPackaging = true`),
  so the **APK grows by about 10 MB, from 54 to about 64 MB**. Taking only
  the two ABIs we already ship drops its x86 libraries.
- **Cloudflare TURN (1.0.5), from Cloudflare's docs:**
  - the server calls
    `POST https://rtc.live.cloudflare.com/v1/turn/keys/{TURN_KEY_ID}/credentials/generate-ice-servers`
    with `Authorization: Bearer {TURN_KEY_API_TOKEN}` and `{"ttl": seconds}`;
  - it answers 201 with `iceServers`: Cloudflare's STUN plus TURN over
    UDP, TCP and TLS on several ports, ready for WebRTC;
  - credentials can be revoked;
  - port 53 is in the list; with trickle ICE (which we use) it costs
    nothing.
- **Supabase Realtime limits (1.0.5), free plan:**
  - 200 connections at once;
  - **100 messages a second for the whole project**;
  - 100 channel joins a second;
  - 256 KB per broadcast.

  Setting up a pair of phones takes about 10-20 small messages (offer,
  answer, candidates), so this is fine. But **the app connects to Realtime
  only on the Online screen and while setting up or healing a room**, never
  for a whole game, and no game data goes through it.
- **Owner setup:** the steps are in [online-setup.md](online-setup.md).
  Waiting on 1.0.3 and 1.0.4.

| # | Work | Who | Output |
|---|---|---|---|
| 1.0.1 | Confirm D1–D7 (especially WebRTC vs our own transport) | Owner | Decisions noted here |
| 1.0.2 | Measure the APK growth from `stream-webrtc-android` (two ABIs) in a throwaway build | Claude | Size in this doc |
| 1.0.3 | Create the Supabase project; turn on anonymous sign-in; note the project URL and anon key | Owner (guide from Claude) | `docs/online-setup.md` |
| 1.0.4 | Create a Cloudflare TURN key; store its id and token as Supabase secrets | Owner (guide) | Secrets set, never in the repo |
| 1.0.5 | Confirm Realtime limits and the TURN endpoint against current docs | Claude | Notes here |

**Exit:** decisions confirmed; Supabase and TURN ready.

### Phase 1.1: Transport seam (no visible change)

| # | Work | Output |
|---|---|---|
| 1.1.1 | `Link` and `Datagrams` interfaces in `core/net`; what `Room` needs from each | Interfaces |
| 1.1.2 | Move today's TCP `Line` and UDP code behind them (the LAN transport) | `LanTransport` |
| 1.1.3 | `Room` uses only the interfaces; addresses (`Member.host`, ports) become opaque transport addresses | Refactored `Session.kt` |
| 1.1.4 | A fake transport for tests: latency, jitter, loss, drop-outs, per pair of phones | `FakeTransport` |
| 1.1.5 | Run `RoomTest`, `ChaosTest` and the soak tests over both the real sockets and the fake transport | Tests |

**Tests:**
- every existing test passes unchanged;
- the same rooms pass over the fake transport.

**Risk:** this is the riskiest refactor of the whole step, because `Session.kt` (2,135 lines) mixes logic and sockets. It ships alone so any regression shows up in Wi-Fi play before anything else changes.

**Results (2026-10-29):**

- **The seam (`core/net/Transport.kt`):**
  - `Link`: reliable, ordered messages to one phone; several sent together
    arrive back to back (a seating and its game state).
  - `Datagrams`: quick, unreliable packets for inputs.
  - `Transport`: listen for links, connect to an `Address`, make datagram
    endpoints, and give the room's key.
  - `Address` (host and port) is all the room keeps of where a phone is. It
    only stores and compares it, so the wire messages (`Member.host`, ports,
    `Moved`) are unchanged.
- **`LanTransport`:** today's TCP and UDP sockets, room ports, the Wi-Fi
  traffic class and the room key from the LAN address, moved out of
  `Session.kt` unchanged. Beacons stay the LAN way of finding rooms; a room
  sends none when `beaconPort` is -1.
- **`Room` uses only the seam.** It gets a `transport` when it's made
  (`Room.host(..., transport)`, `Room.join(transport, address, ...)`); the
  old `Room.join(InetAddress, port, ...)` and `probe(InetAddress, ...)` are
  kept for the app and use `LanTransport`. The tests' fault switches
  (`Faults`: cuts, stalls, resets, loss, delay) stay in the room, so they
  work the same over any transport.
- **The app's only change:** `Redirected.at` is an `Address` now.
- **`FakeNet` (tests):** a simulated network in memory. Each way between two
  phones has its own latency, jitter (datagrams overtake each other), datagram
  loss and outages (links stall, datagrams are lost, nobody new gets through).
- **Tests:**
  - every existing test passes unchanged over real sockets;
  - `RoomOverInternetTest` runs all 25 room tests, and
    `ChaosOverInternetTest` the 4 soaks, over `FakeNet` with 20-80 ms of
    latency, 20 ms of jitter and 2% loss on each way;
  - a new one there: a 2.5 s outage between host and guest passes with
    nobody dropped and no new seating;
  - after the fix below, the soaks pass with seeds 2 to 7 over both networks;
  - CI takes about 3.5 minutes longer.
- **Found and fixed, a bug that was already there on Wi-Fi:** when everyone
  lost the room at once, a phone finding it again could reach another of the
  room's phones that was itself still rejoining: listening, but not
  answering yet. It then waited 30 s for an answer, long enough for its
  held seat to run out (it came back watching). Rejoining now waits 5 s for
  an answer and tries the next place. The internet soak with seed 4 found
  it.
- **Learned, for phase 1.4:** a new owner taking the room over still
  *connects* to each member (`TAKEOVER`). That works over the LAN and
  `FakeNet`, but a phone on the internet can't be connected to. The WebRTC
  transport's `connect` must reuse the peer connection the two phones
  already have (D4), not open a new one. Finding a room that was lost
  (`rejoin`, the owner's scouting) also connects to remembered addresses;
  online, those become the room code looked up on the server.

### Phase 1.2: Backend (Supabase)

| # | Work | Output |
|---|---|---|
| 1.2.1 | Schema: `profiles` (user id, name, icon, friend code, created), `rooms` (code, owner user, game set, ROM fingerprint, max players, status, created, last seen) | `server/supabase/migrations/*.sql` |
| 1.2.2 | Row-level security: everyone reads profiles' public fields; users write only their own; rooms readable by code, written by their owner | Policies |
| 1.2.3 | Edge Function `create-room`: a unique code; at most a few open rooms per user | Function |
| 1.2.4 | Edge Function `turn-credentials`: short-lived TURN servers for a signed-in user | Function |
| 1.2.4a | The relay's hard stop (D8): this month's use from Cloudflare's analytics, no new credentials at 900 GB, revoke live ones at 950 GB, 1-hour credentials tagged with the room | Function + `usage` table |
| 1.2.5 | Realtime authorization: only a room's members join its signaling channel | Policy |
| 1.2.6 | Clean-up: rooms not seen for 10 minutes close | Scheduled job |
| 1.2.7 | Tests: SQL policy tests (a user can't edit another's profile or room) and function tests, including the relay's stop at 900 GB and revoke at 950 GB | Tests |

**Exit:** a signed-in test user can create a room, read it by code, get TURN credentials, and is refused what isn't theirs; with the month's use set past 900 GB, no credentials are given.

**Results (2026-10-29), in `server/`:**

- **Database** (`supabase/migrations/20261029000000_online.sql`):
  - `profiles`: name (1-24 characters), icon, a friend code (8 characters,
    for Step 2). Anyone signed in reads them; you write only your own, and
    never your friend code.
  - `rooms` and `room_members`. You see only the rooms you're in. A room is
    found by its code through `find_room` (any case, dashes and spaces
    ignored), which shows only that one.
  - Room codes: 6 characters from the room keys' alphabet (no 0/O, 1/I),
    made by the server, unique.
  - Made and changed only through functions (`create_room`, `join_room`,
    `touch_room`, `leave_room`): at most 3 open rooms each, 8 phones a room;
    the last one out closes it.
  - **Decided while building:** room creation is a database function, not
    an Edge Function as planned (1.2.3). Same rules, one less thing to
    deploy, and testable in plain Postgres.
- **Clean-up** (`20261029000100_cleanup_job.sql`): every minute (pg_cron),
  rooms nobody touched for 10 minutes close; closed codes are kept a day
  (so an old code says the room ended), then freed.
- **Signaling:** only a room's members read or write its channel
  (`room:CODE`, Realtime's private channels).
- **Relay credentials and the hard stop (D8):** the Edge Function
  `turn-credentials`, with its rules in `relay.ts`.
  - A member of an open room gets relay servers for an hour, tagged with
    the room.
  - It reads this month's use from Cloudflare's analytics (egress plus
    ingress, one sum, as Cloudflare advises for billing) at most every 5
    minutes, and keeps the highest reading.
  - At 900 GB it gives out none, with "paused until the 1st"; at 950 GB
    it also revokes every live credential.
  - **Decided while building:** if Cloudflare can't be asked and the last
    reading is over an hour old, it gives out none, rather than go on
    without knowing.
  - The relay's tables are the server's alone (no access for players).
- **Tests** (`server/test.sh`, and in CI as "Server"):
  - the database's rules, as each kind of user, on a stub of Supabase in
    Postgres 16: your profile only; strangers see no rooms but find one by
    code; members only on the channel; 3 rooms, 8 phones; closing; the
    relay's tables closed;
  - the relay's rules in Node: credentials, strangers, 900 GB, 950 GB, just
    under, 5-minute checks, a lower reading, Cloudflare down, a new month;
  - the function's Deno code type-checked and linted.
- **Deploy:** the "Server deploy" workflow, once the owner adds three repo
  secrets; or by hand (docs/online-setup.md).
- **Not yet, and where:** Realtime's limit on people at once (D8) can't be
  read from the database; room creation checks it in phase 1.5, when
  signaling starts.
- **Found later (2026-10-30), by a live check against the project:** the
  project opens no table to any role by itself, the server's own (service
  role) included, so `turn-credentials` couldn't read rooms or the relay's
  tables and told every member "You're not in that room". Fixed by
  `20261030000100_server_grants.sql`; the tests' stub of Supabase now grants
  nothing by itself either, so the tests catch it, and the function reports
  a failed read as an error instead of "not a member".

### Phase 1.3: Profile and sign-in (app)

| # | Work | Output |
|---|---|---|
| 1.3.1 | Add `supabase-kt` (Auth, Postgrest, Realtime, Functions); URL and anon key in the build config | Dependency |
| 1.3.2 | Guest sign-in on first opening the Online screen, never at app start; the session is kept on the phone | Sign-in |
| 1.3.3 | Profile screen: name, an icon from our own set (palette colors, no game art), friend code; edit name and icon | UI |
| 1.3.4 | Stats: games played per game, counted on the phone and synced. *Wins need each game's winner read from memory, which Step 3 builds; they're shown from then on* | Stats |
| 1.3.5 | Offline: the profile shows from the phone's copy; nothing online blocks the app | Behavior |
| 1.3.6 | Tests: sign-in once, reuse after restart, offline start; UI snapshots | Tests |

**Results (2026-10-30):**

- **Decided while building: no `supabase-kt` (1.3.1).** The app makes a
  handful of calls (guest sign-in, a token refresh, two database functions,
  one read), so `core/online` makes them over plain HTTP with the JDK's own
  client and the JSON library `core` already has. No Ktor, nothing added to
  the APK, and it's tested on the JVM against a fake server. Realtime
  (phase 1.4) needs a WebSocket; that choice is made there.
- **Sign-in** (`Supabase.kt`): a guest (Supabase's anonymous sign-in) the
  first time the player opens their profile, never at app start. Checked
  live against the project: guest sign-in is on, and a guest reads
  profiles.
  - The session is kept on the phone and refreshed when under a minute is
    left. A token the server stops taking is refreshed once and retried.
  - A guest the server no longer knows (refresh token refused) is lost for
    good: a new guest is made, and the phone's profile and counts are saved
    for it again. Only the friend code changes.
  - Plain words for every failure (`explainOnline`): no internet; **online
    paused** when Supabase answers 402, 429, 503 or 540 (D8: a limit
    reached; nothing is billed); guest sign-in switched off.
- **Profile** (`Account.kt`, `ProfilePage.kt`): name (1-24 characters, one
  line), one of 16 icons of our own (8 drawn shapes, two styles, the
  palette's colours), the friend code (tap to copy). Saved through a new
  database function, `save_profile`: Android's HTTP client can't send
  PATCH, and one call that makes or changes the profile is simpler anyway.
- **Games played** (1.3.4): counted on the phone when a game starts, sent
  through `record_plays`, which keeps the highest count it was sent (a
  count never goes down). Sent quietly when home opens, only on a phone
  that signed in before.
- **Offline** (1.3.5): the profile page shows the phone's copy at once;
  edits wait on the phone and go up with the next sync. Never online yet:
  the player can still pick a name and icon.
- **Rooms** show the profile's name instead of the phone's model.
- **Tests:** `AccountTest` (15, over real HTTP against a fake server): no
  sign-in until asked, sign in once, the same guest after a restart,
  refresh, a dropped token, a lost guest, a new phone, edits and counts
  waiting offline, never online, a server at its limit, guest sign-in
  off, a refused save kept. The server's rules (`profile.test.sql`): your
  own profile through `save_profile`, the friend code kept, counts only up,
  bad names, icons, game ids and too many games refused, nothing for the
  signed out. Screenshots of every profile state.

### Phase 1.4: Internet transport (WebRTC)

| # | Work | Output |
|---|---|---|
| 1.4.1 | Add `stream-webrtc-android`; one `PeerConnectionFactory`; ICE servers from `turn-credentials` (plus STUN) | Setup |
| 1.4.2 | Signaling over the room's Realtime channel: offer, answer, candidates, addressed to a member | `Signaling` |
| 1.4.3 | Per pair: a reliable, ordered channel (control) and an unreliable, unordered one (inputs) as `Link` and `Datagrams` | `WebRtcTransport` |
| 1.4.4 | Path and quality: which path ICE chose (local, direct, relay), round-trip time, loss; into the connection report | Diagnostics |
| 1.4.5 | Recovery: ICE restart on a network change (Wi-Fi to 4G); the room's existing hold and rejoin logic covers the gap | Behavior |
| 1.4.6 | Tests: transport contract tests shared with the LAN and fake transports; on-phone checks with two phones (1.9) | Tests |

**Results (2026-10-30):**

- **`WebRtcTransport`** (core): the room's transport over WebRTC. A phone's
  address is its player id plus a port of the transport's own, so the room
  logic runs over it unchanged.
  - One peer connection per pair, with two data channels set up in advance
    (negotiated ids): reliable and ordered for links, unordered without
    resends for inputs. Several links share a connection; messages are cut
    into 16 KB pieces (a game state is hundreds of KB), and a sender waits
    while over 1 MB is queued.
  - **Who offers:** always the phone with the lower id; the other asks with
    a hello. Two phones never offer at once.
  - **Signals get lost while a phone joins:** offers are sent again every
    1.5 s until answered (30 s), a repeated offer gets the same answer, and
    candidates that arrive before their offer wait for it.
  - **A phone that starts over** (app restarted, crashed, goodbye lost) is
    told apart by its run's id: the other side makes a fresh connection
    instead of trying to continue the old one. Connections are numbered from
    the clock, so a restarted offerer's are always newer.
  - **Recovery (1.4.5):** disconnected for 2 s, or failed: an ICE restart
    (the same connection and links, a new way); `networkChanged()` asks for
    one at once (Wi-Fi to 4G). Links wait through it, as TCP does.
- **`Rtc` adapter:** what the transport needs of a WebRTC library, so its
  logic is plain Kotlin. Three behind it: Google's WebRTC on Android
  (`AndroidRtc`, `stream-webrtc-android`), the same library's desktop build
  (`webrtc-java`, tests only), and a simulated one over the fake internet.
- **Signaling (1.4.2):** `RealtimeSignaling` speaks Supabase Realtime's
  protocol (Phoenix channels over OkHttp's WebSocket) on the room's private
  channel: join with the guest's token, heartbeat, a fresh token each hour,
  reconnect with backoff; signals sent while it's down wait (15 s at most).
  A stranger's join is refused by the database's rules.
- **Relay (D6, D8):** `RelayServers` asks `turn-credentials` for an hour's
  TURN servers and asks again 5 minutes before they run out. When the relay
  is paused (900 GB) or can't be asked, connections go on with public STUN
  alone, and its note says why.
- **Path (1.4.4):** `paths()` says, per phone, which way ICE chose (nearby,
  direct, relay) and the round trip, from WebRTC's stats; logged when a
  connection opens.
- **Tests:**
  - `WebRtcTransportTest` (14, simulated WebRTC): order, both directions,
    either phone first, a 700 KB state, close, many links, refused, nobody
    there, datagrams, a short outage, a network change, a restart, a crash
    (either side), leaving.
  - **Every room test and chaos soak runs again over it**
    (`RoomOverWebRtcTest`, `ChaosOverWebRtcTest`), on the simulated
    internet: join, leave, takeover, drop and rejoin, three players.
  - `RealWebRtcTest`: two transports over real WebRTC on this machine
    (ICE, DTLS, SCTP): a 500 KB state, inputs, close.
  - `RealtimeSignalingTest` (4, a fake of Realtime's protocol over a real
    WebSocket): signals reach only the phone they're for, a stranger's join
    is refused with the reason, signals sent before joining wait and go, a
    socket the server closes comes back and joins again.
  - `LiveServerTest` (only with `KOF_LIVE=1`): two guests, a room, its
    Realtime channel, a stranger refused, relay servers. Run against the
    project on 2026-10-30: all as expected, and it found #44's missing
    grants (fixed in #45).

### Phase 1.5: Online rooms

| # | Work | Output |
|---|---|---|
| 1.5.1 | Host: create the room (code), join its signaling channel, wait for members | Flow |
| 1.5.2 | Join by code: look the room up, check the game and ROM fingerprint before connecting ("You need KOF '97 (kof97) to join") | Flow |
| 1.5.3 | Everyone connects to everyone (mesh); the owner runs the room as today | Behavior |
| 1.5.4 | Host takeover and rejoin over the mesh (D4); the room record follows the owner | Behavior |
| 1.5.5 | Room security: only members the owner admitted are in the channel; WebRTC encrypts | Security |
| 1.5.6 | Share the code: copy, the Android share sheet, and a QR code | UI |
| 1.5.7 | Tests: the room tests over the fake transport at internet conditions (join, leave, takeover, drop and rejoin, three players) | Tests |

**Results (2026-10-30):**

- **`OnlineRooms`** (core): a room made on the server (`create_room`, its
  code), then the same room logic as on Wi-Fi over `WebRtcTransport`.
  - **Host:** sign in, make the room, run it; the room's key is its code.
    While open, the server's record is touched every minute.
  - **Join by code (1.5.2):** `find_room` first (its game, ROM and BIOS
    fingerprints, who made it). The app checks this phone has the game
    ("You need KOF '97 (kof97) to join Rafi's room", with the library) and
    the same version before connecting; the room checks again on joining.
  - **Who to reach (1.5.3, 1.5.4):** the one who made it, then the room's
    other phones (members see each other). Every phone takes links on the
    same port, so a room taken over sends a newcomer to its new host, and a
    latecomer still gets in after the maker left.
  - **Leaving:** the room, the connections (what's still being sent goes
    first, so the others hear the goodbye before the connection ends), and
    `leave_room` (the last one out closes it).
  - **Security (1.5.5):** only the room's members join its signaling
    channel (the database's rules); WebRTC encrypts everything.
- **App:** Multiplayer gets **Play over the internet**: a code field with
  Join, and **Host online** (the same game picker). The room sheet shows
  the code with a **QR code**, Copy and Share (1.5.6). The shared text and
  the QR code carry a link (`join.html?code=…`) that the app opens straight
  into joining; without the app, the website's page shows the code and
  where to get the app ("Open in the app" uses `kidarcade://join`).
- **On the game screen:** an internet room isn't tied to the Wi-Fi (no
  LanLink); when the phone's network changes (Wi-Fi to 4G) it asks for an
  ICE restart to every phone at once, and tries to rejoin if the room was
  lost. Closing the game closes the internet room.
- **Tests:** `OnlineRoomsTest` (5, a fake of the server's rules and the
  simulated internet): made and joined by code, no such code, a different
  ROM refused with the reason and not left in the room, the last one out
  closes it, a latecomer after the maker left. Screenshots of the
  Multiplayer screen and the internet room's sheet.
- **Not yet:** the one Online screen, ping bars and nearby-first (1.8);
  the queue for seats (1.7); delay tuned for internet pings (1.6). Phones
  on two networks are tested in 1.9.

### Phase 1.6: Smoothness on the internet

| # | Work | Output |
|---|---|---|
| 1.6.1 | Starting input delay from measured round-trip time (about one frame per 33 ms of round trip, 1–4 frames), then `DelayTuner` adapts | Delay |
| 1.6.2 | `DelayTuner` tuned for internet ping (it stayed at 1 frame at 70 ms) | Tuner |
| 1.6.3 | `SmoothnessTest` internet cases become real tests with limits: no freezes up to 250 ms; corrections stay small up to 110 ms | Tests |
| 1.6.4 | Rollback cost on slow phones: measure replays of the heavy games (SF3, Penguin Brothers) and cap the rollback window by phone speed if needed | Measured |

**Results (2026-10-30):**

- **Starting delay (1.6.1):** an internet room's first game with partners
  starts at the input delay for the ping to them (`startingDelay`: about a
  frame per 33 ms of round trip, 1 to 4), measured by WebRTC's stats every
  5 seconds. `DelayTuner` adapts it from there.
- **Tuner (1.6.2):** up to 4 frames over the internet (3 on Wi-Fi). Its rule
  is unchanged: it kept the delay at 1 at 70 ms because it starts there and
  raises only on a lead of 1.5 frames on average; starting at the ping's
  delay fixes that.
- **Double speed at delay 4 (found on phones, fixed after #49):** the
  emulator ran an extra frame whenever our sampled input was more than 4
  frames ahead of its game, and sampling keeps it delay + 1 ahead. Wi-Fi
  never reaches delay 4, so only internet games were hit, and there every
  frame ran twice. The gap is now `NET_CATCH_UP_GAP` (8) in libretrodroid.h,
  and `LockstepTest` checks it stays above `MAX_ONLINE_DELAY` + 1.
- **Simulated (1.6.3), two phones, 2 minutes, 3 runs each, 1% loss, a
  hiccup of up to 0.2 s every ~8 s; rollbacks a minute per phone:**

  | Ping | Starting at 1 (before) | At the ping's delay (now) | Freezes |
  | --- | --- | --- | --- |
  | 35 ms | 5-57 | the same (delay 1) | none |
  | 70 ms | 83-352 | 4-81 | none |
  | 110 ms | 73-452 | 31-92 | none |
  | 160 ms | 466-483 | 146-326 | none |
  | 250 ms | 474-483 | 474-483 (3.3 frames each, was 4.3) | none |

  Now tests with limits (`SmoothnessTest`): no freezes up to 250 ms; up to
  110 ms, at most 120 corrections a minute of about a frame each; fewer
  corrections than starting at 1 at 70-160 ms.
- **Rollback cost on slow phones (1.6.4):** not measurable without the
  phones; measured in 1.9 (the heavy games, SF3 and Penguin Brothers, on the
  owner's phones), with the rollback window capped by phone speed if needed.

### Phase 1.7: Watch and queue

| # | Work | Output |
|---|---|---|
| 1.7.1 | The owner keeps an ordered line of watchers waiting for a seat | Queue |
| 1.7.2 | A freed seat goes to the first in line (today: whoever presses COIN first) | Behavior |
| 1.7.3 | UI: "You're 2nd in line", "Leave line" (keep watching), and "3 watching" for players | UI |
| 1.7.4 | A watcher limit per room, so watchers never slow the players | Limit |
| 1.7.5 | Tests: line order through joins, leaves, takeovers | Tests |

**Results (2026-10-30), on Wi-Fi and internet rooms alike:**

- **The line (1.7.1, 1.7.2):** a watcher's COIN or START with no seat to
  take puts it in line; the next seat that opens goes to the first in line,
  not to whoever presses next. "Opens" is as before: a free seat, or one
  whose player the game itself shows out (game over once its countdown ran
  out): the room's owner looks every second, and the game's own countdown
  still decides. Each phone's place travels with the room's members
  (`Member.line`), so everyone sees it, and an owner that takes the room
  over keeps the same line. Protocol 12.
- **UI (1.7.3):** the room pill says "2ND IN LINE"; the room sheet says
  "You're 2nd in line" with **Leave line** (still watching), and lists the
  watchers in line order ("1. Tanu"). Players see "3 watching" (the eye and
  count, as before). News: "All seats are in use: you're 2nd in line."
- **Watcher limit (1.7.4):** the room's limit of 8 phones stands (players
  and watchers). Each watcher is fed from its own thread, so a slow one
  only falls behind itself and never holds the players up.
- **Tests (1.7.5):** `watchersWaitInLineAndSeatsGoInOrder` (three in line,
  one leaves the line, a player stands, a game ends: the seats go in order)
  and `theLineSurvivesATakeover`, over sockets, the simulated internet and
  WebRTC. A screenshot of the room sheet in line.

### Phase 1.8: One Online screen

| # | Work | Output |
|---|---|---|
| 1.8.1 | Home: one **Online** entry (replacing the separate Wi-Fi flow) | UI |
| 1.8.2 | With internet: create room, join by code, nearby rooms on top ("Nearby · fastest") | UI |
| 1.8.3 | No internet: the gold **Nearby play** card ("no internet needed, zero lag, no data used"), today's nearby rooms, **Create nearby room** | UI |
| 1.8.4 | Ping bars before joining, and in the room | UI |
| 1.8.5 | Plain-language errors (code not found, room full, different ROM, no connection) | Copy |
| 1.8.6 | Paparazzi snapshots of every screen, light and offline states | Tests |

**Results (2026-10-30):**

- **One Online entry (1.8.1):** home's gold chip is **Online** (it was
  Multiplayer); the share texts and the room sheet say Online too.
- **With internet (1.8.2):** **Create a room** (pinned at the bottom) makes
  an online room; nearby rooms come first, as "Nearby · fastest"; **Join
  with a code** takes an online room's code or a nearby room's key (an
  online code is tried first, then the key, so a 6-character key still
  works); **Create nearby room** stays, for no data used.
- **No internet (1.8.3):** the gold **Nearby play** card ("No internet
  needed … Zero lag, no data used"), nearby rooms, **Create nearby room**,
  and the field takes nearby keys. The screen follows the network as it
  changes.
- **Ping bars (1.8.4):** nearby rooms show full green bars and "Nearby";
  in an internet room the pill shows bars for the round trip to the others
  (green to 120 ms, gold to 200, red beyond), refreshed every 5 seconds.
- **Plain words (1.8.5):** no such code; a room full ("The room is full: 8
  phones at most") and too many rooms open now say so, in the database's
  own words: they came back as HTTP 503 and read as "online is paused".
  Only a refusal with no SQL state (a real limit or outage) means paused
  now. Plus: a different ROM, no internet, and the game or BIOS missing
  (with the library).
- **Tests (1.8.6):** screenshots of the Online screen with internet, without,
  joining by code, and with nearby rooms; `AccountTest`'s full-room case.

### Phase 1.9: Hardening and real-phone tests

| # | Work | Output |
|---|---|---|
| 1.9.1 | A two-phone test checklist the owner runs: Wi-Fi + 4G, 4G + 4G, same Wi-Fi online, hotspot with no internet, network switch mid-game, owner leaves mid-game | `docs/online-testing.md` |
| 1.9.2 | Connection reports from those runs, read and fixed | Fixes |
| 1.9.3 | Data use and battery over a 30-minute game | Numbers here |
| 1.9.4 | README and site: how online play works | Docs |

**Exit:** the Step 1 "Done when" above, on the owner's phones.

**Results (2026-10-30), what can be done without the phones:**

- **The checklist (1.9.1):** [docs/online-testing.md](online-testing.md):
  Wi-Fi + 4G, 4G + 4G, one Wi-Fi online, a hotspot with no internet, a
  network switch mid-game, the maker leaving mid-game, the share link, and
  three phones with the line; plus data, battery and the heavy games.
- **Connection reports (1.9.2):** an internet room's report now says the
  room's code, which way each phone is reached (nearby, direct or relay)
  with its round trip, and the ping, with the room's log. Read and fixed as
  the owner sends them.
- **Data and battery (1.9.3):** measured on the owner's phones (the
  checklist says how). Expected data: about 40 MB an hour for two players.
- **README and site (1.9.4):** how online and nearby play work, the code,
  QR code and link, the line, and data use.

**Still open, on the owner's phones:** the checklist's runs, the data and
battery numbers, and the heavy games' rollback cost (1.6.4). Step 1 is done
when runs 1 and 2 pass ("Done when", above).

**App-wide review (2026-10-30), after Step 1 was built:** four reviews
(the room engine, the game screen, settings, the rest of the app) and the
fixes they led to. Still open, and why:

- **Two players who can't reach each other while both reach the host**
  (only possible with the relay paused and both behind strict NATs): the
  phones now ask for a new way (an ICE restart) instead of only logging it;
  relaying their inputs through the host is the full fix, for later if the
  reports show it.
- **Button size and moved buttons:** offsets saved at one size aren't
  scaled when the size changes (a layout version bump would reset
  everyone's layouts); for later.
- **Air moves read the game's memory on the main thread** (up to a frame's
  wait for the emulator's lock); measured on the phones first.
- **Wanted, not built yet:** physical controllers, a video filter, rapid-fire
  speed, per-game layouts, backing up settings and move lists.

### Order and dependencies

```
1.0 ─┬─ 1.1 (seam) ───────┬─ 1.4 (WebRTC) ─ 1.5 (rooms) ─┬─ 1.7 (queue) ─┬─ 1.8 (UI) ─ 1.9
     └─ 1.2 (backend) ─ 1.3 (profile) ┘                   └─ 1.6 (smooth) ┘
```

- **1.1 and 1.2 can go in parallel**, since they're independent.
- **1.6 and 1.7 build on 1.5.**
- **1.8 needs everything before it.**

### Risks

| Risk | Handling |
|---|---|
| The refactor (1.1) breaks Wi-Fi play | Ships alone; every existing test passes unchanged |
| WebRTC adds too much to the APK | Measured in 1.0; fall back to our own transport (D1) |
| Two 4G phones can't connect directly | The relay; measured in 1.9 |
| Supabase's free project pauses after a week idle | Fine while building; Pro once people play |
| Rollback replays too slow on old phones | Measured in 1.6; the window capped by phone speed |

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
| Game relay, voice | Cloudflare TURN / SFU | 1,000 GB a month, then $0.05/GB; **we stop at 900 GB (D8)** |
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
Step 1's phases are detailed above. Steps 2-4 get the same breakdown when
Step 1 is done.

- Step 1 is the biggest: multiplayer moves from Wi-Fi-only to hybrid.
- In Step 3, the fighter bot is the largest piece.

## Making money (later, and only after licensing)

### First: two things that must be true before any money

This covers subscriptions, purchases, ads and even donations.

1. **The emulator must allow it.** FBNeo's license (`src/license.txt`)
   says: "You may not sell, lease, rent or otherwise seek to gain monetary
   profit from FB Neo" and "You may not ask for donations to support your
   work on any project that uses the FB Neo source code." A paid app needs
   another emulator, either:
   - MAME's libretro core: GPL-2.0-or-later since 2016, so selling is
     allowed if the source is published. It's heavier than FBNeo on
     phones, so smoothness and rollback must be measured again for each
     game; or
   - one the game's licensor provides or approves.
2. **The games must be licensed.** Charging money around unlicensed SNK or
   Capcom games, even when players bring their own ROMs, invites takedowns.
   SNK enforces its rights; it withdrew the Neo Geo X license in 2013. Only
   licensed games go into a paid app (see [Licensing the games](#licensing-the-games)).

### Rules

- **Never sell smoothness.** The relay, ping-based delay and rollback are
  the same for everyone.
- **Never sell wins.** No gameplay advantage, and bots don't change with
  payment.
- **The free app stays complete:** offline, nearby, online with friends,
  public matches, quick chat and a basic profile.

### What to sell

**Customization**, with our own art only, never SNK or Capcom characters:

- **A player avatar character:** outfits, colors, poses, and victory
  animations shown in lobbies and on the profile.
- **Profile cards**, frames, name colors, titles and badges.
- **Controller (HUD) skins and button themes.** Layout and size stay free.
- **Emote and quick-chat packs**, and lobby and room themes.

**A subscription ("Arcade Pass")**, for features that cost money to run or
are extras. Candidates:

- Voice in public rooms (voice with friends stays free).
- Match replays and full match history. Replays are only inputs, so
  they're tiny to store.
- Cloud backup of the profile, control layouts and settings across phones.
- Tournament and host tools: brackets, bigger rooms, queue rules, and
  private lobbies with their own names.
- Monthly customization drops.

The subscription would also pay for Supabase Pro and relay use beyond the
free allowance.

**Payments:**

- Inside a Play Store app, digital goods go through Google Play Billing.
- Outside Play (the GitHub APK), a provider that works in Bangladesh, such
  as bKash.

## Licensing the games

**What we found (2026-10):**

- **SNK licenses its library as a business.** In 2018, IP licensing was
  over 65% of its revenue. Partners include:
  - Hamster: ACA NEOGEO, on Android too (e.g. KOF 2002 and Metal Slug X,
    $3.99 each);
  - Antstream Arcade (a streaming subscription);
  - Blaze (Evercade);
  - Arika (2024).

  There's no public application form. The way in is SNK's business
  contact, as a company.
- **Capcom re-releases its arcade games itself.** Street Fighter III 3rd
  Strike is in the Street Fighter 30th Anniversary Collection, and Marvel
  vs Capcom is in the Marvel vs Capcom Fighting Collection (2024, with
  Marvel). Licensing either to a small app is unlikely.
- **Cadillacs and Dinosaurs has never been re-released**, most likely
  because its rights are tied up with the comic and with GM, which owns
  the Cadillac name. It's effectively not licensable.
- **The KOF hacks are bootlegs** (97 Plus, 2002 Magic Plus, 2005 / 10th
  Anniversary). No one can license them.

| Game | Rights holder | Chance of a license |
| --- | --- | --- |
| KOF '97, KOF 2002, Metal Slug 1-5 and X, Sengoku 3 | SNK | Possible: SNK licenses actively |
| KOF hacks | bootlegs of SNK's games | None |
| Nightmare in the Dark | unclear (published by SNK) | Ask SNK |
| Double Dragon (Neo Geo) | Arc System Works, which bought Technōs's games (to confirm) | Possible: ask |
| Street Fighter III 3rd Strike | Capcom | Unlikely |
| Marvel vs Capcom | Capcom and Marvel | Very unlikely |
| Cadillacs and Dinosaurs | Capcom, plus the comic's and GM's rights | Effectively none |
| Penguin Brothers | Subsino (Taiwan) | Unknown: a smaller company, worth asking |

**What a license usually takes** (to confirm with each licensor):

- a registered company;
- a pitch: the product, platforms, countries, price, and how the games are
  protected;
- usually an advance or minimum guarantee, plus a revenue share;
- the licensor's approval of each release;
- a lawyer for the contract.

Licensed games could then ship inside the app, with no bring-your-own-ROM.
The unlicensed games would have to leave the paid app.

**Paths:**

1. **Stay free, as now:** no money of any kind, including donations
   (FBNeo's license).
2. **License a few SNK games** as a company, sell those, and switch the
   paid app's emulator.
3. **Offer the multiplayer technology** (rollback, nearby plus online,
   rooms) to companies that already hold the licenses, as a business deal.

## Sources

- [FBNeo license](https://github.com/finalburnneo/FBNeo/blob/master/src/license.txt)
- [MAME licensing (BSD/GPL since 2016)](https://en.wikipedia.org/wiki/MAME)
- [ACA NEOGEO on mobile: KOF 2002 and Metal Slug X](https://toucharcade.com/2024/01/17/metal-slug-x-aca-neogeo-download-out-now-iphone-android-mobile-snk-hamster-classics/)
- [SNK's IP licensing business (2018)](https://www.etoday.co.kr/news/view/1695830)
- [SNK withdraws the Neo Geo X license (2013)](https://www.nintendolife.com/news/2013/10/tommo_refuses_to_follow_snks_neo_geo_x_termination_demand)
- [Antstream Arcade partners with SNK](https://www.kitguru.net/?p=440149)
- [Evercade maker partners with SNK](https://www.videogameschronicle.com/news/evercade-manufacturer-announces-partnership-with-snk/)
- [Cadillacs and Dinosaurs: why it was never re-released](https://www.hardcoregaming101.net/cadillacs-dinosaurs/)
- [Supabase pricing](https://supabase.com/pricing)
- [Supabase anonymous sign-ins](https://supabase.com/docs/guides/auth/auth-anonymous)
  and [linking Google later](https://supabase.com/blog/anonymous-sign-ins)
- [Cloudflare TURN: generating credentials](https://developers.cloudflare.com/calls/turn/generate-credentials)
- [stream-webrtc-android](https://github.com/GetStream/webrtc-android): prebuilt WebRTC for Android
- [Cloudflare TURN analytics](https://developers.cloudflare.com/realtime/turn/analytics/)
  and [revoking credentials](https://developers.cloudflare.com/realtime/turn/generate-credentials/)
- [Cloudflare Realtime TURN/SFU pricing](https://developers.cloudflare.com/realtime/sfu/platform/pricing/)
- [GGPO](https://en.wikipedia.org/wiki/GGPO),
  [Fightcade](https://en.wikipedia.org/wiki/Fightcade): the rollback
  approach this follows.
