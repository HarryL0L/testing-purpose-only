"""
lightline.scene — turns a storyboard (JSON) + glyph files (JSON polylines) into
  * one continuous, time-sampled light path (engine.Trail),
  * a smoothed steadicam camera track,
  * a caption schedule and time-varying look parameters.

Storyboard format (storyboard.json):
{
  "title": "...", "subtitle": "...", "end_card": "line 1\\nline 2",
  "fps": 24, "opening_seconds": 6.0, "ending_seconds": 8.5,
  "palette": {"haze_hex": "#7f9bb8"},
  "haze_beta": [0.07, 0.022],           # extinction per metre at film start -> end (haze thins over time)
  "shots": [
    {"id": "ape", "glyph": "ape", "caption": "...", "era_label": "...",
     "pos": [x, 0, z], "rot_y": -90, "height_m": null, "base_y": 0.0,
     "draw_seconds": 5.5, "hold_seconds": 4, "color_hex": "#CDBE8E",
     "cam": {"az0": -30, "az1": 20, "elev0": 8, "elev1": 8, "dist0": null, "dist1": null,
             "ty0": null, "ty1": null, "lead": 0.3}}
  ]
}

Glyph format (glyphs/<id>.json):
{ "id": "...", "metres": true, "smooth": true, "subdiv": 10,
  "strokes": [ [[x,y],[x,y],...],
               {"pts": [[x,y,z],...], "smooth": false, "closed": true,
                "speed": 1.0, "intensity": 1.0, "jitter": 0.0, "pause_after": 0.0, "color_hex": null} ] }
Glyph space: x = forward along the line of evolution (side-view figures face +x), y = up (floor at 0),
z = toward the camera side. With "metres": true coordinates are used as-is; otherwise the glyph is
normalised to unit height (lowest point at y=0, centred in x/z) and scaled by the shot's height_m.
"""
from __future__ import annotations

import json
import math
import os
from dataclasses import dataclass, field

import numpy as np

from .engine import (UP, Camera, Look, Trail, catmull_rom, ease_in_out, hex_to_linear, normalize,
                     polyline_length, resample_by_arclength, rot_y, smoothstep)

SAMPLE_HZ = 192  # path samples per second (8 per frame at 24 fps)


# ----------------------------------------------------------------------------
# glyphs
# ----------------------------------------------------------------------------
@dataclass
class Stroke:
    pts: np.ndarray
    speed: float = 1.0
    intensity: float = 1.0
    jitter: float = 0.0
    pause_after: float = 0.0
    color: np.ndarray | None = None


def load_glyph(path: str) -> tuple[list[Stroke], bool]:
    with open(path) as f:
        g = json.load(f)
    default_smooth = g.get("smooth", True)
    subdiv = max(1, int(g.get("subdiv", 10)))
    strokes: list[Stroke] = []
    for s in g["strokes"]:
        if isinstance(s, dict):
            pts = np.asarray(s["pts"], np.float64)
            smooth = s.get("smooth", default_smooth)
            closed = bool(s.get("closed", False))
            opts = s
        else:
            pts = np.asarray(s, np.float64)
            smooth, closed, opts = default_smooth, False, {}
        if pts.ndim != 2 or len(pts) < 2:
            continue
        if pts.shape[1] == 2:
            pts = np.hstack([pts, np.zeros((len(pts), 1))])
        if closed and np.allclose(pts[0], pts[-1]) and len(pts) > 2:
            pts = pts[:-1]
        if smooth and len(pts) >= 3:
            pts = catmull_rom(pts, subdiv=subdiv, closed=closed)
        elif closed:
            pts = np.vstack([pts, pts[:1]])
        col = opts.get("color_hex")
        strokes.append(Stroke(pts=pts, speed=float(opts.get("speed", 1.0)), intensity=float(opts.get("intensity", 1.0)),
                              jitter=float(opts.get("jitter", 0.0)), pause_after=float(opts.get("pause_after", 0.0)),
                              color=hex_to_linear(col) if col else None))
    metres = bool(g.get("metres", False))
    if not strokes:
        raise ValueError(f"{path}: glyph has no stroke with >= 2 points")
    if not metres:
        allp = np.vstack([s.pts for s in strokes])
        mn, mx = allp.min(0), allp.max(0)
        ext = float(np.max(mx - mn))
        h = float(mx[1] - mn[1])
        if ext < 1e-9 or h < 1e-3 * ext:
            raise ValueError(f"{path}: degenerate glyph bbox (height {h:.3g}, extent {ext:.3g}); set \"metres\": true for flat glyphs")
        off = np.array([(mn[0] + mx[0]) / 2, mn[1], (mn[2] + mx[2]) / 2])
        for s in strokes:
            s.pts = (s.pts - off) / h
    return strokes, metres


