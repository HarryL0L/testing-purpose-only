"""Render frames of the storyboard to PNG files (multiprocess), or a single frame.

usage:
  python scripts/render_frames.py --storyboard storyboard.json --out frames/ [--start 0 --end N --workers 4]
  python scripts/render_frames.py --storyboard storyboard.json --time 12.5 --png /tmp/x.png
"""
import argparse, os, sys, time
import numpy as np, cv2
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from lightline.engine import Look, Renderer
from lightline.scene import build_scene

_scene = None
_renderer = None

def _init(sb, glyph_dir, width, height, scale):
    global _scene, _renderer
    look = Look(width=width, height=height)
    _scene = build_scene(sb, glyph_dir, look)
    _renderer = Renderer(look, seed=11)

def render_at(scene, renderer, t, i):
    for k, v in scene.look_at(t).items():
        setattr(renderer.look, k, v)
    return renderer.render(scene.trail, t, scene.camera(i), texts=scene.texts_at(t), fade=scene.fade_at(t),
                           trail_gain=scene.trail_gain_at(t), head_gain=scene.head_gain_at(t))

def _render_one(args):
    i, out_dir = args
    t = (i + 0.5) / _scene.look.fps
    path = os.path.join(out_dir, f"f{i:05d}.png")
    if os.path.exists(path):
        return i, 0.0
    t0 = time.time()
    img = render_at(_scene, _renderer, t, i)
    cv2.imwrite(path, img, [cv2.IMWRITE_PNG_COMPRESSION, 1])
    return i, time.time() - t0

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--storyboard", default="storyboard.json")
    ap.add_argument("--glyphs", default="lightline/glyphs")
    ap.add_argument("--out", default="output/frames")
    ap.add_argument("--start", type=int, default=0)
    ap.add_argument("--end", type=int, default=-1)
    ap.add_argument("--stride", type=int, default=1)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--width", type=int, default=1920)
    ap.add_argument("--height", type=int, default=1080)
    ap.add_argument("--time", type=float, default=None)
    ap.add_argument("--png", default=None)
    ap.add_argument("--info", action="store_true")
    a = ap.parse_args()
    _init(a.storyboard, a.glyphs, a.width, a.height, 1)
    sc = _scene
    if a.info or a.time is not None:
        print(f"duration {sc.duration:.2f}s frames {sc.frames} samples {len(sc.trail.t)}")
        for sh in sc.shots:
            print(f"  {sh.id:18s} start {sh.t_start:6.2f} drawn {sh.t_draw_end:6.2f} leave {sh.t_leave:6.2f} next {sh.t_next:6.2f} extent {sh.extent:4.2f} center {np.round(sh.center,2)}")
        print(f"  ending at {sc.ending_start:.2f}")
    if a.time is not None:
        i = int(a.time * sc.look.fps)
        t0 = time.time()
        img = render_at(sc, _renderer, a.time, i)
        cv2.imwrite(a.png or "frame.png", img)
        print(f"rendered t={a.time} in {time.time()-t0:.2f}s -> {a.png}")
        return
    if a.info:
        return
    os.makedirs(a.out, exist_ok=True)
    end = sc.frames if a.end < 0 else min(a.end, sc.frames)
    frames = list(range(a.start, end, a.stride))
    import multiprocessing as mp
    t0 = time.time()
    done = 0
    with mp.Pool(a.workers, initializer=_init, initargs=(a.storyboard, a.glyphs, a.width, a.height, 1)) as pool:
        for i, dt in pool.imap_unordered(_render_one, [(i, a.out) for i in frames], chunksize=2):
            done += 1
            if done % 24 == 0 or done == len(frames):
                el = time.time() - t0
                print(f"{done}/{len(frames)} frames, {el:.0f}s elapsed, eta {el/done*(len(frames)-done):.0f}s", flush=True)
    print("done")

if __name__ == "__main__":
    main()
