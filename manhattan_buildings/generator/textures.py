"""Procedural facade / surface textures for the Manhattan building kit.

Every facade texture is a grid of CX x CY "cells". One cell = one structural
bay (horizontally) by one storey (vertically).  The geometry builder maps UVs
so that each wall shows a whole number of bays and storeys, which keeps
windows aligned with floors and corners on every building.

Each cell gets small random variations (blinds, lit rooms, curtains) so the
repeating pattern is not obvious.

Run directly to (re)generate all PNGs:  python3 textures.py <out_dir>
"""
import json
import os
import sys

import numpy as np
from PIL import Image

C = 96  # pixels per cell


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------
def rgb(c):
    return np.array(c, dtype=np.float32) / 255.0


def seamless_noise(h, w, rng, cell=16, amp=1.0):
    """Tileable value noise in [-amp, amp]."""
    gh, gw = max(1, h // cell), max(1, w // cell)
    g = rng.uniform(-1, 1, (gh, gw)).astype(np.float32)
    big = np.tile(g, (3, 3))
    im = Image.fromarray(((big + 1) * 127.5).astype(np.uint8))
    im = im.resize((gw * 3 * cell, gh * 3 * cell), Image.BICUBIC)
    a = np.asarray(im, dtype=np.float32) / 127.5 - 1
    a = a[gh * cell:gh * cell + h, gw * cell:gw * cell + w]
    return a * amp


def fine_noise(h, w, rng, amp):
    return rng.normal(0, amp, (h, w, 1)).astype(np.float32)


def wall_surface(kind, color, H, W, rng, mortar=None):
    col = rgb(color)
    img = np.ones((H, W, 3), np.float32) * col
    if kind == "brick":
        ch, bl = 6, 16
        mort = rgb(mortar) if mortar else np.clip(col * 1.35 + 0.08, 0, 1)
        for row in range(H // ch):
            y0 = row * ch
            off = (row % 2) * (bl // 2)
            for x in range(-bl, W + bl, bl):
                xa, xb = max(0, x + off), min(W, x + off + bl)
                if xa >= xb:
                    continue
                j = rng.normal(0, 0.07)
                tint = np.array([1 + j, 1 + j * 0.8, 1 + j * 0.6])
                img[y0:y0 + ch, xa:xb] *= tint
                img[y0:y0 + ch, xa] = mort
            img[y0, :] = mort
        img += fine_noise(H, W, rng, 0.02)
    elif kind == "stone":
        ch, bl = 24, 48
        mort = np.clip(col * 0.82, 0, 1)
        for row in range(H // ch):
            y0 = row * ch
            off = (row % 2) * (bl // 2)
            for x in range(-bl, W + bl, bl):
                xa, xb = max(0, x + off), min(W, x + off + bl)
                if xa >= xb:
                    continue
                img[y0:y0 + ch, xa:xb] *= 1 + rng.normal(0, 0.03)
                img[y0:y0 + ch, xa] = mort
            img[y0, :] = mort
        img *= 1 + seamless_noise(H, W, rng, 24, 0.05)[..., None]
        img += fine_noise(H, W, rng, 0.015)
    elif kind == "concrete":
        img *= 1 + seamless_noise(H, W, rng, 32, 0.06)[..., None]
        img *= 1 + seamless_noise(H, W, rng, 8, 0.03)[..., None]
        img += fine_noise(H, W, rng, 0.02)
    elif kind == "panel":
        img *= 1 + seamless_noise(H, W, rng, 48, 0.03)[..., None]
        for x in range(0, W, C // 2):
            img[:, x] *= 0.8
        for y in range(0, H, C):
            img[y, :] *= 0.8
    else:  # smooth / painted / stucco
        img *= 1 + seamless_noise(H, W, rng, 32, 0.04)[..., None]
        img += fine_noise(H, W, rng, 0.012)
    return img


def glass_pixels(h, w, rng, glass, refl=0.25, interior=True):
    """A single window pane area with sky reflection + interior variety."""
    g = rgb(glass)
    t = np.linspace(1, 0, h)[:, None, None]
    pane = g * (1 - refl) + (g * 0.5 + 0.5) * refl * t
    pane = np.broadcast_to(pane, (h, w, 3)).copy()
    # diagonal streak reflection
    yy, xx = np.mgrid[0:h, 0:w]
    streak = ((xx + yy * 0.6) / max(1, w) * 3 + rng.uniform(0, 6)) % 3
    pane += (streak < 0.35)[..., None] * 0.06
    if interior:
        r = rng.random()
        if r < 0.10:  # lit room
            pane = pane * 0.3 + rgb((255, 214, 140)) * 0.7
        elif r < 0.45:  # blinds pulled partway down
            bh = int(h * rng.uniform(0.2, 0.8))
            bc = rgb(rng.choice([(225, 220, 205), (240, 238, 230), (200, 190, 170)]))
            pane[:bh] = pane[:bh] * 0.25 + bc * 0.75
            pane[:bh:3] *= 0.92
        elif r < 0.55:  # curtains
            cw = max(1, int(w * 0.28))
            cc = rgb(rng.choice([(150, 40, 40), (60, 80, 120), (200, 170, 110), (90, 110, 70)]))
            pane[:, :cw] = pane[:, :cw] * 0.3 + cc * 0.7
            pane[:, -cw:] = pane[:, -cw:] * 0.3 + cc * 0.7
        else:
            pane *= rng.uniform(0.8, 1.05)
    return pane


def draw_window(img, x0, y0, x1, y1, rng, glass, frame, panes=(1, 2), arch=False,
                fw=2, wall=None):
    """Draw a framed window in pixel rect (y from top)."""
    h, w = y1 - y0, x1 - x0
    if h <= 2 or w <= 2:
        return
    img[y0:y1, x0:x1] = rgb(frame)
    img[y0 + fw:y1 - fw, x0 + fw:x1 - fw] = glass_pixels(h - 2 * fw, w - 2 * fw, rng, glass)
    pc, pr = panes
    for i in range(1, pc):
        x = x0 + int(w * i / pc)
        img[y0:y1, x - 1:x + 1] = rgb(frame)
    for j in range(1, pr):
        y = y0 + int(h * j / pr)
        img[y - 1:y + 1, x0:x1] = rgb(frame)
    if arch and wall is not None:
        r = w / 2.0
        cx = x0 + r
        for yy in range(y0, min(y1, int(y0 + r))):
            dy = (y0 + r) - yy
            half = np.sqrt(max(0.0, r * r - dy * dy))
            xa, xb = int(cx - half), int(np.ceil(cx + half))
            img[yy, x0:max(x0, xa)] = wall[yy, x0:max(x0, xa)]
            img[yy, min(x1, xb):x1] = wall[yy, min(x1, xb):x1]


# --------------------------------------------------------------------------
# texture families
# --------------------------------------------------------------------------
def tex_windows(spec, rng):
    cx, cy = spec.get("cx", 4), spec.get("cy", 4)
    H, W = cy * C, cx * C
    img = wall_surface(spec.get("wall", "brick"), spec["color"], H, W, rng, spec.get("mortar"))
    base = img.copy()
    wx0, wx1, wy0, wy1 = spec.get("win", (0.22, 0.78, 0.15, 0.82))
    glass = spec.get("glass", (45, 60, 75))
    frame = spec.get("frame", (230, 228, 220))
    pier = spec.get("pier")
    band = spec.get("band")
    nwin = spec.get("nwin", 1)
    for j in range(cy):
        top = j * C
        if band:  # horizontal belt course at floor line
            bc, bh = band
            img[top + C - bh:top + C] = rgb(bc) * (1 + fine_noise(bh, W, rng, 0.02))
        for i in range(cx):
            left = i * C
            if pier:  # vertical piers between bays (art deco etc.)
                pc, pw = pier
                img[top:top + C, left:left + pw // 2] = rgb(pc)
                img[top:top + C, left + C - pw // 2:left + C] = rgb(pc) * 0.92
            for k in range(nwin):
                sub = (wx1 - wx0) / nwin
                gap = 0.04 if nwin > 1 else 0
                a = wx0 + k * sub + gap / 2
                b = wx0 + (k + 1) * sub - gap / 2
                x0, x1 = left + int(a * C), left + int(b * C)
                y0, y1 = top + int((1 - wy1) * C), top + int((1 - wy0) * C)
                if spec.get("spandrel"):
                    img[y1:top + C, x0:x1] = rgb(spec["spandrel"])
                    img[top:y0, x0:x1] = rgb(spec["spandrel"])
                if spec.get("lintel"):
                    img[max(top, y0 - 5):y0, x0 - 3:x1 + 3] = rgb(spec["lintel"])
                if spec.get("sill", True):
                    sc = rgb(spec.get("sill_color") or spec.get("lintel") or (205, 200, 190))
                    img[y1:min(top + C, y1 + 3), x0 - 3:x1 + 3] = sc
                if spec.get("shutters"):
                    sw = max(3, (x1 - x0) // 3)
                    img[y0:y1, max(left, x0 - sw):x0] = rgb(spec["shutters"])
                    img[y0:y1, x1:min(left + C, x1 + sw)] = rgb(spec["shutters"])
                draw_window(img, x0, y0, x1, y1, rng, glass, frame, spec.get("panes", (1, 2)),
                            spec.get("arch", False), spec.get("fw", 2), base)
    return img


def tex_curtain(spec, rng):
    """Glass curtain wall: continuous glass, mullions, optional spandrel band."""
    cx, cy = spec.get("cx", 4), spec.get("cy", 4)
    H, W = cy * C, cx * C
    g = rgb(spec["glass"])
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    sky = seamless_noise(H, W, rng, 64, 1.0)
    img = g[None, None, :] * (0.85 + 0.25 * sky[..., None])
    img += (((xx / W + yy / H * 0.7) * 2) % 1 < 0.12)[..., None] * 0.05
    mull = rgb(spec.get("mullion", (170, 175, 180)))
    sp = spec.get("spandrel")
    spf = spec.get("spandrel_h", 0.22)
    vm = spec.get("vmull", 2)
    for j in range(cy):
        top = j * C
        for i in range(cx):
            left = i * C
            r = rng.random()
            cell = img[top:top + C, left:left + C]
            if r < 0.06:
                cell[:] = cell * 0.55 + rgb((250, 220, 160)) * 0.45
            elif r < 0.25:
                cell[:] = cell * 0.88
            if sp:
                img[top + C - int(C * spf):top + C, left:left + C] = rgb(sp) * (1 + rng.normal(0, 0.02))
            for k in range(vm):
                x = left + int(C * k / vm)
                img[top:top + C, x:x + 2] = mull
        img[top:top + 2, :] = mull
        if sp:
            img[top + C - int(C * spf):top + C - int(C * spf) + 1, :] = mull
    return img


def tex_ribbon(spec, rng):
    """Horizontal strip windows on a concrete / panel facade."""
    cx, cy = spec.get("cx", 4), spec.get("cy", 4)
    H, W = cy * C, cx * C
    img = wall_surface(spec.get("wall", "concrete"), spec["color"], H, W, rng)
    y0f, y1f = spec.get("band", (0.25, 0.85))
    mull = rgb(spec.get("mullion", (60, 62, 65)))
    for j in range(cy):
        top = j * C
        y0, y1 = top + int((1 - y1f) * C), top + int((1 - y0f) * C)
        for i in range(cx):
            left = i * C
            img[y0:y1, left:left + C] = glass_pixels(y1 - y0, C, rng, spec.get("glass", (60, 75, 90)), 0.35)
            for k in range(spec.get("vmull", 3)):
                x = left + int(C * k / spec.get("vmull", 3))
                img[y0:y1, x:x + 2] = mull
        img[y0:y0 + 2, :] = mull
        img[y1 - 2:y1, :] = mull
    return img


def tex_store(spec, rng):
    """Ground-floor storefront strip: cx bays x 1 storey."""
    cx = spec.get("cx", 4)
    H, W = C, cx * C
    img = wall_surface(spec.get("wall", "smooth"), spec["color"], H, W, rng)
    signs = spec.get("signs", [(170, 30, 30), (30, 70, 140), (30, 110, 60), (40, 40, 40),
                               (200, 150, 30), (120, 30, 90), (230, 230, 225)])
    glass = spec.get("glass", (55, 70, 80))
    frame = spec.get("frame", (40, 40, 42))
    for i in range(cx):
        left = i * C
        # sign band
        if spec.get("sign", True):
            sc = rgb(signs[rng.integers(len(signs))])
            img[6:22, left + 4:left + C - 4] = sc
            # fake lettering
            lc = np.array([1, 1, 1]) if sc.mean() < 0.6 else np.array([0.1, 0.1, 0.1])
            n = rng.integers(4, 9)
            lw = (C - 20) // n
            for k in range(n):
                if rng.random() < 0.85:
                    xa = left + 10 + k * lw
                    img[10:18, xa:xa + lw - 3] = lc
        # glass
        gy0 = 26
        door = rng.random() < 0.45 or i == 0
        img[gy0:H - 6, left + 4:left + C - 4] = rgb(frame)
        img[gy0 + 2:H - 8, left + 6:left + C - 6] = glass_pixels(H - 8 - gy0 - 2, C - 12, rng, glass, 0.3)
        if spec.get("goods", True):  # displays inside
            for k in range(rng.integers(2, 6)):
                gx = left + int(rng.uniform(8, C - 20))
                gh = int(rng.uniform(6, 22))
                img[H - 8 - gh:H - 8, gx:gx + int(rng.uniform(4, 12))] = rgb(
                    rng.integers(40, 230, 3))
        if door:
            dx = left + int(C * 0.62)
            img[gy0 + 2:H - 6, dx:dx + 2] = rgb(frame)
            img[gy0 + 2:H - 6, dx + 20:dx + 22] = rgb(frame)
        img[H - 6:H, left:left + C] = rgb(spec.get("base", (60, 58, 55)))
    return img


def tex_plain(spec, rng):
    S = spec.get("size", 256)
    return wall_surface(spec.get("wall", "smooth"), spec["color"], S, S, rng, spec.get("mortar"))


def tex_corrugated(spec, rng):
    S = 256
    img = np.ones((S, S, 3), np.float32) * rgb(spec["color"])
    x = np.arange(S)
    img *= (0.85 + 0.15 * np.sin(x / S * np.pi * 2 * 32))[None, :, None]
    img *= 1 + seamless_noise(S, S, rng, 32, 0.08)[..., None]
    return img


def tex_garage(spec, rng):
    """Open parking garage level: slab edge + dark openings with cars."""
    cx, cy = 4, 4
    H, W = cy * C, cx * C
    img = wall_surface("concrete", spec["color"], H, W, rng)
    for j in range(cy):
        top = j * C
        oy0, oy1 = top + 8, top + C - 34
        for i in range(cx):
            left = i * C
            img[oy0:oy1, left + 8:left + C] = rgb((28, 28, 30))
            # car silhouettes
            if rng.random() < 0.7:
                cc = rgb(rng.integers(30, 220, 3))
                img[oy1 - 18:oy1, left + 20:left + 70] = cc
                img[oy1 - 26:oy1 - 18, left + 30:left + 60] = cc * 0.7
        img[oy1:oy1 + 26, :] = rgb(spec["color"]) * 1.05  # parapet
    return img


def tex_garage_door(spec, rng):
    S = 128
    img = np.ones((S, S, 3), np.float32) * rgb(spec["color"])
    for y in range(0, S, 12):
        img[y:y + 2] *= 0.7
    img[:, :4] *= 0.6
    img[:, -4:] *= 0.6
    img[20:40, 16:S - 16] = glass_pixels(20, S - 32, rng, (60, 70, 80), interior=False)
    return img


FAMILIES = {
    "windows": tex_windows,
    "curtain": tex_curtain,
    "ribbon": tex_ribbon,
    "store": tex_store,
    "plain": tex_plain,
    "corrugated": tex_corrugated,
    "garage": tex_garage,
    "garagedoor": tex_garage_door,
}


def generate(specs, out_dir, seed=7):
    os.makedirs(out_dir, exist_ok=True)
    for i, (name, spec) in enumerate(sorted(specs.items())):
        fam = spec.get("family")
        if not fam:
            continue
        rng = np.random.default_rng(seed + i * 101)
        img = FAMILIES[fam](spec, rng)
        img = (np.clip(img, 0, 1) * 255).astype(np.uint8)
        Image.fromarray(img).save(os.path.join(out_dir, name + ".png"))


if __name__ == "__main__":
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from materials import MATERIALS
    generate(MATERIALS, sys.argv[1])
    print("generated", sum(1 for s in MATERIALS.values() if s.get("family")), "textures")