def glyph_bbox(strokes: list[Stroke]):
    allp = np.vstack([s.pts for s in strokes])
    return allp.min(0), allp.max(0)


def place_glyph(strokes: list[Stroke], pos, rot_deg, scale, base_y=0.0) -> list[Stroke]:
    R = rot_y(rot_deg)
    pos = np.asarray(pos, np.float64) + np.array([0, base_y, 0])
    out = []
    for s in strokes:
        out.append(Stroke(pts=(s.pts * scale) @ R.T + pos, speed=s.speed, intensity=s.intensity,
                          jitter=s.jitter, pause_after=s.pause_after, color=s.color))
    return out


# ----------------------------------------------------------------------------
# timeline
# ----------------------------------------------------------------------------
@dataclass
class Shot:
    id: str
    strokes: list
    center: np.ndarray
    extent: float
    height: float
    normal: np.ndarray
    right: np.ndarray
    color: np.ndarray
    caption: str
    era_label: str
    draw_seconds: float
    hold_seconds: float
    cam: dict
    t_start: float = 0.0
    t_draw_end: float = 0.0
    t_leave: float = 0.0
    t_next: float = 0.0
    first_point: np.ndarray = None
    last_point: np.ndarray = None
    pause_times: list = field(default_factory=list)


@dataclass
class Scene:
    look: Look
    trail: Trail
    shots: list
    duration: float
    cam_pos: np.ndarray
    cam_tgt: np.ndarray
    cam_focus: np.ndarray
    texts: list
    opening: float
    ending_start: float
    title: str
    haze_beta: tuple = (0.07, 0.022)
    bloom_step_t: float = 1e9
    pulses: tuple = ()

    @property
    def frames(self) -> int:
        return int(round(self.duration * self.look.fps))

    def camera(self, i: int) -> Camera:
        return Camera(self.cam_pos[i], self.cam_tgt[i], float(self.cam_focus[i]))

    def texts_at(self, t: float):
        out = []
        for key, lines, t0, t1, fi, fo, rgb in self.texts:
            if t < t0 or t > t1:
                continue
            a = min(1.0, (t - t0) / fi) if fi > 0 else 1.0
            a = min(a, (t1 - t) / fo if fo > 0 else 1.0)
            a = float(smoothstep(0, 1, a))
            out.append((key, lines, a, rgb))
        return out

    def fade_at(self, t: float) -> float:
        f = min(1.0, float(smoothstep(0.0, 1.5, t)))
        f = min(f, float(smoothstep(0.0, 1.0, (self.duration - t) / 0.9)))
        return f

    def trail_gain_at(self, t: float) -> float:
        """the trails dim away in the last seconds; the point of light stays."""
        return 1.0 - float(smoothstep(self.duration - 3.2, self.duration - 1.0, t))

    def head_gain_at(self, t: float) -> float:
        g = 1.0
        for tp in self.pulses:
            g += 1.1 * math.exp(-((t - tp) / 0.17) ** 2)
        return g

    def look_at(self, t: float) -> dict:
        u = min(1.0, max(0.0, t / self.duration))
        beta = self.haze_beta[0] + (self.haze_beta[1] - self.haze_beta[0]) * u
        bloom = 0.52 + 0.22 * float(smoothstep(self.bloom_step_t - 0.2, self.bloom_step_t + 0.6, t))
        return {"haze_beta": beta, "bloom": bloom}


def _bezier(p0, p1, p2, p3=None, n=24):
    t = np.linspace(0, 1, n)[:, None]
    if p3 is None:
        return (1 - t) ** 2 * p0 + 2 * (1 - t) * t * p1 + t ** 2 * p2
    return (1 - t) ** 3 * p0 + 3 * (1 - t) ** 2 * t * p1 + 3 * (1 - t) * t ** 2 * p2 + t ** 3 * p3


