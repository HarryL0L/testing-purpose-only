"""Preview a glyph: left = schematic (front view x/y with stroke order, start/end markers, plus a top view),
right = the actual engine render of the finished drawing over the wet floor.

usage: python3 scripts/preview_glyph.py lightline/glyphs/ape.json --out /path/preview.png [--color "#CDBE8E"] [--az 25]
"""
import argparse, json, os, sys, math
import numpy as np, cv2
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from lightline.engine import Look, Renderer
from lightline.scene import build_scene, load_glyph, glyph_bbox

def schematic(strokes, size=1000):
    mn, mx = glyph_bbox(strokes)
    span = max(mx[0]-mn[0], mx[1]-mn[1], 1e-6)
    pad = 70
    sc = (size - 2*pad) / span
    cx = (mn[0]+mx[0])/2; cy = (mn[1]+mx[1])/2
    img = np.full((size, size, 3), 255, np.uint8)
    def P(x, y):
        return (int(pad + (size-2*pad)/2 + (x-cx)*sc), int(pad + (size-2*pad)/2 - (y-cy)*sc))
    # floor line (y=0)
    fy = P(0, 0)[1]
    if 0 <= fy < size:
        cv2.line(img, (0, fy), (size, fy), (200, 200, 200), 1, cv2.LINE_AA)
        cv2.putText(img, "floor y=0", (8, fy-6), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (150,150,150), 1, cv2.LINE_AA)
    # metre grid ticks
    for m in range(int(math.floor(mn[0]))-1, int(math.ceil(mx[0]))+2):
        x = P(m, 0)[0]
        if 0 <= x < size:
            cv2.line(img, (x, size-20), (x, size), (180,180,180), 1)
            cv2.putText(img, f"{m}", (x+2, size-6), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (120,120,120), 1, cv2.LINE_AA)
    colors = [(200,60,60),(60,140,60),(60,60,200),(180,120,0),(140,0,160),(0,150,170),(90,90,90),(220,100,160),(100,160,40),(40,100,220),(200,140,60),(60,200,140),(160,60,100),(120,120,0)]
    for i, st in enumerate(strokes):
        col = colors[i % len(colors)]
        pts = np.array([P(x, y) for x, y, z in st.pts], np.int32)
        cv2.polylines(img, [pts], False, col, 2, cv2.LINE_AA)
        s0, e0 = tuple(pts[0]), tuple(pts[-1])
        cv2.circle(img, s0, 6, col, -1, cv2.LINE_AA)
        cv2.circle(img, e0, 6, col, 2, cv2.LINE_AA)
        cv2.putText(img, str(i+1), (s0[0]+8, s0[1]-8), cv2.FONT_HERSHEY_SIMPLEX, 0.7, col, 2, cv2.LINE_AA)
    cv2.putText(img, "FRONT VIEW (x right = forward along the line, y up). filled dot = stroke start, ring = end", (10, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0,0,0), 1, cv2.LINE_AA)
    cv2.putText(img, f"bbox x[{mn[0]:.2f},{mx[0]:.2f}] y[{mn[1]:.2f},{mx[1]:.2f}] z[{mn[2]:.2f},{mx[2]:.2f}] m", (10, 44), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0,0,0), 1, cv2.LINE_AA)
    # top view inset if any depth
    if mx[2]-mn[2] > 1e-3:
        ins = 300; top = np.full((ins, ins, 3), 245, np.uint8)
        span2 = max(mx[0]-mn[0], mx[2]-mn[2], 1e-6); sc2 = (ins-40)/span2; cz = (mn[2]+mx[2])/2
        def Q(x, z): return (int(20 + (ins-40)/2 + (x-cx)*sc2), int(20 + (ins-40)/2 + (z-cz)*sc2))
        for i, st in enumerate(strokes):
            pts = np.array([Q(x, z) for x, y, z in st.pts], np.int32)
            cv2.polylines(top, [pts], False, colors[i % len(colors)], 2, cv2.LINE_AA)
        cv2.putText(top, "TOP VIEW (x right, +z down = toward camera)", (6, 14), cv2.FONT_HERSHEY_SIMPLEX, 0.38, (0,0,0), 1, cv2.LINE_AA)
        img[size-ins-30:size-30, size-ins-10:size-10] = top
    return img

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("glyph")
    ap.add_argument("--out", required=True)
    ap.add_argument("--color", default="#e8e8ff")
    ap.add_argument("--az", type=float, default=22.0)
    ap.add_argument("--elev", type=float, default=7.5)
    ap.add_argument("--height_m", type=float, default=None)
    ap.add_argument("--draw_seconds", type=float, default=6.0)
    a = ap.parse_args()
    strokes, metres = load_glyph(a.glyph)
    gid = os.path.splitext(os.path.basename(a.glyph))[0]
    gdir = os.path.dirname(os.path.abspath(a.glyph))
    sb = {"title": "", "fps": 24, "opening_seconds": 1.5, "ending_seconds": 1.0,
          "shots": [{"id": gid, "glyph": gid, "pos": [0, 0, 0], "rot_y": 0.0, "height_m": a.height_m,
                     "draw_seconds": a.draw_seconds, "hold_seconds": 1.0, "color_hex": a.color,
                     "cam": {"az0": a.az, "az1": a.az, "elev": a.elev}}]}
    look = Look(width=1280, height=720)
    sc = build_scene(sb, gdir, look)
    r = Renderer(look)
    t = sc.shots[0].t_draw_end + 0.05
    i = min(sc.frames - 1, int(t * 24))
    frame = r.render(sc.trail, t, sc.camera(i))
    left = schematic(strokes, 1000)
    right = cv2.resize(frame, (int(1000 * 16 / 9), 1000))
    canvas = np.zeros((1000, 1000 + right.shape[1] + 20, 3), np.uint8)
    canvas[:, :1000] = left
    canvas[:, 1020:] = right
    cv2.imwrite(a.out, canvas, [cv2.IMWRITE_PNG_COMPRESSION, 3])
    total = sum(float(np.sum(np.linalg.norm(np.diff(s.pts, axis=0), axis=1))) for s in strokes)
    mn, mx = glyph_bbox(strokes)
    print(f"{gid}: {len(strokes)} strokes, total length {total:.2f} ({'metres' if metres else 'normalised units'}), bbox x[{mn[0]:.2f},{mx[0]:.2f}] y[{mn[1]:.2f},{mx[1]:.2f}] z[{mn[2]:.2f},{mx[2]:.2f}] -> {a.out}")

if __name__ == "__main__":
    main()
