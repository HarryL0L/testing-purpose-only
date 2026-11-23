"""The 100 building types.

Every entry is a small function that drives the Builder.  Families share a
parameterised generator; individual entries vary size, storeys, materials and
details.  Front facade faces -Y.
"""
import math

from builder import (bounds, chamfer, circle, offset_rect, rect, rect_xy, rotate_fp, scale_fp)

ENTRIES = []


def reg(key, name, category, desc, fn, **kw):
    i = len(ENTRIES) + 1
    ENTRIES.append(dict(id=i, key=key, name=name, category=category, desc=desc, seed=1000 + i * 7,
                        fn=lambda b, rng, _fn=fn, _kw=kw: _fn(b, rng, **_kw)))


# =========================================================== shared helpers
def sides(front, side, back=None, left=None):
    """side_mats for a rect footprint: [front(-Y), right(+X), back(+Y), left(-X)]."""
    return [front, side, back if back else front, left if left else side]


def door(b, x, y, w=1.4, h=2.6, z=0.0, mat="trim_dark"):
    b.box_xy(x - w / 2, y - 0.1, x + w / 2, y + 0.02, z, z + h, mat)


def flagpole(b, x, y, z, h=6.0, flag="awning_red", angle=False):
    if angle:
        b.beam((x, y, z), (x, y - h * 0.7, z + h * 0.7), 0.12, "steel")
        b.box_xy(x - 0.02, y - h * 0.65, x + 0.02, y - h * 0.65 + 1.6, z + h * 0.45, z + h * 0.65, flag)
    else:
        b.beam((x, y, z), (x, y, z + h), 0.12, "steel")
        b.box_xy(x, y - 0.02, x + 1.8, y + 0.02, z + h - 1.2, z + h - 0.1, flag)


def roof_clutter(b, rng, fp, z, water=False, nhvac=2, bulk=True, bulk_mat="brick_red_blank"):
    x0, y0, x1, y1 = bounds(fp)
    w, d = x1 - x0, y1 - y0
    if bulk and w > 5 and d > 5:
        bw, bd = min(4.5, w * 0.35), min(4.0, d * 0.3)
        b.bulkhead(x0 + bw / 2 + 0.8, y1 - bd / 2 - 0.8, z, bw, bd, 3.0, bulk_mat)
    if water and w >= 7 and d >= 8:
        r = min(2.4, w * 0.17)
        b.water_tower(x1 - r - 1.0, y0 + d * 0.62, z, r=r)
    for _ in range(nhvac):
        if w > 7 and d > 7:
            b.hvac(rng.uniform(x0 + 2.5, x1 - 2.5), rng.uniform(y0 + 2.5, y0 + d * 0.4), z)


def store_cornice(b, w, y0, z, mat="trim_dark"):
    b.box_xy(-w / 2 - 0.1, y0 - 0.3, w / 2 + 0.1, y0, z - 0.45, z, mat)


def bay_count(w, mat_bay):
    return max(1, round(w / mat_bay))


# =========================================================== A. residential
def brownstone(b, rng, floors=4, w=6.0, d=14.0, mat="brownstone", side="brick_brown_blank", bay=False,
               mansard=False, stoop_mat="brownstone_plain", cornice="trim_dark"):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    fh = b.fh(mat)
    z = b.floors(fp, 0, floors, mat, side_mats=sides(mat, side))
    b.cornice(fp, z, 0.9, 0.5, cornice)
    nb = bay_count(w, b.spec(mat)["bay"])
    bw = w / nb
    dx = -w / 2 + bw / 2
    if bay:
        xa, xb = -w / 2 + bw + 0.3, w / 2 - 0.3
        bfp = [(xa, y0), (xa + 0.6, y0 - 0.9), (xb - 0.6, y0 - 0.9), (xb, y0)]
        b.prism(bfp, 0, (floors - 1) * fh, mat, top=cornice, side_mats=[mat, mat, mat, None])
    if mansard:
        b.mansard(fp, z, 3.2, "slate", inset=1.0)
        for i in range(nb):
            x = -w / 2 + bw * (i + 0.5)
            b.box_xy(x - 0.7, y0 + 0.3, x + 0.7, y0 + 1.3, z + 0.2, z + 2.3, "trim_white")
            b.box_xy(x - 0.45, y0 + 0.25, x + 0.45, y0 + 0.3, z + 0.6, z + 1.9, "glass_plain")
            b.gable(x - 0.7, y0 + 0.3, x + 0.7, y0 + 1.3, z + 2.3, 0.6, "slate", axis="y", overhang=0.1)
    else:
        b.box_xy(-w / 2 + 0.2, 1.0, -w / 2 + 1.0, 2.0, z, z + 1.6, side)
        roof_clutter(b, rng, fp, z, nhvac=1, bulk=False)
    sh = 1.6
    b.stoop(dx, y0, 1.8, sh, 6, 0.32, stoop_mat)
    door(b, dx, y0, 1.3, 2.7, sh, "trim_dark")
    b.box_xy(dx - 0.9, y0 - 0.35, dx + 0.9, y0, sh + 2.7, sh + 3.0, cornice)


def tenement(b, rng, floors=5, w=8.0, d=18.0, mat="tenement_red", store="store_mixed", side="brick_red_blank",
             corner=False, fire=True, cornice="trim_dark", awn=None, water=None):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    gside = [store, store if corner else side, side, side]
    z = b.floors(fp, 0, 1, store, top=None, side_mats=gside)
    z2 = b.floors(fp, z, floors, mat, side_mats=[mat, mat if corner else side, mat, side])
    b.cornice(fp, z2, 1.0, 0.6, cornice)
    store_cornice(b, w, y0, z, cornice)
    if corner:
        b.box_xy(w / 2, -d / 2, w / 2 + 0.3, d / 2, z - 0.45, z, cornice)
    if fire:
        fx = min(2.2, w * 0.3)
        b.fire_escape(-fx, fx, y0, z, floors, b.fh(mat))
    if awn:
        b.awning(-w / 2 + 0.4, w / 2 - 0.4, y0 - 0.3, z - 0.7, 1.4, 0.7, awn)
        if corner:
            b.box_xy(w / 2 + 0.3, -d / 2 + 0.5, w / 2 + 1.5, d / 2 - 2, z - 0.8, z - 0.7, awn)
    roof_clutter(b, rng, fp, z2, water=(w >= 10) if water is None else water, nhvac=2, bulk_mat=side)


def federal(b, rng, w=7.0, d=12.0, floors=2, store=False):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    z = 0
    if store:
        z = b.floors(fp, 0, 1, "store_brick", top=None, side_mats=sides("store_brick", "brick_red_blank"))
        store_cornice(b, w, y0, z, "trim_white")
    z = b.floors(fp, z, floors, "federal", top=None, side_mats=sides("federal", "brick_red_blank"))
    b.box_xy(-w / 2 - 0.15, y0 - 0.25, w / 2 + 0.15, y0, z - 0.35, z, "trim_white")
    b.gable(-w / 2, -d / 2, w / 2, d / 2, z, 3.4, "slate", "brick_red_blank", axis="x", overhang=0.25)
    nd = 2 if w < 9 else 3
    for i in range(nd):
        x = -w / 2 + w * (i + 0.5) / nd
        b.box_xy(x - 0.6, y0 + 0.9, x + 0.6, y0 + 2.6, z + 0.5, z + 2.5, "trim_white")
        b.box_xy(x - 0.35, y0 + 0.85, x + 0.35, y0 + 0.9, z + 0.9, z + 2.1, "glass_plain")
        b.gable(x - 0.6, y0 + 0.9, x + 0.6, y0 + 2.6, z + 2.5, 0.7, "slate", axis="y", overhang=0.1)
    for sx in (-1, 1):
        b.box_xy(sx * w / 2 - 0.45, -0.7, sx * w / 2 + 0.45, 0.7, z, z + 4.6, "brick_red_blank")
    if not store:
        b.steps(-w / 2 + 1.0, -w / 2 + 2.6, y0, 0.6, 3, 0.35, "trim_stone")
        door(b, -w / 2 + 1.8, y0, 1.2, 2.5, 0.6, "green_paint")


def prewar(b, rng, w=30.0, d=25.0, floors=14, mat="prewar_brick", base="prewar_limestone", base_floors=2,
           store=None, crown_floors=1, water=True, setback=False, side="brick_tan_blank"):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    z = 0.0
    if store:
        z = b.floors(fp, 0, 1, store, top=None, side_mats=[store, store, "limestone_plain", store])
        store_cornice(b, w, y0, z, "trim_stone")
    z = b.floors(fp, z, base_floors, base, top=None)
    b.cornice(fp, z, 0.5, 0.25, "trim_stone")
    z = b.floors(fp, z, floors - base_floors - crown_floors, mat, top=None, side_mats=sides(mat, mat, side))
    z = b.floors(fp, z, crown_floors, base)
    b.cornice(fp, z, 1.2, 0.9, "trim_stone")
    top_fp = fp
    if setback:
        top_fp = rect(0, d * 0.1, w * 0.6, d * 0.5)
        z = b.floors(top_fp, z, 2, base)
        b.cornice(top_fp, z, 0.6, 0.35, "trim_stone")
    roof_clutter(b, rng, top_fp, z, water=water, nhvac=3, bulk_mat=side)
    if not store:
        b.box_xy(-2.0, y0 - 5.0, 2.0, y0, 3.4, 3.7, "awning_green")
        for sx in (-1.8, 1.8):
            b.beam((sx, y0 - 4.8, 0), (sx, y0 - 4.8, 3.4), 0.08, "chrome")
        door(b, 0, y0, 2.4, 3.0)


def courtyard(b, rng, w=40.0, d=36.0, floors=10, mat="prewar_brick", base="prewar_limestone", cw=None, cd=None):
    y0, y1, x0, x1 = -d / 2, d / 2, -w / 2, w / 2
    cw = cw or w * 0.4
    cd = cd or d * 0.5
    ww = (w - cw) / 2
    parts = [rect_xy(x0, y0 + cd, x1, y1), rect_xy(x0, y0, x0 + ww, y0 + cd), rect_xy(x1 - ww, y0, x1, y0 + cd)]
    for fp in parts:
        z = b.floors(fp, 0, 2, base, top=None)
        z = b.floors(fp, z, floors - 3, mat, top=None)
        z = b.floors(fp, z, 1, base)
        b.cornice(fp, z, 1.0, 0.7, "trim_stone")
    roof_clutter(b, rng, parts[0], z, water=True, nhvac=3)
    # courtyard garden + gate
    b.box_xy(x0 + ww + 1, y0 + 1, x1 - ww - 1, y0 + cd - 1, 0, 0.15, "grass")
    b.box_xy(x0 + ww, y0, x1 - ww, y0 + 0.3, 0, 2.2, "iron")
    for i in range(3):
        tx = x0 + ww + cw * (i + 1) / 4
        b.cylinder(tx, y0 + cd * 0.5, 0.25, 0, 3, "wood", n=6)
        b.pyramid(circle(tx, y0 + cd * 0.5, 1.8, 8), 2.0, 7.0, "grass")


