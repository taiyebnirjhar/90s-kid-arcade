# Recording matches (plan)

Started 2026-11-03 15:58 Dhaka. The owner wants to record gameplay to post (Facebook,
YouTube). Asked first about streaming; recording comes first, streaming
platforms later on top of the same video work.

## What the player sees

- **Off by default.** Settings → **Save my matches** turns it on. The
  welcome steps (onboarding) offer it once, with a plain on/off choice.
- When on, every match is saved by itself: the best moment is never the one
  you pressed Record for. The last **20** are kept; a starred one is kept
  for good (pause menu → **Keep this match**, or the star in Replays).
- **Replays** on the home screen, beside Online and Library: each saved
  match with its game, players, date and length. Tap to watch it again, or
  **Save video**.
- **Save video** makes an MP4 (1080p60, or 720p60) into the gallery, album
  "90s Kid Arcade", with a progress bar. Game sound always; the player's mic
  and the room's voice only if asked.
- After a video is made: **Share** buttons for **WhatsApp**, **Messenger**
  and a **Facebook post** (shown for the apps the phone has), and **More…**
  (Android's share sheet: YouTube, TikTok, anything). Each opens that app
  with the video attached; the player posts from there.
- Settings shows the space saved matches use, with **Clear**.

## How it works

- A saved match is the game's state at its first frame plus every player's
  input for every frame (the confirmed ones, after rollbacks), and the RAM
  checksum every 2 s that online play already makes and compares between
  phones (`netSimulate` in `libretrodroid.cpp`). Estimate: under 1 MB a
  match (the state is most of it); measured in R1.
- **Save video** runs the match again from that state with those inputs,
  off screen and as fast as the phone can, and the phone's hardware encoder
  (MediaCodec, H.264 + AAC) writes the MP4. Nothing extra runs while the
  match is played, so recording can't make play less smooth.
- The replay checks itself: each 2 s checksum must match the saved one. If
  one ever doesn't, it says so rather than make a wrong video.
- The picture as the game screen shows it: smooth, and the player's Screen
  shape (16:9 fills the frame; narrower ones get dark bars), in 1920 × 1080
  (or 1280 × 720).
- Everything stays on the phone: no server, no storage of ours, no cost.
  Players post the video where they like.

## Phases (one PR each; each keeps play, online play and their tests working)

- **R1 Record.** Done (2026-11-03 16:11). Native `MatchRecorder` (`recorder.h/.cpp`):
  alone, each frame's buttons are read once before it runs (it runs with
  exactly what's kept) and a checksum every 120 frames; in a room, the
  frames below what every phone has confirmed (`netUsed`, after rollbacks)
  and the room's own confirmed checksums. A state load, reset, cheat, new
  seating or start frame ends a recording. App: `MatchTaker` starts one a
  second into a game (not while a room seats everyone), keeps each that
  ran 10 s or more as it ends and on leaving, `MatchStore` keeps 20 plus
  starred. Settings → Save my matches (off) and the onboarding step.
  Tests: `recorder_test.cpp` (CI), `MatchTest` (file format, keeping).
- **R2 Replays and the check.** Built (2026-11-03 16:17); the check on a real phone
  is the owner's (play a match with saving on, watch it: "Replay over ·
  the same as the match ✓"). The Replays screen on home: list, watch,
  star, delete, space used. Watching runs the match as a room watcher does
  (`setNetplay` watching, the start state, every seat's inputs pushed) and
  compares each checksum with the saved one: the first proof on a phone
  (KOF 2002 Magic Plus, alone and online) that a replay is the match.
  A replay uses its own saves folder (`replay-saves`), never records itself,
  and doesn't count as a game played.
- **R3 Save video and share.** Built (2026-11-03 17:26), as below with one change:
  the video is made while the replay plays on screen at its own speed (a
  3-minute match takes about 3 minutes, shown as "Making the video… N%"),
  not off screen and faster; faster is a later step. Native capture
  (`LibretroDroid::setCapture`: every frame drawn and the sound, kept until
  taken), `VideoMaker.kt` (EGL into the encoder's surface, sharp-bilinear
  shader, H.264 1080p60 8 Mbps (was 12: 2.5 GB for 26 minutes), 720p at 5 if the phone can't, AAC 192 kbps,
  times by the game's own clock), `Videos.kt` (gallery, share buttons).
  Then, from the owner's first video: exactly 60 frames a second with the
  sound fitted (each frame's own sound made its 1/60 s: `FrameSound`; the
  core's reported sound rate can be wrong), nothing dropped (the replay waits on
  `captureBacklog`), the picture smooth and the Screen setting's shape,
  as the game screen shows it.
  Originally planned: off-screen replay to MediaCodec at
  1080p60/720p60, sharp-pixel scaling, game sound, progress, into the
  gallery (MediaStore); then Share: WhatsApp (`com.whatsapp`), Messenger
  (`com.facebook.orca`), Facebook (`com.facebook.katana`) when installed,
  each an ACTION_SEND of the video's MediaStore URI to that app, and More…
  (the share sheet). WhatsApp and Messenger shrink or limit long videos
  on their side; R5's trim keeps a clip short.
- **R4 Voices.** Mic and room voice into the video, if chosen (needs them
  recorded with the match, with consent from everyone in the room).
- **R5 Polish.** A trim (from/to) before saving, real-phone tests on slow
  phones.

Later: live streaming (YouTube with sign-in; Twitch), using R3's encoder.

## Settled

- Off by default; offered in onboarding (owner, 2026-11-03 15:58).
- Saved on the phone only; videos go to the gallery (owner asked where they'd
  be hosted: nowhere of ours).
- No money features (FBNeo's license): no paid tiers for recording.
