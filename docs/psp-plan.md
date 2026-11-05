# PSP games: the plan

The owner asked (2026-11-05) for good-looking games of 1 GB and more, for
himself only: never on the Play Store, never sold, licences his to handle.
PSP is first: it runs well on most phones and its games look good. Later
consoles (Dreamcast, PS1, GameCube, Wii, 3DS) follow the same path.

Built phase by phase, one PR each. Each phase keeps the arcade games and
their tests working.

## Settled

- **Emulator:** PPSSPP's libretro core (`ppsspp_libretro_android.so`, from
  the libretro buildbot like FBNeo, about 8 MB zipped for arm64). It draws
  with the GPU (OpenGL ES); LibretroDroid already supports cores that do
  (`RETRO_ENVIRONMENT_SET_HW_RENDER`). PPSSPP also needs its asset folder
  (fonts, language files, shaders) in the core's system directory.
- **Games:** the owner's own dumps, as `.iso`, `.cso` or `.chd`. CSO and CHD
  are compressed ISOs that PPSSPP reads directly, 20-50% smaller: use them
  to fit more in R2's free 10 GB.
- **No ROM checks like the arcade's:** a PSP game is one disc image. The app
  reads its title and disc ID from the image (`PARAM.SFO`) to name it and
  find its picture (libretro thumbnails, "Sony - PlayStation Portable").
- **Offline first.** A PSP savestate is tens of MB, too big to save every
  frame for rollback like the arcade games. Playing with friends comes
  later, if at all (PPSSPP's own ad-hoc network play, or delay-based
  lockstep), decided after P4.
- **A library of their own:** PSP games live in a second Cloudflare
  account's R2 (the owner's choice, 2026-11-05), so they get its free 10 GB
  and the arcade library keeps its own. Same `server/worker.js` there, with
  its own key. The app's Library shows both, Arcade and PSP, each with its
  address and key entered once.
- **Free plans only:** each library stays under its account's free 10 GB
  (downloads are free). The Cloudflare dashboard uploads files up to
  300 MB; bigger ones go up with `rclone` (S3 API, multipart).

## Phases

- **P1. A PSP game runs.** CI puts the PPSSPP core and its assets in the
  APK. The game screen runs a GPU-drawn core: picture, sound, 60 fps. A
  game picked from the phone's files starts. Internal resolution (1x-3x)
  in the screen settings.
- **P2. PSP games in the app.** Import and Library take `.iso`, `.cso` and
  `.chd`; the Library reads from both libraries (Arcade and PSP); a PSP row on Home with each game's picture and title. Downloads
  of a few GB from the library, resuming after drops (foreground service,
  as every download).
- **P3. PSP controls.** A layout for the PSP: analog stick, D-pad,
  cross / circle / square / triangle, L and R, START and SELECT, following
  the button layout rules (`adding-a-game.md`, "Button layouts").
- **P4. Saves.** The game's own saves kept per game; save and load states;
  recording matches off for PSP until a GPU frame can be captured.
- **P5. Playing together** (to decide): ad-hoc network play or lockstep,
  after measuring a savestate's size and time on a real phone.

## Testing

The cloud sandbox has no GPU: P1 is checked with PPSSPP's software
renderer where it can be, and on the owner's phone. Test games come from
the owner's library (downloaded with its key, never committed).

## Where things stand

- 2026-11-05: plan written. The arcade library holds 0.96 GB of its free
  10 GB. The owner is making a second Cloudflare account for PSP games.
  Starter games chosen: God of War: Ghost of Sparta, Spider-Man 3, Naruto
  Shippuden: Ultimate Ninja Impact, Tekken 6, GTA: Vice City Stories,
  Assassin's Creed: Bloodlines, Dragon Ball Z: Shin Budokai 2 (under 300 MB,
  the first test). Waiting for them and for his go on P1.
