"""Detailed facade engine for the HD building kit.

Extends the base Builder with real architectural geometry:
  * punched windows with recessed reveals, frames, mullions, sills, lintels,
    arched heads + voussoirs, paired windows
  * vertical "deco" window strips with recessed spandrels between piers
  * glass curtain walls with mullion fins and spandrel bands
  * storefront bases (piers, bulkheads, display glass, sign bands, doors,
    awnings) and lobby entrances with canopies
  * profiled cornices / string courses swept around the footprint, brackets
  * parapets with coping, detailed water towers, HVAC, bulkheads, railings

LOD: self.lod = 0 -> full detail, 1 -> windows become flush textured quads,
no frames / sills / brackets (about 5-10x fewer triangles).
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from builder import Builder, bounds, circle, rect, rect_xy  # noqa: E402
from materials import MATERIALS  # noqa: E402

# ------------------------------------------------------------------ materials
_SURF_TILE = {
    "brick_red": 1.22, "brick_redbrown": 1.22, "brick_brown": 1.22, "brick_tan": 1.22, "brick_buff": 1.22,
    "brick_orange": 1.22, "brick_white": 1.22, "brick_dark": 1.22, "brick_gray": 1.22,
    "limestone": 2.4, "limestone_warm": 2.4, "limestone_rustic": 2.4, "brownstone": 2.4,
    "terracotta": 2.4, "terracotta_white": 2.4,
    "granite_pink": 3.0, "granite_gray": 3.0, "granite_black": 3.0, "granite_flamed": 3.0,
    "concrete_precast": 3.0, "concrete_board": 2.4, "concrete": 4.0,
    "metal_alum": 3.0, "metal_bronze": 3.0, "metal_black": 3.0, "metal_white": 3.0, "copper": 3.0,
    "roof_membrane": 6.0, "roof_gravel": 3.0, "louver": 1.0, "louver_light": 1.0, "wood_staves": 2.0,
    "stucco": 3.0, "asphalt": 8.0, "sidewalk": 3.0,
}
_SURF_PROPS = {"metal_alum": dict(metallic=0.8), "metal_bronze": dict(metallic=0.8),
               "metal_black": dict(metallic=0.7), "metal_white": dict(metallic=0.2), "copper": dict(metallic=0.3),
               "louver": dict(metallic=0.6), "louver_light": dict(metallic=0.5)}

HD = {}
for _n, _t in _SURF_TILE.items():
    HD["hd_" + _n] = dict(pbr=_n, bay=_t, fh=_t, cx=1, cy=1, roughness=1.0, **_SURF_PROPS.get(_n, {}))
for _n, _rough, _met in (("win_res", 0.08, 0.0), ("win_office", 0.08, 0.0), ("win_curtain_blue", 0.05, 0.3),
                         ("win_curtain_green", 0.05, 0.3), ("win_curtain_dark", 0.05, 0.35),
                         ("win_curtain_silver", 0.05, 0.4), ("win_curtain_bronze", 0.05, 0.35),
                         ("win_shop", 0.08, 0.0), ("signs", 0.4, 0.0)):
    HD["hd_" + _n] = dict(atlas=_n, bay=1, fh=1, cx=1, cy=1, roughness=_rough, metallic=_met,
                          emission=(0.35 if _n == "signs" else None))


def _flat(color, rough=0.5, metal=0.0, alpha=1.0, emission=None):
    return dict(color=color, roughness=rough, metallic=metal, alpha=alpha, emission=emission, bay=1, fh=1,
                cx=1, cy=1)


HD.update({
    "hd_frame_white": _flat((232, 230, 224), 0.4),
    "hd_frame_black": _flat((28, 28, 30), 0.35, 0.6),
    "hd_frame_bronze": _flat((92, 70, 48), 0.35, 0.8),
    "hd_frame_green": _flat((38, 70, 52), 0.45, 0.3),
    "hd_frame_alum": _flat((175, 178, 182), 0.3, 0.9),
    "hd_frame_brown": _flat((70, 50, 38), 0.5, 0.0),
    "hd_steel": _flat((120, 122, 125), 0.45, 0.85),
    "hd_steel_dark": _flat((45, 46, 48), 0.5, 0.8),
    "hd_gold": _flat((205, 160, 70), 0.25, 1.0),
    "hd_chrome": _flat((210, 212, 215), 0.12, 1.0),
    "hd_spandrel_dark": _flat((38, 42, 48), 0.25, 0.4),
    "hd_spandrel_bronze": _flat((78, 60, 44), 0.3, 0.7),
    "hd_spandrel_green": _flat((48, 70, 66), 0.25, 0.4),
    "hd_spandrel_white": _flat((220, 222, 224), 0.3, 0.3),
    "hd_glass_rail": _flat((150, 180, 190), 0.05, 0.0, alpha=0.35),
    "hd_glass_door": _flat((40, 50, 55), 0.05, 0.2),
    "hd_awning_red": _flat((150, 28, 32), 0.85),
    "hd_awning_green": _flat((28, 82, 52), 0.85),
    "hd_awning_navy": _flat((26, 38, 78), 0.85),
    "hd_awning_black": _flat((22, 22, 24), 0.85),
    "hd_awning_burgundy": _flat((100, 25, 40), 0.85),
    "hd_awning_tan": _flat((180, 150, 105), 0.85),
    "hd_hvac": _flat((185, 188, 190), 0.45, 0.5),
    "hd_grille": _flat((30, 31, 33), 0.6, 0.5),
    "hd_lamp": _flat((255, 236, 190), 0.4, 0.0, emission=(1.0, 0.85, 0.6)),
    "hd_marquee": _flat((250, 248, 240), 0.4, 0.0, emission=(1.0, 0.95, 0.8)),
    "hd_planter": _flat((60, 95, 45), 0.9),
    "hd_dark": _flat((18, 18, 20), 0.9),
    "hd_door_wood": _flat((70, 42, 28), 0.5),
    "hd_red_light": _flat((255, 40, 30), 0.4, 0.0, emission=(1.0, 0.1, 0.05)),
})
MATERIALS.update(HD)


def M(name):
    return name if name.startswith("hd_") else "hd_" + name


# ------------------------------------------------------------------ geometry utils
def offset_poly(fp, o):
    """Mitred offset of a convex CCW polygon (o > 0 grows)."""
    n = len(fp)
    out = []
    for i in range(n):
        p0, p1, p2 = fp[i - 1], fp[i], fp[(i + 1) % n]
        e1 = (p1[0] - p0[0], p1[1] - p0[1])
        e2 = (p2[0] - p1[0], p2[1] - p1[1])
        l1 = math.hypot(*e1) or 1
        l2 = math.hypot(*e2) or 1
        n1 = (e1[1] / l1, -e1[0] / l1)
        n2 = (e2[1] / l2, -e2[0] / l2)
        d = 1 + n1[0] * n2[0] + n1[1] * n2[1]
        out.append((p1[0] + o * (n1[0] + n2[0]) / d, p1[1] + o * (n1[1] + n2[1]) / d))
    return out


PROFILES = {
    # (outward offset, height) pairs from bottom to top, metres
    "cornice_grand": [(0, 0), (0.08, 0), (0.08, 0.18), (0.22, 0.3), (0.22, 0.42), (0.3, 0.45), (0.3, 0.55),
                      (0.95, 0.68), (1.15, 0.72), (1.2, 0.9), (1.2, 1.1), (0.0, 1.25)],
    "cornice": [(0, 0), (0.06, 0), (0.06, 0.15), (0.18, 0.25), (0.18, 0.32), (0.6, 0.45), (0.72, 0.5),
                (0.75, 0.62), (0.75, 0.75), (0, 0.85)],
    "cornice_small": [(0, 0), (0.05, 0), (0.05, 0.1), (0.3, 0.22), (0.35, 0.3), (0.35, 0.4), (0, 0.45)],
    "band": [(0, 0), (0.08, 0.0), (0.12, 0.06), (0.12, 0.22), (0.0, 0.3)],
    "band_flat": [(0, 0), (0.06, 0), (0.06, 0.25), (0, 0.32)],
    "coping": [(0, 0), (0.05, 0), (0.05, 0.12), (0, 0.15)],
    "deco_cap": [(0, 0), (0.15, 0), (0.15, 0.6), (0.3, 0.6), (0.3, 0.9), (0, 0.95)],
    "slab_edge": [(0, 0), (0.12, 0), (0.12, 0.35), (0, 0.35)],
}


class HDBuilder(Builder):
    def __init__(self, lod=0):
        super().__init__()
        self.lod = lod
        self._cell = 0

    # ---------------------------------------------------------------- basics
    def P(self, a, d, n, u, z, dep):
        return (a[0] + d[0] * u - n[0] * dep, a[1] + d[1] * u - n[1] * dep, z)

    def quad_w(self, a, d, n, u0, u1, z0, z1, dep, mat):
        """Quad parallel to the wall at depth dep (positive = into the wall), facing out."""
        if u1 - u0 < 1e-4 or z1 - z0 < 1e-4:
            return
        P = self.P
        self.face_auto([P(a, d, n, u0, z0, dep), P(a, d, n, u1, z0, dep), P(a, d, n, u1, z1, dep),
                        P(a, d, n, u0, z1, dep)], mat)

    def box_w(self, a, d, n, u0, u1, z0, z1, dep0, dep1, mat, back=False):
        """Box aligned to the wall between depths dep0 < dep1 (negative = in front of wall)."""
        if u1 - u0 < 1e-4 or z1 - z0 < 1e-4:
            return
        P = self.P
        f = lambda u, z, dp: P(a, d, n, u, z, dp)  # noqa: E731
        self.face_auto([f(u0, z0, dep0), f(u1, z0, dep0), f(u1, z1, dep0), f(u0, z1, dep0)], mat)  # front
        self.face_auto([f(u0, z1, dep0), f(u1, z1, dep0), f(u1, z1, dep1), f(u0, z1, dep1)], mat)  # top
        self.face_auto([f(u0, z0, dep1), f(u1, z0, dep1), f(u1, z0, dep0), f(u0, z0, dep0)], mat)  # bottom
        self.face_auto([f(u0, z0, dep1), f(u0, z0, dep0), f(u0, z1, dep0), f(u0, z1, dep1)], mat)  # left
        self.face_auto([f(u1, z0, dep0), f(u1, z0, dep1), f(u1, z1, dep1), f(u1, z1, dep0)], mat)  # right
        if back:
            self.face_auto([f(u1, z0, dep1), f(u0, z0, dep1), f(u0, z1, dep1), f(u1, z1, dep1)], mat)

    def atlas_quad(self, a, d, n, u0, u1, z0, z1, dep, mat, rng, rows=4, band=None):
        """Quad mapped to one random cell of a 4x4 atlas (or one of 4 bands within a cell)."""
        if u1 - u0 < 1e-4 or z1 - z0 < 1e-4:
            return
        ci = rng.randrange(16)
        cu, cv = (ci % 4) / 4.0, (ci // 4) / 4.0
        e = 0.004
        if band is not None:
            b = rng.randrange(4)
            vb0 = cv + (3 - b) * (0.25 / 4) + e
            vb1 = vb0 + 0.25 / 4 - 2 * e
        else:
            vb0, vb1 = cv + e, cv + 0.25 - e
        ub0, ub1 = cu + e, cu + 0.25 - e
        P = self.P
        self.face([P(a, d, n, u0, z0, dep), P(a, d, n, u1, z0, dep), P(a, d, n, u1, z1, dep),
                   P(a, d, n, u0, z1, dep)], [(ub0, vb0), (ub1, vb0), (ub1, vb1), (ub0, vb1)], mat)

    def reveal(self, a, d, n, ua, ub, za, zb, dep, mat, top=True, bottom=True):
        P = self.P
        f = lambda u, z, dp: P(a, d, n, u, z, dp)  # noqa: E731
        self.face_auto([f(ua, za, 0), f(ua, za, dep), f(ua, zb, dep), f(ua, zb, 0)], mat)  # left, faces +u
        self.face_auto([f(ub, za, dep), f(ub, za, 0), f(ub, zb, 0), f(ub, zb, dep)], mat)  # right
        if top:
            self.face_auto([f(ua, zb, dep), f(ub, zb, dep), f(ub, zb, 0), f(ua, zb, 0)], mat)  # head, faces down
        if bottom:
            self.face_auto([f(ua, za, 0), f(ua, za, dep), f(ub, za, dep), f(ub, za, 0)], mat)  # sill, faces up

    # ---------------------------------------------------------------- windows
    def window_unit(self, a, d, n, ua, ub, za, zb, st, rng, wall):
        dep = st.get("depth", 0.22)
        glass = M(st.get("glass", "win_res"))
        frame = M(st.get("frame", "frame_white"))
        if self.lod >= 1:
            self.atlas_quad(a, d, n, ua, ub, za, zb, -0.015, glass, rng)
            return
        arch = st.get("arch", False)
        r = (ub - ua) / 2
        ztop = zb + (r if arch else 0)
        self.reveal(a, d, n, ua, ub, za, ztop, dep, wall)
        self.atlas_quad(a, d, n, ua, ub, za, ztop, dep, glass, rng)
        # frame + mullions (thin boxes in front of the glass)
        t = st.get("frame_t", 0.06)
        fd0, fd1 = dep - 0.07, dep - 0.005
        self.box_w(a, d, n, ua, ua + t, za, ztop, fd0, fd1, frame)
        self.box_w(a, d, n, ub - t, ub, za, ztop, fd0, fd1, frame)
        self.box_w(a, d, n, ua + t, ub - t, za, za + t * 1.3, fd0, fd1, frame)
        self.box_w(a, d, n, ua + t, ub - t, ztop - t, ztop, fd0, fd1, frame)
        mc, mr = st.get("mullions", (1, 2))
        mt = t * 0.6
        for i in range(1, mc):
            u = ua + (ub - ua) * i / mc
            self.box_w(a, d, n, u - mt / 2, u + mt / 2, za + t, (zb if arch else ztop) - t, fd0 + 0.02, fd1, frame)
        for j in range(1, mr):
            z = za + (zb - za) * j / mr
            self.box_w(a, d, n, ua + t, ub - t, z - mt / 2, z + mt / 2, fd0 + 0.01, fd1, frame)
        if arch:
            # infill the corners above the spring line with wall + voussoir ring
            seg = 6
            pts = [(ua + r - r * math.cos(math.pi * k / seg), zb + r * math.sin(math.pi * k / seg))
                   for k in range(seg + 1)]
            P = self.P
            for k in range(seg // 2):
                (u1, z1), (u2, z2) = pts[k], pts[k + 1]
                self.face_auto([P(a, d, n, u1, z1, -0.002), P(a, d, n, u2, z2, -0.002),
                                P(a, d, n, ua, ztop, -0.002)], wall)
            for k in range(seg // 2, seg):
                (u1, z1), (u2, z2) = pts[k], pts[k + 1]
                self.face_auto([P(a, d, n, u1, z1, -0.002), P(a, d, n, u2, z2, -0.002),
                                P(a, d, n, ub, ztop, -0.002)], wall)
            vm = M(st.get("lintel", ("limestone",))[0]) if st.get("lintel") else wall
            w = 0.28
            for k in range(seg):
                a0, a1 = math.pi * k / seg, math.pi * (k + 1) / seg
                q = [(ua + r - (r + w) * math.cos(a0), zb + (r + w) * math.sin(a0)),
                     (ua + r - (r + w) * math.cos(a1), zb + (r + w) * math.sin(a1)),
                     (ua + r - r * math.cos(a1), zb + r * math.sin(a1)),
                     (ua + r - r * math.cos(a0), zb + r * math.sin(a0))]
                self.face_auto([P(a, d, n, u, z, -0.04) for u, z in q][::-1], vm)
            if st.get("keystone"):
                self.box_w(a, d, n, ua + r - 0.18, ua + r + 0.18, ztop - 0.05, ztop + w + 0.1, -0.08, 0, vm)
        else:
            lt = st.get("lintel")
            if lt:
                lm, lh, lo = lt[0], lt[1] if len(lt) > 1 else 0.3, lt[2] if len(lt) > 2 else 0.1
                self.box_w(a, d, n, ua - lo, ub + lo, zb, zb + lh, -0.04, 0, M(lm))
                if st.get("keystone"):
                    self.box_w(a, d, n, ua + r - 0.15, ua + r + 0.15, zb - 0.05, zb + lh + 0.05, -0.08, 0, M(lm))
        sl = st.get("sill_mat")
        if sl:
            self.box_w(a, d, n, ua - 0.08, ub + 0.08, za - 0.08, za, -0.07, 0, M(sl))
        if st.get("ac") and rng.random() < st["ac"]:  # window air-conditioner
            self.box_w(a, d, n, ua + 0.15, ua + 0.75, za + 0.02, za + 0.42, -0.35, dep - 0.1, "hd_hvac")
            self.box_w(a, d, n, ua + 0.2, ua + 0.7, za + 0.05, za + 0.38, -0.36, -0.35, "hd_grille")

    def window_slots(self, L, st):
        edge = st.get("edge", 0.7)
        bay = st.get("bay", 3.0)
        usable = L - 2 * edge
        if usable < bay * 0.55:
            return []
        nb = max(1, int(round(usable / bay)))
        bw = usable / nb
        pair = st.get("pair", 1)
        gap = st.get("pair_gap", 0.4)
        ww = min(st.get("win_w", 1.3), (bw - 0.45 - (pair - 1) * gap) / pair)
        slots = []
        for i in range(nb):
            c = edge + bw * (i + 0.5)
            tot = pair * ww + (pair - 1) * gap
            for k in range(pair):
                u0 = c - tot / 2 + k * (ww + gap)
                slots.append((u0, u0 + ww, i))
        return slots

    def facade(self, a, b, z0, floors, fh, st, rng, ztop=None):
        """One wall segment a->b (CCW footprint edge) of `floors` storeys starting at z0."""
        kind = st.get("type", "punched")
        L = math.hypot(b[0] - a[0], b[1] - a[1])
        if L < 1e-3 or floors <= 0:
            return
        d = ((b[0] - a[0]) / L, (b[1] - a[1]) / L)
        n = (d[1], -d[0])
        zt = z0 + floors * fh if ztop is None else ztop
        if kind == "blank":
            self.quad_w(a, d, n, 0, L, z0, zt, 0, M(st["wall"]))
            return
        if kind == "curtain":
            return self.curtain(a, d, n, L, z0, floors, fh, st, rng, zt)
        if kind == "ribbon":
            return self.ribbon(a, d, n, L, z0, floors, fh, st, rng, zt)
        wall = M(st["wall"])
        slots = self.window_slots(L, st)
        if not slots:
            self.quad_w(a, d, n, 0, L, z0, zt, 0, wall)
            return
        sill = st.get("sill", 0.85)
        wh = min(st.get("win_h", 1.9), fh - sill - 0.3)
        arch = st.get("arch", False)
        r_extra = 0.0
        if arch:
            r_extra = (slots[0][1] - slots[0][0]) / 2
            wh = min(wh, fh - sill - 0.35 - r_extra)
        vertical = kind == "vertical"
        piers = st.get("pier")
        if self.lod >= 1:
            self.quad_w(a, d, n, 0, L, z0, zt, 0, wall)
            for f in range(floors):
                za = z0 + f * fh + sill
                for ua, ub, _ in slots:
                    self.window_unit(a, d, n, ua, ub, za, za + wh + r_extra, st, rng, wall)
            return
        if vertical:
            # continuous recessed strips: glass + spandrels, piers between
            dep = st.get("depth", 0.3)
            za0 = z0 + sill
            zb1 = z0 + (floors - 1) * fh + sill + wh
            self.quad_w(a, d, n, 0, L, z0, za0, 0, wall)
            self.quad_w(a, d, n, 0, L, zb1, zt, 0, wall)
            prev = 0.0
            for ua, ub, _ in slots:
                self.quad_w(a, d, n, prev, ua, za0, zb1, 0, wall)
                prev = ub
                self.reveal(a, d, n, ua, ub, za0, zb1, dep, wall)
                sp = M(st.get("spandrel", "spandrel_dark"))
                for f in range(floors):
                    za = z0 + f * fh + sill
                    self.atlas_quad(a, d, n, ua, ub, za, za + wh, dep, M(st.get("glass", "win_office")), rng)
                    t = st.get("frame_t", 0.05)
                    fr = M(st.get("frame", "frame_black"))
                    self.box_w(a, d, n, ua, ub, za + wh, za + wh + t, dep - 0.06, dep, fr)
                    mc = st.get("mullions", (2, 1))[0]
                    for i in range(1, mc):
                        u = ua + (ub - ua) * i / mc
                        self.box_w(a, d, n, u - 0.03, u + 0.03, za, za + wh, dep - 0.06, dep, fr)
                    if f < floors - 1:
                        self.quad_w(a, d, n, ua, ub, za + wh + t, za + fh, dep, sp)
            self.quad_w(a, d, n, prev, L, za0, zb1, 0, wall)
            if piers:
                self._piers(a, d, n, L, slots, z0, zt, piers)
            return
        # punched: horizontal bands + piers between windows per floor
        z_prev = z0
        for f in range(floors):
            za = z0 + f * fh + sill
            zb = za + wh + r_extra
            self.quad_w(a, d, n, 0, L, z_prev, za, 0, wall)
            prev = 0.0
            for ua, ub, _ in slots:
                self.quad_w(a, d, n, prev, ua, za, zb, 0, wall)
                prev = ub
            self.quad_w(a, d, n, prev, L, za, zb, 0, wall)
            for ua, ub, _ in slots:
                self.window_unit(a, d, n, ua, ub, za, za + wh, st, rng, wall)
            z_prev = zb
        self.quad_w(a, d, n, 0, L, z_prev, zt, 0, wall)
        if piers:
            self._piers(a, d, n, L, slots, z0, zt, piers)

    def _piers(self, a, d, n, L, slots, z0, zt, piers):
        pm, pw, pd = piers
        bays = {}
        for ua, ub, i in slots:
            bays.setdefault(i, [ua, ub])
            bays[i][0] = min(bays[i][0], ua)
            bays[i][1] = max(bays[i][1], ub)
        edges = sorted(bays.values())
        us = [max(0.0, edges[0][0] - 0.25)]
        for k in range(len(edges) - 1):
            us.append((edges[k][1] + edges[k + 1][0]) / 2)
        us.append(min(L, edges[-1][1] + 0.25))
        for u in us:
            self.box_w(a, d, n, max(0, u - pw / 2), min(L, u + pw / 2), z0, zt, -pd, 0, M(pm))

    def curtain(self, a, d, n, L, z0, floors, fh, st, rng, zt):
        bay = st.get("bay", 1.5)
        nb = max(1, int(round(L / bay)))
        bw = L / nb
        glass = M(st.get("glass", "win_curtain_blue"))
        mul = M(st.get("mullion", "frame_alum"))
        sp = st.get("spandrel")
        sph = st.get("spandrel_h", 1.0)
        fin = st.get("fin", 0.12)
        for f in range(floors):
            zf = z0 + f * fh
            zg0 = zf + (sph if sp else 0)
            for i in range(nb):
                u0, u1 = i * bw, (i + 1) * bw
                self.atlas_quad(a, d, n, u0, u1, zg0, zf + fh, 0.0, glass, rng)
            if sp:
                self.quad_w(a, d, n, 0, L, zf, zg0, 0.0, M(sp))
        if zt > z0 + floors * fh:
            self.quad_w(a, d, n, 0, L, z0 + floors * fh, zt, 0.0, M(sp or "spandrel_dark"))
        if self.lod >= 1:
            return
        for i in range(nb + 1):
            u = i * bw
            self.box_w(a, d, n, max(0, u - 0.04), min(L, u + 0.04), z0, zt, -fin, 0, mul)
        hstep = st.get("transom_every", 1)
        for f in range(0, floors + 1, hstep):
            z = z0 + f * fh
            self.box_w(a, d, n, 0, L, z - 0.04, z + 0.04, -0.05, 0, mul)

    def ribbon(self, a, d, n, L, z0, floors, fh, st, rng, zt):
        wall = M(st["wall"])
        glass = M(st.get("glass", "win_office"))
        y0f, y1f = st.get("band", (0.3, 0.88))
        dep = st.get("depth", 0.12)
        mul = M(st.get("frame", "frame_alum"))
        bay = st.get("bay", 1.5)
        nb = max(1, int(round((L - 0.6) / bay)))
        zp = z0
        for f in range(floors):
            za, zb = z0 + f * fh + y0f * fh, z0 + f * fh + y1f * fh
            self.quad_w(a, d, n, 0, L, zp, za, 0, wall)
            self.quad_w(a, d, n, 0, 0.3, za, zb, 0, wall)
            self.quad_w(a, d, n, L - 0.3, L, za, zb, 0, wall)
            if self.lod < 1:
                self.reveal(a, d, n, 0.3, L - 0.3, za, zb, dep, wall)
            for i in range(nb):
                u0 = 0.3 + (L - 0.6) * i / nb
                u1 = 0.3 + (L - 0.6) * (i + 1) / nb
                self.atlas_quad(a, d, n, u0, u1, za, zb, dep if self.lod < 1 else -0.015, glass, rng)
                if self.lod < 1 and i:
                    self.box_w(a, d, n, u0 - 0.03, u0 + 0.03, za, zb, dep - 0.05, dep, mul)
            zp = zb
        self.quad_w(a, d, n, 0, L, zp, zt, 0, wall)

    # ---------------------------------------------------------------- facades around a footprint
    def facades(self, fp, z0, floors, fh, st, rng, side_styles=None, ztop=None):
        n = len(fp)
        for i in range(n):
            s = side_styles[i] if side_styles and side_styles[i] is not None else st
            if s == "skip":
                continue
            self.facade(fp[i], fp[(i + 1) % n], z0, floors, fh, s, rng, ztop)
        return z0 + floors * fh if ztop is None else ztop

    # ---------------------------------------------------------------- sweeps / trims
    def sweep(self, fp, z, profile, mat):
        mat = M(mat)
        rings = [(offset_poly(fp, o), z + dz) for o, dz in profile]
        n = len(fp)
        for k in range(len(rings) - 1):
            (r0, z0), (r1, z1) = rings[k], rings[k + 1]
            for i in range(n):
                j = (i + 1) % n
                p = [(r0[i][0], r0[i][1], z0), (r0[j][0], r0[j][1], z0), (r1[j][0], r1[j][1], z1),
                     (r1[i][0], r1[i][1], z1)]
                if abs(z1 - z0) < 1e-6 and abs(profile[k][0] - profile[k + 1][0]) < 1e-6:
                    continue
                self.face_auto(p, mat)
        return z + profile[-1][1]

    def cornice(self, fp, z, profile="cornice", mat="limestone", brackets=None, roof="roof_membrane"):
        prof = PROFILES[profile] if isinstance(profile, str) else profile
        if self.lod >= 1:
            prof = [prof[0], (max(o for o, _ in prof), prof[len(prof) // 2][1]), (max(o for o, _ in prof),
                                                                                    prof[-1][1]), prof[-1]]
        zt = self.sweep(fp, z, prof, mat)
        if brackets and self.lod < 1:
            # modillions under the corona
            o_max = max(o for o, _ in prof)
            zb = z + [dz for o, dz in prof if o >= o_max * 0.7][0]
            step = brackets
            nfp = len(fp)
            for i in range(nfp):
                a, b = fp[i], fp[(i + 1) % nfp]
                L = math.hypot(b[0] - a[0], b[1] - a[1])
                dv = ((b[0] - a[0]) / L, (b[1] - a[1]) / L)
                nv = (dv[1], -dv[0])
                k = int(L // step)
                for q in range(1, k):
                    u = L * q / k
                    self.box_w(a, dv, nv, u - 0.1, u + 0.1, zb - 0.32, zb, -o_max * 0.75, 0, M(mat))
        if roof:
            self.cap(fp, zt, M(roof))
        return zt

    def band(self, fp, z, profile="band", mat="limestone"):
        if self.lod >= 1:
            return
        self.sweep(fp, z, PROFILES[profile] if isinstance(profile, str) else profile, mat)

    def parapet(self, fp, z, h, t=0.3, wall="brick_red", coping="limestone", roof="roof_membrane"):
        wall, roof = M(wall), M(roof)
        n = len(fp)
        inner = offset_poly(fp, -t)
        for i in range(n):
            a, b = fp[i], fp[(i + 1) % n]
            self.wall_auto(a, b, z, z + h, wall)
            c, d = inner[i], inner[(i + 1) % n]
            self.wall_auto(d, c, z, z + h, wall)
        self.sweep(fp, z + h, [(-t - 0.04, 0), (0.05, 0), (0.05, 0.1), (-t - 0.04, 0.12), (-t - 0.04, 0)],
                   coping)
        if roof:
            self.cap(fp, z, roof)
        return z + h

    def wall_auto(self, a, b, z0, z1, mat):
        self.face_auto([(a[0], a[1], z0), (b[0], b[1], z0), (b[0], b[1], z1), (a[0], a[1], z1)], mat)

    def solid(self, fp, z0, z1, mat, top=None):
        mat = M(mat)
        n = len(fp)
        for i in range(n):
            self.wall_auto(fp[i], fp[(i + 1) % n], z0, z1, mat)
        self.cap(fp, z1, M(top) if top else mat)
        return z1

    # ---------------------------------------------------------------- ground floor
    def storefront(self, a, b, z0, h, st, rng, entrance=None):
        """Retail base along one wall. entrance = u position of a lobby door (or None)."""
        L = math.hypot(b[0] - a[0], b[1] - a[1])
        d = ((b[0] - a[0]) / L, (b[1] - a[1]) / L)
        n = (d[1], -d[0])
        pier = M(st.get("pier", "granite_black"))
        bay = st.get("bay", 4.5)
        pw = st.get("pier_w", 0.6)
        rec = st.get("recess", 0.35)
        sign_h = st.get("sign_h", 0.9)
        nb = max(1, int(round(L / bay)))
        bw = L / nb
        zo = z0 + h - (sign_h + 0.25 if st.get("signs", True) else 0.35)
        self.quad_w(a, d, n, 0, L, zo, z0 + h, 0, pier)
        ent_bay = None
        if entrance is not None:
            ent_bay = min(nb - 1, max(0, int(entrance / bw)))
        for i in range(nb):
            u0, u1 = i * bw, (i + 1) * bw
            self.quad_w(a, d, n, u0, u0 + pw / 2, z0, zo, 0, pier)
            self.quad_w(a, d, n, u1 - pw / 2, u1, z0, zo, 0, pier)
            oa, ob = u0 + pw / 2, u1 - pw / 2
            if self.lod >= 1:
                if i != ent_bay:
                    self.atlas_quad(a, d, n, oa, ob, z0, zo, -0.015, "hd_win_shop", rng)
                else:
                    self.quad_w(a, d, n, oa, ob, z0, zo, -0.015, "hd_glass_door")
                if st.get("signs", True) and i != ent_bay:
                    self.atlas_quad(a, d, n, oa + 0.1, ob - 0.1, zo + 0.12, zo + 0.12 + sign_h, -0.03,
                                    "hd_signs", rng, band=True)
                continue
            self.reveal(a, d, n, oa, ob, z0, zo, rec, pier, bottom=False)
            fr = M(st.get("frame", "frame_black"))
            if i == ent_bay:
                # lobby entrance: deeper, revolving-door style glass, canopy
                self.quad_w(a, d, n, oa, ob, z0, zo, rec + 0.01, "hd_glass_door")
                for k in range(1, 4):
                    u = oa + (ob - oa) * k / 4
                    self.box_w(a, d, n, u - 0.04, u + 0.04, z0, zo, rec - 0.05, rec, M("gold"))
                self.box_w(a, d, n, oa, ob, zo - 0.6, zo, rec - 0.08, rec, M("gold"))
                cz = z0 + min(h - 0.4, 4.2)
                cw = st.get("canopy", None)
                if cw:
                    self.box_w(a, d, n, oa - 0.4, ob + 0.4, cz, cz + 0.45, -cw, 0, M(st.get("canopy_mat", "frame_black")))
                    trim = M(st.get("canopy_trim", "gold"))
                    self.box_w(a, d, n, oa - 0.42, ob + 0.42, cz + 0.45, cz + 0.55, -cw - 0.02, 0, trim)
                    if st.get("canopy_posts"):
                        for u in (oa - 0.2, ob + 0.2):
                            self.box_w(a, d, n, u - 0.06, u + 0.06, z0, cz, -cw + 0.1, -cw + 0.22, trim)
                continue
            # bulkhead + display glass + transom + door
            bh = st.get("bulkhead", 0.55)
            self.box_w(a, d, n, oa, ob, z0, z0 + bh, rec - 0.05, rec, pier)
            self.atlas_quad(a, d, n, oa, ob, z0 + bh, zo, rec, "hd_win_shop", rng)
            self.box_w(a, d, n, oa, ob, z0 + bh - 0.02, z0 + bh + 0.06, rec - 0.09, rec, fr)
            zt_ = zo - 0.7
            self.box_w(a, d, n, oa, ob, zt_ - 0.04, zt_ + 0.04, rec - 0.09, rec, fr)
            self.box_w(a, d, n, oa, ob, zo - 0.06, zo, rec - 0.09, rec, fr)
            if rng.random() < 0.6:
                du = oa + (ob - oa) * (0.15 if rng.random() < 0.5 else 0.62)
                dw = min(1.1, (ob - oa) * 0.3)
                self.quad_w(a, d, n, du, du + dw, z0, zt_ - 0.04, rec - 0.01, "hd_glass_door")
                self.box_w(a, d, n, du - 0.05, du, z0, zt_, rec - 0.09, rec, fr)
                self.box_w(a, d, n, du + dw, du + dw + 0.05, z0, zt_, rec - 0.09, rec, fr)
            else:
                mid = (oa + ob) / 2
                self.box_w(a, d, n, mid - 0.04, mid + 0.04, z0 + bh, zt_, rec - 0.08, rec, fr)
            if st.get("signs", True):
                self.atlas_quad(a, d, n, oa + 0.1, ob - 0.1, zo + 0.12, zo + 0.12 + sign_h, -0.06, "hd_signs",
                                rng, band=True)
                self.box_w(a, d, n, oa + 0.05, ob - 0.05, zo + 0.07, zo + 0.17 + sign_h, -0.05, 0, fr)
            aw = st.get("awnings", 0)
            if aw and rng.random() < aw:
                col = M(rng.choice(st.get("awning_cols", ["awning_red", "awning_green", "awning_navy",
                                                          "awning_black", "awning_burgundy"])))
                self.awning_w(a, d, n, oa - 0.05, ob + 0.05, zt_ + 0.15, 1.3, 0.75, col)
        if self.lod < 1 and st.get("signs", True) is False:
            pass

    def awning_w(self, a, d, n, u0, u1, z, depth, drop, mat):
        P = self.P
        # sloped top
        top = [P(a, d, n, u0, z - drop, -depth), P(a, d, n, u1, z - drop, -depth), P(a, d, n, u1, z, 0.0),
               P(a, d, n, u0, z, 0.0)]
        self.face_auto(top, mat)
        self.face_auto(top[::-1], mat)
        # valance
        self.quad_w(a, d, n, u0, u1, z - drop - 0.25, z - drop, -depth, mat)
        for u, rev in ((u0, False), (u1, True)):
            tri = [P(a, d, n, u, z, 0.0), P(a, d, n, u, z - drop, -depth), P(a, d, n, u, z - drop - 0.25, -depth),
                   P(a, d, n, u, z - drop - 0.25, -0.02)]
            self.face_auto(tri if rev else tri[::-1], mat)
            self.face_auto(tri[::-1] if rev else tri, mat)

    def base_all(self, fp, z0, h, st, rng, entrance_edge=0, side_styles=None):
        """Storefront base on every edge (or per-edge style dicts / 'skip' / blank styles)."""
        nfp = len(fp)
        for i in range(nfp):
            a, b = fp[i], fp[(i + 1) % nfp]
            s = side_styles[i] if side_styles else st
            if s == "skip":
                continue
            if s.get("type") == "blank":
                self.facade(a, b, z0, 1, h, s, rng)
                continue
            L = math.hypot(b[0] - a[0], b[1] - a[1])
            self.storefront(a, b, z0, h, s, rng, entrance=L / 2 if i == entrance_edge else None)
        return z0 + h

    # ---------------------------------------------------------------- rooftop
    def water_tower_hd(self, cx, cy, z, r=2.6, h=5.0, legs=4.0):
        stl, dark = "hd_steel_dark", "hd_steel"
        nleg = 6
        pts = [(cx + r * 0.8 * math.cos(2 * math.pi * k / nleg), cy + r * 0.8 * math.sin(2 * math.pi * k / nleg))
               for k in range(nleg)]
        for k, (x, y) in enumerate(pts):
            self.beam((x, y, z), (x, y, z + legs), 0.22, stl)
            x2, y2 = pts[(k + 1) % nleg]
            if self.lod < 1:
                self.beam((x, y, z + 0.3), (x2, y2, z + legs - 0.3), 0.06, stl)
                self.beam((x2, y2, z + 0.3), (x, y, z + legs - 0.3), 0.06, stl)
        self.prism(circle(cx, cy, r * 1.05, 12), z + legs - 0.25, z + legs, dark, top=dark)
        self.prism(circle(cx, cy, r, 16), z + legs, z + legs + h, "hd_wood_staves", top="hd_wood_staves",
                   continuous_u=True)
        if self.lod < 1:
            for k in range(6):
                zz = z + legs + 0.4 + k * (h - 0.6) / 5
                self.prism(circle(cx, cy, r + 0.035, 16), zz, zz + 0.07, stl, top=None)
            self.beam((cx + r + 0.15, cy, z), (cx + r + 0.15, cy, z + legs + h + 0.6), 0.08, stl)
        self.pyramid(circle(cx, cy, r + 0.2, 16), z + legs + h, z + legs + h + 1.9, "hd_wood_staves")
        self.beam((cx, cy, z + legs + h + 1.8), (cx, cy, z + legs + h + 2.4), 0.1, stl)

    def hvac_hd(self, cx, cy, z, w=3.0, dd=2.0, h=1.6, rot=False):
        if rot:
            w, dd = dd, w
        self.box(cx, cy, w, dd, z, z + h, "hd_hvac")
        if self.lod < 1:
            for k in range(2):
                fx = cx - w / 4 + k * w / 2 if w >= dd else cx
                fy = cy if w >= dd else cy - dd / 4 + k * dd / 2
                self.prism(circle(fx, fy, min(w, dd) * 0.22, 10), z + h, z + h + 0.12, "hd_grille", top="hd_grille")
            self.box(cx, cy, w * 0.9, 0.1, z + 0.2, z + h - 0.2, "hd_grille") if w >= dd else None

    def bulkhead_hd(self, cx, cy, z, w=5, dd=4, h=3.4, wall="brick_red"):
        fp = rect(cx, cy, w, dd)
        self.solid(fp, z, z + h, wall, top="roof_membrane")
        if self.lod < 1:
            self.sweep(fp, z + h, PROFILES["coping"], "limestone")
            a, b = fp[0], fp[1]
            L = w
            dv, nv = (1, 0), (0, -1)
            self.box_w(a, dv, nv, L * 0.3, L * 0.3 + 1.0, z, z + 2.2, -0.03, 0.02, "hd_steel_dark")

    def railing(self, fp, z, h=1.1, mat="hd_steel_dark", glass=False):
        nfp = len(fp)
        for i in range(nfp):
            a, b = fp[i], fp[(i + 1) % nfp]
            if glass:
                self.face_auto([(a[0], a[1], z), (b[0], b[1], z), (b[0], b[1], z + h), (a[0], a[1], z + h)],
                               "hd_glass_rail")
                self.face_auto([(b[0], b[1], z), (a[0], a[1], z), (a[0], a[1], z + h), (b[0], b[1], z + h)],
                               "hd_glass_rail")
            if self.lod < 1:
                self.beam((a[0], a[1], z + h), (b[0], b[1], z + h), 0.06, mat)
                if not glass:
                    L = math.hypot(b[0] - a[0], b[1] - a[1])
                    k = max(1, int(L / 1.5))
                    for q in range(k):
                        t = q / k
                        x, y = a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t
                        self.beam((x, y, z), (x, y, z + h), 0.05, mat)

    def roof_kit(self, fp, z, rng, water=0, hvac=2, bulk=True, wall="brick_red", antenna=False, dishes=0):
        x0, y0, x1, y1 = bounds(fp)
        w, dd = x1 - x0, y1 - y0
        if bulk and w > 8 and dd > 8:
            self.bulkhead_hd(x0 + 4.0, y1 - 3.5, z, min(6, w * 0.3), min(5, dd * 0.3), 3.4, wall)
        for k in range(water):
            r = min(2.6, w * 0.12)
            self.water_tower_hd(x1 - r - 2.0 - k * (2 * r + 2.5), y0 + dd * 0.6, z, r=r)
        for _ in range(hvac):
            if w > 10 and dd > 10:
                self.hvac_hd(rng.uniform(x0 + 3, x1 - 3), rng.uniform(y0 + 3, y0 + dd * 0.45), z,
                             rot=rng.random() < 0.5)
        if antenna:
            self.beam(((x0 + x1) / 2, (y0 + y1) / 2, z), ((x0 + x1) / 2, (y0 + y1) / 2, z + antenna), 0.3, "hd_steel")
            self.box((x0 + x1) / 2, (y0 + y1) / 2, 0.3, 0.3, z + antenna, z + antenna + 0.3, "hd_red_light")
        for k in range(dishes):
            cx, cy = rng.uniform(x0 + 2, x1 - 2), rng.uniform(y0 + 2, y1 - 2)
            self.beam((cx, cy, z), (cx, cy, z + 1.2), 0.1, "hd_steel")
            self.prism(circle(cx, cy - 0.3, 0.7, 10), z + 1.0, z + 1.1, "hd_frame_white", top="hd_frame_white")

    def balconies(self, a, b, z0, floors, fh, rng, every=2, width=3.2, depth=1.5, glass=True, offset=1.6,
                  slab="hd_concrete"):
        L = math.hypot(b[0] - a[0], b[1] - a[1])
        d = ((b[0] - a[0]) / L, (b[1] - a[1]) / L)
        n = (d[1], -d[0])
        k = int((L - 2 * offset) // (width + 1.2))
        if k <= 0:
            return
        span = (L - 2 * offset)
        for f in range(1, floors):
            zf = z0 + f * fh
            for i in range(k):
                if (i + f) % every:
                    continue
                uc = offset + span * (i + 0.5) / k
                u0, u1 = uc - width / 2, uc + width / 2
                self.box_w(a, d, n, u0, u1, zf - 0.2, zf, -depth, 0, M(slab))
                if glass:
                    self.quad_w(a, d, n, u0, u1, zf, zf + 1.05, -depth + 0.02, "hd_glass_rail")
                    if self.lod < 1:
                        self.box_w(a, d, n, u0, u1, zf + 1.05, zf + 1.1, -depth, -depth + 0.06, "hd_steel_dark")
                else:
                    self.box_w(a, d, n, u0, u1, zf, zf + 1.05, -depth, -depth + 0.08, M(slab))
