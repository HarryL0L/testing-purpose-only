"""PBR texture generator for the HD (realistic) building kit.

Every surface material gets three tileable maps:
  <name>_col.png   base colour (sRGB)
  <name>_nrm.png   tangent-space normal map (OpenGL / glTF convention, +Y up)
  <name>_rgh.png   roughness (linear grey)
plus window / storefront / sign "atlases" (4x4 cells) that give every
window a different interior.

All noise is generated periodically (FFT filtering), so maps tile seamlessly.
usage: python3 hd_textures.py <out_dir>
"""
import os
import sys

import numpy as np
from PIL import Image

N = 1024  # surface map resolution
CELL = 256  # atlas cell resolution (4x4 cells -> 1024)


# =================================================================== noise
def pnoise(h, w, rng, scale, octaves=1, persistence=0.5):
    """Periodic band-limited noise in roughly [-1, 1]. scale = feature size (px)."""
    out = np.zeros((h, w), np.float32)
    amp, tot = 1.0, 0.0
    fy = np.fft.fftfreq(h)[:, None]
    fx = np.fft.fftfreq(w)[None, :]
    f = np.sqrt(fx * fx + fy * fy)
    for o in range(octaves):
        s = scale / (2 ** o)
        filt = np.exp(-(f * s) ** 2 * 4.0)
        wn = rng.standard_normal((h, w))
        n = np.real(np.fft.ifft2(np.fft.fft2(wn) * filt)).astype(np.float32)
        n /= (n.std() + 1e-6)
        out += n * amp
        tot += amp
        amp *= persistence
    return out / tot / 2.0


def rgb(c):
    return np.array(c, np.float32) / 255.0


def normal_from_height(h, strength):
    dx = (np.roll(h, -1, 1) - np.roll(h, 1, 1)) * 0.5
    dy = (np.roll(h, -1, 0) - np.roll(h, 1, 0)) * 0.5
    nx = -dx * strength
    ny = dy * strength  # image rows go down, +Y (green) is up
    nz = np.ones_like(h)
    l = np.sqrt(nx * nx + ny * ny + nz * nz)
    return np.stack([nx / l, ny / l, nz / l], -1) * 0.5 + 0.5


def box_blur(a, r):
    if r <= 0:
        return a
    out = a.copy()
    for ax in (0, 1):
        acc = np.zeros_like(out)
        for k in range(-r, r + 1):
            acc += np.roll(out, k, ax)
        out = acc / (2 * r + 1)
    return out


def save(img, path, mode="RGB"):
    a = np.clip(img, 0, 1)
    a = (a * 255 + 0.5).astype(np.uint8)
    if mode == "L":
        Image.fromarray(a, "L").save(path)
    else:
        Image.fromarray(a, "RGB").save(path)


