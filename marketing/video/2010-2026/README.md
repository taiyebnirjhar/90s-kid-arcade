# "2010 → 2026" (42 s, vertical 1080x1920, 30 fps)

The owner's story: 2010, 1 taka a coin; after school; the whole day on
holidays; kokhono school paliye; ABBU! and the belt (three hits: shake, red flash,
THASH! DHISH!, whoosh-crack-thud each; "abbu dhora khowar por mair dito"). Then "2026." and the
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

Render: `node render.js 0 1251 frames` (three in parallel), then
`ffmpeg -framerate 30 -i frames/%05d.jpg -i music.wav -c:v libx264 -pix_fmt yuv420p -crf 18 -c:a aac -b:a 192k -shortest -movflags +faststart out.mp4`.

## Real play on the real game screen (v3)

The owner: "u are not showing controlles ... Need real exp. Real play and
control view", then "Use all real layout, and reduce opacity of control.
Like real app does". The gameplay is played, not the attract mode:
`kofstart.py` (coin, start, team: Kyo first) and `kofplay.py` (Kyo:
Aragami, Oniyaki, MAX, Orochinagi against the CPU), `play.py` for Mostofa
(Mustapha), Metal Slug X and Sengoku 3; each writes its frames and, per
frame, what was held (stick direction, buttons, the move pill).

`RealPlay.kt` (a Paparazzi test, copied into app/src/test only to render,
never committed) builds the game screen as GameActivity does: the frame in
the 16:9 box (ScreenMode.WIDE) on black, the real HudView for that game
(Kyo's six moves, size 1, DEFAULT_OPACITY), and the presses sent as real
touches, so the stick ring and the pressed buttons light as in the app.
Run with `RP_DIR=<this folder's work dir>` after writing `realplay_<scene>.txt`
(frame path | dir | buttons | move per line); its pictures go to
`clips/rp_<scene>/`. The music is normalised to -14 LUFS.
