# "1999 → 2026" (21 s, vertical 1080x1920, 30 fps)

The arcade in 1999 (10 taka, one coin; the queue; Ammu calls you home; the CRT
switches off), the drop at "2026.", then the app: every classic on a phone,
play online (chat + voice), tournaments and the trophy, God of War, the end card.

Nothing here holds game footage or app pictures; they're made where it's rendered:

- `clips/<name>/00000.jpg...`: game footage at 30 fps, from the attract modes
  with `tools/emu.py` (`python3 grab.py kof97 kof97 6200 7600`; also
  `mslugx mslug 1000 2500`, `sfiii3n sf3 6000 6900`, `mvsc mvsc2 6950 7800`)
  and God of War with `tools/pspemu.py` (`gow.py <state in play>`).
- `assets/`: the app's own screens from the Paparazzi snapshots
  (`home`, `bracket` = tournament landscape, `champ`, `gowhud_t` = the God of
  War controls with the grey background made clear).
- `music.wav`: `python3 music.py` (our own synth track, 120 bpm, cuts on the beat).

Render: `node render.js 0 630 frames` (three in parallel: 0-210, 210-420,
420-630), then
`ffmpeg -framerate 30 -i frames/%05d.jpg -i music.wav -c:v libx264 -pix_fmt yuv420p -crf 18 -c:a aac -b:a 192k -shortest -movflags +faststart out.mp4`.