# =================================================================== surfaces
def masonry(rng, unit_w, unit_h, mortar, base, var, mortar_col, bond="running", height_relief=1.0,
            rough=(0.85, 0.95), soot=0.25, header_dark=0.0, block_noise=0.08, h=N, w=N):
    """Generic masonry: bricks / ashlar blocks with recessed joints."""
    rows = int(round(h / unit_h))
    cols = int(round(w / unit_w))
    uh, uw = h / rows, w / cols
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    row = np.floor(yy / uh).astype(int)
    off = np.where(row % 2 == 1, 0.5, 0.0) if bond == "running" else 0.0
    if bond == "stack":
        off = 0.0
    u = xx / uw + off
    col = np.floor(u).astype(int) % cols
    fy = yy / uh - row
    fx = u - np.floor(u)
    mj = mortar / uh
    mjx = mortar / uw
    joint = (fy < mj) | (fx < mjx)
    # per unit random values
    ids = row * 977 + col
    rv = np.random.default_rng(int(rng.integers(1e9))).random((rows * 977 + cols + 2, 4)).astype(np.float32)
    r = rv[ids]
    b = rgb(base)
    jitter = (r[..., 0:1] - 0.5) * 2 * var
    tint = 1 + jitter * np.array([1.0, 0.85, 0.7], np.float32)
    colr = b * tint
    if header_dark > 0:
        dark = (r[..., 1] < 0.12)[..., None]
        colr = np.where(dark, colr * (1 - header_dark), colr)
    big = pnoise(h, w, rng, 220, 3)
    fine = pnoise(h, w, rng, 6, 2)
    colr *= (1 + big[..., None] * 0.10 + fine[..., None] * block_noise)
    # soot / weathering: darker streaks from top
    streak = pnoise(h, w, rng, 40, 2)
    streak = box_blur(streak, 0)
    colr *= (1 - soot * np.clip(streak * 0.6 + big * 0.5, 0, 1)[..., None] * 0.35)
    mc = rgb(mortar_col) * (1 + fine[..., None] * 0.08)
    colr = np.where(joint[..., None], mc, colr)
    # height: units raised, joints recessed, chamfered edges
    edge = np.minimum.reduce([fy - mj, 1 - fy, (fx - mjx) * uw / uh, (1 - fx) * uw / uh])
    hgt = np.clip(edge * uh / 4.0, 0, 1) * height_relief
    hgt = np.where(joint, 0.0, hgt) + fine * 0.15 + big * 0.05
    rough_map = np.where(joint, rough[1], rough[0]) + fine * 0.04
    return colr, hgt, rough_map


def brick(rng, base, mortar_col=(150, 143, 132), var=0.12, soot=0.3, header_dark=0.25):
    # 1.2192 m tile: 6 bricks x 16 courses
    return masonry(rng, N / 6, N / 16, 7, base, var, mortar_col, header_dark=header_dark, soot=soot)


def ashlar(rng, base, var=0.04, unit=(N / 2, N / 4), joint=3, rough=(0.7, 0.85), soot=0.2):
    return masonry(rng, unit[0], unit[1], joint, base, var, np.array(base) * 0.82, height_relief=0.4,
                   rough=rough, soot=soot, block_noise=0.05)


def rusticated(rng, base):
    c, h, r = masonry(rng, N / 2, N / 4, 4, base, 0.04, np.array(base) * 0.6, height_relief=1.0,
                      rough=(0.75, 0.9), soot=0.25, block_noise=0.05)
    # deep horizontal channels
    yy = np.arange(N)[:, None] % (N / 4)
    ch = (yy < 26).astype(np.float32)
    h = h * (1 - ch) + ch * (0.0 + 0.3 * (yy < 13))
    c = c * (1 - ch[..., None] * 0.35)
    return c, h, r


def terracotta(rng, base):
    return masonry(rng, N / 4, N / 8, 4, base, 0.05, np.array(base) * 0.88, height_relief=0.5,
                   rough=(0.38, 0.8), soot=0.15, block_noise=0.04)


def granite(rng, base, polished=True, panel=N / 2):
    c, h, r = masonry(rng, panel, panel, 3, base, 0.03, np.array(base) * 0.6, bond="stack",
                      height_relief=0.1, rough=(0.22 if polished else 0.6, 0.8), soot=0.05)
    sp = pnoise(N, N, rng, 1.5, 1)
    sp2 = pnoise(N, N, rng, 2.5, 1)
    c = c * (1 + (sp > 0.55)[..., None] * -0.45) + (sp2 > 0.6)[..., None] * 0.18
    return c, h, r