class _Segments:
    """collects timed path segments and samples them into a Trail."""

    def __init__(self, hz=SAMPLE_HZ):
        self.hz = hz
        self.items = []
        self.t = 0.0

    def add(self, poly, duration, kind, color, inten, width=1.0, head=1.0, jitter=0.0):
        poly = np.asarray(poly, np.float64)
        duration = max(float(duration), 1.0 / self.hz)
        self.items.append((poly, self.t, duration, kind, np.asarray(color, np.float32), width, head, inten, jitter))
        self.t += duration

    def sample(self, rng) -> Trail:
        ts, ps, ins, cols, wds, hds = [], [], [], [], [], []
        freqs = rng.uniform(1.2, 5.5, (3, 4))
        phases = rng.uniform(0, 2 * math.pi, (3, 4))
        amps = rng.uniform(0.4, 1.0, (3, 4))
        jf = rng.uniform(9.0, 23.0, (3, 3))
        jp = rng.uniform(0, 2 * math.pi, (3, 3))

        def wobble(t, F, P, A=None):
            w = np.zeros((len(t), 3))
            for a in range(3):
                for j in range(F.shape[1]):
                    w[:, a] += (A[a, j] if A is not None else 1.0) * np.sin(F[a, j] * t + P[a, j])
            return w / F.shape[1]

        for poly, t0, D, kind, color, width, head, inten, jitter in self.items:
            n = max(2, int(round(D * self.hz)))
            tt = t0 + (np.arange(n) + 0.5) / n * D
            u = (tt - t0) / D
            tau_s = tt - t0
            env = smoothstep(0.0, 0.25, tau_s) * smoothstep(0.0, 0.25, D - tau_s)  # zero at both ends: no steps between kinds
            if kind == "stroke":
                pos = resample_by_arclength(poly, ease_in_out(u, 0.45))
                tau = tt - t0
                it = inten * (0.55 + 0.45 * smoothstep(0.0, 0.14, tau))
                amp = 0.008
            elif kind == "lift":
                pos = resample_by_arclength(poly, ease_in_out(u, 0.8))
                it = np.full(n, inten)
                amp = 0.02
            elif kind == "hover":
                pos = poly[0][None, :] + env[:, None] * np.stack([0.04 * np.sin(1.3 * tt), 0.025 * np.sin(0.9 * tt + 1.0), 0.035 * np.cos(1.1 * tt)], 1)
                it = np.full(n, inten)
                amp = 0.0
            elif kind == "still":
                pos = np.repeat(poly[0][None, :], n, 0)
                it = np.full(n, inten)
                amp = 0.0
            elif kind == "transition":
                pos = resample_by_arclength(poly, ease_in_out(u, 0.9))
                it = np.full(n, inten)
                amp = 0.035
            else:
                raise ValueError(kind)
            if amp > 0:
                pos = pos + wobble(tt, freqs, phases, amps) * (amp * env[:, None])
            if jitter > 0:
                pos = pos + wobble(tt, jf, jp) * jitter
            ts.append(tt)
            ps.append(pos)
            ins.append(it)
            cols.append(np.repeat(color[None, :], n, 0))
            wds.append(np.full(n, width))
            hds.append(np.full(n, head))
        return Trail(t=np.concatenate(ts), pos=np.vstack(ps), inten=np.concatenate(ins).astype(np.float32),
                     color=np.vstack(cols).astype(np.float32), width=np.concatenate(wds).astype(np.float32),
                     head=np.concatenate(hds).astype(np.float32))


# ----------------------------------------------------------------------------
# build
# ----------------------------------------------------------------------------
def _load_storyboard(sb):
    if isinstance(sb, dict):
        return sb
    with open(sb) as f:
        return json.load(f)


