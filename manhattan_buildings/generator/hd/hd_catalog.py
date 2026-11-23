"""30 detailed mid/high-rise Manhattan building types (40-150 m) for the HD kit.

Each building is a spec run through `tower()`: a storefront/lobby base, a
stack of tiers (footprint + storeys + facade style + trim), then a crown and
rooftop equipment.  Front faces -Y.
"""
import math

from hd_builder import PROFILES, M, offset_poly  # noqa: F401
from builder import bounds, circle, rect, rect_xy

ENTRIES = []


def reg(key, name, desc, fn, **kw):
    i = len(ENTRIES) + 1
    ENTRIES.append(dict(id=i, key=key, name=name, desc=desc, seed=5000 + i * 11,
                        fn=lambda b, rng, _f=fn, _k=kw: _f(b, rng, **_k)))


# ====================================================================== styles
def S(**kw):
    d = dict(type="punched", wall="brick_red", bay=3.2, win_w=1.2, win_h=1.75, sill=0.85, depth=0.22,
             frame="frame_white", mullions=(1, 2), glass="win_res", edge=0.9)
    d.update(kw)
    return d


PREWAR_BRICK = S(lintel=("limestone", 0.28, 0.1), sill_mat="limestone", ac=0.10, bay=2.7, win_w=1.1, win_h=1.8)
PREWAR_LIME = S(wall="limestone", frame="frame_black", sill_mat="limestone", lintel=None, ac=0.05)
RUSTIC = S(wall="limestone_rustic", frame="frame_black", win_w=1.3, win_h=2.1, sill=0.9, depth=0.35,
           lintel=None, sill_mat="limestone", keystone=True)


def blank(wall):
    return dict(type="blank", wall=wall)


SHOPS = dict(pier="granite_black", bay=4.6, awnings=0.5, signs=True, frame="frame_black")
SHOPS_LIME = dict(pier="limestone", bay=4.8, awnings=0.4, signs=True, frame="frame_bronze")
SHOPS_CASTIRON = dict(pier="metal_black", bay=4.2, awnings=0.3, signs=True, frame="frame_black")
LOBBY_LIME = dict(pier="limestone_rustic", bay=4.0, signs=False, awnings=0, frame="frame_bronze", canopy=5.5,
                  canopy_mat="awning_green", canopy_posts=True, canopy_trim="frame_bronze")
LOBBY_MODERN = dict(pier="granite_black", bay=6.0, signs=False, awnings=0, frame="frame_alum", canopy=3.0,
                    canopy_mat="frame_black", sign_h=0.6, bulkhead=0.15)
LOBBY_GLASS = dict(pier="metal_black", bay=3.0, signs=False, awnings=0, frame="frame_black", recess=0.15,
                   bulkhead=0.0, sign_h=0.4, canopy=4.0, canopy_mat="metal_black")


def rounded_rect(cx, cy, w, d, r, seg=3):
    x0, x1, y0, y1 = cx - w / 2, cx + w / 2, cy - d / 2, cy + d / 2
    pts = []
    corners = [((x1 - r, y0 + r), -90), ((x1 - r, y1 - r), 0), ((x0 + r, y1 - r), 90), ((x0 + r, y0 + r), 180)]
    for (ccx, ccy), a0 in corners:
        for k in range(seg + 1):
            a = math.radians(a0 + 90 * k / seg)
            pts.append((ccx + r * math.cos(a), ccy + r * math.sin(a)))
    return pts


def chamfer_corner(w, d, c, corner="front_right"):
    """Rect with one chamfered (cut) corner: the classic Manhattan corner building."""
    x0, x1, y0, y1 = -w / 2, w / 2, -d / 2, d / 2
    if corner == "front_right":
        return [(x0, y0), (x1 - c, y0), (x1, y0 + c), (x1, y1), (x0, y1)]
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


# ====================================================================== core
def tower(b, rng, fh=3.1, gf=4.8, base=None, base_sides=None, entrance_edge=0, tiers=(), crown=None,
          roof=None, plaza=None):
    z = 0.0
    if plaza:
        px0, py0, px1, py1 = plaza
        b.solid(rect_xy(px0, py0, px1, py1), -0.25, 0.03, "granite_flamed", top="granite_flamed")
    t0 = tiers[0]
    if base is not None:
        bfp = t0.get("base_fp", t0["fp"])
        z = b.base_all(bfp, 0.0, gf, base, rng, entrance_edge, base_sides)
        if t0.get("base_fp"):
            b.cap(bfp, z, "hd_roof_membrane")
        b.band(t0["fp"], z, "band_flat", t0.get("trim", "limestone"))
    last = None
    for t in tiers:
        tfh = t.get("fh", fh)
        fp = t["fp"]
        z0 = z
        z = b.facades(fp, z, t["floors"], tfh, t["style"], rng, t.get("sides"))
        for bf in t.get("bands", []):
            b.band(fp, z0 + bf * tfh, t.get("band_profile", "band"), t.get("trim", "limestone"))
        for bal in t.get("balconies", []):
            e = bal.get("edge", 0)
            b.balconies(fp[e], fp[(e + 1) % len(fp)], z0, t["floors"], tfh, rng, **{k: v for k, v in bal.items()
                                                                                    if k != "edge"})
        top = t.get("top", {"kind": "cap"})
        k = top.get("kind")
        if k == "cornice":
            z = b.cornice(fp, z, top.get("profile", "cornice"), top.get("mat", "limestone"), top.get("brackets"),
                          top.get("roof", "roof_membrane"))
        elif k == "parapet":
            z = b.parapet(fp, z, top.get("h", 1.1), top.get("t", 0.3), top.get("wall", t["style"].get("wall",
                          "brick_red")), top.get("coping", "limestone"), top.get("roof", "roof_membrane"))
        elif k == "railing":
            b.cap(fp, z, M(top.get("roof", "roof_gravel")))
            b.sweep(fp, z, PROFILES["slab_edge"], top.get("edge_mat", "concrete"))
            b.railing(offset_poly(fp, -0.1), z + 0.35, 1.1, glass=top.get("glass", True))
        elif k == "cap":
            b.cap(fp, z, M(top.get("roof", "roof_membrane")))
        last = (fp, z, t)
    fp, z, t = last
    if crown:
        z = crown[0](b, rng, fp, z, t, **crown[1])
    if roof:
        b.roof_kit(roof.get("fp", fp), roof.get("z", z), rng, **{k: v for k, v in roof.items() if k not in ("fp", "z")})
    return z