def concrete(rng, base, boards=False, panels=None):
    h_, w_ = N, N
    big = pnoise(h_, w_, rng, 160, 3)
    fine = pnoise(h_, w_, rng, 3, 2)
    c = rgb(base) * (1 + big[..., None] * 0.12 + fine[..., None] * 0.05)
    # vertical rain streaks
    st = pnoise(h_, w_, rng, 30, 1)
    st = box_blur(np.repeat(st[:1], h_, 0) * 0 + st, 0)
    streaks = np.clip(pnoise(h_, 64, rng, 4, 1), 0, 1)
    streaks = np.array(Image.fromarray((streaks * 255).astype(np.uint8)).resize((w_, h_), Image.BILINEAR),
                       np.float32) / 255
    c *= (1 - streaks[..., None] * 0.18)
    hgt = fine * 0.3 + big * 0.1
    r = 0.88 + fine * 0.05
    if boards:
        yy = np.arange(h_)[:, None].repeat(w_, 1)
        bl = (yy % 64 < 3)
        c = np.where(bl[..., None], c * 0.82, c)
        hgt = hgt - bl * 0.6 + pnoise(h_, w_, rng, 2, 1) * 0.2
        grain = pnoise(h_, w_, rng, 1.5, 1)
        hgt += np.abs(np.sin(np.arange(w_)[None, :] / 9.0 + grain * 3)) * 0.15
    if panels:
        px, py = panels
        yy, xx = np.mgrid[0:h_, 0:w_]
        j = ((xx % px) < 4) | ((yy % py) < 4)
        c = np.where(j[..., None], c * 0.7, c)
        hgt = np.where(j, -0.8, hgt)
    return c, hgt, r