def build_scene(storyboard, glyph_dir: str, look: Look | None = None, seed: int = 3) -> Scene:
    sb = _load_storyboard(storyboard)
    look = look or Look()
    look.fps = int(sb.get("fps", look.fps))
    if "palette" in sb and "haze_hex" in sb["palette"]:
        look.haze_tint = hex_to_linear(sb["palette"]["haze_hex"])
    rng = np.random.default_rng(seed)

    opening = float(sb.get("opening_seconds", 6.0))
    ending = float(sb.get("ending_seconds", 8.5))

    # ---- place glyphs
    shots: list[Shot] = []
    for s in sb["shots"]:
        strokes, metres = load_glyph(os.path.join(glyph_dir, f"{s['glyph']}.json"))
        mn, mx = glyph_bbox(strokes)
        if s.get("height_m"):
            hh = float(mx[1] - mn[1])
            if hh < 1e-6 * max(1.0, float(np.max(mx - mn))):
                raise ValueError(f"shot {s['id']}: cannot scale a zero-height glyph by height_m")
            scale = s["height_m"] / hh
        else:
            scale = 1.0
        placed = place_glyph(strokes, s.get("pos", [0, 0, 0]), s.get("rot_y", 0.0), scale, s.get("base_y", 0.0))
        mn, mx = glyph_bbox(placed)
        R = rot_y(s.get("rot_y", 0.0))
        shots.append(Shot(
            id=s["id"], strokes=placed, center=(mn + mx) / 2, extent=float(np.max(mx - mn)), height=float(mx[1] - mn[1]),
            normal=normalize(R @ np.array([0, 0, 1.0])), right=normalize(R @ np.array([1.0, 0, 0])),
            color=hex_to_linear(s.get("color_hex", "#ffffff")), caption=s.get("caption", ""),
            era_label=s.get("era_label", ""), draw_seconds=float(s["draw_seconds"]),
            hold_seconds=float(s["hold_seconds"]), cam=s.get("cam", {}) or {},
            first_point=placed[0].pts[0], last_point=placed[-1].pts[-1]))

    # ---- the path
    seg = _Segments()
    p_start = shots[0].first_point + shots[0].normal * 0.6 + UP * 0.2
    pulses = [1.75]
    seg.add([p_start], opening - 1.0, "hover", shots[0].color, 0.02, 0.5, 0.95)
    seg.add(_bezier(p_start, p_start + UP * 0.25, shots[0].first_point), 1.0, "lift", shots[0].color, 0.06, 0.55, 0.85)
    bloom_step_t = 1e9

    for k, sh in enumerate(shots):
        sh.t_start = seg.t
        lifts = []
        for i in range(len(sh.strokes) - 1):
            a, b = sh.strokes[i].pts[-1], sh.strokes[i + 1].pts[0]
            dist = float(np.linalg.norm(b - a))
            ctrl = (a + b) / 2 + sh.normal * (0.08 + 0.16 * dist) + UP * (0.03 + 0.08 * dist)
            lifts.append((_bezier(a, ctrl, b), float(np.clip(dist / 2.8, 0.1, 0.6)), float(0.05 * np.clip(0.35 / max(dist, 1e-3), 0.4, 1.0))))
        lengths = [polyline_length(st.pts) / max(st.speed, 0.05) for st in sh.strokes]
        lift_total = sum(l[1] for l in lifts)
        pause_total = sum(st.pause_after for st in sh.strokes)
        stroke_time = max(sh.draw_seconds - lift_total - pause_total, 0.3 * len(sh.strokes))
        total_len = max(sum(lengths), 1e-6)
        raw = [max(0.26, stroke_time * L / total_len) for L in lengths]
        scale_t = stroke_time / sum(raw)
        for i, st in enumerate(sh.strokes):
            col = st.color if st.color is not None else sh.color
            seg.add(st.pts, raw[i] * scale_t, "stroke", col, st.intensity, 1.0, 1.0, jitter=st.jitter)
            if st.pause_after > 0:
                sh.pause_times.append(seg.t)
                if sh.id == "first_fire" and bloom_step_t > 1e8:
                    bloom_step_t = seg.t
                seg.add([st.pts[-1]], st.pause_after, "still", col, 0.0, 1.0, 1.25)
            if i < len(lifts):
                seg.add(lifts[i][0], lifts[i][1], "lift", sh.color, lifts[i][2], 0.55, 0.72)
        sh.t_draw_end = seg.t
        if k + 1 < len(shots):
            pause = min(0.6, sh.hold_seconds * 0.3)
            seg.add([sh.last_point], pause, "hover", sh.color, 0.03, 0.5, 0.9)
            sh.t_leave = seg.t
            nx = shots[k + 1]
            a, b = sh.last_point, nx.first_point
            dist = float(np.linalg.norm(b - a))
            c1 = a + sh.normal * (0.5 + 0.1 * dist) + UP * (0.45 + 0.1 * dist)
            c2 = b + nx.normal * (0.5 + 0.1 * dist) + UP * (0.35 + 0.1 * dist)
            col = 0.5 * (sh.color + nx.color)
            seg.add(_bezier(a, c1, c2, b, n=48), max(sh.hold_seconds - pause, 0.8), "transition", col, 0.09, 0.6, 0.8)
            sh.t_next = seg.t
        else:
            # the star: the light stops dead and hangs
            seg.add([sh.last_point], sh.hold_seconds, "still", sh.color, 0.0, 1.0, 1.0)
            sh.t_leave = sh.t_draw_end
            sh.t_next = seg.t
    ending_start = seg.t
    last = shots[-1]
    pulses.append(ending_start + ending * 0.5)
    seg.add([last.last_point], ending + 0.5, "still", last.color, 0.0, 1.0, 1.0)
    duration = ending_start + ending
    trail = seg.sample(rng)

    # ---- camera track
    fps = look.fps
    F = int(round(duration * fps))
    centers = np.array([sh.center for sh in shots])
    centroid_all = centers.mean(0)
    radius_all = float(np.max(np.linalg.norm(centers - centroid_all, axis=1))) + max(sh.extent for sh in shots) * 0.5
    hfov = 2 * math.atan(math.tan(math.radians(look.vfov_deg) / 2) * look.width / look.height)
    dist_all = radius_all / math.tan(hfov / 2) * 1.08 + 1.5
    mean_n = normalize(sum(sh.normal for sh in shots))
    # look back down the line from beyond its end, from the camera side, high
    line_dir = normalize(shots[-1].center - shots[0].center)
    end_dir = normalize(line_dir * 0.75 + mean_n * 0.55 + UP * 0.55)
    end_pos = centroid_all + end_dir * dist_all
    end_tgt = centroid_all + UP * 0.8
    # fit the end pose to the projected content (the sphere fit ignores perspective asymmetry)
    allpts = np.vstack([st.pts for sh in shots for st in sh.strokes])
    for _ in range(5):
        camf = Camera(end_pos, end_tgt, 1.0)
        fsx, fsy, _fz = camf.project(allpts, look)
        bw = (fsx.max() - fsx.min()) / (0.80 * look.width)
        bh = (fsy.max() - fsy.min()) / (0.70 * look.height)
        cx = (fsx.max() + fsx.min()) / 2 - look.width / 2
        cy = (fsy.max() + fsy.min()) / 2 - look.height / 2
        dcen = float(np.linalg.norm(end_tgt - end_pos))
        end_tgt = end_pos + camf.fwd * dcen + camf.right * (cx / look.focal_px * dcen) - camf.up * (cy / look.focal_px * dcen)
        end_pos = end_tgt + (end_pos - end_tgt) * max(bw, bh, 0.6)
    end_pos[1] = max(end_pos[1], 1.0)

    def lerp(a, b, u):
        return a + (b - a) * u

    def shot_state(sh: Shot, u: float):
        """camera pose for shot `sh` at progress u in [0,1] (over draw + hold)."""
        c = sh.cam
        ue = float(smoothstep(0, 1, u))
        az = math.radians(lerp(c.get("az0", -28.0), c.get("az1", 22.0), u))
        elev = math.radians(lerp(c.get("elev0", c.get("elev", 7.5)), c.get("elev1", c.get("elev", 7.5)), ue))
        auto_d = max(3.8, sh.extent * 2.3 + 2.3)
        dist = lerp(c.get("dist0") or c.get("dist") or auto_d, c.get("dist1") or c.get("dist") or auto_d, ue)
        ty_auto = sh.center[1] - sh.height * 0.28
        ty = lerp(c.get("ty0") if c.get("ty0") is not None else ty_auto, c.get("ty1") if c.get("ty1") is not None else ty_auto, ue)
        d = (math.cos(elev) * (math.cos(az) * sh.normal + math.sin(az) * sh.right) + math.sin(elev) * UP)
        tgt = sh.center.copy()
        tgt[1] = ty
        pos = tgt + d * dist
        pos[1] = max(pos[1], 0.35)
        return pos, tgt

    def desired(t):
        o_sh = shots[0]
        if t < shots[0].t_start:
            u = t / max(shots[0].t_start, 1e-6)
            tgt = p_start.copy()
            tgt[1] = p_start[1] * 0.55
            az = math.radians(-40 + 20 * u)
            d = math.cos(math.radians(5)) * (math.cos(az) * o_sh.normal + math.sin(az) * o_sh.right) + math.sin(math.radians(5)) * UP
            pos = tgt + d * (4.2 - 0.9 * u)
            pos[1] = max(pos[1], 0.5)
            if u > 0.72:
                w = smoothstep(0.72, 1.0, u)
                p1, t1 = shot_state(o_sh, 0.0)
                pos, tgt = pos * (1 - w) + p1 * w, tgt * (1 - w) + t1 * w
            return pos, tgt, 0.5
        if t >= ending_start:
            u = (t - ending_start) / ending
            w = smoothstep(0.0, 0.8, u)
            p0, t0 = shot_state(last, 1.0)
            return p0 * (1 - w) + end_pos * w, t0 * (1 - w) + end_tgt * w, 0.3 * (1 - w)
        for k, sh in enumerate(shots):
            if t < sh.t_next:
                dur = max(sh.t_next - sh.t_start, 1e-6)
                u = (t - sh.t_start) / dur
                pos, tgt = shot_state(sh, u)
                head_blend = sh.cam.get("lead", 0.3)
                if k + 1 < len(shots) and t >= sh.t_leave:
                    w = smoothstep(sh.t_leave, sh.t_next, t)
                    nx = shots[k + 1]
                    p1, t1 = shot_state(nx, 0.0)
                    pos, tgt = pos * (1 - w) + p1 * w, tgt * (1 - w) + t1 * w
                    head_blend = head_blend + 0.3 * math.sin(math.pi * w)
                return pos, tgt, head_blend
        return shot_state(last, 1.0)[0], last.center, 0.3

    frame_t = (np.arange(F) + 0.5) / fps
    head_idx = np.clip(np.searchsorted(trail.t, frame_t, side="right") - 1, 0, len(trail.t) - 1)
    head_pos = trail.pos[head_idx]

    tau_pos, tau_tgt, tau_head = 0.6, 0.45, 0.3
    dt = 1.0 / fps
    ap, at, ah = 1 - math.exp(-dt / tau_pos), 1 - math.exp(-dt / tau_tgt), 1 - math.exp(-dt / tau_head)
    cam_pos = np.zeros((F, 3))
    cam_tgt = np.zeros((F, 3))
    cam_focus = np.zeros(F)
    p1 = p2 = t1 = t2 = None
    hs1 = hs2 = head_pos[0].copy()
    for i in range(F):
        t = frame_t[i]
        # look ahead by each cascade's group delay so the causal smoothing does not lag the subject
        dp, _, _ = desired(min(t + 2 * tau_pos, duration - 1e-3))
        _, dtg, hb = desired(min(t + 2 * tau_tgt, duration - 1e-3))
        hs1 = hs1 + (head_pos[min(i + int(round(2 * tau_head * fps)), F - 1)] - hs1) * ah
        hs2 = hs2 + (hs1 - hs2) * ah
        dtg = dtg * (1 - hb) + hs2 * hb
        if p1 is None:
            p1, p2, t1, t2 = dp.copy(), dp.copy(), dtg.copy(), dtg.copy()
        p1 = p1 + (dp - p1) * ap
        p2 = p2 + (p1 - p2) * ap
        t1 = t1 + (dtg - t1) * at
        t2 = t2 + (t1 - t2) * at
        cam_pos[i] = p2
        cam_tgt[i] = t2
        if t < ending_start:
            focus_pt = 0.5 * (hs2 + t2)
        else:
            w = smoothstep(ending_start, ending_start + ending * 0.6, t)
            focus_pt = hs2 * (1 - w) + end_tgt * w
        cam_focus[i] = max(0.5, float(np.linalg.norm(focus_pt - p2)))
    cam_pos[:, 1] = np.maximum(cam_pos[:, 1], 0.3)

    # ---- captions
    W, H = look.width, look.height
    texts = []
    title = sb.get("title", "")
    if title:
        texts.append(("title", [dict(text=title.upper(), size=92, weight="Light", tracking=18.0, x=W / 2, y=H / 2 - 110, anchor="center")],
                      2.0, opening - 0.2, 1.3, 1.0, (0.93, 0.95, 1.0)))
        if sb.get("subtitle"):
            texts.append(("subtitle", [dict(text=sb["subtitle"], size=25, weight="Light", tracking=6.5, x=W / 2, y=H / 2 + 26, anchor="center")],
                          2.9, opening - 0.2, 1.3, 1.0, (0.62, 0.66, 0.74)))
    for sh in shots:
        t0 = sh.t_start + 1.0
        t1 = sh.t_next - 0.2
        if sh.era_label:
            texts.append((f"era:{sh.id}", [dict(text=sh.era_label.upper(), size=20, weight="Medium", tracking=4.6, x=150, y=H - 208)],
                          t0, t1, 0.9, 0.8, (0.55, 0.60, 0.68)))
        if sh.caption:
            texts.append((f"cap:{sh.id}", [dict(text=sh.caption, size=36, weight="Light", tracking=0.8, x=150, y=H - 172)],
                          t0 + 0.25, t1, 0.9, 0.8, (0.90, 0.92, 0.96)))
    end_card = sb.get("end_card", "")
    if end_card:
        lines = end_card.split("\n")
        te0 = ending_start + ending * 0.5
        texts.append(("end1", [dict(text=lines[0].upper(), size=46, weight="Light", tracking=14.0, x=W / 2, y=H / 2 - 52, anchor="center")],
                      te0, duration - 0.5, 1.4, 1.0, (0.93, 0.95, 1.0)))
        if len(lines) > 1:
            texts.append(("end2", [dict(text=lines[1], size=22, weight="Light", tracking=5.0, x=W / 2, y=H / 2 + 24, anchor="center")],
                          te0 + 0.6, duration - 0.5, 1.4, 1.0, (0.60, 0.64, 0.72)))

    hb = sb.get("haze_beta", [0.07, 0.022])
    return Scene(look=look, trail=trail, shots=shots, duration=duration, cam_pos=cam_pos, cam_tgt=cam_tgt,
                 cam_focus=cam_focus, texts=texts, opening=opening, ending_start=ending_start, title=title,
                 haze_beta=(float(hb[0]), float(hb[1])), bloom_step_t=bloom_step_t, pulses=tuple(pulses))


