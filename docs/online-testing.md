# Online play: the two-phone checklist

Step 1 of [the online plan](online-plan.md) is done when two friends on
different networks play a smooth game from a room code, and the same flow
still works on one Wi-Fi with no internet. Everything up to here is tested on
a simulated internet and on real WebRTC on one machine. **This checklist is
the part only real phones can test.** Run it with the latest app on two
phones (three for the last runs), each with the same game (KOF '97 is a good
one: its zip and the BIOS).

After each run, in the game: **Pause → Connection report → Send**, and
paste it to Claude. The report says which way the phones connected
(nearby, direct or relay), the ping, every hiccup, and what the room did.

## Before you start

- [ ] Both phones have the latest app (the website's download) and the game.
- [ ] Both have opened **Profile** once (it makes the guest sign-in).

## The runs

For each: one phone **Online → Create a room**, the other **Online → Join
with a code** (type it, or scan the QR code). Play for 5 minutes: both
players take a seat (COIN, START), a few rounds.

| # | Phone 1 | Phone 2 | Check | Done |
| --- | --- | --- | --- | --- |
| 1 | Home Wi-Fi | 4G (Wi-Fi off) | Joins in under 10 s; smooth; the pill's bars show the ping | [ ] |
| 2 | 4G | 4G (another operator if you can) | Joins; smooth; the report says direct or relay | [ ] |
| 3 | Same Wi-Fi | Same Wi-Fi | An online room on one Wi-Fi: the report says nearby (zero lag) | [ ] |
| 4 | Hotspot on, no data | On phone 1's hotspot | **Create nearby room** (no internet): joins by key or the list; smooth | [ ] |
| 5 | Wi-Fi | 4G | Mid-game, phone 2 switches to Wi-Fi (or turns Wi-Fi off): the game holds for a moment and goes on, no one leaves | [ ] |
| 6 | Wi-Fi | 4G | Mid-game, the room's maker leaves (Leave): phone 2 runs the room and plays on | [ ] |
| 7 | Wi-Fi | 4G | The share link: phone 1 shares the room, phone 2 taps the link: the app opens and joins | [ ] |
| 8 | 3 phones | | Two play, the third watches and presses COIN: "1st in line"; a player leaves: the third gets the seat | [ ] |
| 9 | Wi-Fi | 4G | Phone 1 makes an **Open** room: within 10 s it's under **Rooms online** on phone 2, tagged Open; one tap joins. The report says relay | [ ] |
| 10 | Wi-Fi | 4G | Phone 1 makes a **Locked** room: listed with a lock on phone 2; tapping asks for the code; the right code joins, a wrong one says no room | [ ] |
| 11 | Wi-Fi | 4G | In an open room, phone 1 taps **Lock** in the room sheet: phone 2's list shows it locked; **Open** again lists it open | [ ] |

## Numbers to write down

- [ ] **Data:** Android Settings → Network → Data usage → 90s Kid Arcade, before
  and after 30 minutes of run 1 or 2 (expected: about 20 MB for 30 minutes).
- [ ] **Battery:** the percentage before and after the same 30 minutes, on each
  phone.
- [ ] **Heavy games:** a few rounds of Street Fighter III and Penguin Brothers
  in run 2; does the game slow down or stutter on the older phone? (The
  report's "slowest frame" says how long the worst frame took.)

## If something goes wrong

Send the connection report from both phones, and say which run and what
happened ("froze for 2 s after 3 minutes", "couldn't join: …"). The report
is what gets it fixed.