def metal_panel(rng, base, px=N // 2, py=N, brushed=True, rough=0.35):
    big = pnoise(N, N, rng, 200, 2)
    c = rgb(base) * (1 + big[..., None] * 0.05)
    hgt = np.zeros((N, N), np.float32)
    if brushed:
        br = pnoise(N, 1, rng, 1, 1).repeat(N, 1) * 0.3 + pnoise(N, N, rng, 1, 1) * 0.05
        c *= (1 + br[..., None] * 0.08)
    yy, xx = np.mgrid[0:N, 0:N]
    j = ((xx % px) < 5) | ((yy % py) < 5)
    c = np.where(j[..., None], c * 0.45, c)
    hgt = np.where(j, -1.0, hgt + big * 0.05)
    r = np.full((N, N), rough, np.float32) + big * 0.05
    return c, hgt, r


def copper(rng):
    big = pnoise(N, N, rng, 120, 3)
    fine = pnoise(N, N, rng, 3, 2)
    c = rgb((92, 158, 132)) * (1 + big[..., None] * 0.15 + fine[..., None] * 0.06)
    dark = np.clip(big * 1.5, 0, 1)[..., None]
    c = c * (1 - dark) + rgb((70, 110, 90)) * dark
    xx = np.arange(N)[None, :] % (N // 4)
    seam = (xx < 8).repeat(N, 0)
    c = np.where(seam[..., None], c * 0.8, c)
    return c, np.where(seam, 1.0, 0.0) + fine * 0.2, 0.6 + fine * 0.05


def roofing(rng, base, gravel=False):
    big = pnoise(N, N, rng, 150, 3)
    fine = pnoise(N, N, rng, 2, 2)
    c = rgb(base) * (1 + big[..., None] * 0.15 + fine[..., None] * (0.25 if gravel else 0.05))
    h = fine * (1.0 if gravel else 0.2)
    if not gravel:
        yy = np.arange(N)[:, None].repeat(N, 1)
        s = (yy % (N // 3)) < 6
        c = np.where(s[..., None], c * 0.85, c)
        h = np.where(s, h + 0.8, h)
    return c, h, 0.9 + fine * 0.05


def louver(rng, base):
    yy = np.arange(N)[:, None].repeat(N, 1).astype(np.float32)
    p = (yy % 32) / 32
    h = np.where(p < 0.8, p / 0.8, (1 - p) / 0.2) * 1.0
    big = pnoise(N, N, rng, 200, 2)
    c = rgb(base) * (0.75 + 0.35 * (1 - p))[..., None] * (1 + big[..., None] * 0.05)
    return c, h, np.full((N, N), 0.5, np.float32)


def wood_staves(rng):
    xx = np.arange(N)[None, :].repeat(N, 0).astype(np.float32)
    sv = N / 8
    p = (xx % sv) / sv
    stave = np.floor(xx / sv).astype(int)
    rv = np.random.default_rng(int(rng.integers(1e9))).random(16)
    grain = pnoise(N, N, rng, 2, 2)
    big = pnoise(N, N, rng, 120, 2)
    base = rgb((112, 82, 58))
    c = base * (0.75 + 0.5 * rv[stave % 16])[..., None] * (1 + grain[..., None] * 0.12 + big[..., None] * 0.2)
    gap = (p < 0.03)
    c = np.where(gap[..., None], c * 0.35, c)
    h = np.where(gap, 0.0, 0.6) + grain * 0.2
    return c, h, 0.85 + grain * 0.05


def stucco(rng, base):
    fine = pnoise(N, N, rng, 2, 3)
    big = pnoise(N, N, rng, 150, 2)
    c = rgb(base) * (1 + big[..., None] * 0.08 + fine[..., None] * 0.05)
    return c, fine * 0.5, 0.9 + fine * 0.03


def asphalt(rng):
    fine = pnoise(N, N, rng, 1.5, 2)
    big = pnoise(N, N, rng, 200, 3)
    c = rgb((58, 58, 60)) * (1 + fine[..., None] * 0.25 + big[..., None] * 0.15)
    return c, fine * 0.6, 0.85 + fine * 0.05


def sidewalk(rng):
    fine = pnoise(N, N, rng, 2, 2)
    big = pnoise(N, N, rng, 150, 2)
    c = rgb((165, 162, 155)) * (1 + fine[..., None] * 0.08 + big[..., None] * 0.1)
    yy, xx = np.mgrid[0:N, 0:N]
    j = ((xx % (N // 2)) < 4) | ((yy % (N // 2)) < 4)
    c = np.where(j[..., None], c * 0.7, c)
    return c, np.where(j, -1.0, fine * 0.3), 0.85 + fine * 0.04


# name -> (generator, physical tile size in metres, extra material props)
def surface_specs():
    return {
        "brick_red": (lambda r: brick(r, (128, 50, 36)), 1.22, {}),
        "brick_redbrown": (lambda r: brick(r, (120, 58, 42), header_dark=0.3), 1.22, {}),
        "brick_brown": (lambda r: brick(r, (98, 60, 44), (160, 150, 135)), 1.22, {}),
        "brick_tan": (lambda r: brick(r, (188, 152, 112), (205, 195, 178), var=0.08), 1.22, {}),
        "brick_buff": (lambda r: brick(r, (205, 182, 135), (215, 205, 185), var=0.07, header_dark=0.1), 1.22, {}),
        "brick_orange": (lambda r: brick(r, (178, 96, 58)), 1.22, {}),
        "brick_white": (lambda r: brick(r, (222, 218, 208), (200, 196, 188), var=0.03, soot=0.35,
                                        header_dark=0.0), 1.22, {}),
        "brick_dark": (lambda r: brick(r, (72, 48, 40), (120, 112, 105), header_dark=0.2), 1.22, {}),
        "brick_gray": (lambda r: brick(r, (128, 124, 120), (170, 166, 160), var=0.06), 1.22, {}),
        "limestone": (lambda r: ashlar(r, (212, 202, 182)), 2.4, {}),
        "limestone_warm": (lambda r: ashlar(r, (220, 200, 165)), 2.4, {}),
        "limestone_rustic": (lambda r: rusticated(r, (205, 195, 175)), 2.4, {}),
        "brownstone": (lambda r: ashlar(r, (110, 72, 56), var=0.05, soot=0.3), 2.4, {}),
        "terracotta": (lambda r: terracotta(r, (226, 210, 180)), 2.4, {}),
        "terracotta_white": (lambda r: terracotta(r, (232, 230, 222)), 2.4, {}),
        "granite_pink": (lambda r: granite(r, (176, 128, 118)), 3.0, {}),
        "granite_gray": (lambda r: granite(r, (140, 140, 142)), 3.0, {}),
        "granite_black": (lambda r: granite(r, (48, 48, 52)), 3.0, {}),
        "granite_flamed": (lambda r: granite(r, (160, 150, 140), polished=False), 3.0, {}),
        "concrete_precast": (lambda r: concrete(r, (196, 192, 184), panels=(N // 2, N // 2)), 3.0, {}),
        "concrete_board": (lambda r: concrete(r, (150, 148, 142), boards=True), 2.4, {}),
        "concrete": (lambda r: concrete(r, (175, 172, 165)), 4.0, {}),
        "metal_alum": (lambda r: metal_panel(r, (190, 194, 198)), 3.0, {"metallic": 0.8}),
        "metal_bronze": (lambda r: metal_panel(r, (110, 82, 58)), 3.0, {"metallic": 0.8}),
        "metal_black": (lambda r: metal_panel(r, (40, 40, 42), rough=0.4), 3.0, {"metallic": 0.7}),
        "metal_white": (lambda r: metal_panel(r, (228, 228, 224), brushed=False, rough=0.45), 3.0,
                        {"metallic": 0.2}),
        "copper": (copper, 3.0, {"metallic": 0.3}),
        "roof_membrane": (lambda r: roofing(r, (120, 120, 122)), 6.0, {}),
        "roof_gravel": (lambda r: roofing(r, (138, 132, 122), True), 3.0, {}),
        "louver": (lambda r: louver(r, (110, 112, 115)), 1.0, {"metallic": 0.6}),
        "louver_light": (lambda r: louver(r, (190, 190, 186)), 1.0, {"metallic": 0.5}),
        "wood_staves": (wood_staves, 2.0, {}),
        "stucco": (lambda r: stucco(r, (225, 215, 196)), 3.0, {}),
        "asphalt": (asphalt, 8.0, {}),
        "sidewalk": (sidewalk, 3.0, {}),
    }


# =================================================================== atlases
def sky_reflection(h, w, rng, tint=(0.62, 0.72, 0.85), strength=0.3):
    t = np.linspace(1, 0, h)[:, None, None]
    sky = np.array(tint, np.float32) * (0.75 + 0.45 * t)
    yy, xx = np.mgrid[0:h, 0:w]
    streak = (((xx + yy * 0.8) / w * 2.2 + rng.uniform(0, 3)) % 2.2 < 0.25)[..., None] * 0.05
    cloud = pnoise(h, w, rng, 40, 2)[..., None] * 0.06
    return np.broadcast_to(sky, (h, w, 3)) + streak + cloud, strength


def room(rng, kind="res"):
    S = CELL
    img = np.zeros((S, S, 3), np.float32)
    lit = rng.random() < (0.22 if kind == "res" else 0.35)
    wall_cols = [(215, 205, 185), (230, 226, 216), (190, 200, 205), (205, 190, 170), (175, 160, 140),
                 (200, 205, 190)]
    wc = rgb(wall_cols[rng.integers(len(wall_cols))])
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32) / S
    # simple perspective box: back wall, floor, ceiling, side walls
    m = 0.18
    back = (xx > m) & (xx < 1 - m) & (yy > 0.14) & (yy < 0.78)
    img[:] = wc * 0.7
    img[back] = wc
    floor = yy > 0.78 - (np.abs(xx - 0.5) - (0.5 - m)).clip(0) * 0.9
    fc = rgb([(90, 65, 45), (120, 90, 60), (70, 70, 72), (150, 140, 125)][rng.integers(4)])
    img[floor & (yy > 0.6)] = fc
    ceil = yy < 0.14 + (np.abs(xx - 0.5) - (0.5 - m)).clip(0) * 0.4
    img[ceil & (yy < 0.3)] = wc * 0.85
    side = (~back) & (~floor) & (~ceil)
    img[side] = wc * 0.6
    if kind == "office":
        for k in range(3):
            y = 0.03 + k * 0.035
            img[int(y * S):int(y * S) + 3, int(0.25 * S):int(0.75 * S)] = 1.0
        for k in range(rng.integers(2, 5)):
            x = rng.uniform(0.1, 0.8)
            img[int(0.6 * S):int(0.8 * S), int(x * S):int((x + 0.15) * S)] = rgb((120, 125, 130)) * rng.uniform(.7, 1)
    else:
        # furniture silhouettes
        for k in range(rng.integers(1, 4)):
            x = rng.uniform(0.1, 0.75)
            wdt = rng.uniform(0.1, 0.3)
            hgt = rng.uniform(0.1, 0.35)
            fcol = rgb(rng.integers(30, 160, 3))
            img[int((0.8 - hgt) * S):int(0.8 * S), int(x * S):int(min(1, x + wdt) * S)] = fcol
        if rng.random() < 0.5:  # picture frame
            x = rng.uniform(0.3, 0.6)
            img[int(0.3 * S):int(0.45 * S), int(x * S):int((x + 0.14) * S)] = rgb(rng.integers(40, 200, 3))
        if rng.random() < 0.4:  # lamp
            x = rng.uniform(0.2, 0.8)
            img[int(0.4 * S):int(0.5 * S), int(x * S):int((x + 0.06) * S)] = rgb((250, 225, 170))
    shade = 0.16 + 0.10 * rng.random()
    if lit:
        img = img * 0.55 + rgb((255, 214, 150)) * 0.45 * img.mean(-1, keepdims=True) * 2.0
        img *= 1.05
    else:
        img *= shade
    # window treatments
    r = rng.random()
    if r < 0.35:  # blinds
        bh = rng.uniform(0.2, 0.95)
        bc = rgb([(232, 228, 215), (210, 205, 190), (240, 240, 236), (180, 170, 150)][rng.integers(4)])
        n = int(bh * S)
        sl = bc * (0.8 if not lit else 1.0)
        img[:n] = sl * (0.85 + 0.15 * ((np.arange(n) % 6) < 4))[:, None, None]
    elif r < 0.55:  # curtains
        cw = rng.uniform(0.15, 0.35)
        cc = rgb(rng.integers(60, 220, 3)) * (0.6 if not lit else 0.9)
        folds = (0.8 + 0.2 * np.sin(np.arange(S) / 4.0))[None, :, None]
        img[:, :int(cw * S)] = (cc * folds)[:, :int(cw * S)]
        img[:, S - int(cw * S):] = (cc * folds)[:, S - int(cw * S):]
    elif r < 0.65:  # sheer
        img = img * 0.5 + rgb((235, 232, 225)) * 0.5 * (0.6 if not lit else 1.0)
    refl, s = sky_reflection(S, S, rng, tint=(0.42, 0.5, 0.6), strength=0.18 if lit else 0.3)
    img = img * (1 - s) + refl * s
    return img * 0.85


def atlas(rng, fn):
    A = np.zeros((CELL * 4, CELL * 4, 3), np.float32)
    for j in range(4):
        for i in range(4):
            A[j * CELL:(j + 1) * CELL, i * CELL:(i + 1) * CELL] = fn(rng)
    return A


def curtain_cell(rng, tint):
    S = CELL
    refl, _ = sky_reflection(S, S, rng, tint=tint, strength=1)
    img = refl * 0.5
    if rng.random() < 0.6:  # faint interior: ceiling lights + blinds
        for k in range(2):
            y = int((0.06 + 0.05 * k) * S)
            img[y:y + 3, int(0.1 * S):int(0.9 * S)] += 0.25
        if rng.random() < 0.4:
            n = int(rng.uniform(0.2, 0.7) * S)
            img[:n] = img[:n] * 0.6 + 0.25
    return img


def shop_cell(rng):
    S = CELL
    img = np.zeros((S, S, 3), np.float32)
    wc = rgb(rng.integers(150, 250, 3))
    img[:] = wc * 0.9
    img[int(0.82 * S):] = rgb((120, 110, 100))
    for k in range(3):  # shelves with goods
        y = int((0.3 + k * 0.17) * S)
        img[y:y + 4] = rgb((80, 70, 60))
        x = 0
        while x < S:
            gw = int(rng.uniform(6, 18))
            gh = int(rng.uniform(10, 30))
            img[y - gh:y, x:x + gw] = rgb(rng.integers(30, 255, 3))
            x += gw + int(rng.uniform(1, 6))
    for k in range(3):
        x = int(rng.uniform(0.1, 0.9) * S)
        img[2:8, x:x + 30] = 1.0
    img = img * 0.85 + 0.15
    refl, s = sky_reflection(S, S, rng, strength=0.3)
    return img * (1 - s) + refl * s


GLYPHS = None


def sign_cell(rng):
    S = CELL
    img = np.zeros((S, S, 3), np.float32)
    bg = rgb([(20, 40, 90), (150, 25, 30), (20, 90, 50), (25, 25, 28), (230, 230, 225), (200, 140, 20),
              (90, 20, 70), (10, 90, 110)][rng.integers(8)])
    img[:] = bg
    fg = np.array([1, 1, 1], np.float32) if bg.mean() < 0.5 else np.array([0.08, 0.08, 0.1], np.float32)
    if rng.random() < 0.3:
        fg = rgb((235, 190, 70))
    # sign is a horizontal band; the cell holds 4 bands (each 64 px)
    for band in range(4):
        y0 = band * 64
        img[y0:y0 + 64] = bg * (0.9 + 0.2 * rng.random())
        img[y0:y0 + 3] = img[y0 + 61:y0 + 64] = fg * 0.6
        n = rng.integers(5, 11)
        lw = int((S - 30) / n)
        x = 15
        for k in range(n):
            if rng.random() < 0.88:
                th = 4
                gx0, gy0, gx1, gy1 = x, y0 + 18, x + lw - 6, y0 + 46
                pat = rng.integers(0, 6)
                img[gy0:gy1, gx0:gx0 + th] = fg if pat != 3 else img[gy0:gy1, gx0:gx0 + th]
                if pat in (0, 1, 4):
                    img[gy0:gy0 + th, gx0:gx1] = fg
                if pat in (0, 2, 4, 5):
                    img[gy1 - th:gy1, gx0:gx1] = fg
                if pat in (1, 2, 3, 4):
                    img[gy0:gy1, gx1 - th:gx1] = fg
                if pat in (0, 3, 5):
                    img[(gy0 + gy1) // 2 - 2:(gy0 + gy1) // 2 + 2, gx0:gx1] = fg
            x += lw
    return img


def generate(out):
    os.makedirs(out, exist_ok=True)
    specs = surface_specs()
    for i, (name, (fn, tile, extra)) in enumerate(sorted(specs.items())):
        rng = np.random.default_rng(1234 + i * 31)
        c, h, r = fn(rng)
        save(c, os.path.join(out, f"{name}_col.png"))
        strength = 6.0
        save(normal_from_height(h.astype(np.float32), strength), os.path.join(out, f"{name}_nrm.png"))
        save(np.clip(r, 0.05, 1.0).astype(np.float32), os.path.join(out, f"{name}_rgh.png"), "L")
    at = {
        "win_res": lambda r: atlas(r, lambda q: room(q, "res")),
        "win_office": lambda r: atlas(r, lambda q: room(q, "office")),
        "win_curtain_blue": lambda r: atlas(r, lambda q: curtain_cell(q, (0.45, 0.6, 0.75))),
        "win_curtain_green": lambda r: atlas(r, lambda q: curtain_cell(q, (0.45, 0.62, 0.58))),
        "win_curtain_dark": lambda r: atlas(r, lambda q: curtain_cell(q, (0.25, 0.3, 0.36))),
        "win_curtain_silver": lambda r: atlas(r, lambda q: curtain_cell(q, (0.65, 0.7, 0.75))),
        "win_curtain_bronze": lambda r: atlas(r, lambda q: curtain_cell(q, (0.48, 0.4, 0.32))),
        "win_shop": lambda r: atlas(r, shop_cell),
        "signs": lambda r: atlas(r, sign_cell),
    }
    for i, (name, fn) in enumerate(at.items()):
        rng = np.random.default_rng(777 + i * 13)
        save(fn(rng), os.path.join(out, f"{name}_col.png"))
    print("hd textures:", len(specs), "surfaces,", len(at), "atlases")


if __name__ == "__main__":
    generate(sys.argv[1])
