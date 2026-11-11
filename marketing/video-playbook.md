# Gameplay videos: what works (playbook)

Started 2026-11-03 23:27 Dhaka. The owner on draft 1 ("one more round", captions over
a 3-minute match): "it has no point. No story, nothing." A video needs a
story first; footage second. This file collects what works, and grows as
the owner sends viral examples to break down.

## Where they go: Reels (Facebook), YouTube Shorts, TikTok

One edit for all three (the owner: "Reels mostly. On yt, tiktok, fb"):
vertical 1080 x 1920, 30 or 60 fps, H.264 + AAC MP4, 15-30 s.
Keep captions and the game in the middle: each app lays its buttons
over the right edge and the bottom fifth (name, caption, music), and
TikTok's top bar over the top tenth. Post the same file to all three;
the first second decides whether it's watched on every one of them.

## The rules every video follows

1. **One story, said in the first second.** The top caption sets up a
   conflict the viewer wants settled ("He said Rugal is for noobs").
   No caption that only describes what's on screen ("round 1", "14 hit
   combo"): that's draft 1's mistake.
2. **Open loop, late payoff.** Ask a question in the hook (will he win?
   what does the cheap move do?), answer it in the last 3 seconds.
3. **Under 25 seconds.** One fight moment, not a match: cut everything
   between the setup and the payoff.
4. **Payoff is a reaction, not just a KO.** Freeze, zoom, meme sound,
   a line that turns the setup around ("He uninstalled").
5. **Relatable to people who grew up with the arcade.** In-jokes they
   lived: the 10-taka coin, the friend who spammed one move, the owner
   who switched the machine off, "Ammu ashche".
6. **Big captions, few words**, in the middle third of the screen; Bangla
   for local reach, English for wider.
7. **Loops:** the last frame leads back into the first, so it replays.
8. **End card 1.5 s max:** "90s Kid Arcade: play with friends, free".

## Story formats (to try, one at a time)

| # | Format | Setup (hook) | Payoff |
|---|---|---|---|
| 1 | Trash talk, paid back | Friend's real chat line: "easy win" | Perfect KO, his line again, "...", meme sound |
| 2 | That one friend | "Every arcade had this guy" | He spams the same move the whole round, and wins (or loses to it) |
| 3 | 1 HP comeback | Health bar at 1%, "he thought it was over" | Slow-mo super, the win |
| 4 | Then vs now | "1999: 10 taka a coin, the queue, Ammu calling" | "2026: my cousin in Chattogram, from bed" |
| 5 | Main stereotypes | "Iori mains be like" | The exact Iori habits, played straight |
| 6 | Worst character challenge | "Beating my friend with Choi only" | Day 1 loses, day 5 wins (a series) |
| 7 | The arcade owner | "When you're winning and the shop closes" | Screen goes black mid-super |

## How a video gets made

1. Decide the story (pick a format above) before playing.
2. Play it out in a room with a friend, saving matches on.
3. Save the match as a video (Replays, download icon); upload to Drive.
4. The editor (Claude) cuts setup to payoff, adds captions, zooms, sound
   and the end card, vertical 1080x1920; the owner reviews; repeat.

## Examples to break down

The owner sends viral videos (files or downloads; TikTok/Reels pages
can't be read from here); each gets a frame-by-frame breakdown below:
hook, beats with timings, captions, sounds, payoff, why it works.

- **Chess memes (sent 2026-11-03 23:27):** 103 s, 720x1280, 30 fps. A square board
  in the middle, black animated lines above and below. Pieces swap for
  meme faces as they "react"; something changes every 1-2 s; no talking,
  the memes carry it. Works because the board is a cast of characters
  and every move is a punchline.

## Made so far

- **Red vs Blue: Boss Supremacy** (2026-11-04 01:56). From a 16-minute recording of
  KOF 2002 Magic Plus, two players both on Rugal (red and blue palettes),
  6 matches, red 5-1. Two cuts:
  - **Reel** (34 s, 1080x1920): "Both of us picked the boss" over the
    order-select face-off, "Which side are you on? Comment below", a
    montage of supers with a bass hit per cut and "1-tap super" tags,
    "16 minutes later…", match point (red 4-0), the final hit in slow
    motion, Rugal's own win line "Done your packing? Your life's journey
    is over", "Red wins 5-1", then one banner card (play online, chat +
    voice, every super in one tap, free, link in bio). The owner's notes:
    feature clips after the payoff didn't fit (one banner card instead);
    "Play online", not "Play friends online".
  - **Full fight** (14:48, 1920x1080, 60 fps) for YouTube and Facebook:
    every round kept; select screens and "continue" cut; "Match n of 6,
    red x-y blue" on each face-off; final score; the banner. Sent as two
    parts (matches 1-3, 4-6; the send limit is 500 MB).
- Owner's note on the full fight's end card ("why this huge empty space"):
  the Facebook cover (1640x624) padded into 16:9 left a dark band. Long
  videos use `marketing/video/banner16x9.html` (1920x1080, rendered with
  Chromium like the cover), with RED VS BLUE over it for the intro.
- Then (owner): "Doesn't look good the end badge. Keep things in center
  and clean." The end card is `marketing/video/endcard16x9.html`: the
  round logo, one line of features, "Free on Android" and the address,
  centred on the dark background, nothing else.

- **1999 → 2026** (2026-11-11 19:15 Dhaka; the owner: "product marketing
  videos ... trending, modern and eye catching"). 21 s vertical: the
  nostalgia hook (format 4, then vs now) over a CRT, the beat drop at
  "2026.", then the app in fast beat cuts (kinetic captions, real screens,
  game footage on phones), end card. Made in code (`video/1999-2026/`):
  footage from the attract modes, our own synth track. The owner chose to
  show the games in full (told: takedown risk, SNK/Capcom/Sony footage).