def postwar(b, rng, w=40.0, d=22.0, floors=20, mat="postwar_white", balconies=True, tiers=3,
            side="brick_white_blank"):
    y0 = -d / 2
    fp = rect(0, 0, w, d)
    z = b.floors(fp, 0, 1, "lobby_glass", top="roof_tar")
    per = [int(floors * 0.6)] + [max(1, (floors - int(floors * 0.6)) // (tiers - 1))] * (tiers - 1)
    fh = b.fh(mat)
    for k, n in enumerate(per):
        tfp = rect(0, k * 1.5, w - k * 6, d - k * 3)
        ty0 = -d / 2 + k * 3
        z0 = z
        z = b.floors(tfp, z, n, mat, side_mats=sides(mat, side, mat))
        b.parapet(tfp, z, 1.0, 0.25, "trim_white")
        if balconies:
            bw = (w - k * 6)
            nb = int(bw // 6)
            for f in range(n):
                zf = z0 + f * fh
                for i in range(nb):
                    if (i + k) % 2:
                        continue
                    bx = -bw / 2 + 3 + i * 6
                    b.box_xy(bx - 1.4, ty0 - 1.3, bx + 1.4, ty0, zf, zf + 0.15, "trim_white")
                    b.box_xy(bx - 1.4, ty0 - 1.3, bx + 1.4, ty0 - 1.22, zf + 0.15, zf + 1.05, "trim_white")
    roof_clutter(b, rng, rect(0, (tiers - 1) * 1.5, w - (tiers - 1) * 6, d - (tiers - 1) * 3), z, nhvac=3,
                 bulk_mat=side)
    b.box_xy(-4, y0 - 6, 4, y0, 4.5, 4.9, "trim_dark")


def project_cross(b, rng, arm=9.0, core=12.0, floors=14, mat="project_brick"):
    a, c = arm, core / 2
    fp = [(-c, -c - a), (c, -c - a), (c, -c), (c + a, -c), (c + a, c), (c, c), (c, c + a), (-c, c + a), (-c, c),
          (-c - a, c), (-c - a, -c), (-c, -c)]
    z = b.floors(fp, 0, floors, mat, top="roof_tar")
    b.parapet(rect(0, 0, core, core), z, 0.8, 0.3, "concrete")
    b.bulkhead(0, 0, z, 6, 6, 3.5, "brick_red_blank")
    b.box(0, 0, 7, 7, z + 3.5, z + 3.8, "concrete")
    for x in (-c - a * 0.5, c + a * 0.5):
        b.hvac(x, 0, z)
    b.box_xy(-3, -c - a - 3, 3, -c - a, 3.0, 3.3, "concrete")
    door(b, 0, -c - a, 2.2, 2.6)
    b.box_xy(-30, -32, 30, -c - a - 4, 0, 0.12, "grass")


def project_slab(b, rng, w=60.0, d=14.0, floors=12, mat="project_brick"):
    fp = rect(0, 0, w, d)
    z = b.floors(fp, 0, floors, mat, side_mats=sides(mat, "brick_red_blank"))
    for sx in (-1, 1):
        b.box_xy(sx * w / 2 - (5 if sx > 0 else 0.4), -d / 2 - 1.5, sx * w / 2 + (0.4 if sx > 0 else 5), d / 2 - 2,
                 0, z + 2.5, "brick_red_blank", "roof_tar")
    b.bulkhead(0, 2, z, 8, 5, 3.5, "brick_red_blank")
    for x in (-w * 0.3, w * 0.3):
        b.box_xy(x - 2.5, -d / 2 - 3, x + 2.5, -d / 2, 3.0, 3.3, "concrete")
        door(b, x, -d / 2, 2.2, 2.6)
    b.box_xy(-w / 2 - 5, -d / 2 - 25, w / 2 + 5, -d / 2 - 4, 0, 0.12, "grass")


def glass_condo(b, rng, w=26.0, d=24.0, floors=34, mat="glass_resi"):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    z = b.floors(fp, 0, 1, "lobby_glass", top="roof_tar")
    fh = b.fh(mat)
    z0 = z
    z = b.floors(fp, z, floors, mat)
    for f in range(2, floors, 1):
        zf = z0 + f * fh
        b.box_xy(-w / 2 - 0.2, y0 - 1.8, w / 2 + 0.2, y0, zf, zf + 0.2, "trim_white")
        b.box_xy(-w / 2 - 0.2, y0 - 1.8, w / 2 + 0.2, y0 - 1.7, zf + 0.2, zf + 1.1, "glass_plain")
    b.box_xy(-w / 2, y0, w / 2, d / 2, z, z + 4.0, "ribbon_dark", "roof_gravel")
    b.box_xy(-w / 4, y0 - 6, w / 4, y0, 5.2, 5.6, "trim_dark")


# =========================================================== B. commercial
def castiron(b, rng, w=15.0, d=30.0, floors=5, mat="castiron_white", store="store_stone", side="brick_red_blank",
             fire=False, cornice="trim_white"):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    z = b.floors(fp, 0, 1, store, top=None, side_mats=[store, side, side, side])
    z = b.floors(fp, z, floors, mat, side_mats=[mat, side, side, side])
    b.cornice(fp, z, 1.5, 1.0, cornice)
    b.box_xy(-w * 0.2, y0 - 0.9, w * 0.2, y0 + 0.5, z, z + 1.8, cornice)
    store_cornice(b, w, y0, 4.5, cornice)
    if fire:
        b.fire_escape(-w * 0.25, w * 0.25, y0, 4.5, floors, b.fh(mat))
    roof_clutter(b, rng, fp, z, water=w >= 12, nhvac=2, bulk_mat=side)


def warehouse(b, rng, w=25.0, d=30.0, floors=7, mat="warehouse_arch", side="brick_red_blank", store=None,
              corner=True, water=True):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    z = 0
    if store:
        z = b.floors(fp, 0, 1, store, top=None, side_mats=[store, store if corner else side, side, side])
        floors -= 1
    z = b.floors(fp, z, floors, mat, side_mats=[mat, mat if corner else side, side, side])
    b.parapet(fp, z, 1.2, 0.4, side)
    b.cornice(fp, z, 0.6, 0.35, "trim_stone")
    b.box_xy(-w / 2, y0 - 2.6, w / 2, y0, 4.0, 4.3, "steel")
    for x in (-w / 2 + 0.5, 0, w / 2 - 0.5):
        b.beam((x, y0 - 2.5, 4.0), (x, y0, 6.0), 0.06, "iron")
    if not store:
        for x in (-w * 0.25, w * 0.25):
            b.box_xy(x - 1.8, y0 - 0.05, x + 1.8, y0 + 0.02, 0, 3.6, "garage_door_gray")
        b.box_xy(-w / 2, y0 - 2.5, w / 2, y0, 0, 1.1, "concrete")
    roof_clutter(b, rng, fp, z, water=water, nhvac=1, bulk_mat=side)


def early_sky(b, rng, w=30.0, d=30.0, floors=22, mat="limestone_ornate", crown="cupola", store="store_stone",
              shaft_side=None):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    z = b.floors(fp, 0, 1, store, top=None)
    z = b.floors(fp, z, 2, "limestone", top=None)
    b.cornice(fp, z, 0.6, 0.4, "trim_stone")
    sm = sides(mat, shaft_side or mat)
    z = b.floors(fp, z, floors - 5, mat, top=None, side_mats=sm)
    b.cornice(fp, z, 0.4, 0.25, "trim_stone")
    z = b.floors(fp, z, 2, "terracotta")
    b.cornice(fp, z, 1.6, 1.3, "trim_stone")
    if crown == "cupola":
        r = w * 0.18
        z2 = b.floors(rect(0, 0, w * 0.5, d * 0.5), z, 2, "limestone")
        b.cylinder(0, 0, r, z2, z2 + 5, "limestone_plain", n=16)
        dz = b.dome(0, 0, r * 1.05, z2 + 5, "copper", n=16, rings=5)
        b.cylinder(0, 0, 1.0, dz - 0.3, dz + 3, "copper", n=8)
        b.spire(0, 0, dz + 3, 6, 1.0, "gold")
    elif crown == "mansard":
        b.mansard(fp, z, 9, "copper", inset=3.5, top="copper")
        for i in range(4):
            x = -w / 2 + w * (i + 0.5) / 4
            b.box_xy(x - 1, y0 + 1.0, x + 1, y0 + 2.6, z + 1, z + 4, "trim_white")
            b.box_xy(x - 0.6, y0 + 0.95, x + 0.6, y0 + 1.0, z + 1.5, z + 3.5, "glass_plain")
        flagpole(b, 0, 0, z + 9, 8)
    elif crown == "pyramid":
        b.pyramid(scale_fp(fp, 0.85), z, z + w * 0.55, "copper")
        b.spire(0, 0, z + w * 0.55, 4, 0.6, "gold")
    else:
        roof_clutter(b, rng, fp, z, water=True, nhvac=3)
        flagpole(b, 0, y0 + 1, z, 8)
    door(b, 0, y0, 3.0, 3.6, 0, "gold")


def deco(b, rng, w=40.0, d=36.0, tiers=((1.0, 1.0, 18), (0.8, 0.75, 12), (0.6, 0.55, 10), (0.4, 0.38, 8)),
         mat="deco_light", store="store_stone", crown="spire", crown_mat="steel", parapet="trim_stone"):
    y0 = -d / 2
    z = b.floors(rect(0, 0, w, d), 0, 1, store, top=None)
    store_cornice(b, w, y0, z, "trim_stone")
    fp = None
    for sx, sy, n in tiers:
        fp = rect(0, (1 - sy) * d * 0.15, w * sx, d * sy)
        z = b.floors(fp, z, n, mat)
        b.parapet(fp, z, 0.8, 0.3, parapet)
    x0, fy0, x1, fy1 = bounds(fp)
    cx, cy = (x0 + x1) / 2, (fy0 + fy1) / 2
    tw, td = x1 - x0, fy1 - fy0
    if crown == "spire":
        z = b.floors(rect(cx, cy, tw * 0.6, td * 0.6), z, 3, mat)
        for k in range(4):
            s = 0.55 - k * 0.1
            b.frustum(rect(cx, cy, tw * s, td * s), z + k * 5, z + k * 5 + 5, 0.8, crown_mat, top=crown_mat)
        b.spire(cx, cy, z + 20, w * 0.7, tw * 0.12, crown_mat)
    elif crown == "pyramid":
        b.pyramid(rect(cx, cy, tw, td), z, z + tw * 0.55, crown_mat)
        b.antenna(cx, cy, z + tw * 0.55, 8)
    elif crown == "stepped":
        for k in range(4):
            s = 0.8 - k * 0.18
            z = b.floors(rect(cx, cy, tw * s, td * s), z, 2, mat)
            b.parapet(rect(cx, cy, tw * s, td * s), z, 0.6, 0.25, crown_mat)
        b.antenna(cx, cy, z, 15)
    elif crown == "mast":
        z = b.floors(rect(cx, cy, tw * 0.7, td * 0.7), z, 4, mat)
        b.cylinder(cx, cy, tw * 0.22, z, z + 12, "trim_stone", n=12)
        b.cylinder(cx, cy, tw * 0.16, z + 12, z + 20, "chrome", n=12)
        b.dome(cx, cy, tw * 0.16, z + 20, "trim_stone", n=12, rings=3)
        b.antenna(cx, cy, z + 20 + tw * 0.16, 40, 0.8)
    door(b, 0, y0, 4.0, 4.0, 0, "gold")


def glass_slab(b, rng, w=50.0, d=24.0, floors=38, mat="glass_dark", plaza=True, podium=None, mech="ribbon_dark"):
    y0 = -d / 2
    fp = rect(0, 0, w, d)
    z = 0.0
    if plaza:
        b.box_xy(-w * 0.8, y0 - 25, w * 0.8, d / 2 + 4, 0, 0.3, "granite_plain", "concrete")
        z = 0.3
        b.box_xy(-6, y0 - 18, 6, y0 - 8, z, z + 0.8, "granite_plain", "glass_plain")
    if podium:
        pw, pd, pf = podium
        pfp = rect(0, 0, w * pw, d * pd)
        z = b.floors(pfp, z, 1, "lobby_glass", top=None)
        z = b.floors(pfp, z, pf, mat)
    else:
        z = b.floors(scale_fp(fp, 0.9, 0.8), z, 1, "lobby_glass", top="roof_tar")
        for x in [(-w / 2 + 1) + i * (w - 2) / 6 for i in range(7)]:
            b.beam((x, y0 + 0.6, 0.3), (x, y0 + 0.6, z), 0.8, "steel")
    z = b.floors(fp, z, floors, mat)
    z = b.floors(scale_fp(fp, 0.96), z, 2, mech)
    b.box(w * 0.2, 0, w * 0.3, d * 0.4, z, z + 4, "concrete_dark", "roof_tar")


def postmodern(b, rng, w=34.0, d=34.0, floors=36, mat="granite_pink", top="gable", top_mat="granite_plain"):
    y0 = -d / 2
    fp = chamfer(0, 0, w, d, w * 0.15)
    z = b.floors(rect(0, 0, w, d), 0, 1, "store_stone", top=None)
    b.box_xy(-5, y0 - 0.6, 5, y0 + 0.5, 0, 9, "granite_plain")
    b.box_xy(-3.5, y0 - 0.7, 3.5, y0 - 0.6, 0, 7, "glass_plain")
    z = b.floors(fp, z, floors, mat, continuous_u=True)
    if top == "gable":
        b.pediment(-w / 2, w / 2, -d / 2 + w * 0.15, d / 2 - w * 0.15, z, w * 0.35, top_mat)
        b.prism(fp, z, z + 0.01, top_mat, top="roof_gravel")
        b.cylinder(0, -d / 2 + w * 0.15 - 0.05, w * 0.08, z + w * 0.08, z + w * 0.08 + 0.01, "dark")
    elif top == "pyramid":
        b.pyramid(fp, z, z + w * 0.6, top_mat)
        b.spire(0, 0, z + w * 0.6, 6, 0.6, "gold")
    else:
        for k in range(3):
            s = 0.8 - k * 0.2
            z = b.floors(chamfer(0, 0, w * s, d * s, w * s * 0.15), z, 3, mat, continuous_u=True)
        b.pyramid(chamfer(0, 0, w * 0.4, d * 0.4, w * 0.06), z, z + 8, top_mat)


def supertall(b, rng, w=50.0, floors=100, mat="glass_silver", shape="taper", spire=60.0):
    y0 = -w / 2
    z = b.floors(rect(0, 0, w * 1.2, w * 1.2), 0, 2, "lobby_glass", top="roof_tar")
    if shape == "taper":
        n = 5
        for k in range(n):
            s = 1.0 - k * 0.1
            z = b.floors(chamfer(0, 0, w * s, w * s, w * s * 0.18), z, floors // n, mat, continuous_u=True)
        top_w = w * 0.6
    elif shape == "round":
        z = b.floors(circle(0, 0, w / 2, 24), z, floors - 6, mat, continuous_u=True)
        z = b.floors(circle(0, 0, w * 0.42, 24), z, 6, mat, continuous_u=True)
        b.prism(circle(0, 0, w * 0.42, 24), z, z + 10, "steel", top=None)
        top_w = w * 0.4
    else:  # chamfer + slanted crown
        fp = chamfer(0, 0, w, w, w * 0.25)
        z = b.floors(fp, z, floors - 8, mat, continuous_u=True)
        b.frustum(fp, z, z + 32, 0.55, mat, top="roof_tar")
        z += 32
        top_w = w * 0.5
    b.antenna(0, 0, z, spire, max(0.8, top_w * 0.04))


def twisting(b, rng, w=36.0, floors=80, mat="glass_blue", seg=20, deg=2.2):
    z = b.floors(circle(0, 0, w * 0.75, 12), 0, 1, "lobby_glass", top="roof_tar")
    per = max(1, floors // seg)
    for k in range(seg):
        s = 1.0 - k * 0.012
        fp = rotate_fp(rect(0, 0, w * s, w * s), math.radians(k * deg))
        z = b.floors(fp, z, per, mat, top="glass_plain", continuous_u=True)
    b.antenna(0, 0, z, 25, 0.8)


def pencil(b, rng, w=20.0, d=22.0, floors=90, mat="glass_resi", crown="open"):
    y0 = -d / 2
    fp = rect(0, 0, w, d)
    z = b.floors(rect(0, 0, w + 6, d + 4), 0, 1, "lobby_glass", top="roof_tar")
    if crown == "stepped":
        z = b.floors(fp, z, floors - 12, mat)
        for k in range(4):
            s = 0.9 - k * 0.15
            z = b.floors(rect(0, 0, w * s, d * s), z, 3, mat)
            b.parapet(rect(0, 0, w * s, d * s), z, 1.0, 0.3, "trim_stone")
        b.spire(0, 0, z, 10, w * 0.12, "copper")
    else:
        z = b.floors(fp, z, floors, mat)
        z = b.floors(fp, z, 3, "ribbon_dark")
        b.prism(fp, z, z + 6, "glass_plain", top=None)


def midrise_ribbon(b, rng, w=45.0, d=30.0, floors=12, mat="ribbon_concrete", podium=True):
    y0 = -d / 2
    z = 0.0
    if podium:
        pfp = rect(0, 0, w + 10, d + 6)
        z = b.floors(pfp, 0, 2, "store_modern")
    z = b.floors(rect(0, 3 if podium else 0, w, d), z, floors - (2 if podium else 0), mat)
    b.box(0, 3 if podium else 0, w * 0.5, d * 0.5, z, z + 5, "concrete", "roof_tar")
    roof_clutter(b, rng, rect(0, 3, w, d), z, nhvac=3, bulk=False)


# =========================================================== C. retail & mixed
def bodega(b, rng, w=7.0, d=12.0, floors=2, mat="brick_yellow", side="brick_yellow_blank", awn="awning_green",
           corner=True, sign="sign_red"):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    z = b.floors(fp, 0, 1, "store_mixed", top=None, side_mats=["store_mixed", "store_mixed" if corner else side,
                                                                side, side])
    z2 = b.floors(fp, z, floors, mat, side_mats=[mat, mat if corner else side, side, side])
    b.parapet(fp, z2, 0.8, 0.3, side)
    b.box_xy(-w / 2 - 0.1, y0 - 0.25, w / 2 + 0.1, y0, z - 0.45, z, "trim_dark")
    b.awning(-w / 2 + 0.3, w / 2 - 0.3, y0 - 0.25, z - 0.6, 1.5, 0.7, awn)
    if corner:
        prof_x = w / 2 + 0.25
        b.box_xy(prof_x, -d / 2 + 0.5, prof_x + 1.5, d / 2 - 1.0, z - 0.75, z - 0.6, awn)
        b.blade_sign(w / 2 - 0.4, y0, z + 0.5, 3.0, 1.0, sign)
    else:
        b.blade_sign(w / 2 - 0.6, y0, z + 0.5, 2.5, 1.0, sign)
    b.hvac(0, 1, z2, 1.8, 1.4, 1.0)


def diner(b, rng, w=17.0, d=8.0):
    fp = chamfer(0, 0, w, d, 2.0)
    b.prism(chamfer(0, 0, w + 0.4, d + 0.4, 2.1), 0, 0.9, "chrome", top=None)
    z = b.floors(fp, 0.9, 1, "store_modern", top="trim_white", continuous_u=True)
    b.prism(chamfer(0, 0, w + 0.6, d + 0.6, 2.2), z - 0.2, z + 0.5, "chrome", top="trim_white")
    b.prism(chamfer(0, 0, w + 0.62, d + 0.62, 2.2), z - 0.0, z + 0.25, "sign_red", top=None)
    for x in (-3, 3):
        b.beam((x, 0, z + 0.5), (x, 0, z + 2.5), 0.15, "steel")
    b.box_xy(-4.5, -0.15, 4.5, 0.15, z + 2.5, z + 4.3, "sign_red")
    b.box_xy(-4.3, -0.2, 4.3, -0.15, z + 2.7, z + 4.1, "sign_white")
    b.steps(-1.2, 1.2, -d / 2 - 0.2, 0.9, 3, 0.35, "concrete")


def dept_store(b, rng, w=60.0, d=45.0, floors=9, mat="limestone", store="store_lux", flags=True, corner_clock=False):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    z = b.floors(fp, 0, 1, store, top=None, side_mats=[store, store, "limestone_plain", store])
    store_cornice(b, w, y0, z, "trim_stone")
    n = bay_count(w, 5.0)
    for i in range(n):
        x = -w / 2 + (i + 0.5) * w / n
        b.awning(x - 2.0, x + 2.0, y0 - 0.3, z - 1.2, 1.3, 0.8, "awning_black")
    z = b.floors(fp, z, floors - 2, mat, top=None)
    b.cornice(fp, z, 0.7, 0.5, "trim_stone")
    z = b.floors(fp, z, 1, "limestone_ornate")
    b.cornice(fp, z, 1.4, 1.1, "trim_stone")
    if flags:
        for i in range(5):
            x = -w / 2 + (i + 0.5) * w / 5
            flagpole(b, x, y0, 10, 5, "awning_red", angle=True)
    if corner_clock:
        b.box_xy(w / 2 - 6, y0 - 0.5, w / 2 + 0.5, y0 + 6, 0, z + 9, "limestone_ornate", "copper")
        b.cylinder(w / 2 - 2.75, y0 - 0.55, 1.8, z + 4, z + 4.05, "trim_white")
        b.pyramid(rect(w / 2 - 2.75, y0 + 2.75, 6.5, 6.5), z + 9, z + 14, "copper")
    roof_clutter(b, rng, fp, z, nhvac=4, bulk_mat="limestone_plain")


def chain_store(b, rng, w=22.0, d=26.0, sign="sign_blue"):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    z = b.floors(fp, 0, 1, "store_modern", top=None, side_mats=sides("store_modern", "concrete"))
    z = b.floors(fp, z, 1, "ribbon_white", side_mats=sides("ribbon_white", "concrete"))
    b.box_xy(-w / 2 + 1, y0 - 0.4, w / 2 - 1, y0, 3.6, 4.9, sign)
    b.parapet(fp, z, 1.0, 0.3, "concrete")
    for i in range(3):
        b.hvac(-w / 4 + i * w / 4, 4, z)


def bank(b, rng, w=24.0, d=30.0, floors=2, tower=0, mat="civic_stone"):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    b.box_xy(-w / 2 - 1, y0 - 4.5, w / 2 + 1, d / 2, 0, 1.2, "limestone_plain")
    b.steps(-w / 2 + 1, w / 2 - 1, y0 - 4.5, 1.2, 4, 0.4, "limestone_plain")
    z = b.floors(fp, 1.2, floors, mat, top=None if tower else "roof_gravel")
    b.columns(-w / 2 + 1.5, w / 2 - 1.5, y0 - 2.5, 1.2, z - 1.6, 6, 0.55)
    b.box_xy(-w / 2, y0 - 3.4, w / 2, y0, z - 1.6, z, "trim_stone")
    b.pediment(-w / 2, w / 2, y0 - 3.4, y0 + 0.5, z, w * 0.18)
    if tower:
        tfp = rect(0, d * 0.15, w * 0.9, d * 0.7)
        z = b.floors(tfp, z, tower, "limestone_ornate")
        b.cornice(tfp, z, 1.0, 0.7)
        roof_clutter(b, rng, tfp, z, water=True, nhvac=2, bulk_mat="limestone_plain")
    else:
        b.parapet(fp, z, 1.0, 0.4, "trim_stone")
    door(b, 0, y0, 3.0, 5.0, 1.2, "gold")


def theater(b, rng, w=22.0, d=40.0, front_floors=4, mat="terracotta", fly_h=26.0, marquee="sign_white",
            blade="sign_yellow"):
    y0 = -d / 2
    front = rect_xy(-w / 2, y0, w / 2, y0 + 10)
    z = b.floors(front, 0, 1, "store_stone", top=None)
    z = b.floors(front, z, front_floors, mat)
    b.cornice(front, z, 1.0, 0.7)
    b.box_xy(-w / 2, y0 + 10, w / 2, d / 2, 0, fly_h, "brick_brown_blank", "roof_tar")
    b.box_xy(-w / 2 + 3, y0 + 18, w / 2 - 3, d / 2 - 3, fly_h, fly_h + 8, "brick_brown_blank", "roof_tar")
    b.box_xy(-w * 0.42, y0 - 3.5, w * 0.42, y0, 4.0, 5.6, marquee)
    b.box_xy(-w * 0.43, y0 - 3.6, w * 0.43, y0 + 0.01, 5.6, 5.85, "sign_red")
    b.box_xy(-w * 0.43, y0 - 3.6, w * 0.43, y0 + 0.01, 3.75, 4.0, "sign_red")
    b.blade_sign(w / 2 - 1.5, y0, 7.0, min(14, z - 8), 2.0, blade)


def hotel(b, rng, w=36.0, d=28.0, floors=24, mat="hotel_stone", crown="mansard", lobby="store_stone"):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    z = b.floors(fp, 0, 1, lobby, top=None)
    b.box_xy(-5, y0 - 6, 5, y0, 4.2, 4.8, "trim_dark")
    b.box_xy(-5.05, y0 - 6.05, 5.05, y0, 4.8, 4.95, "gold")
    for x in (-4.6, 4.6):
        b.beam((x, y0 - 5.6, 0), (x, y0 - 5.6, 4.2), 0.12, "gold")
    z = b.floors(fp, z, floors, mat)
    b.cornice(fp, z, 1.0, 0.8)
    if crown == "mansard":
        b.mansard(fp, z, 8, "copper", inset=3, top="copper")
        for i in range(6):
            x = -w / 2 + w * (i + 0.5) / 6
            b.box_xy(x - 1, y0 + 1, x + 1, y0 + 2.6, z + 1, z + 4, "trim_white")
            b.box_xy(x - 0.6, y0 + 0.95, x + 0.6, y0 + 1.0, z + 1.5, z + 3.5, "glass_plain")
        for x in (-w * 0.3, w * 0.3):
            flagpole(b, x, 0, z + 8, 7, "awning_blue")
    else:
        roof_clutter(b, rng, fp, z, nhvac=4, bulk_mat="limestone_plain")
    for f in range(3):
        flagpole(b, -6 - f * 2, y0, 5.2, 3.5, ["awning_red", "awning_blue", "awning_green"][f], angle=True)


def glass_hotel(b, rng, w=28.0, d=24.0, floors=30, mat="glass_teal"):
    pfp = rect(0, 0, w + 12, d + 8)
    y0 = -(d + 8) / 2
    z = b.floors(pfp, 0, 1, "lobby_glass")
    z = b.floors(pfp, z, 2, "ribbon_dark")
    z = b.floors(rect(0, 2, w, d), z, floors, mat)
    b.box(0, 2, w * 0.7, d * 0.6, z, z + 6, "ribbon_dark", "roof_tar")
    b.box_xy(-8, y0 - 7, 8, y0, 4.8, 5.3, "steel")
    b.box_xy(-8, y0 - 7.05, 8, y0 - 6.95, 5.3, 6.3, "sign_white")


def nightclub(b, rng, w=9.0, d=20.0):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    b.prism(fp, 0, 4.5, "brick_dark_blank", top=None)
    z = b.floors(fp, 4.5, 2, "brick_dark", side_mats=sides("brick_dark", "brick_dark_blank"))
    b.parapet(fp, z, 0.8, 0.3, "brick_dark_blank")
    door(b, 0, y0, 2.0, 2.8, 0, "iron")
    b.awning(-2, 2, y0, 3.4, 1.5, 0.4, "awning_black")
    b.box_xy(-3.5, y0 - 0.15, 3.5, y0, 3.7, 4.3, "sign_blue")
    b.blade_sign(w / 2 - 0.5, y0, 5.0, 4.0, 1.2, "sign_red")
    b.blade_sign(-w / 2 + 0.5, y0, 5.5, 3.0, 0.9, "sign_blue")
    b.box_xy(-w / 2 - 3, y0 - 3.5, -w / 2, y0 - 3.4, 0, 1.0, "gold")


# =========================================================== D. civic
def firehouse(b, rng, w=12.0, d=25.0, floors=3, mat="brick_red", ground="limestone_plain", doors=2,
              side="brick_red_blank"):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    b.prism(fp, 0, 5.0, ground, top=None, side_mats=sides(ground, side))
    z = b.floors(fp, 5.0, floors - 1, mat, side_mats=sides(mat, side))
    b.cornice(fp, z, 1.0, 0.7)
    store_cornice(b, w, y0, 5.0, "trim_stone")
    dw = (w - 2) / doors - 0.6
    for i in range(doors):
        x = -w / 2 + 1 + (i + 0.5) * (w - 2) / doors
        b.box_xy(x - dw / 2, y0 - 0.06, x + dw / 2, y0 + 0.02, 0, 4.3, "garage_door")
    b.box_xy(w / 2 - 4, d / 2 - 4, w / 2, d / 2, z, z + 7, side, "roof_tar")
    flagpole(b, -w / 2 + 0.8, y0, z, 6, "awning_blue")
    b.box_xy(-w / 2, y0 - 6, w / 2, y0, 0, 0.12, "concrete")


def precinct(b, rng, w=26.0, d=24.0):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    b.box_xy(-w / 2, y0 - 2.4, w / 2, d / 2, 0, 1.0, "limestone_plain")
    b.steps(-4, 4, y0 - 2.4, 1.0, 3, 0.45, "limestone_plain")
    b.prism(fp, 1.0, 5.5, "limestone_plain", top=None)
    z = b.floors(fp, 5.5, 3, "limestone_ornate")
    b.cornice(fp, z, 1.2, 0.9)
    b.parapet(fp, z, 0.9, 0.3, "trim_stone")
    door(b, 0, y0, 3.0, 3.6, 1.0, "green_paint")
    for x in (-2.5, 2.5):
        b.beam((x, y0 - 2.0, 1.0), (x, y0 - 2.0, 3.6), 0.12, "iron")
        b.box(x, y0 - 2.0, 0.5, 0.5, 3.6, 4.3, "lamp")
    flagpole(b, 0, 0, z, 7)


def courthouse(b, rng, w=70.0, d=50.0):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    b.box_xy(-w / 2 - 2, y0 - 8, w / 2 + 2, d / 2 + 2, 0, 3.0, "limestone_plain")
    b.steps(-w * 0.3, w * 0.3, y0 - 8, 3.0, 8, 0.6, "limestone_plain")
    z = b.floors(fp, 3.0, 3, "civic_stone", top="roof_gravel")
    b.columns(-w * 0.3, w * 0.3, y0 - 4, 3.0, z - 2.0, 10, 0.8)
    b.box_xy(-w * 0.32, y0 - 5.5, w * 0.32, y0, z - 2.0, z, "trim_stone")
    b.pediment(-w * 0.32, w * 0.32, y0 - 5.5, y0 + 2, z, 6.5)
    b.parapet(fp, z, 1.2, 0.5, "trim_stone")
    b.cylinder(0, 4, 11, z, z + 8, "civic_stone", "limestone_plain", n=20)
    dz = b.dome(0, 4, 11.5, z + 8, "copper", n=20, rings=6)
    b.cylinder(0, 4, 2, dz - 0.5, dz + 4, "limestone_plain", n=8)
    b.dome(0, 4, 2.2, dz + 4, "copper", n=8, rings=3)


def city_hall(b, rng, w=80.0, d=30.0):
    y0 = -d / 2
    for x0, x1 in ((-w / 2, -w / 6), (w / 6, w / 2)):
        fp = rect_xy(x0, y0 + 3, x1, d / 2)
        z = b.floors(fp, 0, 3, "civic_stone")
        b.parapet(fp, z, 1.0, 0.4, "trim_stone")
        b.mansard(fp, z, 4, "slate", inset=2, top="slate")
    cfp = rect_xy(-w / 6, y0, w / 6, d / 2)
    z = b.floors(cfp, 0, 3, "civic_stone")
    b.columns(-w / 6 + 2, w / 6 - 2, y0 - 2.5, 1.0, z - 1.5, 8, 0.6)
    b.box_xy(-w / 6, y0 - 3.5, w / 6, y0, z - 1.5, z, "trim_stone")
    b.box_xy(-w / 6, y0 - 4, w / 6, y0, 0, 1.0, "limestone_plain")
    b.parapet(cfp, z, 1.2, 0.4)
    t = rect(0, 2, 10, 10)
    z2 = b.floors(t, z, 2, "limestone")
    b.cornice(t, z2, 0.8, 0.6)
    b.cylinder(0, 2, 4, z2, z2 + 6, "trim_white", n=12)
    for k in range(8):
        a = 2 * math.pi * k / 8
        b.cylinder(4.3 * math.cos(a), 2 + 4.3 * math.sin(a), 0.3, z2, z2 + 6, "trim_white", n=6)
    dz = b.dome(0, 2, 4.5, z2 + 6, "copper", n=12, rings=4)
    b.cylinder(0, 2, 0.4, dz - 0.2, dz + 2, "gold", n=6)
    b.box(0, 2, 0.6, 0.6, dz + 2, dz + 4, "gold")


def library(b, rng, w=90.0, d=45.0):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    b.box_xy(-w / 2 - 2, y0 - 16, w / 2 + 2, d / 2, 0, 2.0, "limestone_plain")
    b.steps(-w * 0.25, w * 0.25, y0 - 16, 2.0, 6, 0.8, "limestone_plain")
    z = b.floors(fp, 2.0, 2, "civic_stone", top=None)
    z = b.floors(fp, z, 1, "limestone_ornate", top="roof_gravel")
    b.cornice(fp, z, 1.3, 1.0)
    b.parapet(fp, z, 1.3, 0.5, "trim_stone")
    pc = rect_xy(-w * 0.2, y0 - 4, w * 0.2, y0)
    b.prism(pc, 2.0, z - 3, "trim_stone", top="trim_stone")
    b.columns(-w * 0.19, w * 0.19, y0 - 4.5, 2.0, z - 3, 8, 0.8)
    b.box_xy(-w * 0.21, y0 - 5.5, w * 0.21, y0, z - 3, z, "trim_stone")
    for x in (-w * 0.17, w * 0.17):
        b.box_xy(x - 1.5, y0 - 13, x + 1.5, y0 - 8, 2.0, 4.0, "limestone_plain")
        b.box_xy(x - 0.7, y0 - 12.8, x + 0.7, y0 - 8.4, 4.0, 5.6, "trim_stone")
    for x in (-w * 0.42, w * 0.42):
        flagpole(b, x, y0 - 14, 2, 10, "awning_blue")


def school(b, rng, w=70.0, d=40.0, floors=5, mat="school_brick", shape="H"):
    y0 = -d / 2
    ww = 16.0
    if shape == "H":
        parts = [rect_xy(-w / 2, y0, -w / 2 + ww, d / 2), rect_xy(w / 2 - ww, y0, w / 2, d / 2),
                 rect_xy(-w / 2 + ww, -7, w / 2 - ww, 7)]
    else:
        parts = [rect_xy(-w / 2, y0, w / 2, y0 + ww), rect_xy(w / 2 - ww, y0 + ww, w / 2, d / 2)]
    z = 0
    for fp in parts:
        z = b.floors(fp, 0, floors, mat)
        b.parapet(fp, z, 1.0, 0.35, "trim_stone")
        b.cornice(fp, z, 0.5, 0.3)
    if shape != "H":
        g = rect_xy(-w / 2, y0 + ww, w / 2 - ww - 0.01, d / 2)
        b.prism(g, 0, 11, "brick_red_blank", top=None)
        x0, gy0, x1, gy1 = bounds(g)
        prof = [(gy0, 11), (gy1, 11)] + [((gy0 + gy1) / 2 + (gy1 - gy0) / 2 * math.cos(math.pi * k / 8),
                                          11 + 4 * math.sin(math.pi * k / 8)) for k in range(1, 8)]
        b.extrude_x(prof, x0, x1, "corrugated", "brick_red_blank")
    ex = -w / 2 + ww / 2 if shape == "H" else 0
    b.box_xy(ex - 4, y0 - 0.6, ex + 4, y0 + 0.1, 0, 6, "limestone_plain")
    door(b, ex, y0 - 0.5, 4.0, 3.2, 0, "trim_dark")
    flagpole(b, ex, y0 - 0.6, 6, 6)
    b.hvac(0, 0, z)


def hospital(b, rng, w=60.0, d=22.0, floors=16):
    y0 = -d / 2
    low = rect(0, 4, w + 30, d + 30)
    z = b.floors(low, 0, 1, "lobby_glass", top=None)
    z = b.floors(low, z, 4, "hospital_white")
    b.parapet(low, z, 0.8, 0.3, "concrete")
    fp = rect(0, 4, w, d)
    z2 = b.floors(fp, z, floors - 5, "hospital_white")
    b.box(-w * 0.25, 4, 14, 14, z2, z2 + 0.5, "concrete_dark")
    b.box(-w * 0.25, 4, 10, 10, z2 + 0.5, z2 + 0.55, "trim_white")
    b.box(w * 0.25, 4, 12, 8, z2, z2 + 4, "concrete", "roof_tar")
    ly0 = 4 - (d + 30) / 2
    b.box_xy(w * 0.25 - 8, ly0 - 8, w * 0.25 + 8, ly0, 4.5, 5.2, "trim_white")
    b.box_xy(w * 0.25 - 8, ly0 - 8.05, w * 0.25 + 8, ly0 - 7.95, 5.2, 6.2, "sign_red")
    b.box_xy(-w / 2, ly0 - 0.3, -w / 2 + 14, ly0, z - 2.5, z - 0.5, "sign_blue")


def gothic_church(b, rng):
    b.box_xy(-11, -10, 11, 28, 0, 0.01, "concrete")
    nave = rect_xy(-8, -10, 8, 28)
    z = b.floors(nave, 0, 2, "gothic_stone", top=None)
    b.gable(-8, -10, 8, 28, z, 10, "slate", "gothic_stone", axis="y", overhang=0.4)
    for sx in (-1, 1):
        aisle = rect_xy(min(sx * 8, sx * 13), -10, max(sx * 8, sx * 13), 26)
        b.floors(aisle, 0, 1, "gothic_stone", top="slate")
    front = rect_xy(-5, -17, 5, -10)
    zf = b.floors(front, 0, 2, "gothic_stone", top="slate")
    b.extrude_y([(-5, zf), (5, zf), (0, zf + 9)], -17, -10, "gothic_stone")
    rose = [(math.cos(2 * math.pi * k / 16) * 2.6, math.sin(2 * math.pi * k / 16) * 2.6 + zf + 3.0) for k in range(16)]
    b.face_auto([(x, -17.05, zz) for x, zz in rose], "glass_plain")
    for sx in (-1, 1):
        t = rect(sx * 8.5, -13.5, 7, 7)
        zt = b.floors(t, 0, 5, "gothic_stone")
        b.parapet(t, zt, 1.2, 0.4, "trim_stone")
        b.spire(sx * 8.5, -13.5, zt, 20, 3.4, "slate", n=8)
        for dx, dy in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            b.spire(sx * 8.5 + dx * 3.1, -13.5 + dy * 3.1, zt, 4, 0.5, "trim_stone", n=4)
    door(b, 0, -17, 3.6, 6.0, 0, "wood")


def small_church(b, rng):
    nave = rect_xy(-6, -10, 6, 18)
    z = b.prism(nave, 0, 9, "brick_red_blank", top=None)
    b.gable(-6, -10, 6, 18, z, 6, "slate", "brick_red_blank", axis="y", overhang=0.4)
    for sx in (-1, 1):
        for k in range(5):
            y = -6 + k * 5
            x = sx * 6.02
            b.face_auto([(x, y, 2.5), (x, y + 1.6, 2.5), (x, y + 1.6, 7.0), (x, y, 7.0)] if sx > 0 else
                        [(x, y + 1.6, 2.5), (x, y, 2.5), (x, y, 7.0), (x, y + 1.6, 7.0)], "glass_plain")
    t = rect(0, -12.5, 5, 5)
    zt = b.prism(t, 0, 20, "brick_red_blank", top="trim_stone")
    b.box(0, -12.5, 5.6, 5.6, zt - 4, zt - 3.6, "trim_stone")
    b.face_auto([(-1, -15.06, 14), (1, -15.06, 14), (1, -15.06, 17), (-1, -15.06, 17)], "dark")
    b.spire(0, -12.5, zt, 14, 3.2, "slate", n=8)
    b.beam((0, -12.5, zt + 14), (0, -12.5, zt + 16.5), 0.2, "gold")
    b.beam((-0.8, -12.5, zt + 15.8), (0.8, -12.5, zt + 15.8), 0.2, "gold")
    door(b, 0, -15, 1.8, 3.2, 0, "red_paint")


def synagogue(b, rng):
    body = rect_xy(-12, -14, 12, 22)
    z = b.floors(body, 0, 3, "moorish", side_mats=sides("moorish", "moorish", "brick_tan_blank"))
    b.parapet(body, z, 1.0, 0.4, "trim_stone")
    b.extrude_y([(-6, z), (6, z), (0, z + 5)], -14, -12, "moorish")
    for sx in (-1, 1):
        t = rect(sx * 9.5, -12.5, 5, 5)
        zt = b.floors(t, 0, 5, "moorish")
        b.box(sx * 9.5, -12.5, 5.6, 5.6, zt, zt + 0.6, "trim_stone")
        b.cylinder(sx * 9.5, -12.5, 2.0, zt + 0.6, zt + 2.5, "moorish", n=12)
        oz = b.onion(sx * 9.5, -12.5, 2.4, zt + 2.5, "copper")
        b.beam((sx * 9.5, -12.5, oz), (sx * 9.5, -12.5, oz + 2), 0.15, "gold")
    door(b, 0, -14, 3.2, 5.0, 0, "wood")
    b.steps(-5, 5, -14, 0.9, 3, 0.4, "trim_stone")


def museum(b, rng):
    boxes = [(0, 0, 34, 30, 9, "ribbon_white"), (2, -1, 30, 26, 8, "corrugated"), (-2, 1, 32, 24, 8, "stucco"),
             (3, 0, 26, 22, 7, "corrugated"), (-1, 1, 22, 20, 7, "ribbon_white")]
    z = b.floors(rect(0, 0, 36, 32), 0, 1, "lobby_glass", top="roof_tar")
    for cx, cy, w, d, h, m in boxes:
        b.prism(rect(cx, cy, w, d), z, z + h, m, top="roof_gravel")
        z += h
    b.box(4, 0, 8, 6, z, z + 3, "concrete")
    b.box_xy(-8, -16.3, 8, -16.0, 4.0, 5.2, "sign_white")


def post_office(b, rng, w=110.0, d=50.0):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    b.box_xy(-w / 2 - 1, y0 - 9, w / 2 + 1, d / 2, 0, 3.0, "limestone_plain")
    b.steps(-w / 2 + 2, w / 2 - 2, y0 - 9, 3.0, 7, 0.7, "limestone_plain")
    z = b.floors(fp, 3.0, 3, "civic_stone", top="roof_gravel")
    b.columns(-w / 2 + 3, w / 2 - 3, y0 - 2.5, 3.0, z - 2.5, 20, 0.85)
    b.box_xy(-w / 2, y0 - 3.8, w / 2, y0, z - 2.5, z, "trim_stone")
    z = b.floors(fp, z, 1, "limestone", top="roof_gravel")
    b.cornice(fp, z, 1.0, 0.8)
    for x in (-w * 0.35, w * 0.35):
        flagpole(b, x, y0 - 8, 3, 12)


def armory(b, rng, w=60.0):
    front = rect_xy(-w / 2, -40, w / 2, -24)
    z = b.floors(front, 0, 4, "brick_red", side_mats=sides("brick_red", "brick_red_blank", "brick_red_blank"))
    b.crenellate(front, z, 1.2, 2.4, "brick_red_blank")
    for sx in (-1, 1):
        t = rect(sx * (w / 2 - 4), -38, 9, 9)
        zt = b.floors(t, 0, 6, "brick_red")
        b.crenellate(t, zt, 1.4, 2.2, "brick_red_blank")
    ct = rect(0, -40.5, 12, 6)
    zt = b.floors(ct, 0, 5, "brick_red")
    b.crenellate(ct, zt, 1.4, 2.0, "brick_red_blank")
    door(b, 0, -43.5, 5, 6, 0, "wood")
    shed = rect_xy(-w / 2, -24, w / 2, 40)
    b.prism(shed, 0, 12, "warehouse_arch", top=None, side_mats=["warehouse_arch", "warehouse_arch",
                                                               "brick_red_blank", "warehouse_arch"])
    prof = [(-24, 12), (40, 12)] + [(8 + 32 * math.cos(math.pi * k / 10), 12 + 14 * math.sin(math.pi * k / 10))
                                    for k in range(1, 10)]
    b.extrude_x(prof, -w / 2, w / 2, "corrugated", "brick_red_blank")


# =========================================================== E. industrial & infrastructure
def parking_garage(b, rng, w=40.0, d=60.0, levels=7, store=False):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    z = 0.0
    if store:
        z = b.floors(fp, 0, 1, "store_modern", top=None)
        b.box_xy(w / 2 - 8, y0 - 0.05, w / 2 - 2, y0 + 0.02, 0, 4.0, "dark")
    z = b.floors(fp, z, levels, "garage_open", top="concrete_dark")
    b.parapet(fp, z, 1.1, 0.3, "concrete")
    b.box_xy(-w / 2 - 4, d / 2 - 8, -w / 2, d / 2, 0, z + 3.5, "concrete", "roof_tar")
    b.blade_sign(w / 2 - 1, y0, 6, 10, 2.0, "sign_blue")
    for x, y in ((-w / 4, -d / 4), (w / 4, -d / 4), (-w / 4, d / 4), (w / 4, d / 4)):
        b.beam((x, y, z), (x, y, z + 6), 0.2, "steel")
        b.box(x, y, 1.2, 0.5, z + 6, z + 6.3, "lamp")
    if not store:
        b.box_xy(-4, y0 - 0.05, 4, y0 + 0.02, 0, 3.4, "dark")


def pier_shed(b, rng, w=40.0, d=120.0):
    y0 = -d / 2
    b.box_xy(-w / 2 - 4, y0, w / 2 + 4, d / 2 + 6, -1.5, 0, "concrete_dark", "concrete")
    shed = rect_xy(-w / 2, y0 + 8, w / 2, d / 2)
    b.prism(shed, 0, 10, "corrugated", top=None)
    prof = [(-w / 2, 10), (w / 2, 10)] + [(w / 2 * math.cos(math.pi * k / 10), 10 + 7 * math.sin(math.pi * k / 10))
                                          for k in range(1, 10)]
    b.extrude_y(prof, y0 + 8, d / 2, "corrugated_red", "corrugated")
    head = rect_xy(-w / 2 - 1, y0, w / 2 + 1, y0 + 8)
    z = b.floors(head, 0, 1, "store_stone", top=None)
    z = b.floors(head, z, 2, "castiron_cream")
    b.cornice(head, z, 1.0, 0.6, "trim_white")
    b.pediment(-w / 2 - 1, w / 2 + 1, y0 - 0.6, y0 + 8, z, 6, "trim_white")
    b.cylinder(0, y0 - 0.65, 1.8, z + 2.0, z + 2.05, "trim_white")


def power_plant(b, rng, w=80.0, d=50.0):
    fp = rect(0, 0, w, d)
    z = b.floors(fp, 0, 5, "warehouse_arch", top="roof_tar")
    b.parapet(fp, z, 1.0, 0.4, "brick_red_blank")
    for i in range(4):
        x = -w / 2 + (i + 0.5) * w / 4
        b.frustum(circle(x, 4, 3.5, 12), z, z + 70, 0.7, "brick_red_blank", top="dark")
        b.cylinder(x, 4, 2.7, z + 69, z + 71, "trim_dark", "dark", n=12)
    b.box_xy(-w / 2, d / 2, -w / 2 + 25, d / 2 + 20, 0, 14, "corrugated", "roof_tar")
    for k in range(3):
        b.cylinder(w / 2 - 8 - k * 12, d / 2 + 10, 5, 0, 12, "steel", "steel", n=16)


def factory(b, rng, w=40.0, d=50.0):
    fp = rect(0, 0, w, d)
    z = b.floors(fp, 0, 3, "warehouse_brown", top=None,
                 side_mats=sides("warehouse_brown", "warehouse_brown", "brick_brown_blank"))
    teeth = 6
    tl = d / teeth
    for k in range(teeth):
        ya = -d / 2 + k * tl
        b.extrude_x([(ya, z), (ya + tl, z), (ya + tl, z + 3.5)], -w / 2, w / 2, "corrugated", "brick_brown_blank")
        b.face_auto([(w / 2, ya + tl + 0.03, z), (-w / 2, ya + tl + 0.03, z), (-w / 2, ya + tl + 0.03, z + 3.2),
                     (w / 2, ya + tl + 0.03, z + 3.2)], "glass_plain")
    b.frustum(circle(w / 2 - 5, d / 2 - 5, 2.5, 10), 0, 45, 0.6, "brick_red_blank", top="dark")
    b.water_tower(-w / 2 + 6, d / 2 - 8, z + 3.5)
    b.box_xy(-w / 2, -d / 2 - 2.5, -w / 2 + 14, -d / 2, 0, 1.2, "concrete")
    for x in (-w / 2 + 3.5, -w / 2 + 10):
        b.box_xy(x - 1.8, -d / 2 - 0.05, x + 1.8, -d / 2 + 0.02, 1.2, 4.6, "garage_door_gray")


def gas_station(b, rng):
    b.box_xy(-16, -18, 16, 14, 0, 0.1, "concrete")
    b.box_xy(-11, -12, 11, 2, 5.0, 6.2, "trim_white")
    b.box_xy(-11.05, -12.05, 11.05, 2.05, 5.2, 5.8, "steel_red")
    for x in (-8, 8):
        for y in (-9, -1):
            b.box(x, y, 0.6, 0.6, 0.1, 5.0, "trim_white")
    for x in (-5, 0, 5):
        b.box(x, -5, 0.9, 1.6, 0.1, 0.4, "concrete")
        b.box(x, -5, 0.6, 1.0, 0.4, 2.1, "steel_red")
        b.box(x, -5.52, 0.4, 0.05, 1.3, 1.8, "sign_white")
    shop = rect_xy(-9, 5, 9, 13)
    z = b.floors(shop, 0, 1, "store_modern", side_mats=sides("store_modern", "concrete"))
    b.box_xy(-9, 4.6, 9, 5, z - 1, z, "steel_red")
    b.beam((13, -15, 0.1), (13, -15, 8), 0.4, "steel")
    b.box_xy(11.5, -15.3, 14.5, -14.7, 8, 11, "sign_white")
    b.box_xy(11.5, -15.35, 14.5, -15.3, 10, 11, "steel_red")


def telephone_exchange(b, rng, w=40.0, d=40.0):
    z = b.floors(rect(0, 0, w, d), 0, 1, "store_stone", top=None)
    tiers = [(1.0, 1.0, 14), (0.85, 0.85, 8), (0.65, 0.65, 4)]
    fp = None
    for sx, sy, n in tiers:
        fp = rect(0, 0, w * sx, d * sy)
        z = b.floors(fp, z, n, "telephone_stone")
        b.parapet(fp, z, 1.0, 0.3, "trim_stone")
    b.box(0, 0, w * 0.35, d * 0.35, z, z + 6, "concrete", "roof_tar")
    b.beam((0, 0, z + 6), (0, 0, z + 30), 1.2, "steel")
    for k in range(3):
        a = 2 * math.pi * k / 3
        cx, cy = 3 * math.cos(a), 3 * math.sin(a)
        b.cylinder(cx, cy, 1.4, z + 18 + k * 3, z + 18.6 + k * 3, "trim_white", n=10)


def self_storage(b, rng, w=35.0, d=50.0):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    z = b.floors(fp, 0, 1, "store_modern", top=None, side_mats=sides("store_modern", "concrete"))
    z = b.floors(fp, z, 6, "storage_orange", side_mats=sides("storage_orange", "concrete"))
    b.box_xy(-w / 2 + 2, y0 - 0.3, w / 2 - 2, y0, z - 3.0, z - 0.5, "sign_white")
    b.box_xy(-w / 2, y0 - 0.2, w / 2, y0, 4.6, 5.0, "steel")
    b.parapet(fp, z, 0.8, 0.3, "concrete")


def ferry_terminal(b, rng, w=60.0, d=30.0):
    y0 = -d / 2
    b.box_xy(-w / 2 - 5, y0 - 5, w / 2 + 5, d / 2 + 25, -1.0, 0, "concrete_dark", "concrete")
    hall = rect(0, 0, w, d)
    z = b.floors(hall, 0, 3, "glass_teal", top=None)
    prof = [(y0, z), (d / 2, z)] + [(d / 2 * math.cos(math.pi * k / 10), z + 8 * math.sin(math.pi * k / 10))
                                    for k in range(1, 10)]
    b.extrude_x(prof, -w / 2 - 1.5, w / 2 + 1.5, "steel", "glass_teal")
    for i in range(3):
        x = -w / 3 + i * w / 3
        b.box_xy(x - 4, d / 2, x + 4, d / 2 + 22, 3, 7, "steel", "steel")
        b.box_xy(x - 3.5, d / 2 - 0.01, x + 3.5, d / 2 + 22.01, 3.5, 6.5, "glass_plain")
    b.box_xy(-10, y0 - 0.3, 10, y0, z - 2.8, z - 1.2, "sign_blue")


def construction(b, rng, w=30.0, d=30.0, floors=12, done=5):
    fp = rect(0, 0, w, d)
    z = b.floors(fp, 0, done, "glass_blue")
    fh = 4.0
    n = 4
    for f in range(done, floors):
        zf = f * fh
        b.box_xy(-w / 2, -d / 2, w / 2, d / 2, zf, zf + 0.35, "concrete")
        for i in range(n):
            for j in range(n):
                x = -w / 2 + 0.4 + i * (w - 0.8) / (n - 1)
                y = -d / 2 + 0.4 + j * (d - 0.8) / (n - 1)
                b.beam((x, y, zf), (x, y, zf + fh), 0.5, "steel_red")
        b.box_xy(-w / 2, -d / 2, w / 2, -d / 2 + 0.4, zf + fh - 0.5, zf + fh, "steel_red")
        b.box_xy(-w / 2, d / 2 - 0.4, w / 2, d / 2, zf + fh - 0.5, zf + fh, "steel_red")
    top = floors * fh
    b.box_xy(-w / 2, -d / 2, w / 2, d / 2, top, top + 0.35, "concrete")
    cx, cy = w / 2 + 4, 0
    ch = top + 25
    b.box(cx, cy, 2.2, 2.2, 0, ch, "steel_yellow")
    b.box(cx, cy, 3.0, 3.0, ch, ch + 3, "steel_yellow")
    b.box_xy(cx - 45, cy - 0.8, cx + 1.5, cy + 0.8, ch + 3, ch + 4.5, "steel_yellow")
    b.box_xy(cx + 1.5, cy - 0.8, cx + 16, cy + 0.8, ch + 3, ch + 4.2, "steel_yellow")
    b.box_xy(cx + 11, cy - 1.5, cx + 16, cy + 1.5, ch + 0.5, ch + 3, "concrete")
    b.beam((cx, cy, ch + 4.5), (cx, cy, ch + 10), 0.6, "steel_yellow")
    b.beam((cx, cy, ch + 10), (cx - 40, cy, ch + 4.5), 0.08, "steel")
    b.beam((cx, cy, ch + 10), (cx + 15, cy, ch + 4.2), 0.08, "steel")
    b.box(cx + 1.5, cy - 1.8, 2.0, 1.6, ch + 1, ch + 3, "trim_white")
    b.beam((cx - 30, cy, ch + 3), (cx - 30, cy, top + 6), 0.05, "steel")
    b.box(cx - 30, cy, 3, 3, top + 4, top + 6, "steel")
    for k in range(3):
        b.box_xy(-w / 2 - 6, -d / 2 - 6 + k * 3.2, -w / 2 - 0.5, -d / 2 - 3.5 + k * 3.2, 0, 2.6, "trim_white")
    b.box_xy(-w / 2 - 8, -d / 2 - 8, w / 2 + 8, -d / 2 - 7.9, 0, 2.4, "wood")


# =========================================================== F. landmarks (generic)
def arena(b, rng, r=55.0):
    z = b.floors(circle(0, 0, r, 32), 0, 1, "lobby_glass", top=None, continuous_u=True)
    z = b.floors(circle(0, 0, r, 32), z, 4, "ribbon_dark", top=None, continuous_u=True)
    b.prism(circle(0, 0, r + 0.4, 32), z - 3.2, z - 1.6, "sign_blue", top=None)
    b.dome(0, 0, r, z, "steel", n=32, rings=5, squash=0.18)
    for k in range(4):
        a = math.pi / 4 + k * math.pi / 2
        b.box(r * 0.95 * math.cos(a), r * 0.95 * math.sin(a), 8, 8, 0, 4.5, "trim_dark")


def observation_tower(b, rng, w=45.0, floors=110):
    z = b.floors(rect(0, 0, w + 10, w + 10), 0, 2, "lobby_glass", top="roof_tar")
    fp = chamfer(0, 0, w, w, w * 0.12)
    z = b.floors(fp, z, floors - 6, "glass_blue", continuous_u=True)
    z = b.floors(chamfer(0, 0, w + 6, w + 6, w * 0.15), z, 3, "glass_dark", continuous_u=True)
    b.parapet(rect(0, 0, w + 6, w + 6), z, 1.4, 0.2, "glass_plain")
    z = b.floors(chamfer(0, 0, w * 0.7, w * 0.7, w * 0.08), z, 3, "glass_blue", continuous_u=True)
    b.frustum(chamfer(0, 0, w * 0.7, w * 0.7, w * 0.08), z, z + 14, 0.4, "steel", top="steel")
    b.antenna(0, 0, z + 14, 80, 1.6)


def gothic_university(b, rng, w=70.0, d=50.0):
    y0 = -d / 2
    ww = 14.0
    parts = [rect_xy(-w / 2, d / 2 - ww, w / 2, d / 2), rect_xy(-w / 2, y0, -w / 2 + ww, d / 2 - ww),
             rect_xy(w / 2 - ww, y0, w / 2, d / 2 - ww)]
    for i, fp in enumerate(parts):
        z = b.floors(fp, 0, 3, "gothic_stone", top=None)
        x0, fy0, x1, fy1 = bounds(fp)
        b.gable(x0, fy0, x1, fy1, z, 7, "slate", "gothic_stone", axis="x" if i == 0 else "y", overhang=0.3)
    t = rect(0, d / 2 - ww - 4, 12, 12)
    zt = b.floors(t, 0, 7, "gothic_stone")
    b.crenellate(t, zt, 1.6, 2.0, "gothic_stone")
    for dx, dy in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
        b.box(dx * 5.5, d / 2 - ww - 4 + dy * 5.5, 2, 2, zt, zt + 5, "trim_stone")
        b.spire(dx * 5.5, d / 2 - ww - 4 + dy * 5.5, zt + 5, 4, 1.2, "trim_stone", n=4)
    door(b, 0, d / 2 - ww - 10, 3.0, 5.0, 0, "wood")
    b.box_xy(-w / 2 + ww + 2, y0 + 2, w / 2 - ww - 2, d / 2 - ww - 12, 0, 0.12, "grass")


def convention_center(b, rng, w=160.0, d=90.0):
    fp = rect(0, 0, w, d)
    y0 = -d / 2
    z = b.floors(fp, 0, 6, "glass_blue", top="steel")
    for i in range(9):
        x = -w / 2 + i * w / 8
        b.box_xy(x - 1.0, y0 - 1.0, x + 1.0, d / 2 + 1.0, z, z + 3, "steel")
    b.box_xy(-w / 2 - 1, y0 - 1, w / 2 + 1, d / 2 + 1, z + 3, z + 3.6, "steel")
    b.box_xy(-30, y0 - 14, 30, y0, 8, 9, "trim_white")
    for x in (-28, -14, 0, 14, 28):
        b.beam((x, y0 - 13, 0), (x, y0 - 13, 8), 0.5, "steel")
    b.box_xy(-20, y0 - 14.05, 20, y0 - 13.95, 9, 11, "sign_blue")


def eco_tower(b, rng, w=40.0, floors=60):
    z = b.floors(rect(0, 0, w + 8, w + 8), 0, 1, "lobby_glass", top="roof_tar")
    n = 4
    for k in range(n):
        s = 1.0 - k * 0.15
        fp = rect(k * 2.0, k * 2.0, w * s, w * s)
        z = b.floors(fp, z, floors // n, "glass_green", top="grass")
        x0, y0, x1, y1 = bounds(fp)
        b.parapet(fp, z, 1.1, 0.15, "glass_plain")
        nxt = rect((k + 1) * 2.0, (k + 1) * 2.0, w * (s - 0.15), w * (s - 0.15))
        nx0, ny0, nx1, ny1 = bounds(nxt)
        for t in range(4):
            tx = x0 + 2.5 + (x1 - x0 - 5) * rng.random()
            if k < n - 1:
                ty = y0 + 1.5 + max(0.3, ny0 - y0 - 3) * rng.random()
            else:
                ty = y0 + 2.5 + (y1 - y0 - 5) * rng.random()
            b.cylinder(tx, ty, 0.25, z, z + 2.5, "wood", n=6)
            b.pyramid(circle(tx, ty, 1.5, 8), z + 1.5, z + 5.5, "grass")
    b.antenna(n * 2.0, n * 2.0, z, 30, 0.8)


# =========================================================== REGISTRY
R, C, S, V, I, L = "Residential", "Commercial", "Retail & Mixed-Use", "Civic & Institutional", \
    "Industrial & Infrastructure", "Landmark-scale"

# ---- A. Residential (25)
reg("brownstone_classic", "Classic Brownstone", R, "4-storey brownstone rowhouse with stoop and cornice.",
    brownstone)
reg("brownstone_baywindow", "Brownstone with Bay Window", R, "Brownstone with a three-sided oriel bay.",
    brownstone, bay=True)
reg("brownstone_mansard", "Mansard Brownstone", R, "Brownstone topped with a slate mansard and dormers.",
    brownstone, mansard=True)
reg("sandstone_rowhouse", "Sandstone Rowhouse", R, "3-storey light sandstone rowhouse.",
    brownstone, floors=3, mat="brownstone_light", stoop_mat="limestone_plain", cornice="trim_stone",
    side="brick_tan_blank")
reg("brownstone_wide", "Wide Brownstone", R, "5-storey double-width brownstone.",
    brownstone, floors=5, w=9.0, d=16.0)
reg("sandstone_bay_mansard", "Sandstone Bay & Mansard", R, "Light rowhouse with bay window and mansard roof.",
    brownstone, floors=3, mat="brownstone_light", bay=True, mansard=True, stoop_mat="limestone_plain",
    cornice="trim_stone", side="brick_tan_blank")
reg("tenement_red", "Red Brick Walk-up", R, "5-storey walk-up with storefront and fire escape.",
    tenement)
reg("tenement_tan", "Tan Walk-up with Awning", R, "6-storey tan brick walk-up with awninged shop.",
    tenement, floors=6, w=9.0, mat="tenement_tan", side="brick_tan_blank", awn="awning_red",
    cornice="trim_white")
reg("tenement_corner_yellow", "Corner Walk-up (Yellow)", R, "Corner walk-up with shopfronts on two streets.",
    tenement, floors=5, w=10.0, d=20.0, mat="tenement_yellow", side="brick_yellow_blank", corner=True,
    awn="awning_green")
reg("tenement_narrow", "Narrow Walk-up", R, "Slim 5-storey brown brick walk-up.",
    tenement, floors=5, w=6.5, d=16.0, mat="tenement_brown", side="brick_brown_blank", awn="awning_blue")
reg("tenement_corner_wide", "Wide Corner Walk-up", R, "Wide 6-storey corner tenement.",
    tenement, floors=6, w=13.0, d=22.0, corner=True, awn="awning_black")
reg("tenement_oldlaw", "Old-law Tenement", R, "4-storey tenement without fire escape, with shop.",
    tenement, floors=4, w=7.5, d=17.0, mat="tenement_tan", side="brick_tan_blank", fire=False,
    store="store_brick")
reg("federal_rowhouse", "Federal Rowhouse", R, "2.5-storey Federal-style house, dormers and chimneys.",
    federal)
reg("federal_threestory", "Federal Townhouse", R, "3-storey Federal townhouse with gable roof.",
    federal, w=9.0, d=13.0, floors=3)
reg("federal_shop", "Federal House with Shop", R, "Federal house with a ground-floor shop.",
    federal, w=7.5, d=12.0, floors=2, store=True)
reg("prewar_apartment", "Prewar Apartment Building", R, "14-storey brick co-op with limestone base.",
    prewar)
reg("prewar_shops", "Prewar Apartments over Shops", R, "12-storey apartment block with retail base.",
    prewar, w=34.0, floors=12, store="store_stone", base_floors=1)
reg("prewar_penthouse", "Prewar Tower with Penthouse", R, "18-storey prewar tower with setback penthouse.",
    prewar, w=28.0, d=28.0, floors=18, setback=True, mat="brick_tan", crown_floors=2)
reg("courtyard_brick", "Courtyard Apartments", R, "U-shaped 10-storey building around a garden court.",
    courtyard)
reg("courtyard_limestone", "Limestone Courtyard Building", R, "8-storey limestone/yellow brick court building.",
    courtyard, w=46.0, d=34.0, floors=8, mat="brick_yellow", base="limestone")
reg("postwar_whitebrick", "White-brick Apartment Tower", R, "20-storey postwar white-brick with balconies.",
    postwar)
reg("postwar_tan_setback", "Tan-brick Setback Apartments", R, "16-storey wedding-cake setback apartments.",
    postwar, w=36.0, d=24.0, floors=16, mat="postwar_tan", side="brick_tan_blank", tiers=4)
reg("project_cross_tower", "Housing Tower (Cross Plan)", R, "14-storey cross-plan public housing tower.",
    project_cross)
reg("project_slab", "Housing Slab", R, "12-storey slab block with stair towers.",
    project_slab)
reg("glass_condo", "Glass Condo Tower", R, "34-storey glass condo with continuous balconies.",
    glass_condo)

# ---- B. Commercial (30)
reg("castiron_white", "Cast-iron Loft (White)", C, "5-storey arched cast-iron loft building.",
    castiron)
reg("castiron_cream", "Cast-iron Loft (Cream)", C, "6-storey cream cast-iron loft with fire escape.",
    castiron, floors=6, w=12.0, mat="castiron_cream", fire=True, cornice="trim_stone")
reg("castiron_gray", "Cast-iron Loft (Gray)", C, "5-storey gray cast-iron commercial building.",
    castiron, floors=5, w=18.0, mat="castiron_gray", cornice="trim_dark", store="store_mixed")
reg("castiron_green", "Cast-iron Loft (Green)", C, "4-storey green-painted cast-iron building.",
    castiron, floors=4, w=10.0, mat="castiron_green", cornice="trim_white", fire=True)
reg("warehouse_arched", "Arched Brick Warehouse", C, "7-storey Romanesque warehouse with loading dock.",
    warehouse)
reg("warehouse_brown", "Brown Brick Warehouse", C, "8-storey industrial loft warehouse.",
    warehouse, floors=8, w=30.0, d=35.0, mat="warehouse_brown", side="brick_brown_blank")
reg("warehouse_loft_shops", "Warehouse Lofts over Shops", C, "6-storey converted loft with stores.",
    warehouse, floors=6, w=22.0, store="store_brick", water=True)
reg("early_sky_cupola", "Beaux-Arts Tower with Cupola", C, "22-storey early skyscraper with domed cupola.",
    early_sky)
reg("early_sky_mansard", "Mansard-crowned Office", C, "26-storey office with copper mansard crown.",
    early_sky, floors=26, w=34.0, d=28.0, crown="mansard", mat="terracotta")
reg("early_sky_cornice", "Classic Office Block", C, "18-storey limestone office with heavy cornice.",
    early_sky, floors=18, w=36.0, d=30.0, crown="cornice", mat="limestone", shaft_side="brick_tan_blank")
reg("deco_spire", "Art Deco Spire Tower", C, "Setback Art Deco tower with stepped crown and spire.",
    deco)
reg("deco_gold_pyramid", "Art Deco Gold Pyramid", C, "Art Deco tower with gilded pyramid roof.",
    deco, w=36.0, d=36.0, tiers=((1.0, 1.0, 22), (0.75, 0.75, 14), (0.55, 0.55, 8)), mat="deco_gray",
    crown="pyramid", crown_mat="gold")
reg("deco_stepped", "Art Deco Ziggurat", C, "Brick Art Deco tower stepping up in many tiers.",
    deco, w=44.0, d=40.0, tiers=((1.0, 1.0, 12), (0.85, 0.85, 8), (0.7, 0.7, 8), (0.55, 0.55, 6)),
    mat="deco_brick", crown="stepped", crown_mat="trim_stone")
reg("deco_mast", "Art Deco Mast Tower", C, "Tall Art Deco tower with mooring-mast style crown.",
    deco, w=50.0, d=40.0, tiers=((1.0, 1.0, 6), (0.85, 0.8, 30), (0.6, 0.55, 25), (0.45, 0.4, 10)),
    crown="mast")
reg("deco_brick_midrise", "Art Deco Brick Midrise", C, "24-storey brick Art Deco office.",
    deco, w=32.0, d=30.0, tiers=((1.0, 1.0, 14), (0.8, 0.8, 6), (0.6, 0.6, 4)), mat="deco_brick",
    crown="pyramid", crown_mat="copper")
reg("glass_slab_dark", "Dark Glass Slab on Plaza", C, "38-storey International Style slab on a plaza.",
    glass_slab)
reg("glass_slab_green", "Green Glass Slab", C, "30-storey green curtain-wall slab with spandrels.",
    glass_slab, w=45.0, d=22.0, floors=30, mat="glass_green", plaza=False, podium=(1.6, 2.0, 2))
reg("glass_podium_silver", "Silver Tower on Podium", C, "44-storey silver curtain wall over a podium.",
    glass_slab, w=40.0, d=32.0, floors=44, mat="glass_silver", plaza=False, podium=(1.5, 1.6, 4))
reg("glass_bronze", "Bronze Glass Tower", C, "36-storey bronze-tinted glass office.",
    glass_slab, w=38.0, d=30.0, floors=36, mat="glass_bronze", plaza=True)
reg("postmodern_gable", "Postmodern Gabled Tower", C, "Pink granite tower with a broken pediment top.",
    postmodern)
reg("postmodern_pyramid", "Postmodern Pyramid Tower", C, "Dark granite chamfered tower with pyramid crown.",
    postmodern, floors=42, mat="granite_dark", top="pyramid", top_mat="copper")
reg("postmodern_stepped", "Postmodern Stepped Tower", C, "Granite tower with stepped crown.",
    postmodern, w=30.0, d=30.0, floors=34, top="stepped")
reg("supertall_tapered", "Tapered Supertall", C, "100-storey tapering glass supertall with spire.",
    supertall)
reg("supertall_round", "Cylindrical Supertall", C, "Round glass tower with crown ring and mast.",
    supertall, w=44.0, floors=80, mat="glass_blue", shape="round", spire=40.0)
reg("supertall_chamfer", "Chamfered Supertall", C, "Chamfered tower with sloped glass crown.",
    supertall, w=48.0, floors=90, mat="glass_dark", shape="chamfer", spire=30.0)
reg("pencil_glass", "Glass Pencil Tower", C, "Super-slender 90-storey glass residential tower.",
    pencil)
reg("pencil_terracotta", "Terracotta Pencil Tower", C, "Slender tower with terracotta piers and setbacks.",
    pencil, w=18.0, d=20.0, floors=75, mat="terracotta", crown="stepped")
reg("twisting_tower", "Twisting Tower", C, "Glass tower rotating as it rises.",
    twisting)
reg("ribbon_concrete", "Concrete Ribbon-window Office", C, "1960s office with strip windows on a podium.",
    midrise_ribbon)
reg("ribbon_white", "White Panel Office", C, "1970s white panel office block.",
    midrise_ribbon, w=40.0, d=28.0, floors=16, mat="ribbon_white", podium=False)

# ---- C. Retail & mixed-use (15)
reg("bodega_corner", "Corner Bodega", S, "2-storey corner deli/grocery with awnings.",
    bodega)
reg("bodega_midblock", "Mid-block Shop", S, "3-storey mid-block shop with flat above.",
    bodega, floors=2, w=6.0, mat="brick_red", side="brick_red_blank", awn="awning_red", corner=False,
    sign="sign_yellow")
reg("diner", "Chrome Diner", S, "Stainless-steel diner with rooftop sign.",
    diner)
reg("deli_apartments", "Deli with Apartments", S, "3-storey mixed-use deli building.",
    tenement, floors=2, w=7.0, d=14.0, mat="tenement_brown", side="brick_brown_blank", fire=False,
    awn="awning_green", water=False)
reg("restaurant_row", "Restaurant Row Building", S, "4-storey restaurant building with red awning.",
    tenement, floors=3, w=8.0, d=16.0, mat="tenement_yellow", side="brick_yellow_blank", awn="awning_red",
    store="store_brick", water=False)
reg("dept_store", "Department Store", S, "9-storey limestone department store with flags.",
    dept_store)
reg("dept_store_clock", "Department Store with Clock", S, "Large store with corner clock tower.",
    dept_store, w=70.0, d=50.0, floors=8, mat="terracotta", flags=False, corner_clock=True)
reg("chain_store", "Chain Pharmacy", S, "Modern 2-storey chain store box.",
    chain_store)
reg("bank_portico", "Classical Bank", S, "Columned bank with pediment and steps.",
    bank)
reg("bank_tower", "Bank Tower", S, "Bank hall with 12-storey office tower above.",
    bank, w=28.0, d=34.0, tower=12)
reg("theater_marquee", "Broadway Theater", S, "Theater with lit marquee, blade sign and fly tower.",
    theater)
reg("cinema", "Movie Palace", S, "Movie palace with large marquee.",
    theater, w=26.0, d=44.0, front_floors=3, mat="limestone_ornate", marquee="sign_yellow", blade="sign_red",
    fly_h=22.0)
reg("hotel_mansard", "Grand Hotel", S, "24-storey hotel with copper mansard and flags.",
    hotel)
reg("hotel_glass", "Modern Glass Hotel", S, "30-storey teal glass hotel on a podium.",
    glass_hotel)
reg("nightclub", "Nightclub", S, "Dark brick club with neon signs.",
    nightclub)

# ---- D. Civic & institutional (15)
reg("firehouse", "Firehouse", V, "3-storey firehouse with two apparatus doors.",
    firehouse)
reg("firehouse_small", "Single-bay Firehouse", V, "Narrow firehouse with one red door.",
    firehouse, w=9.0, d=22.0, floors=4, mat="brick_tan", side="brick_tan_blank", doors=1)
reg("police_precinct", "Police Precinct", V, "Limestone station house with green lamps.",
    precinct)
reg("courthouse", "Courthouse", V, "Colonnaded courthouse with copper dome.",
    courthouse)
reg("city_hall", "City Hall", V, "Civic hall with wings, portico and cupola.",
    city_hall)
reg("public_library", "Public Library", V, "Beaux-Arts library with grand steps.",
    library)
reg("school_h", "Public School (H-plan)", V, "5-storey H-plan brick school.",
    school)
reg("school_gym", "School with Gymnasium", V, "L-plan school with barrel-roofed gym.",
    school, w=60.0, d=44.0, floors=4, shape="L")
reg("hospital", "Hospital", V, "Medical centre with tower, podium and helipad.",
    hospital)
reg("gothic_church", "Gothic Church", V, "Twin-spired Gothic Revival church with rose window.",
    gothic_church)
reg("small_church", "Neighborhood Church", V, "Brick church with a single steeple.",
    small_church)
reg("synagogue", "Moorish Revival Synagogue", V, "Banded facade with twin onion-domed towers.",
    synagogue)
reg("museum_modern", "Modern Art Museum", V, "Stack of shifted boxes.",
    museum)
reg("post_office", "General Post Office", V, "Monumental colonnade on a full-width stair.",
    post_office)
reg("armory", "Regiment Armory", V, "Crenellated brick armory with drill hall.",
    armory)

# ---- E. Industrial & infrastructure (10)
reg("parking_garage", "Parking Garage", I, "7-level open parking structure.",
    parking_garage)
reg("parking_retail", "Parking over Retail", I, "Parking levels over a retail base.",
    parking_garage, w=36.0, d=50.0, levels=6, store=True)
reg("pier_shed", "Pier Shed", I, "Waterfront pier with cast-iron headhouse.",
    pier_shed)
reg("power_plant", "Power Station", I, "Brick power station with four smokestacks.",
    power_plant)
reg("factory_sawtooth", "Sawtooth Factory", I, "Factory with sawtooth roof, chimney, water tank.",
    factory)
reg("gas_station", "Gas Station", I, "Canopy, pumps and convenience shop.",
    gas_station)
reg("telephone_exchange", "Telephone Exchange", I, "Near-windowless setback switching building.",
    telephone_exchange)
reg("self_storage", "Self-storage", I, "Orange panel storage building.",
    self_storage)
reg("ferry_terminal", "Ferry Terminal", I, "Glass terminal with barrel roof and slips.",
    ferry_terminal)
reg("construction_site", "Tower Under Construction", I, "Half-built tower with steel frame and crane.",
    construction)

# ---- F. Landmark-scale (5)
reg("arena", "Arena", L, "Round arena with LED band and shallow dome.",
    arena)
reg("observation_tower", "Observation Supertall", L, "110-storey tower with cantilevered sky deck.",
    observation_tower)
reg("gothic_university", "Gothic University Hall", L, "Collegiate Gothic quad with crenellated tower.",
    gothic_university)
reg("convention_center", "Convention Center", L, "Huge glass exhibition hall with steel roof trusses.",
    convention_center)
reg("eco_tower", "Terraced Eco Tower", L, "Glass tower with planted sky terraces.",
    eco_tower)

assert len(ENTRIES) == 100, len(ENTRIES)
