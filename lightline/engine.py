"""
lightline.engine — a software "long-exposure" renderer for a single point of light
that draws a continuous trail hanging in 3D space above a wet floor.

Everything is emissive and additive, so the renderer never needs occlusion:
  1. the light path is sampled at high temporal resolution (path.py builds it),
  2. per frame, every trail segment emitted so far is projected, subdivided to
     sub-pixel spacing in screen space and splatted into depth slices,
  3. every slice is blurred by its own radius (line width + depth-of-field + haze),
  4. the same is done for the mirrored path (wet floor), with an anisotropic
     blur, ripples, puddle masks and a Fresnel-ish falloff,
  5. the floor itself is lit by the head of the light and the trail,
  6. bloom, atmospheric haze, filmic tone-mapping, vignette, chromatic
     aberration, grain and typographic captions finish the frame.

Coordinates: world is right-handed, y up, floor at y = 0, metres.
"""
from __future__ import annotations

import math
import os
from dataclasses import dataclass, field

import cv2
import numpy as np

cv2.setNumThreads(1)  # we parallelise over frames, not inside one frame

UP = np.array([0.0, 1.0, 0.0], np.float64)


# ----------------------------------------------------------------------------
# small helpers
# ----------------------------------------------------------------------------
def hex_to_linear(h: str) -> np.ndarray:
    h = h.lstrip("#")
    srgb = np.array([int(h[i : i + 2], 16) / 255.0 for i in (0, 2, 4)], np.float64)
    lin = np.where(srgb <= 0.04045, srgb / 12.92, ((srgb + 0.055) / 1.055) ** 2.4)
    return lin.astype(np.float32)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def ease_in_out(u, a=0.55):
    """blend of linear and smoothstep: natural hand-drawn pen speed."""
    return (1.0 - a) * u + a * smoothstep(0.0, 1.0, u)


def rot_y(deg: float) -> np.ndarray:
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return np.array([[c, 0.0, s], [0.0, 1.0, 0.0], [-s, 0.0, c]], np.float64)


def normalize(v):
    n = np.linalg.norm(v)
    return v / n if n > 1e-12 else v


def catmull_rom(points: np.ndarray, subdiv: int = 8, closed: bool = False) -> np.ndarray:
    """Smooth polyline through control points (3D). Keeps endpoints."""
    p = np.asarray(points, np.float64)
    if len(p) < 3:
        return p
    if closed:
        p = np.vstack([p, p[:1]])
        pad = np.vstack([p[-2:-1], p, p[1:2]])
    else:
        pad = np.vstack([2 * p[:1] - p[1:2], p, 2 * p[-1:] - p[-2:-1]])
    out = []
    t = np.linspace(0, 1, subdiv, endpoint=False)[:, None]
    for i in range(1, len(pad) - 2):
        p0, p1, p2, p3 = pad[i - 1], pad[i], pad[i + 1], pad[i + 2]
        a = 2 * p1
        b = p2 - p0
        c = 2 * p0 - 5 * p1 + 4 * p2 - p3
        d = -p0 + 3 * p1 - 3 * p2 + p3
        out.append(0.5 * (a + b * t + c * t * t + d * t * t * t))
    out.append(pad[-2:-1])
    return np.vstack(out)


def polyline_length(p: np.ndarray) -> float:
    if len(p) < 2:
        return 0.0
    return float(np.sum(np.linalg.norm(np.diff(p, axis=0), axis=1)))


def resample_by_arclength(p: np.ndarray, u: np.ndarray) -> np.ndarray:
    """points at normalised arc-length positions u in [0,1]."""
    seg = np.linalg.norm(np.diff(p, axis=0), axis=1)
    cum = np.concatenate([[0.0], np.cumsum(seg)])
    total = cum[-1]
    if total <= 1e-9:
        return np.repeat(p[:1], len(u), axis=0)
    s = np.clip(u, 0, 1) * total
    idx = np.clip(np.searchsorted(cum, s, side="right") - 1, 0, len(seg) - 1)
    f = (s - cum[idx]) / np.maximum(seg[idx], 1e-9)
    return p[idx] + (p[idx + 1] - p[idx]) * f[:, None]


