# "2010 → 2026" (35 s, vertical 1080x1920, 30 fps)

The owner's story: 2010, 1 taka a coin; after school; the whole day on
holidays; kokhono school paliye; ABBU! (with a belt). Then "2026." and the
app: every classic on a phone (Magic Plus, Mostofa, Sengoku 3, KOF '97,
Metal Slug, MvC), play online (chat + voice), a tournament and the trophy
(won by Nirjhor), God of War, the end card.

Timing (the owner: "frames bit slower ... so human eye can see"): every
caption held at least 2 s (0.3 s a word + 0.5 s), game cuts 0.8 s, scenes
2.5-4 s; every line in the same spot. Scene times are the `S` table in
compose.html; music.py hits the same times.

Nothing here holds game footage or app pictures; they're made where it's rendered:

- `clips/<name>/00000.jpg...`: game footage at 30 fps from the attract modes
  with `tools/emu.py`: `python3 grab.py kf2k2mp2 k2mp 7920 9300`,
  `dino dino 3960 5200`, `sengoku3 sg3 3000 4700`, `mslugx mslug 1000 2500`,
  `kof97 kof97 6200 7600`, `mvsc mvsc2 6950 7800`; God of War with
  `tools/pspemu.py` (`gow.py <a state in play>`).
- `assets/`: the app's own screens from the Paparazzi snapshots: `home`,
  `bracket` (tournament landscape) and `champ` recorded with "Rafi" renamed
  "Nirjhor" in UiSnapshots (not committed), `gowhud_t` (the God of War
  controls with the grey background made clear).
- `music.wav`: `python3 music.py` (our own synth track, 120 bpm).

Render: `node render.js 0 1050 frames` (three in parallel), then
`ffmpeg -framerate 30 -i frames/%05d.jpg -i music.wav -c:v libx264 -pix_fmt yuv420p -crf 18 -c:a aac -b:a 192k -shortest -movflags +faststart out.mp4`.