# ====================================================================== crowns
def crown_twin_turrets(b, rng, fp, z, t, size=7.0, floors=3, wall="brick_tan", roofm="copper"):
    x0, y0, x1, y1 = bounds(fp)
    st = dict(t["style"], wall=wall, bay=2.6)
    top = z
    for sx in (x0 + size / 2 + 1.5, x1 - size / 2 - 1.5):
        tf = rect(sx, y0 + size / 2 + 1.0, size, size)
        zt = b.facades(tf, z, floors, t.get("fh", 3.1), st, rng)
        zt = b.cornice(tf, zt, "cornice_small", "limestone")
        b.pyramid(offset_poly(tf, 0.2), zt, zt + size * 0.7, M(roofm))
        b.beam((sx, y0 + size / 2 + 1.0, zt + size * 0.7), (sx, y0 + size / 2 + 1.0, zt + size * 0.7 + 1.5), 0.12,
               "hd_gold")
        top = max(top, zt + size * 0.7)
    return z


def crown_ziggurat(b, rng, fp, z, t, steps=3, step_h=4.0, shrink=0.18, mat=None, finial=12.0):
    x0, y0, x1, y1 = bounds(fp)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    w, d = x1 - x0, y1 - y0
    mat = mat or t["style"]["wall"]
    for k in range(steps):
        s = 1 - shrink * (k + 1)
        f = rect(cx, cy, w * s, d * s)
        b.solid(f, z, z + step_h, mat, top="roof_membrane")
        b.sweep(f, z + step_h, PROFILES["deco_cap"], "limestone")
        z += step_h + 0.95
    if finial:
        b.beam((cx, cy, z), (cx, cy, z + finial), 0.5, "hd_steel")
        b.spire(cx, cy, z, finial * 0.4, 1.2, "hd_steel", n=8)
    return z


def crown_lantern(b, rng, fp, z, t, r=5.0, h=8.0, dome="copper", spire=10.0, cols=8, mat="limestone"):
    x0, y0, x1, y1 = bounds(fp)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    b.prism(circle(cx, cy, r + 0.6, 16), z, z + 1.2, M(mat), top=M(mat))
    b.prism(circle(cx, cy, r * 0.75, 12), z + 1.2, z + 1.2 + h, "hd_win_office", top=None)
    for k in range(cols):
        a = 2 * math.pi * k / cols
        b.cylinder(cx + r * math.cos(a), cy + r * math.sin(a), 0.35, z + 1.2, z + 1.2 + h, M(mat), n=8)
    zt = z + 1.2 + h
    b.prism(circle(cx, cy, r + 0.5, 16), zt, zt + 0.9, M(mat), top=M(mat))
    zd = b.dome(cx, cy, r * 0.85, zt + 0.9, M(dome), n=16, rings=5)
    if spire:
        b.spire(cx, cy, zd - 0.2, spire, 0.6, "hd_gold", n=8)
    return zd


def crown_pyramid(b, rng, fp, z, t, h_ratio=0.45, mat="copper", inset=0.5, finial=4.0):
    f = offset_poly(fp, -inset)
    x0, y0, x1, y1 = bounds(fp)
    h = max(x1 - x0, y1 - y0) * h_ratio
    b.pyramid(f, z, z + h, M(mat))
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    if finial:
        b.beam((cx, cy, z + h - 0.3), (cx, cy, z + h + finial), 0.25, "hd_gold")
    return z + h