def value_noise_2d(h, w, cells, rng, octaves=3, persistence=0.5):
    """tileable-ish smooth noise in [0,1] built from upsampled random grids."""
    acc = np.zeros((h, w), np.float32)
    amp, norm = 1.0, 0.0
    for o in range(octaves):
        c = int(cells * (2 ** o))
        g = rng.random((c, c)).astype(np.float32)
        g = np.pad(g, ((0, 1), (0, 1)), mode="wrap")  # tileable
        up = cv2.resize(g, (w + 1, h + 1), interpolation=cv2.INTER_CUBIC)[:h, :w]
        acc += amp * up
        norm += amp
        amp *= persistence
    acc /= norm
    acc = (acc - acc.min()) / max(acc.max() - acc.min(), 1e-6)
    return acc


def gaussian_blur(img: np.ndarray, sx: float, sy: float | None = None) -> np.ndarray:
    """Gaussian blur with automatic downsampling for large radii (energy preserving)."""
    if sy is None:
        sy = sx
    m = max(sx, sy)
    if m < 0.3:
        return img
    if m <= 7.0:
        return cv2.GaussianBlur(img, (0, 0), sigmaX=max(sx, 0.3), sigmaY=max(sy, 0.3))
    f = 2 if m <= 16 else (4 if m <= 40 else 8)
    h, w = img.shape[:2]
    small = cv2.resize(img, (max(1, w // f), max(1, h // f)), interpolation=cv2.INTER_AREA)
    small = cv2.GaussianBlur(small, (0, 0), sigmaX=max(sx / f, 0.3), sigmaY=max(sy / f, 0.3))
    return cv2.resize(small, (w, h), interpolation=cv2.INTER_LINEAR)


def aces_tonemap(x):
    a, b, c, d, e = 2.51, 0.03, 2.43, 0.59, 0.14
    return np.clip((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0)


def linear_to_srgb(x):
    x = np.clip(x, 0.0, 1.0)
    return np.where(x <= 0.0031308, 12.92 * x, 1.055 * np.power(x, 1 / 2.4) - 0.055)


# ----------------------------------------------------------------------------
# look parameters
# ----------------------------------------------------------------------------
@dataclass
class Look:
    width: int = 1920
    height: int = 1080
    fps: int = 24
    vfov_deg: float = 36.0
    line_width_m: float = 0.014      # physical width of the light trail
    line_gain: float = 0.80         # energy per pixel of trail length at unit intensity
    dof_aperture: float = 0.10       # metres; bigger = softer out-of-focus trails
    dof_max_px: float = 46.0
    haze_beta: float = 0.028         # extinction per metre
    haze_gain: float = 0.42          # how much scattered light comes back as glow
    haze_sigma_px: float = 150.0
    haze_tint: np.ndarray = field(default_factory=lambda: hex_to_linear("#8fb6d9"))
    fog_floor: float = 0.0016        # faint ambient fog brightness (linear)
    reflect_base: float = 0.58
    reflect_fresnel: float = 0.40
    reflect_height_falloff: float = 4.5   # metres: reflections of high points fade faster
    reflect_aniso: float = 1.7            # vertical blur multiplier for the wet floor
    reflect_blur_add: float = 1.6
    ripple_px: float = 7.0
    floor_albedo: np.ndarray = field(default_factory=lambda: np.array([0.052, 0.056, 0.062], np.float32))
    floor_gain: float = 0.40
    head_core: float = 9.0
    head_halo: float = 2.4
    head_glow: float = 1.2
    bloom: float = 0.55
    exposure: float = 1.0
    vignette: float = 0.42
    chroma_px: float = 1.4
    grain: float = 0.011
    settle_floor: float = 0.72       # fraction of brightness an old trail settles to
    settle_tau: float = 1.6
    near: float = 0.08
    slice_edges: np.ndarray = field(default_factory=lambda: np.geomspace(0.22, 220.0, 20))

    @property
    def focal_px(self) -> float:
        return (self.height / 2.0) / math.tan(math.radians(self.vfov_deg) / 2.0)


# ----------------------------------------------------------------------------
# camera
# ----------------------------------------------------------------------------
@dataclass
class Camera:
    pos: np.ndarray
    target: np.ndarray
    focus: float
    fwd: np.ndarray = field(init=False)
    right: np.ndarray = field(init=False)
    up: np.ndarray = field(init=False)

    def __post_init__(self):
        self.fwd = normalize(self.target - self.pos)
        r = np.cross(self.fwd, UP)
        if np.linalg.norm(r) < 1e-6:
            r = np.array([1.0, 0.0, 0.0])
        self.right = normalize(r)
        self.up = normalize(np.cross(self.right, self.fwd))

    def project(self, P: np.ndarray, look: Look):
        v = P - self.pos
        x = v @ self.right
        y = v @ self.up
        z = v @ self.fwd
        fl = look.focal_px
        zs = np.where(np.abs(z) < 1e-6, 1e-6, z)
        sx = look.width / 2.0 + fl * x / zs
        sy = look.height / 2.0 - fl * y / zs
        return sx, sy, z

    def horizon_y(self, look: Look) -> float:
        """screen y of the floor-plane horizon (camera has no roll)."""
        # direction with zero world-y: solve fwd.y + u*up.y = 0 (ignoring right, which has y=0)
        if abs(self.up[1]) < 1e-6:
            return -1e9
        u = -self.fwd[1] / self.up[1]
        return look.height / 2.0 - look.focal_px * u


# ----------------------------------------------------------------------------
# the trail: arrays produced by path.py
# ----------------------------------------------------------------------------
@dataclass
class Trail:
    t: np.ndarray          # (N,) emission time, seconds, increasing
    pos: np.ndarray        # (N,3)
    inten: np.ndarray      # (N,)
    color: np.ndarray      # (N,3) linear rgb
    width: np.ndarray      # (N,) multiplier on Look.line_width_m
    head: np.ndarray       # (N,) brightness of the point of light itself


# ----------------------------------------------------------------------------
# the renderer
# ----------------------------------------------------------------------------
class Renderer:
    def __init__(self, look: Look, seed: int = 7):
        self.look = look
        self.seed = seed
        self.rng = np.random.default_rng(seed)
        H, W = look.height, look.width
        self.H, self.W = H, W
        # procedural textures for the floor and ripples
        self.tex_wet = value_noise_2d(512, 512, 5, self.rng, octaves=3)        # puddle pattern, tile = 9 m
        self.tex_grain = value_noise_2d(512, 512, 24, self.rng, octaves=2)     # floor micro texture
        self.ripples = [value_noise_2d(H // 8 + 2, W // 8 + 2, 14, self.rng, octaves=3) * 2 - 1 for _ in range(4)]
        # screen grids
        self.px = (np.arange(W, dtype=np.float32) - W / 2.0)
        self.py = (np.arange(H, dtype=np.float32) - H / 2.0)
        yy, xx = np.meshgrid(self.py, self.px, indexing="ij")
        self.xx, self.yy = xx, yy
        rr = np.sqrt((xx / (W / 2.0)) ** 2 + (yy / (H / 2.0)) ** 2)
        self.vignette = (1.0 - look.vignette * np.clip(rr / 1.42, 0, 1) ** 2.2).astype(np.float32)
        self._text_cache = {}

    # ---------------------------------------------------------------- geometry
    def _floor_hits(self, cam: Camera, scale: int):
        """per-pixel floor intersection at reduced resolution. returns (hit, x, z, dist)."""
        look = self.look
        H, W = self.H // scale, self.W // scale
        fl = look.focal_px / scale
        px = (np.arange(W, dtype=np.float32) + 0.5 - W / 2.0) / fl
        py = -(np.arange(H, dtype=np.float32) + 0.5 - H / 2.0) / fl
        PX, PY = np.meshgrid(px, py)
        d = (cam.fwd[None, None, :] + PX[..., None] * cam.right[None, None, :] + PY[..., None] * cam.up[None, None, :]).astype(np.float32)
        dy = d[..., 1]
        hit = dy < -1e-4
        s = np.where(hit, -cam.pos[1] / np.where(hit, dy, -1.0), 0.0).astype(np.float32)
        x = cam.pos[0] + s * d[..., 0]
        z = cam.pos[2] + s * d[..., 2]
        dist = s * np.linalg.norm(d, axis=-1)
        return hit, x, z, dist

    def _sample_tex(self, tex, x, z, tile_m):
        n = tex.shape[0]
        u = ((x / tile_m) % 1.0) * (n - 1)
        v = ((z / tile_m) % 1.0) * (n - 1)
        return cv2.remap(tex, u.astype(np.float32), v.astype(np.float32), cv2.INTER_LINEAR, borderMode=cv2.BORDER_WRAP)

    # ---------------------------------------------------------------- splatting
    def _subsample_segments(self, sx, sy, z, energy, margin=260.0, step=0.7, nmax=160):
        """turn consecutive projected samples into dense sub-pixel points.
        returns X, Y, Z, E(3) arrays (may be empty)."""
        look = self.look
        W, H = self.W, self.H
        N = len(sx)
        if N < 2:
            return None
        x0, y0, z0 = sx[:-1], sy[:-1], z[:-1]
        x1, y1, z1 = sx[1:], sy[1:], z[1:]
        valid = (z0 > look.near) & (z1 > look.near)
        # bbox overlap with the (expanded) screen
        valid &= (np.maximum(x0, x1) > -margin) & (np.minimum(x0, x1) < W + margin)
        valid &= (np.maximum(y0, y1) > -margin) & (np.minimum(y0, y1) < H + margin)
        dx, dy = x1 - x0, y1 - y0
        L = np.hypot(dx, dy)
        valid &= L < 3000.0
        idx_seg = np.nonzero(valid)[0]
        if len(idx_seg) == 0:
            return None
        L = L[idx_seg]
        n = np.clip(np.ceil(L / step), 1, nmax).astype(np.int64)
        tot = int(n.sum())
        rep = np.repeat(np.arange(len(idx_seg)), n)
        starts = np.cumsum(n) - n
        offs = np.arange(tot) - np.repeat(starts, n)
        f = ((offs + 0.5) / n[rep]).astype(np.float32)
        seg = idx_seg[rep]
        X = x0[seg] + f * dx[seg]
        Y = y0[seg] + f * dy[seg]
        Z = z0[seg] * (1 - f) + z1[seg] * f
        e0, e1 = energy[seg], energy[seg + 1]
        E = (e0 * (1 - f)[:, None] + e1 * f[:, None]) * (L[rep] / n[rep])[:, None]
        keep = (X > -margin) & (X < W + margin) & (Y > -margin) & (Y < H + margin)
        return X[keep], Y[keep], Z[keep], E[keep]

    def _splat_bilinear(self, X, Y, E, factor=1, pad=(0, 0)):
        """accumulate RGB energies at float positions into a ((H+2py)/f x (W+2px)/f x 3) buffer.
        Pixel-centre convention (sample at continuous X lands at the same place for every factor);
        energy density is preserved when downsampling."""
        px, py = pad
        H, W = (self.H + 2 * py) // factor, (self.W + 2 * px) // factor
        X = (X + px) / factor - 0.5
        Y = (Y + py) / factor - 0.5
        if factor > 1:
            E = E / float(factor * factor)
        xf = np.floor(X)
        yf = np.floor(Y)
        fx = (X - xf).astype(np.float32)
        fy = (Y - yf).astype(np.float32)
        xi = xf.astype(np.int64)
        yi = yf.astype(np.int64)
        idx_all, w_all, e_all = [], [], []
        for ox, oy, w in ((0, 0, (1 - fx) * (1 - fy)), (1, 0, fx * (1 - fy)), (0, 1, (1 - fx) * fy), (1, 1, fx * fy)):
            xs, ys = xi + ox, yi + oy
            m = (xs >= 0) & (xs < W) & (ys >= 0) & (ys < H)
            if not m.any():
                continue
            idx_all.append(ys[m] * W + xs[m])
            w_all.append(w[m])
            e_all.append(E[m])
        out = np.zeros((3, H, W), np.float32)
        if idx_all:
            idx = np.concatenate(idx_all)
            w = np.concatenate(w_all)
            e = np.concatenate(e_all)
            for c in range(3):
                out[c] = np.bincount(idx, weights=e[:, c] * w, minlength=H * W).reshape(H, W)
        return np.ascontiguousarray(out.transpose(1, 2, 0))

    def _sigma_for_depth(self, Z, focus):
        """per-sample blur sigma (px): line core width (+) depth-of-field circle of confusion."""
        look = self.look
        fl = look.focal_px
        Zc = np.maximum(Z, look.near)
        w_px = look.line_width_m * fl / Zc
        core = np.maximum(0.55, 0.42 * w_px)
        coc = np.minimum(look.dof_max_px, look.dof_aperture * fl * np.abs(Zc - focus) / (Zc * max(focus, 0.3)))
        return np.sqrt(core * core + coc * coc).astype(np.float32)

    HPAD = 33  # haze-buffer padding in 1/8-res pixels (= ceil(260 / 8), the subsample margin)

    def _render_pass(self, sx, sy, z, energy, cam: Camera, reflection: bool):
        """project+splat+blur, bucketed by blur radius. returns (direct HxWx3, haze_small padded hxwx3)."""
        look = self.look
        H, W = self.H, self.W
        hs, ws = H // 8, W // 8
        hp = self.HPAD
        direct = np.zeros((H, W, 3), np.float32)
        haze_small = np.zeros((hs + 2 * hp, ws + 2 * hp, 3), np.float32)
        sub = self._subsample_segments(sx, sy, z, energy)
        if sub is None:
            return direct, haze_small
        X, Y, Z, E = sub
        T = np.exp(-look.haze_beta * Z).astype(np.float32)
        # scattered part -> padded low-res haze buffer (one splat); off-frame samples keep their place
        Eh = E * (1.0 - T)[:, None]
        xs = np.floor(X / 8.0).astype(np.int64) + hp
        ys = np.floor(Y / 8.0).astype(np.int64) + hp
        m = (xs >= 0) & (xs < ws + 2 * hp) & (ys >= 0) & (ys < hs + 2 * hp)
        flat = ys[m] * (ws + 2 * hp) + xs[m]
        for c in range(3):
            haze_small[..., c] = np.bincount(flat, weights=Eh[m, c], minlength=(hs + 2 * hp) * (ws + 2 * hp)).reshape(hs + 2 * hp, ws + 2 * hp)
        # direct part, bucketed by sigma
        Ed = E * T[:, None]
        sig = self._sigma_for_depth(Z, cam.focus)
        edges = np.geomspace(0.5, 64.0, 26)
        bid = np.searchsorted(edges, sig)
        for b in np.unique(bid):
            mb = bid == b
            s_b = float(np.exp(np.mean(np.log(sig[mb]))))
            f = 1 if s_b < 2.6 else (2 if s_b < 7.0 else (4 if s_b < 20.0 else 8))
            if reflection:
                sx_b, sy_b = s_b + 1.0, s_b * look.reflect_aniso + look.reflect_blur_add
            else:
                sx_b, sy_b = s_b, s_b
            px = int(min(260, math.ceil(3.0 * sx_b)) + f - 1) // f * f
            py = int(min(260, math.ceil(3.0 * sy_b)) + f - 1) // f * f
            buf = self._splat_bilinear(X[mb], Y[mb], Ed[mb], factor=f, pad=(px, py))
            bl = cv2.GaussianBlur(buf, (0, 0), sigmaX=max(sx_b / f, 0.3), sigmaY=max(sy_b / f, 0.3), borderType=cv2.BORDER_CONSTANT)
            if f > 1:
                bl = cv2.resize(bl, (W + 2 * px, H + 2 * py), interpolation=cv2.INTER_LINEAR)
            direct += bl[py:py + H, px:px + W]
        return direct, haze_small

    # ---------------------------------------------------------------- head light
    def _add_head(self, img, sx, sy, z, color, strength, cam: Camera, mult=1.0, aniso=1.0):
        look = self.look
        if z <= look.near:
            return
        H, W = self.H, self.W
        fl = look.focal_px
        d = max(z, 0.2)
        scale = min(3.0, 2.0 / math.sqrt(d / 3.0))  # closer = bigger glow, gently
        for sig, gain in ((1.7 * scale, look.head_core), (9.0 * scale, look.head_halo), (46.0 * scale, look.head_glow)):
            sig_y = sig * aniso
            r = int(3.2 * max(sig, sig_y)) + 1
            x0, x1 = int(sx) - r, int(sx) + r + 1
            y0, y1 = int(sy) - r, int(sy) + r + 1
            if x1 <= 0 or y1 <= 0 or x0 >= W or y0 >= H:
                continue
            cx0, cx1 = max(x0, 0), min(x1, W)
            cy0, cy1 = max(y0, 0), min(y1, H)
            gx = np.exp(-0.5 * ((np.arange(cx0, cx1) + 0.5 - sx) / sig) ** 2).astype(np.float32)
            gy = np.exp(-0.5 * ((np.arange(cy0, cy1) + 0.5 - sy) / sig_y) ** 2).astype(np.float32)
            k = gy[:, None] * gx[None, :] * (gain * strength * mult / (2 * math.pi * sig * sig_y))
            img[cy0:cy1, cx0:cx1] += k[..., None] * (0.55 * color + 0.45)[None, None, :]

    # ---------------------------------------------------------------- floor
    def _floor_light(self, cam: Camera, lights_pos, lights_rgb, head_pos, head_rgb, head_w):
        """diffuse lighting of the wet floor by the head and (subsampled) trail. low-res."""
        look = self.look
        scale = 8
        hit, x, z, dist = self._floor_hits(cam, scale)
        h, w = hit.shape
        P = np.stack([x, np.zeros_like(x), z], -1).reshape(-1, 3).astype(np.float32)
        L = np.vstack([lights_pos, head_pos[None, :]]).astype(np.float32)
        C = np.vstack([lights_rgb, head_rgb[None, :] * head_w]).astype(np.float32)
        hy = np.maximum(L[:, 1], 0.03)
        # |P-L|^2 = |P|^2 + |L|^2 - 2 P.L  (no 3-D temporaries)
        acc = np.zeros((P.shape[0], 3), np.float32)
        p2 = np.einsum("ij,ij->i", P, P)
        step = 128
        for i in range(0, len(L), step):
            Li = L[i : i + step]
            r2 = p2[:, None] + np.einsum("ij,ij->i", Li, Li)[None, :] - 2.0 * (P @ Li.T) + 0.02
            irr = hy[None, i : i + step] / (r2 * np.sqrt(r2))
            acc += irr @ C[i : i + step]
        out = acc.reshape(h, w, 3) * hit[..., None]
        out *= np.exp(-look.haze_beta * dist)[..., None]
        return cv2.resize(out, (self.W, self.H), interpolation=cv2.INTER_LINEAR)

    # ---------------------------------------------------------------- captions
    def _text_mask(self, key, lines):
        """lines: list of dict(text, size, weight, tracking, x, y, anchor). cached alpha mask."""
        if key in self._text_cache:
            return self._text_cache[key]
        from PIL import Image, ImageDraw, ImageFont

        H, W = self.H, self.W
        img = Image.new("L", (W, H), 0)
        draw = ImageDraw.Draw(img)
        fontdir = "/usr/share/fonts/opentype/inter"
        for ln in lines:
            weight = ln.get("weight", "Light")
            try:
                font = ImageFont.truetype(os.path.join(fontdir, f"Inter-{weight}.otf"), ln["size"])
            except OSError:
                font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", ln["size"])
            text = ln["text"]
            tr = ln.get("tracking", 0.0)
            widths = [font.getlength(ch) + tr for ch in text]
            total = sum(widths) - tr
            anchor = ln.get("anchor", "left")
            x = ln["x"] - (total / 2 if anchor == "center" else 0)
            y = ln["y"]
            for ch, wch in zip(text, widths):
                draw.text((x, y), ch, fill=255, font=font)
                x += wch
        mask = np.asarray(img, np.float32) / 255.0
        glow = gaussian_blur(mask, 6.0) * 0.35 + gaussian_blur(mask, 1.2) * 0.25
        res = (mask, glow)
        self._text_cache[key] = res
        return res

    # ---------------------------------------------------------------- frame
    def render(self, trail: Trail, t: float, cam: Camera, texts=(), fade=1.0, head_color=None, trail_gain=1.0, head_gain=1.0, frame=None):
        """render one frame at time t. texts: iterable of (key, lines, alpha, rgb). frame seeds the grain."""
        look = self.look
        H, W = self.H, self.W
        fl = look.focal_px

        # ---- trail samples emitted so far
        n = int(np.searchsorted(trail.t, t, side="right"))
        head_strength = float(trail.head[n - 1]) * head_gain if n > 0 else 0.0
        img = np.zeros((H, W, 3), np.float32)
        hpad = self.HPAD
        haze_small = np.zeros((H // 8 + 2 * hpad, W // 8 + 2 * hpad, 3), np.float32)
        refl = np.zeros((H, W, 3), np.float32)
        head_pos = trail.pos[max(n - 1, 0)] if n > 0 else None
        head_rgb = (trail.color[max(n - 1, 0)] if head_color is None else head_color) if n > 0 else None

        if n >= 2:
            P = trail.pos[:n]
            age = t - trail.t[:n]
            settle = look.settle_floor + (1 - look.settle_floor) * np.exp(-age / look.settle_tau)
            headglow = 1.0 + 1.8 * np.exp(-age / 0.07)
            inten = trail.inten[:n] * settle * headglow * trail_gain
            sx, sy, z = cam.project(P, look)
            w_px = np.maximum(trail.width[:n] * look.line_width_m * fl / np.maximum(z, look.near), 1.0)
            energy = (trail.color[:n] * (inten * look.line_gain * w_px)[:, None]).astype(np.float32)
            d_img, d_haze = self._render_pass(sx, sy, z, energy, cam, reflection=False)
            img += d_img
            haze_small += d_haze

            # ---- wet floor reflection (mirror about y=0)
            Pm = P.copy()
            Pm[:, 1] = -Pm[:, 1]
            sxm, sym, zm = cam.project(Pm, look)
            vdir = Pm - cam.pos[None, :]
            vlen = np.linalg.norm(vdir, axis=1) + 1e-6
            cos_t = np.abs(vdir[:, 1]) / vlen
            fres = look.reflect_base + look.reflect_fresnel * (1 - cos_t) ** 3
            hfall = np.exp(-np.maximum(P[:, 1], 0) / look.reflect_height_falloff)
            energy_m = energy * (fres * hfall)[:, None]
            r_img, r_haze = self._render_pass(sxm, sym, zm, energy_m, cam, reflection=True)
            refl += r_img
            haze_small += r_haze * 0.5

        # ---- head light (and its reflection)
        if head_pos is not None:
            hp = head_pos[None, :]
            sx, sy, z = cam.project(hp, look)
            flick = 1.0 + 0.07 * math.sin(t * 37.1) * math.sin(t * 23.7 + 1.0)
            self._add_head(img, float(sx[0]), float(sy[0]), float(z[0]), head_rgb, head_strength * flick, cam)
            hpm = hp.copy()
            hpm[0, 1] = -hpm[0, 1]
            sxm, sym, zm = cam.project(hpm, look)
            vd = hpm[0] - cam.pos
            cos_t = abs(vd[1]) / (np.linalg.norm(vd) + 1e-6)
            fres = look.reflect_base + look.reflect_fresnel * (1 - cos_t) ** 3
            self._add_head(refl, float(sxm[0]), float(sym[0]), float(zm[0]), head_rgb, head_strength * flick, cam,
                           mult=fres * math.exp(-max(head_pos[1], 0) / look.reflect_height_falloff), aniso=2.2)

        # ---- floor: hits, puddles, texture, diffuse light
        hit, fx, fz, fdist = self._floor_hits(cam, 2)
        wet = self._sample_tex(self.tex_wet, fx, fz, 9.0)
        wet = smoothstep(0.30, 0.68, wet) * 0.75 + 0.25
        grain = self._sample_tex(self.tex_grain, fx, fz, 3.0)
        texture = 0.75 + 0.5 * grain
        T_floor = np.exp(-look.haze_beta * fdist)
        hitf = hit.astype(np.float32)
        # upsample to full res
        wet_f = cv2.resize(wet * hitf, (W, H), interpolation=cv2.INTER_LINEAR)
        tex_f = cv2.resize(texture, (W, H), interpolation=cv2.INTER_LINEAR)
        mask_f = cv2.resize(gaussian_blur(hitf, 1.5), (W, H), interpolation=cv2.INTER_LINEAR)
        Tf = cv2.resize(T_floor * hitf, (W, H), interpolation=cv2.INTER_LINEAR)

        if head_pos is not None and n >= 2:
            # lights along the trail: cap ~360, weight by intensity & spacing
            stride = max(1, len(trail.t) // 300)  # constant over the film: no light-set churn between frames
            Ls = trail.pos[:n:stride]
            Li = (trail.inten[:n:stride] * stride * 0.0035 * trail_gain)[:, None] * trail.color[:n:stride]
            floor_lit = self._floor_light(cam, Ls, Li, head_pos, head_rgb, head_strength * 0.45)
            diffuse = floor_lit * look.floor_albedo[None, None, :] * look.floor_gain * (tex_f * (1.0 - 0.45 * wet_f))[..., None]
            img += diffuse

        # ---- ripple-distorted reflection
        if n >= 1:
            hy = cam.horizon_y(look)
            below = np.clip((self.yy + H / 2.0 - hy) / H, 0, 1)
            amp = (look.ripple_px * below + 0.6).astype(np.float32)
            w = [math.cos(t * 0.37), math.sin(t * 0.37), math.cos(t * 0.23 + 1.3), math.sin(t * 0.23 + 1.3)]
            nx = w[0] * self.ripples[0] + w[1] * self.ripples[1]
            ny = w[2] * self.ripples[2] + w[3] * self.ripples[3]
            nx = cv2.resize(nx, (W, H), interpolation=cv2.INTER_LINEAR)
            ny = cv2.resize(ny, (W, H), interpolation=cv2.INTER_LINEAR)
            map_x = (self.xx + W / 2.0 + nx * amp * 0.5).astype(np.float32)
            map_y = (self.yy + H / 2.0 + ny * amp).astype(np.float32)
            refl = cv2.remap(refl, map_x, map_y, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
            img += refl * (wet_f * mask_f)[..., None]

        # ---- haze: scattered light comes back as a wide soft glow + faint fog gradient
        hz = gaussian_blur(haze_small, look.haze_sigma_px / 8.0)[hpad:-hpad, hpad:-hpad]
        hz = cv2.resize(hz, (W, H), interpolation=cv2.INTER_LINEAR)
        lum = hz @ np.array([0.2126, 0.7152, 0.0722], np.float32)
        img += (0.5 * hz + 0.5 * lum[..., None] * look.haze_tint[None, None, :]) * look.haze_gain
        hy = cam.horizon_y(look)
        band = np.exp(-((self.py + H / 2.0 - hy) / (0.5 * H)) ** 2).astype(np.float32)
        img += (look.fog_floor * (0.45 + 0.55 * band))[:, None, None] * look.haze_tint[None, None, :]

        # ---- bloom pyramid
        bloom = np.zeros_like(img)
        src = img
        weights = (0.30, 0.26, 0.22, 0.18, 0.14)
        for k, wgt in enumerate(weights):
            src = cv2.resize(src, (max(8, src.shape[1] // 2), max(8, src.shape[0] // 2)), interpolation=cv2.INTER_AREA)
            src = cv2.GaussianBlur(src, (0, 0), 2.2)
            bloom += cv2.resize(src, (W, H), interpolation=cv2.INTER_LINEAR) * wgt
        img = img + bloom * look.bloom

        # ---- grade
        img *= look.exposure
        # gentle split tone: cool shadows, warm highlights
        l = img @ np.array([0.2126, 0.7152, 0.0722], np.float32)
        img += (np.clip(l, 0, 0.02) * 0.6)[..., None] * np.array([-0.15, 0.0, 0.25], np.float32)[None, None, :]
        img = aces_tonemap(img)
        img = linear_to_srgb(img)
        img *= self.vignette[..., None]
        # chromatic aberration: scale R out, B in, about the centre
        if look.chroma_px > 0:
            s = look.chroma_px / (W / 2.0)
            for c, sc in ((0, 1 + s), (2, 1 - s)):
                M = np.array([[sc, 0, W / 2.0 * (1 - sc)], [0, sc, H / 2.0 * (1 - sc)]], np.float32)
                img[..., c] = cv2.warpAffine(img[..., c], M, (W, H), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT)
        # film grain, stronger in the shadows, also dithers the 8-bit gradients
        grng = self.rng if frame is None else np.random.default_rng([self.seed, int(frame)])
        g = grng.standard_normal((H, W)).astype(np.float32)
        lumi = img @ np.array([0.2126, 0.7152, 0.0722], np.float32)
        img += (g * look.grain * (1.0 - 0.6 * lumi))[..., None]
        if fade < 1.0:
            img *= fade

        # ---- captions (screen-space, display-referred)
        for key, lines, alpha, rgb in texts:
            if alpha <= 0.001:
                continue
            mask, glow = self._text_mask(key, lines)
            col = np.asarray(rgb, np.float32)
            img = img + (mask * alpha)[..., None] * col[None, None, :] * (1 - img) + (glow * alpha)[..., None] * col[None, None, :] * 0.6

        out = np.clip(img * 255.0 + 0.5, 0, 255).astype(np.uint8)
        return out[..., ::-1]  # BGR for cv2.imwrite