def auto_layout(sb: dict, glyph_dir: str, turn_deg: float = -9.0, gap_m: float = 3.2) -> dict:
    """assign pos/rot_y to shots along a gently curving walk (in place, returns sb).
    per-shot optional: "side_m" (offset toward the camera side), "yaw_deg" (extra facing rotation),
    "gap_m" (distance from the previous drawing's centre, overrides the automatic spacing)."""
    theta = 0.0
    p = np.zeros(3)
    prev_e = None
    for s in sb["shots"]:
        strokes, metres = load_glyph(os.path.join(glyph_dir, f"{s['glyph']}.json"))
        mn, mx = glyph_bbox(strokes)
        scale = (s["height_m"] / max(mx[1] - mn[1], 1e-6)) if s.get("height_m") else 1.0
        e = float(np.max((mx - mn) * scale))
        heading = np.array([math.sin(math.radians(theta)), 0, math.cos(math.radians(theta))])
        if prev_e is not None:
            step = s.get("gap_m") or ((prev_e + e) * 0.5 + gap_m)
            p = p + heading * step
            theta += turn_deg
            heading = np.array([math.sin(math.radians(theta)), 0, math.cos(math.radians(theta))])
        side = np.array([-math.cos(math.radians(theta)), 0, math.sin(math.radians(theta))])  # camera side
        q = p + side * float(s.get("side_m", 0.0))
        s["pos"] = [round(float(v), 3) for v in q]
        s["rot_y"] = round(theta - 90.0 + float(s.get("yaw_deg", 0.0)), 2)
        prev_e = e
    return sb