def crown_mansard(b, rng, fp, z, t, h=6.0, mat="copper", inset=2.2, dormers=True):
    x0, y0, x1, y1 = bounds(fp)
    w = x1 - x0
    s = (w - 2 * inset) / w
    b.frustum(fp, z, z + h, s, M(mat), top=M("roof_membrane"))
    if dormers and b.lod < 1:
        n = max(2, int(w // 5))
        for i in range(n):
            x = x0 + w * (i + 0.5) / n
            yy = y0 + inset * 0.45
            b.box_xy(x - 0.8, yy - 0.2, x + 0.8, yy + 1.6, z + 1.0, z + 3.6, "hd_limestone")
            b.box_xy(x - 0.5, yy - 0.25, x + 0.5, yy - 0.2, z + 1.4, z + 3.2, "hd_win_res")
            b.gable(x - 0.8, yy - 0.2, x + 0.8, yy + 1.6, z + 3.6, 0.9, M(mat), axis="y", overhang=0.1)
    return z + h


def crown_gothic(b, rng, fp, z, t, h=10.0, mat="copper", pinnacle=6.0, stone="terracotta_white"):
    x0, y0, x1, y1 = bounds(fp)
    b.frustum(fp, z, z + h, 0.15, M(mat), top=M(mat))
    for x, y in ((x0, y0), (x1, y0), (x1, y1), (x0, y1)):
        b.box(x, y, 1.6, 1.6, z - 2, z + 2.5, M(stone), M(stone))
        b.spire(x, y, z + 2.5, pinnacle, 0.9, M(stone), n=4)
    w = x1 - x0
    for i in range(1, 4):
        x = x0 + w * i / 4
        b.box(x, y0, 0.8, 0.8, z - 1, z + 1.5, M(stone), M(stone))
        b.spire(x, y0, z + 1.5, pinnacle * 0.6, 0.5, M(stone), n=4)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    b.beam((cx, cy, z + h - 0.5), (cx, cy, z + h + 6), 0.2, "hd_gold")
    return z + h


def crown_mech(b, rng, fp, z, t, h=6.0, inset=2.0, mat="louver"):
    f = offset_poly(fp, -inset)
    b.solid(f, z, z + h, M(mat), top="roof_membrane")
    return z + h


def crown_fins(b, rng, fp, z, t, h=10.0, mat="frame_alum", spacing=1.5):
    nfp = len(fp)
    for i in range(nfp):
        a, c = fp[i], fp[(i + 1) % nfp]
        L = math.hypot(c[0] - a[0], c[1] - a[1])
        k = max(1, int(L / spacing))
        for q in range(k + 1):
            tq = q / k
            x, y = a[0] + (c[0] - a[0]) * tq, a[1] + (c[1] - a[1]) * tq
            b.beam((x, y, z), (x, y, z + h), 0.18, M(mat), 0.6)
    b.cap(fp, z, "hd_roof_membrane")
    return z + h


# ====================================================================== the 30 buildings
def b01_ues_coop(b, rng):
    W, D = 30.0, 26.0
    fp = rect(0, 0, W, D)
    side_blank = blank("brick_redbrown")
    tower(b, rng, fh=3.1, gf=5.0, base=LOBBY_LIME, base_sides=[LOBBY_LIME, LOBBY_LIME, blank("brick_redbrown"),
                                                               blank("brick_redbrown")],
          tiers=[dict(fp=fp, floors=2, style=RUSTIC, sides=[None, None, side_blank, side_blank], bands=[2],
                      top={"kind": None}),
                 dict(fp=fp, floors=10, style=PREWAR_BRICK, sides=[None, None, None, side_blank], bands=[10],
                      top={"kind": None}),
                 dict(fp=fp, floors=2, style=S(wall="limestone", arch=True, frame="frame_black", win_h=1.6,
                                               lintel=("limestone",), sill_mat="limestone"),
                      sides=[None, None, side_blank, side_blank],
                      top={"kind": "cornice", "profile": "cornice_grand", "brackets": 1.1})],
          roof=dict(water=1, hvac=3, wall="brick_redbrown"))


def b02_uws_twin(b, rng):
    W, D = 36.0, 30.0
    fp = rect(0, 0, W, D)
    st = S(wall="brick_tan", lintel=("limestone", 0.3, 0.12), sill_mat="limestone", pair=2, bay=3.6, win_w=0.95,
           ac=0.08)
    tower(b, rng, fh=3.1, gf=5.0, base=SHOPS_LIME,
          tiers=[dict(fp=fp, floors=3, style=S(wall="limestone", frame="frame_bronze", sill_mat="limestone",
                                               pair=2, bay=3.6, win_w=0.95), bands=[3], top={"kind": None}),
                 dict(fp=fp, floors=13, style=st, bands=[13], top={"kind": "cornice", "brackets": 1.2}),
                 dict(fp=rect(0, 1.5, W - 4, D - 5), floors=2, style=st,
                      top={"kind": "parapet", "wall": "brick_tan"})],
          crown=(crown_twin_turrets, dict(size=7.5, floors=3)),
          roof=dict(water=1, hvac=2, bulk=False))


def b03_rounded_corner(b, rng):
    fp = rounded_rect(0, 0, 34, 28, 5.0, 3)
    st = S(wall="brick_buff", frame="frame_alum", mullions=(2, 1), win_w=1.6, win_h=1.6, bay=3.4, sill_mat=None,
           lintel=None, edge=0.6, ac=0.12)
    nfp = len(fp)
    sides = [None] * nfp
    bsides = [dict(SHOPS, awnings=0.6)] * nfp
    tower(b, rng, fh=3.0, gf=5.0, base=SHOPS, base_sides=bsides,
          tiers=[dict(fp=fp, floors=13, style=st, bands=[0, 4, 8], band_profile="band_flat", trim="limestone",
                      top={"kind": "parapet", "wall": "brick_buff", "h": 1.2}),
                 dict(fp=rounded_rect(0, 3, 22, 16, 3, 3), floors=2, style=st,
                      top={"kind": "parapet", "wall": "brick_buff", "h": 1.0})],
          roof=dict(water=1, hvac=2, bulk=False))
    del sides


def b04_lightcourt(b, rng):
    W, D = 44.0, 30.0
    st = S(wall="brick_buff", lintel=("limestone", 0.25, 0.08), sill_mat="limestone", ac=0.1)
    lime = S(wall="limestone", frame="frame_black", sill_mat="limestone")
    parts = [rect_xy(-W / 2, -D / 2, -W / 2 + 14, D / 2 - 10), rect_xy(W / 2 - 14, -D / 2, W / 2, D / 2 - 10),
             rect_xy(-W / 2, D / 2 - 10, W / 2, D / 2)]
    for k, fp in enumerate(parts):
        sides = None
        if k == 2:
            sides = ["skip", None, None, None]
            front_seg = (fp[0], fp[1])
        tower(b, rng, fh=3.1, gf=4.8, base=LOBBY_LIME if k == 0 else SHOPS_LIME,
              base_sides=None if k < 2 else ["skip", SHOPS_LIME, SHOPS_LIME, SHOPS_LIME],
              tiers=[dict(fp=fp, floors=2, style=lime, sides=sides, bands=[2], top={"kind": None}),
                     dict(fp=fp, floors=12, style=st, sides=sides,
                          top={"kind": "cornice", "brackets": 1.2 if k < 2 else None})],
              roof=dict(water=1 if k == 2 else 0, hvac=1, bulk=k == 2, wall="brick_buff"))
    # inner face of the rear bar inside the court
    a, c = (-W / 2 + 14, D / 2 - 10), (W / 2 - 14, D / 2 - 10)
    b.facade(a, c, 0, 1, 4.8, blank("limestone"), rng)
    b.facade(a, c, 4.8, 14, 3.1, st, rng)
    b.solid(rect_xy(-W / 2 + 14.5, -D / 2 + 0.5, W / 2 - 14.5, D / 2 - 10.5), 0, 0.12, "sidewalk")


def b05_beaux_arts_office(b, rng):
    W, D = 38.0, 32.0
    fp = rect(0, 0, W, D)
    shaft = S(wall="limestone", frame="frame_black", glass="win_office", pair=2, bay=4.0, win_w=1.2, win_h=2.1,
              sill=0.9, lintel=("limestone", 0.25, 0.06), sill_mat="limestone", mullions=(1, 2))
    top = S(wall="limestone", frame="frame_black", glass="win_office", arch=True, bay=4.0, win_w=2.2, win_h=1.9,
            sill=0.8, lintel=("limestone",), keystone=True, pier=("limestone", 0.7, 0.18), mullions=(2, 2))
    tower(b, rng, fh=3.8, gf=5.5, base=dict(SHOPS_LIME, pier="granite_gray", canopy=3.0, canopy_mat="frame_bronze"),
          tiers=[dict(fp=fp, floors=2, style=dict(RUSTIC, glass="win_office", arch=True, bay=4.0, win_w=2.0,
                                                  win_h=1.8), bands=[2], band_profile="cornice_small",
                      top={"kind": None}, fh=4.2),
                 dict(fp=fp, floors=11, style=shaft, bands=[11], top={"kind": None}),
                 dict(fp=fp, floors=3, style=top, top={"kind": "cornice", "profile": "cornice_grand",
                                                       "brackets": 1.0})],
          roof=dict(water=1, hvac=3, bulk=True, wall="limestone"))
    x0, y0, x1, y1 = bounds(fp)


def b06_gothic_office(b, rng):
    W, D = 32.0, 30.0
    fp = rect(0, 0, W, D)
    st = S(type="vertical", wall="terracotta_white", bay=3.0, win_w=1.7, win_h=2.3, sill=0.8, depth=0.35,
           glass="win_office", spandrel="spandrel_green", frame="frame_green", pier=("terracotta_white", 0.5, 0.35),
           mullions=(2, 1))
    tower(b, rng, fh=3.8, gf=6.0, base=dict(SHOPS_LIME, pier="terracotta_white", frame="frame_green"),
          tiers=[dict(fp=fp, floors=24, style=st, top={"kind": "cornice", "profile": "cornice_small",
                                                       "mat": "terracotta_white"})],
          crown=(crown_gothic, dict(h=11.0, mat="copper")),
          roof=None)


def b07_chicago_loft(b, rng):
    W, D = 30.0, 34.0
    fp = rect(0, 0, W, D)
    st = S(wall="brick_brown", bay=5.0, win_w=3.6, win_h=2.4, sill=0.8, depth=0.3, frame="frame_black",
           mullions=(3, 2), glass="win_office", lintel=("brick_dark", 0.35, 0.05), sill_mat="limestone",
           pier=("brick_brown", 0.8, 0.25))
    tower(b, rng, fh=3.9, gf=5.2, base=SHOPS_CASTIRON, base_sides=[SHOPS_CASTIRON, SHOPS_CASTIRON,
                                                                   blank("brick_brown"), blank("brick_brown")],
          tiers=[dict(fp=fp, floors=13, style=st, sides=[None, None, blank("brick_brown"), blank("brick_brown")],
                      bands=[1, 12], top={"kind": "cornice", "profile": "cornice", "mat": "metal_black",
                                          "brackets": 1.5})],
          roof=dict(water=1, hvac=2, wall="brick_brown"))


def b08_garment_loft(b, rng):
    W, D = 40.0, 30.0
    st = S(wall="brick_tan", bay=4.2, win_w=3.0, win_h=2.5, sill=0.75, depth=0.25, frame="frame_green",
           mullions=(4, 3), glass="win_office", lintel=None, sill_mat="limestone", pier=("brick_tan", 0.5, 0.12))
    tower(b, rng, fh=3.9, gf=5.0, base=SHOPS, base_sides=[SHOPS, SHOPS, blank("brick_tan"), SHOPS],
          tiers=[dict(fp=rect(0, 0, W, D), floors=12, style=st, sides=[None, None, blank("brick_tan"), None],
                      top={"kind": "parapet", "wall": "brick_tan", "h": 1.0}),
                 dict(fp=rect(0, 2, W - 6, D - 4), floors=3, style=st,
                      top={"kind": "parapet", "wall": "brick_tan", "h": 1.0}),
                 dict(fp=rect(0, 4, W - 14, D - 8), floors=2, style=st,
                      top={"kind": "parapet", "wall": "brick_tan", "h": 1.0})],
          roof=dict(water=2, hvac=1, wall="brick_tan"))


def b09_deco_office(b, rng):
    W, D = 42.0, 36.0
    st = S(type="vertical", wall="brick_buff", bay=3.0, win_w=1.5, win_h=2.2, sill=0.85, depth=0.3,
           spandrel="spandrel_dark", frame="frame_black", glass="win_office", pier=("brick_buff", 0.6, 0.25),
           mullions=(2, 1))
    tiers = []
    for (sw, sd, n) in ((1.0, 1.0, 17), (0.82, 0.8, 6), (0.64, 0.6, 5), (0.48, 0.45, 3)):
        tiers.append(dict(fp=rect(0, (1 - sd) * D * 0.2, W * sw, D * sd), floors=n, style=st,
                          top={"kind": "parapet", "wall": "brick_buff", "h": 1.2, "coping": "limestone"}))
    tower(b, rng, fh=3.8, gf=6.0, base=dict(SHOPS_LIME, pier="granite_black", canopy=2.5, frame="frame_bronze"),
          tiers=tiers, crown=(crown_ziggurat, dict(steps=3, step_h=3.5, shrink=0.2, finial=14.0)))


def b10_deco_apartment(b, rng):
    W, D = 34.0, 28.0
    st = S(wall="brick_orange", frame="frame_black", win_w=1.5, mullions=(3, 1), lintel=None,
           sill_mat="brick_dark", edge=0.3, ac=0.1)
    tower(b, rng, fh=3.0, gf=5.0, base=SHOPS_LIME,
          tiers=[dict(fp=rect(0, 0, W, D), floors=14, style=st, bands=[0], band_profile="band_flat",
                      top={"kind": "parapet", "wall": "brick_orange", "h": 1.1}),
                 dict(fp=rect(0, 2.5, W - 8, D - 5), floors=3, style=st,
                      top={"kind": "parapet", "wall": "brick_orange", "h": 1.1}),
                 dict(fp=rect(0, 4.5, W - 16, D - 9), floors=2, style=st,
                      top={"kind": "parapet", "wall": "brick_orange", "h": 1.1})],
          crown=(crown_ziggurat, dict(steps=1, step_h=4.0, shrink=0.35, mat="brick_orange", finial=0)))


def b11_deco_limestone(b, rng):
    W, D = 36.0, 32.0
    st = S(type="vertical", wall="limestone", bay=3.0, win_w=1.6, win_h=2.3, sill=0.8, depth=0.32,
           spandrel="spandrel_bronze", frame="frame_bronze", glass="win_office", pier=("limestone", 0.5, 0.3),
           mullions=(2, 1))
    tiers = []
    for (sw, sd, n) in ((1.0, 1.0, 22), (0.8, 0.78, 6), (0.62, 0.6, 4), (0.45, 0.42, 3)):
        tiers.append(dict(fp=rect(0, 0, W * sw, D * sd), floors=n, style=st,
                          top={"kind": "parapet", "wall": "limestone", "h": 1.0, "coping": "limestone"}))
    tower(b, rng, fh=3.8, gf=6.0, base=dict(SHOPS_LIME, pier="granite_black", frame="frame_bronze"),
          tiers=tiers, crown=(crown_lantern, dict(r=4.0, h=7.0, dome="metal_alum", spire=12.0)))


def b12_neo_georgian(b, rng):
    W, D = 32.0, 26.0
    st = S(wall="brick_red", frame="frame_white", mullions=(2, 3), win_w=1.15, win_h=1.9,
           lintel=("limestone", 0.32, 0.12), keystone=True, sill_mat="limestone", ac=0.06)
    tower(b, rng, fh=3.1, gf=5.0, base=LOBBY_LIME,
          tiers=[dict(fp=rect(0, 0, W, D), floors=1, style=dict(RUSTIC, bay=3.2, arch=True, win_w=1.3),
                      bands=[1], top={"kind": None}),
                 dict(fp=rect(0, 0, W, D), floors=10, style=st, bands=[10], trim="limestone",
                      top={"kind": "cornice", "profile": "cornice", "brackets": 1.2})],
          crown=(crown_mansard, dict(h=5.0, mat="roof_membrane", inset=2.0)),
          roof=dict(water=1, hvac=1, bulk=False))


def b13_romanesque_warehouse(b, rng):
    W, D = 32.0, 36.0
    fp = rect(0, 0, W, D)
    low = S(wall="brick_redbrown", bay=3.6, win_w=1.6, win_h=2.1, sill=0.8, depth=0.35, frame="frame_green",
            mullions=(2, 2), lintel=("brick_dark", 0.35, 0.05), sill_mat="limestone", glass="win_office")
    arch = dict(low, arch=True, win_w=1.7, win_h=1.9, lintel=("brick_dark",), mullions=(2, 2))
    tower(b, rng, fh=3.8, gf=5.0, base=SHOPS_CASTIRON,
          base_sides=[SHOPS_CASTIRON, SHOPS_CASTIRON, blank("brick_redbrown"), SHOPS_CASTIRON],
          tiers=[dict(fp=fp, floors=6, style=low, sides=[None, None, blank("brick_redbrown"), None],
                      bands=[6], top={"kind": None}),
                 dict(fp=fp, floors=3, style=arch, sides=[None, None, blank("brick_redbrown"), None],
                      top={"kind": "cornice", "profile": "cornice", "mat": "brick_dark", "brackets": 1.0})],
          roof=dict(water=1, hvac=1, wall="brick_redbrown"))


def b14_postwar_white(b, rng):
    W, D = 40.0, 24.0
    st = S(wall="brick_white", frame="frame_alum", win_w=2.2, win_h=1.6, mullions=(3, 1), lintel=None,
           sill_mat=None, bay=3.6, edge=1.2, ac=0.15, depth=0.15)
    tower(b, rng, fh=2.9, gf=5.0, base=LOBBY_MODERN,
          tiers=[dict(fp=rect(0, 0, W, D), floors=19, style=st,
                      balconies=[dict(edge=0, every=2, width=3.4, depth=1.4, glass=False)],
                      top={"kind": "parapet", "wall": "brick_white", "h": 1.0, "coping": "concrete"}),
                 dict(fp=rect(0, 2, W - 8, D - 4), floors=4, style=st,
                      top={"kind": "parapet", "wall": "brick_white", "h": 1.0, "coping": "concrete"})],
          roof=dict(water=0, hvac=2, bulk=True, wall="brick_white"))


def b15_fifties_coop(b, rng):
    W, D = 34.0, 28.0
    st = S(wall="brick_red", frame="frame_alum", win_w=1.8, win_h=1.6, mullions=(2, 1), lintel=None,
           sill_mat="limestone", bay=3.4, edge=0.25, ac=0.12, depth=0.16)
    tower(b, rng, fh=2.9, gf=4.8, base=dict(LOBBY_MODERN, pier="limestone"),
          tiers=[dict(fp=rect(0, 0, W, D), floors=17, style=st,
                      top={"kind": "parapet", "wall": "brick_red", "h": 1.0})],
          roof=dict(water=0, hvac=3, bulk=True))


def b16_intl_glass(b, rng):
    W, D = 44.0, 26.0
    st = dict(type="curtain", glass="win_curtain_green", bay=1.5, mullion="frame_alum", fin=0.12,
              spandrel="spandrel_green", spandrel_h=1.0)
    lobby = rect(0, 0, W - 6, D - 6)
    tower(b, rng, fh=3.7, gf=6.0, base=LOBBY_GLASS, plaza=(-W * 0.8, -D / 2 - 22, W * 0.8, D / 2 + 3),
          tiers=[dict(fp=rect(0, 0, W, D), base_fp=lobby, floors=30, style=st,
                      top={"kind": "cap"})],
          crown=(crown_mech, dict(h=7.0, inset=1.0, mat="louver")))
    for x in [-W / 2 + 1 + i * (W - 2) / 7 for i in range(8)]:
        for y in (-D / 2 + 1, D / 2 - 1):
            b.box(x, y, 0.7, 0.7, 0, 6.0, "hd_metal_black", "hd_metal_black")


def b17_precast_grid(b, rng):
    W, D = 40.0, 32.0
    st = S(wall="concrete_precast", bay=3.0, win_w=2.3, win_h=2.4, sill=0.7, depth=0.45, frame="frame_bronze",
           mullions=(2, 1), glass="win_office", lintel=None, sill_mat=None, edge=0.35)
    tower(b, rng, fh=3.8, gf=6.5, base=dict(LOBBY_MODERN, pier="granite_gray"),
          tiers=[dict(fp=rect(0, 0, W, D), floors=24, style=st, top={"kind": "cap"})],
          crown=(crown_mech, dict(h=6.0, inset=3.0, mat="concrete_precast")))


def b18_dark_box(b, rng):
    W, D = 42.0, 34.0
    st = dict(type="curtain", glass="win_curtain_dark", bay=1.5, mullion="frame_bronze", fin=0.2,
              spandrel="spandrel_dark", spandrel_h=1.1)
    tower(b, rng, fh=3.9, gf=7.0, base=dict(LOBBY_GLASS, pier="granite_black"),
          plaza=(-W * 0.75, -D / 2 - 18, W * 0.75, D / 2 + 3),
          tiers=[dict(fp=rect(0, 0, W, D), base_fp=rect(0, 1.5, W - 4, D - 3), floors=28, style=st,
                      top={"kind": "cap"})],
          crown=(crown_mech, dict(h=5.0, inset=0.4, mat="spandrel_dark")))
    for x in [-W / 2 + 1 + i * (W - 2) / 6 for i in range(7)]:
        b.box(x, -D / 2 + 0.6, 1.0, 1.0, 0, 7.0, "hd_granite_black", "hd_granite_black")


def b19_brutalist(b, rng):
    W, D = 36.0, 30.0
    st = S(wall="concrete_board", bay=3.2, win_w=1.8, win_h=1.9, sill=0.9, depth=0.75, frame="frame_black",
           mullions=(2, 1), glass="win_office", lintel=None, sill_mat=None, pier=("concrete_board", 0.9, 0.55))
    tower(b, rng, fh=3.5, gf=6.0, base=dict(LOBBY_GLASS, pier="concrete_board"),
          tiers=[dict(fp=rect(0, 0, W, D), base_fp=rect(0, 0, W - 5, D - 5), floors=22, style=st,
                      top={"kind": "cap"})],
          crown=(crown_mech, dict(h=7.0, inset=4.0, mat="concrete_board")))
    for x in (-W / 2 + 1.2, -W / 6, W / 6, W / 2 - 1.2):
        for y in (-D / 2 + 1.2, D / 2 - 1.2):
            b.box(x, y, 1.4, 1.4, 0, 6.0, "hd_concrete_board", "hd_concrete_board")


def b20_pomo_granite(b, rng):
    W, D = 36.0, 32.0
    st = S(wall="granite_pink", bay=3.0, win_w=1.9, win_h=2.2, sill=0.8, depth=0.3, frame="frame_bronze",
           mullions=(2, 1), glass="win_office", lintel=None, sill_mat=None, edge=1.4)
    base3 = dict(st, wall="granite_gray", arch=True, win_w=2.0, win_h=2.4)
    tower(b, rng, fh=3.9, gf=6.5, base=dict(SHOPS_LIME, pier="granite_gray", canopy=3.0, frame="frame_bronze"),
          tiers=[dict(fp=rect(0, 0, W, D), floors=2, style=base3, bands=[2], band_profile="cornice_small",
                      trim="granite_gray", top={"kind": None}),
                 dict(fp=rect(0, 0, W, D), floors=20, style=st, bands=[20], trim="granite_gray",
                      top={"kind": "parapet", "wall": "granite_pink", "h": 1.0, "coping": "granite_gray"}),
                 dict(fp=rect(0, 0, W - 6, D - 6), floors=4, style=st,
                      top={"kind": "parapet", "wall": "granite_pink", "h": 1.0, "coping": "granite_gray"}),
                 dict(fp=rect(0, 0, W - 14, D - 14), floors=3, style=st, top={"kind": "cap"})],
          crown=(crown_pyramid, dict(h_ratio=0.5, mat="copper", inset=0.0, finial=5.0)))


def b21_eighties_bays(b, rng):
    W, D = 36.0, 30.0
    st = S(wall="brick_buff", frame="frame_bronze", win_w=1.6, win_h=1.7, mullions=(2, 1), lintel=None,
           sill_mat="limestone", bay=3.3, edge=1.0, depth=0.18)
    bay_st = dict(st, bay=2.6, edge=0.3, win_w=1.8)
    fp = rect(0, 0, W, D)
    zt = tower(b, rng, fh=3.0, gf=5.5, base=dict(LOBBY_MODERN, pier="limestone"),
               tiers=[dict(fp=fp, floors=31, style=st, top={"kind": "parapet", "wall": "brick_buff", "h": 1.0}),
                      dict(fp=rect(0, 1, W - 8, D - 6), floors=3, style=st,
                           top={"kind": "parapet", "wall": "brick_buff", "h": 1.0})],
               roof=dict(water=0, hvac=2, bulk=True, wall="brick_buff"))
    # projecting bay windows on the front facade
    for x in (-W / 2 + 6, 0, W / 2 - 6):
        bf = [(x - 2.6, -D / 2), (x - 1.8, -D / 2 - 1.2), (x + 1.8, -D / 2 - 1.2), (x + 2.6, -D / 2)]
        b.facades(bf, 5.5, 31, 3.0, bay_st, rng, side_styles=[None, None, None, "skip"])
        b.cap(bf, 5.5 + 31 * 3.0, "hd_roof_membrane")
    del zt


def b22_glass_condo(b, rng):
    W, D = 32.0, 26.0
    st = dict(type="curtain", glass="win_curtain_silver", bay=1.6, mullion="frame_white", fin=0.08,
              spandrel="spandrel_white", spandrel_h=0.35)
    tower(b, rng, fh=3.3, gf=6.0, base=LOBBY_GLASS, base_sides=[LOBBY_GLASS, SHOPS, SHOPS, SHOPS],
          tiers=[dict(fp=rect(0, 0, W, D), floors=24, style=st,
                      balconies=[dict(edge=0, every=1, width=4.5, depth=1.8, glass=True, slab="spandrel_white"),
                                 dict(edge=1, every=2, width=4.0, depth=1.6, glass=True, slab="spandrel_white")],
                      top={"kind": "railing"}),
                 dict(fp=rect(-2, 2, W - 8, D - 6), floors=4, style=st, top={"kind": "railing"}),
                 dict(fp=rect(-3, 4, W - 16, D - 12), floors=2, style=st, top={"kind": "railing"})],
          roof=dict(water=0, hvac=2, bulk=False))


def b23_new_prewar(b, rng):
    W, D = 30.0, 28.0
    st = S(wall="limestone_warm", frame="frame_bronze", win_w=1.7, win_h=2.2, mullions=(2, 2), lintel=None,
           sill_mat="limestone", bay=3.3, pier=("limestone_warm", 0.5, 0.15), depth=0.3)
    tower(b, rng, fh=3.4, gf=5.5, base=dict(SHOPS_LIME, canopy=3.0, frame="frame_bronze", awnings=0.0),
          tiers=[dict(fp=rect(0, 0, W, D), floors=16, style=st, bands=[2, 16], trim="limestone",
                      top={"kind": "parapet", "wall": "limestone_warm", "h": 1.1}),
                 dict(fp=rect(0, 2, W - 6, D - 6), floors=4, style=st,
                      top={"kind": "parapet", "wall": "limestone_warm", "h": 1.1}),
                 dict(fp=rect(0, 3, W - 12, D - 10), floors=3, style=st,
                      top={"kind": "cornice", "profile": "cornice_small", "mat": "limestone"})],
          crown=(crown_lantern, dict(r=3.0, h=5.0, dome="copper", spire=4.0, mat="limestone_warm")))


def b24_slender_hotel(b, rng):
    W, D = 22.0, 20.0
    st = dict(type="ribbon", wall="metal_white", glass="win_curtain_blue", band=(0.25, 0.92), bay=1.4,
              frame="frame_alum", depth=0.1)
    tower(b, rng, fh=3.2, gf=7.0, base=dict(LOBBY_GLASS, canopy=6.0),
          tiers=[dict(fp=rect(0, 0, W, D), floors=34, style=st, top={"kind": "cap"})],
          crown=(crown_fins, dict(h=9.0, mat="frame_alum", spacing=1.4)))


def b25_hospital(b, rng):
    W, D = 52.0, 26.0
    st = dict(type="ribbon", wall="brick_buff", glass="win_office", band=(0.3, 0.85), bay=1.6,
              frame="frame_alum", depth=0.14)
    mech = dict(type="blank", wall="louver_light")
    fp = rect(0, 0, W, D)
    tower(b, rng, fh=4.0, gf=6.0, base=dict(LOBBY_GLASS, canopy=8.0, canopy_posts=True, canopy_mat="metal_white"),
          tiers=[dict(fp=fp, floors=9, style=st, top={"kind": None}),
                 dict(fp=fp, floors=1, style=mech, top={"kind": None}),
                 dict(fp=fp, floors=9, style=st, top={"kind": "cap"})],
          crown=(crown_mech, dict(h=6.0, inset=3.0, mat="louver_light")),
          roof=None)


def b26_university(b, rng):
    W, D = 40.0, 30.0
    st = S(wall="brick_redbrown", bay=3.4, win_w=1.6, win_h=2.2, frame="frame_black", glass="win_office",
           lintel=("limestone", 0.25, 0.06), sill_mat="limestone", mullions=(2, 2))
    glass = dict(type="curtain", glass="win_curtain_blue", bay=1.5, mullion="frame_black", fin=0.25)
    tower(b, rng, fh=3.9, gf=5.5, base=dict(LOBBY_GLASS, pier="limestone"),
          tiers=[dict(fp=rect(0, 0, W, D), floors=14, style=st, sides=[None, glass, None, None], bands=[1, 14],
                      top={"kind": "parapet", "wall": "brick_redbrown", "h": 1.0})],
          roof=dict(water=0, hvac=4, bulk=True, wall="brick_redbrown"))


def b27_diagrid(b, rng):
    W = 34.0
    st = dict(type="curtain", glass="win_curtain_silver", bay=1.5, mullion="frame_alum", fin=0.06)
    fh, floors, gf = 4.0, 30, 7.0
    tower(b, rng, fh=fh, gf=gf, base=LOBBY_GLASS,
          tiers=[dict(fp=rect(0, 0, W, W), floors=floors, style=st, top={"kind": "cap"})],
          crown=(crown_mech, dict(h=4.0, inset=0.5, mat="spandrel_white")))
    if True:
        step = 4 * fh
        for e in range(4):
            fp = rect(0, 0, W + 0.5, W + 0.5)
            a, c = fp[e], fp[(e + 1) % 4]
            for k in range(floors // 4):
                z0 = gf + k * step
                for q in range(4):
                    t0, t1 = q / 4, (q + 1) / 4
                    p0 = (a[0] + (c[0] - a[0]) * t0, a[1] + (c[1] - a[1]) * t0)
                    p1 = (a[0] + (c[0] - a[0]) * t1, a[1] + (c[1] - a[1]) * t1)
                    if (q + k) % 2 == 0:
                        b.beam((p0[0], p0[1], z0), (p1[0], p1[1], z0 + step), 0.5, "hd_frame_alum")
                    else:
                        b.beam((p0[0], p0[1], z0 + step), (p1[0], p1[1], z0), 0.5, "hd_frame_alum")


def b28_gothic_residential(b, rng):
    W, D = 30.0, 28.0
    st = S(wall="terracotta", frame="frame_brown", win_w=1.3, win_h=1.8, mullions=(2, 2), lintel=("terracotta",
           0.3, 0.1), sill_mat="terracotta", pier=("terracotta", 0.45, 0.25), bay=3.0, ac=0.05)
    top = dict(st, arch=True, lintel=("terracotta",))
    tower(b, rng, fh=3.1, gf=5.2, base=dict(LOBBY_LIME, pier="terracotta", canopy_mat="awning_burgundy"),
          tiers=[dict(fp=rect(0, 0, W, D), floors=18, style=st, bands=[2, 18], trim="terracotta",
                      top={"kind": None}),
                 dict(fp=rect(0, 0, W, D), floors=2, style=top,
                      top={"kind": "cornice", "profile": "cornice_small", "mat": "terracotta"})],
          crown=(crown_gothic, dict(h=8.0, mat="copper", pinnacle=5.0, stone="terracotta")))


def b29_podium_tower(b, rng):
    W, D = 48.0, 40.0
    pod = dict(type="ribbon", wall="metal_alum", glass="win_office", band=(0.2, 0.9), bay=1.5,
               frame="frame_black")
    st = S(wall="brick_gray", frame="frame_black", win_w=1.8, win_h=1.9, mullions=(2, 1), lintel=None,
           sill_mat=None, bay=3.3, depth=0.2)
    tower(b, rng, fh=3.1, gf=5.5, base=SHOPS,
          tiers=[dict(fp=rect(0, 0, W, D), floors=5, style=pod, fh=4.0,
                      top={"kind": "railing", "glass": True, "roof": "roof_gravel"}),
                 dict(fp=rect(-6, 4, 28, 22), floors=24, style=st,
                      balconies=[dict(edge=0, every=2, width=3.6, depth=1.5, glass=True)],
                      top={"kind": "parapet", "wall": "brick_gray", "h": 1.2, "coping": "concrete"})],
          roof=dict(water=0, hvac=2, bulk=True, wall="brick_gray"))
    b.box(14, 4, 8, 6, 25.5, 26.4, "hd_planter", "hd_planter")


def b30_civic_setback(b, rng):
    W, D = 40.0, 34.0
    st = S(wall="limestone", frame="frame_black", glass="win_office", win_w=1.4, win_h=2.1, bay=3.0,
           sill_mat="limestone", lintel=None, pier=("limestone", 0.6, 0.2), mullions=(1, 2))
    tiers = [dict(fp=rect(0, 0, W, D), floors=2, style=dict(RUSTIC, glass="win_office", arch=True, bay=4.0,
                                                           win_w=2.2, win_h=2.2), fh=4.5, bands=[2],
                  band_profile="cornice_small", top={"kind": None})]
    for (sw, sd, n) in ((1.0, 1.0, 14), (0.78, 0.76, 4), (0.58, 0.55, 3)):
        tiers.append(dict(fp=rect(0, 0, W * sw, D * sd), floors=n, style=st,
                          top={"kind": "parapet", "wall": "limestone", "h": 1.2, "coping": "limestone"}))
    tower(b, rng, fh=3.8, gf=6.0, base=dict(LOBBY_LIME, pier="granite_gray", canopy=None),
          tiers=tiers, crown=(crown_lantern, dict(r=5.0, h=8.0, dome="copper", spire=9.0)))


reg("ues_prewar_coop", "Upper East Side Prewar Co-op",
    "15-storey red-brick co-op: rusticated limestone base, arched top floors, grand bracketed cornice, water tower.",
    b01_ues_coop)
reg("uws_twin_turret", "West Side Twin-Turret Apartments",
    "21-storey tan brick apartment house with paired windows and two copper-roofed rooftop turrets.", b02_uws_twin)
reg("rounded_corner_apartments", "Round-Corner Apartment House",
    "15-storey buff-brick building with rounded corners and shops wrapping the base.", b03_rounded_corner)
reg("lightcourt_apartments", "Light-Court Apartment Building",
    "U-plan 15-storey building around a front light court, limestone base, cornices.", b04_lightcourt)
reg("beaux_arts_office", "Beaux-Arts Office Building",
    "17-storey limestone office: rusticated arched base, paired windows, arcaded top, grand cornice.",
    b05_beaux_arts_office)
reg("gothic_terracotta_office", "Gothic Terracotta Office Tower",
    "25-storey cream terracotta tower with soaring piers, green spandrels and a pinnacled copper crown.",
    b06_gothic_office)
reg("chicago_school_loft", "Chicago-School Loft",
    "14-storey brick loft with wide three-part windows, piers and a metal cornice.", b07_chicago_loft)
reg("garment_district_loft", "Garment District Setback Loft",
    "18-storey tan-brick loft with factory windows, two setbacks and twin water towers.", b08_garment_loft)
reg("art_deco_office", "Art Deco Setback Office",
    "32-storey buff-brick deco tower: vertical piers, dark spandrels, four setbacks and a ziggurat crown.",
    b09_deco_office)
reg("art_deco_apartments", "Art Deco Apartment House",
    "20-storey orange-brick apartments with corner windows and stepped terraces.", b10_deco_apartment)
reg("art_deco_limestone_tower", "Art Deco Limestone Tower",
    "36-storey limestone tower with bronze spandrels, setbacks and a colonnaded lantern crown.",
    b11_deco_limestone)
reg("neo_georgian_apartments", "Neo-Georgian Apartment House",
    "12-storey red brick with white multi-pane windows, keystone lintels and a mansard roof.", b12_neo_georgian)
reg("romanesque_warehouse", "Romanesque Revival Warehouse",
    "10-storey warehouse with arched upper floors, brick cornice and cast-iron storefronts.",
    b13_romanesque_warehouse)
reg("postwar_white_brick", "Postwar White-Brick Tower",
    "24-storey white-brick apartment tower with balconies and a setback.", b14_postwar_white)
reg("fifties_corner_window_coop", "1950s Corner-Window Co-op",
    "18-storey red-brick co-op with corner windows and aluminium frames.", b15_fifties_coop)
reg("international_style_slab", "International Style Glass Slab",
    "31-storey green curtain-wall slab on pilotis over a granite plaza.", b16_intl_glass)
reg("precast_grid_office", "Precast Concrete Grid Office",
    "25-storey office with deep-set windows in a precast concrete grid.", b17_precast_grid)
reg("dark_glass_office", "Dark Glass Corporate Tower",
    "29-storey dark glass and bronze tower with a colonnaded plaza base.", b18_dark_box)
reg("brutalist_tower", "Brutalist Concrete Tower",
    "23-storey board-formed concrete tower with deep windows and heavy piers on pilotis.", b19_brutalist)
reg("postmodern_granite_office", "Postmodern Granite Office",
    "30-storey pink granite office with arched base, setbacks and a copper pyramid crown.", b20_pomo_granite)
reg("eighties_bay_window_tower", "1980s Bay-Window Apartment Tower",
    "35-storey brick tower with full-height projecting bay windows.", b21_eighties_bays)
reg("glass_condo_terraces", "Glass Condo with Terraces",
    "31-storey glass condo with wraparound balconies and terraced setbacks.", b22_glass_condo)
reg("new_prewar_limestone", "New Limestone Condo",
    "24-storey contemporary limestone condo with setback terraces and a lantern crown.", b23_new_prewar)
reg("slender_hotel", "Slender Hotel Tower",
    "35-storey white-panel hotel with ribbon windows and a finned crown.", b24_slender_hotel)
reg("hospital_tower", "Hospital Tower",
    "20-storey medical tower with ribbon windows, mechanical floor and drop-off canopy.", b25_hospital)
reg("university_hall", "University Hall",
    "15-storey academic building, brick with a glass curtain-wall side.", b26_university)
reg("diagrid_office", "Diagrid Office Tower",
    "31-storey silver glass tower wrapped in a diagonal steel grid.", b27_diagrid)
reg("gothic_apartment_tower", "Gothic Revival Apartment Tower",
    "21-storey terracotta apartment tower with arched top floors and pinnacled crown.", b28_gothic_residential)
reg("podium_tower", "Mixed-Use Podium Tower",
    "5-storey retail/office podium with a 24-storey residential tower and roof terrace.", b29_podium_tower)
reg("civic_setback_tower", "Civic Limestone Setback Tower",
    "24-storey municipal-style limestone tower with arcaded base and lantern crown.", b30_civic_setback)

assert len(ENTRIES) == 30
