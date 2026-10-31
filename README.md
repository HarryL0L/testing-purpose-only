# ONE LINE — the evolution of life, drawn by a single point of light

A short motion-graphics film rendered entirely offline with a custom Python
"long-exposure" renderer: one point of light travels along a single continuous
path and draws the story of evolution as glowing trails that hang in 3D above a
wet black floor. The virtual camera follows the light; the look is long-exposure
light painting with haze, soft depth-of-field blur, rippled wet-floor reflections,
bloom and film grain.

* `output/one_line.mp4` — the finished film (1920×1080, 24 fps, H.264 + AAC score)
* `storyboard.json` — the locked shot list: captions, colours, timings, 3D placement, camera keyframes
* `lightline/glyphs/*.json` — the drawings as 3D polylines (metres, y up, floor at y = 0)
* `lightline/engine.py` — the renderer (projection, additive line splatting bucketed by blur radius,
  depth of field, haze, mirror reflection with ripples, floor lighting, bloom, ACES grade, captions)
* `lightline/scene.py` — path builder (strokes, lifts, transitions, the final star), steadicam camera rig,
  caption schedule, time-varying haze
* `lightline/audio.py` — generative ambient score, cue-locked to the shot list
* `scripts/` — `preview_glyph.py` (author/judge a drawing), `layout.py` (place drawings along the walk),
  `render_frames.py` (multiprocess frame render), `make_movie.py` (ffmpeg encode + mux), `contact_sheet.py` (QA grid)

## Rebuild the film

```bash
pip install numpy scipy opencv-python-headless pillow      # ffmpeg must be on PATH
python3 scripts/layout.py --storyboard storyboard.json      # only if you moved/added drawings
python3 scripts/render_frames.py --storyboard storyboard.json --out output/frames --workers 4
python3 -m lightline.audio --out output/score.wav
python3 scripts/make_movie.py --frames output/frames --audio output/score.wav --out output/one_line.mp4
```

Render a single frame to look at: `python3 scripts/render_frames.py --storyboard storyboard.json --time 55.2 --png frame.png`

## How a drawing is made

Each glyph is a list of strokes (Catmull-Rom control points or straight polylines).
The light draws the strokes in order, lifting between them along a faint 10 %
connector so the whole film is literally one line; per-stroke options control
relative speed, brightness, hand tremor, a pause at the end of a stroke and colour.
`scripts/preview_glyph.py` renders a schematic plus a real engine preview of any glyph.
