# Notes for working on this repo

## Read first: docs/AGENTS.md and docs/decisions.md

[docs/AGENTS.md](docs/AGENTS.md) is the handbook: the owner's workflow
rules (branch, PRs he merges himself, trailers, telling him when to merge
and when the APK is ready), hard rules, a map of the code, every build,
test and release command, services, and gotchas.
[docs/decisions.md](docs/decisions.md) logs every decision with its date and
time (Dhaka, UTC+6: `TZ=Asia/Dhaka date`), what was asked, what was decided and why, and ends with where
things stand. Rely on them rather than on memory of earlier sessions.
**Every pull request that decides something adds a dated entry to
decisions.md and updates "Where things stand"; anything in AGENTS.md the
change makes wrong is fixed in the same PR.**

## The repo is `taiyebnirjhar/90s-kid-arcade`

It was renamed from `kof-arcade`. Use `90s-kid-arcade` for every GitHub call
(PRs, CI, subscriptions). The old name only redirects git, and the GitHub
tools and PR events fail with it. A local clone may still sit in a folder
called `kof-arcade`.

## Online play: Step 1 planned, built phase by phase

The owner asked for online play. Follow [docs/online-plan.md](docs/online-plan.md):
its principles, connection order (nearby, then direct, then relay), settled
decisions, and Step 1's work breakdown (phases 1.0-1.9, one PR each, each
keeping same-Wi-Fi play and its tests working). Start a phase only when the
owner says so, and record what's decided and learned in the doc as you go.

## Recording matches: planned in phases

Follow [docs/recording-plan.md](docs/recording-plan.md): off by default,
offered in onboarding; a match is saved as its start state, inputs and
checksums; **Save video** replays it into an MP4 in the gallery. Nothing is
hosted by us. Phases R1-R5, one PR each.

## PSP: God of War and Assassin's Creed

Follow [docs/psp-plan.md](docs/psp-plan.md): three PSP games for the owner,
offline, and a game ships only if it never lags or hangs on his phone
(the rule in the plan). Phases P1-P4, one PR each; start a phase only when
the owner says so. The arcade games stay as they are.

## Tournaments: planned

Follow [docs/tournament-plan.md](docs/tournament-plan.md): up to 8 players
in a Tournament room, single elimination, phases T1-T6, one PR each; start
a phase only when the owner says so.

## Seats in rooms follow the game's own countdown

A watcher's COIN or START takes a free seat, or the seat of a player the game
itself shows out: never started, or game over once its continue countdown
ran out. No timer of our own. Every game says where it keeps each player's
status (`Game.seats`), checked in the game; `GamesTest` fails without it.
Adding a game: follow [docs/adding-a-game.md](docs/adding-a-game.md).

## Never past a free allowance

Online services run only on free plans. Each one has a hard stop below its
free allowance for the month, and the feature switches off with a note in
the app until the month resets (the relay: no new credentials at 900 GB of
Cloudflare's 1,000 GB, live ones revoked at 950 GB). Nothing is ever
billed. A new paid service gets its stop before it ships. Details: D8 in
[docs/online-plan.md](docs/online-plan.md).

## Money: none while the app uses FBNeo

FBNeo's license forbids selling, any other profit, and asking for donations
for a project that uses it. Don't add payments, ads, subscriptions or
donation links. The plan for making money later, after licensing, is in
[docs/online-plan.md](docs/online-plan.md#making-money-later-and-only-after-licensing).
