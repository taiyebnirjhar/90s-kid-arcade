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
- **Free plans only:** the library stays on R2's free tier (10 GB stored a
  month, downloads free). The Cloudflare dashboard uploads files up to
  300 MB; bigger ones go up with `rclone` (S3 API, multipart).

## Phases

- **P1. A PSP game runs.** CI puts the PPSSPP core and its assets in the
  APK. The game screen runs a GPU-drawn core: picture, sound, 60 fps. A
  game picked from the phone's files starts. Internal resolution (1x-3x)
  in the screen settings.
- **P2. PSP games in the app.** Import and Library take `.iso`, `.cso` and
  `.chd`; a PSP row on Home with each game's picture and title. Downloads
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

- 2026-11-05: plan written. The library holds 0.96 GB of the free 10 GB.
  Waiting for the owner to upload a few PSP games (CSO/CHD), and for his go
  on P1.
