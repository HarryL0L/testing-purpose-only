"""Low-poly geometry builder.

Collects faces with per-face material + UVs into one mesh. Pure Python; only
`to_blender()` touches bpy.  Conventions: metres, Z up, building centred on
the origin, main facade faces -Y ("front").
"""
import math

from materials import MATERIALS


# ---------------------------------------------------------------- footprints
def rect(cx, cy, w, d):
    x0, x1, y0, y1 = cx - w / 2, cx + w / 2, cy - d / 2, cy + d / 2
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


def rect_xy(x0, y0, x1, y1):
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


def circle(cx, cy, r, n=16, rot=0.0):
    return [(cx + r * math.cos(rot + 2 * math.pi * i / n), cy + r * math.sin(rot + 2 * math.pi * i / n))
            for i in range(n)]


def chamfer(cx, cy, w, d, c):
    x0, x1, y0, y1 = cx - w / 2, cx + w / 2, cy - d / 2, cy + d / 2
    return [(x0 + c, y0), (x1 - c, y0), (x1, y0 + c), (x1, y1 - c), (x1 - c, y1), (x0 + c, y1),
            (x0, y1 - c), (x0, y0 + c)]


def scale_fp(fp, s, sy=None):
    sy = s if sy is None else sy
    cx = sum(p[0] for p in fp) / len(fp)
    cy = sum(p[1] for p in fp) / len(fp)
    return [(cx + (x - cx) * s, cy + (y - cy) * sy) for x, y in fp]


def offset_rect(fp, e):
    """Grow an axis-aligned rect footprint by e on every side."""
    xs = [p[0] for p in fp]
    ys = [p[1] for p in fp]
    return rect_xy(min(xs) - e, min(ys) - e, max(xs) + e, max(ys) + e)


def rotate_fp(fp, ang, cx=0.0, cy=0.0):
    c, s = math.cos(ang), math.sin(ang)
    return [(cx + (x - cx) * c - (y - cy) * s, cy + (x - cx) * s + (y - cy) * c) for x, y in fp]


def bounds(fp):
    xs = [p[0] for p in fp]
    ys = [p[1] for p in fp]
    return min(xs), min(ys), max(xs), max(ys)


# ---------------------------------------------------------------- vectors
def _sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _norm(a):
    l = math.sqrt(a[0] ** 2 + a[1] ** 2 + a[2] ** 2) or 1.0
    return (a[0] / l, a[1] / l, a[2] / l)


def _dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


# ---------------------------------------------------------------- builder
class Builder:
    def __init__(self):
        self.verts, self.faces, self.uvs, self.fmat = [], [], [], []
        self.mat_names = []

    def _mi(self, name):
        if name not in MATERIALS:
            raise KeyError(f"unknown material {name}")
        if name not in self.mat_names:
            self.mat_names.append(name)
        return self.mat_names.index(name)

    @staticmethod
    def spec(mat):
        return MATERIALS[mat]

    def fh(self, mat):
        return MATERIALS[mat]["fh"]

    # -- raw ------------------------------------------------------------
    def face(self, pts, uvs, mat):
        base = len(self.verts)
        self.verts.extend(pts)
        self.faces.append(list(range(base, base + len(pts))))
        self.uvs.append(list(uvs))
        self.fmat.append(self._mi(mat))

    def face_auto(self, pts, mat):
        """Planar face with UVs projected on its own plane (metres / tile)."""
        sp = MATERIALS[mat]
        n = _norm(_cross(_sub(pts[1], pts[0]), _sub(pts[2], pts[0])))
        if abs(n[2]) > 0.9:
            ua, va = (1, 0, 0), (0, 1, 0)
        else:
            ua = _norm((-n[1], n[0], 0))
            va = _cross(n, ua)
        su, sv = sp["bay"] * sp["cx"], sp["fh"] * sp["cy"]
        uvs = [(_dot(p, ua) / su, _dot(p, va) / sv) for p in pts]
        self.face(pts, uvs, mat)

    # -- walls ------------------------------------------------------------
    def wall(self, a, b, z0, z1, mat, vbase=None, u0=0.0):
        sp = MATERIALS[mat]
        L = math.hypot(b[0] - a[0], b[1] - a[1])
        if L < 1e-6 or z1 <= z0:
            return
        nb = max(1, round(L / sp["bay"]))
        vb = z0 if vbase is None else vbase
        u1 = u0 + nb / sp["cx"]
        v0 = (z0 - vb) / sp["fh"] / sp["cy"]
        v1 = (z1 - vb) / sp["fh"] / sp["cy"]
        self.face([(a[0], a[1], z0), (b[0], b[1], z0), (b[0], b[1], z1), (a[0], a[1], z1)],
                  [(u0, v0), (u1, v0), (u1, v1), (u0, v1)], mat)

    def cap(self, fp, z, mat, up=True):
        pts = [(x, y, z) for x, y in fp]
        if not up:
            pts = pts[::-1]
        self.face_auto(pts, mat)

    def prism(self, fp, z0, z1, mat, top="roof_tar", bottom=False, vbase=None, side_mats=None,
              continuous_u=False):
        """Extrude a CCW footprint. side_mats: optional per-edge material list."""
        n = len(fp)
        u = 0.0
        for i in range(n):
            a, b = fp[i], fp[(i + 1) % n]
            m = side_mats[i] if side_mats else mat
            if m:
                self.wall(a, b, z0, z1, m, vbase, u if continuous_u else 0.0)
            if continuous_u:
                sp = MATERIALS[m or mat]
                u += max(1, round(math.hypot(b[0] - a[0], b[1] - a[1]) / sp["bay"])) / sp["cx"]
        if top:
            self.cap(fp, z1, top)
        if bottom:
            self.cap(fp, z0, bottom if isinstance(bottom, str) else mat, up=False)
        return z1

    def floors(self, fp, z0, nfloors, mat, top="roof_tar", side_mats=None, continuous_u=False):
        """Prism sized to a whole number of storeys of `mat`. Returns top z."""
        z1 = z0 + nfloors * MATERIALS[mat]["fh"]
        self.prism(fp, z0, z1, mat, top, side_mats=side_mats, continuous_u=continuous_u)
        return z1

    def box(self, cx, cy, w, d, z0, z1, mat, top=None):
        return self.prism(rect(cx, cy, w, d), z0, z1, mat, top or mat, bottom=False)

    def box_xy(self, x0, y0, x1, y1, z0, z1, mat, top=None, bottom=False):
        return self.prism(rect_xy(x0, y0, x1, y1), z0, z1, mat, top or mat, bottom=bottom)

    def cylinder(self, cx, cy, r, z0, z1, mat, top=None, n=16):
        return self.prism(circle(cx, cy, r, n), z0, z1, mat, top or mat, continuous_u=True)

    # -- sloped ----------------------------------------------------------
    def frustum(self, fp, z0, z1, s, mat, top=None):
        """Sloped sides from fp at z0 to fp scaled by s at z1 (s=0 -> pyramid)."""
        tp = scale_fp(fp, s) if s > 0 else None
        n = len(fp)
        cx = sum(p[0] for p in fp) / n
        cy = sum(p[1] for p in fp) / n
        for i in range(n):
            a, b = fp[i], fp[(i + 1) % n]
            if tp:
                c, d = tp[(i + 1) % n], tp[i]
                self.face_auto([(a[0], a[1], z0), (b[0], b[1], z0), (c[0], c[1], z1), (d[0], d[1], z1)], mat)
            else:
                self.face_auto([(a[0], a[1], z0), (b[0], b[1], z0), (cx, cy, z1)], mat)
        if tp and top:
            self.cap(tp, z1, top)
        return z1

    def pyramid(self, fp, z0, z1, mat):
        return self.frustum(fp, z0, z1, 0, mat)

    def extrude_x(self, profile, x0, x1, mat, end_mat=None):
        """Extrude a CCW (y,z) profile along +X. Used for gables, awnings, pediments."""
        n = len(profile)
        end_mat = end_mat or mat
        for i in range(n):
            (ya, za), (yb, zb) = profile[i], profile[(i + 1) % n]
            self.face_auto([(x0, ya, za), (x0, yb, zb), (x1, yb, zb), (x1, ya, za)], mat)
        self.face_auto([(x0, y, z) for y, z in profile][::-1], end_mat)
        self.face_auto([(x1, y, z) for y, z in profile], end_mat)

    def extrude_y(self, profile, y0, y1, mat, end_mat=None):
        """Extrude a CCW (x,z) profile along +Y."""
        n = len(profile)
        end_mat = end_mat or mat
        for i in range(n):
            (xa, za), (xb, zb) = profile[i], profile[(i + 1) % n]
            self.face_auto([(xa, y0, za), (xb, y0, zb), (xb, y1, zb), (xa, y1, za)][::-1], mat)
        self.face_auto([(x, y0, z) for x, z in profile], end_mat)
        self.face_auto([(x, y1, z) for x, z in profile][::-1], end_mat)

    def gable(self, x0, y0, x1, y1, z0, h, mat, end_mat=None, axis="x", overhang=0.3):
        """Pitched roof; ridge runs along `axis`."""
        if axis == "x":
            ym = (y0 + y1) / 2
            prof = [(y0 - overhang, z0), (y1 + overhang, z0), (ym, z0 + h)]
            self.extrude_x(prof, x0, x1, mat, end_mat)
        else:
            xm = (x0 + x1) / 2
            prof = [(x0 - overhang, z0), (x1 + overhang, z0), (xm, z0 + h)]
            self.extrude_y(prof, y0, y1, mat, end_mat)

    def mansard(self, fp, z0, h, mat, inset=1.2, top="roof_tar"):
        xs = [p[0] for p in fp]
        w = max(xs) - min(xs)
        s = max(0.1, (w - 2 * inset) / w)
        return self.frustum(fp, z0, z0 + h, s, mat, top)

    def dome(self, cx, cy, r, z0, mat, n=16, rings=6, squash=1.0, top_point=True):
        prev = circle(cx, cy, r, n)
        pz = z0
        for k in range(1, rings + 1):
            a = (math.pi / 2) * k / rings
            rr = r * math.cos(a)
            z = z0 + r * squash * math.sin(a)
            if k == rings and top_point:
                for i in range(n):
                    p, q = prev[i], prev[(i + 1) % n]
                    self.face_auto([(p[0], p[1], pz), (q[0], q[1], pz), (cx, cy, z)], mat)
            else:
                cur = circle(cx, cy, rr, n)
                for i in range(n):
                    p, q, s_, t = prev[i], prev[(i + 1) % n], cur[(i + 1) % n], cur[i]
                    self.face_auto([(p[0], p[1], pz), (q[0], q[1], pz), (s_[0], s_[1], z), (t[0], t[1], z)], mat)
                prev, pz = cur, z
        return z0 + r * squash

    def onion(self, cx, cy, r, z0, mat, n=12):
        prof = [(0.75, 0.0), (1.0, 0.25), (0.95, 0.5), (0.6, 0.8), (0.25, 1.05), (0.0, 1.5)]
        prev = None
        for rr, hz in prof:
            ring = circle(cx, cy, r * rr, n) if rr > 0 else None
            z = z0 + r * hz
            if prev is not None:
                pr, pz = prev
                for i in range(n):
                    p, q = pr[i], pr[(i + 1) % n]
                    if ring is None:
                        self.face_auto([(p[0], p[1], pz), (q[0], q[1], pz), (cx, cy, z)], mat)
                    else:
                        s_, t = ring[(i + 1) % n], ring[i]
                        self.face_auto([(p[0], p[1], pz), (q[0], q[1], pz), (s_[0], s_[1], z),
                                        (t[0], t[1], z)], mat)
            prev = (ring, z)
        return z0 + r * 1.5

    # -- linear members ----------------------------------------------------
    def beam(self, p0, p1, t, mat, t2=None):
        """Square-section member from p0 to p1 (any direction)."""
        t2 = t if t2 is None else t2
        d = _norm(_sub(p1, p0))
        ref = (0, 0, 1) if abs(d[2]) < 0.95 else (1, 0, 0)
        a = _norm(_cross(d, ref))
        b = _cross(d, a)
        h, h2 = t / 2, t2 / 2
        corners = [(-h, -h2), (h, -h2), (h, h2), (-h, h2)]

        def P(base, i):
            u, v = corners[i]
            return (base[0] + a[0] * u + b[0] * v, base[1] + a[1] * u + b[1] * v, base[2] + a[2] * u + b[2] * v)

        for i in range(4):
            j = (i + 1) % 4
            self.face_auto([P(p0, i), P(p0, j), P(p1, j), P(p1, i)], mat)
        self.face_auto([P(p1, i) for i in range(4)], mat)
        self.face_auto([P(p0, i) for i in range(4)][::-1], mat)

    def spire(self, cx, cy, z0, h, r, mat, n=8):
        self.pyramid(circle(cx, cy, r, n, math.pi / n), z0, z0 + h, mat)
        return z0 + h

    def antenna(self, cx, cy, z0, h, t=0.4, mat="steel"):
        self.beam((cx, cy, z0), (cx, cy, z0 + h), t, mat)
        self.box(cx, cy, t * 3, t * 3, z0 + h * 0.6, z0 + h * 0.6 + 0.5, mat)
        return z0 + h

    # -- composite details -------------------------------------------------
    def cornice(self, fp, z, h=0.8, e=0.45, mat="trim_stone"):
        """Projecting band; top/bottom are rings so the roof inside stays visible."""
        outer = offset_rect(fp, e)
        inner = rect_xy(*bounds(fp))
        self.prism(outer, z - h, z, mat, top=None)
        for i in range(4):
            a, b2 = outer[i], outer[(i + 1) % 4]
            c, d = inner[(i + 1) % 4], inner[i]
            self.face_auto([(a[0], a[1], z), (b2[0], b2[1], z), (c[0], c[1], z), (d[0], d[1], z)], mat)
            self.face_auto([(d[0], d[1], z - h), (c[0], c[1], z - h), (b2[0], b2[1], z - h), (a[0], a[1], z - h)], mat)

    def parapet(self, fp, z, h=1.0, t=0.3, mat="trim_stone"):
        x0, y0, x1, y1 = bounds(fp)
        self.box_xy(x0, y0, x1, y0 + t, z, z + h, mat)
        self.box_xy(x0, y1 - t, x1, y1, z, z + h, mat)
        self.box_xy(x0, y0 + t, x0 + t, y1 - t, z, z + h, mat)
        self.box_xy(x1 - t, y0 + t, x1, y1 - t, z, z + h, mat)

    def crenellate(self, fp, z, h=1.2, step=2.0, mat="brick_red_blank"):
        x0, y0, x1, y1 = bounds(fp)
        for x in frange(x0, x1 - step / 2, step):
            self.box_xy(x, y0, x + step / 2, y0 + 0.5, z, z + h, mat)
            self.box_xy(x, y1 - 0.5, x + step / 2, y1, z, z + h, mat)
        for y in frange(y0 + step, y1 - step, step):
            self.box_xy(x0, y, x0 + 0.5, y + step / 2, z, z + h, mat)
            self.box_xy(x1 - 0.5, y, x1, y + step / 2, z, z + h, mat)

    def water_tower(self, cx, cy, z, r=2.4, h=4.5, legs=3.5):
        for dx, dy in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            self.beam((cx + dx * r * 0.6, cy + dy * r * 0.6, z), (cx + dx * r * 0.6, cy + dy * r * 0.6, z + legs),
                      0.25, "steel")
        self.box(cx, cy, r * 1.6, r * 1.6, z + legs - 0.2, z + legs, "steel")
        self.cylinder(cx, cy, r, z + legs, z + legs + h, "wood", "wood", n=12)
        self.pyramid(circle(cx, cy, r + 0.15, 12), z + legs + h, z + legs + h + 1.6, "wood")

    def hvac(self, cx, cy, z, w=2.5, d=1.8, h=1.4):
        self.box(cx, cy, w, d, z, z + h, "hvac")
        self.cylinder(cx, cy, min(w, d) * 0.3, z + h, z + h + 0.2, "dark", n=8)

    def bulkhead(self, cx, cy, z, w=4, d=3.5, h=3.0, mat="brick_red_blank"):
        self.box(cx, cy, w, d, z, z + h, mat, "roof_tar")

    def fire_escape(self, x0, x1, y, z_start, nfloors, fh, depth=1.3):
        """Black iron balconies + ladders on the -Y face at plane y."""
        for k in range(nfloors):
            z = z_start + k * fh
            self.box_xy(x0, y - depth, x1, y, z, z + 0.1, "iron")
            self.box_xy(x0, y - depth, x1, y - depth + 0.06, z + 1.0, z + 1.06, "iron")
            self.beam((x0, y - depth + 0.03, z), (x0, y - depth + 0.03, z + 1.0), 0.05, "iron")
            self.beam((x1, y - depth + 0.03, z), (x1, y - depth + 0.03, z + 1.0), 0.05, "iron")
            if k < nfloors - 1:
                xm = (x0 + x1) / 2
                self.beam((xm - 0.9, y - depth / 2, z + 0.1), (xm + 0.9, y - depth / 2, z + fh), 0.12, "iron", 0.5)

    def stoop(self, cx, y, w, h, steps=6, depth=0.3, mat="brownstone_plain"):
        for i in range(steps):
            zt = h * (steps - i) / steps
            self.box_xy(cx - w / 2, y - (i + 1) * depth, cx + w / 2, y - i * depth, 0, zt, mat)
        L = steps * depth
        for sx in (-1, 1):
            x = cx + sx * (w / 2 + 0.05)
            self.beam((x, y - L, 0.9), (x, y, h + 0.9), 0.06, "iron")

    def awning(self, x0, x1, y, z, depth=1.4, drop=0.6, mat="awning_red"):
        prof = [(y, z), (y, z + 0.1), (y - depth, z - drop + 0.1), (y - depth, z - drop)]
        self.extrude_x(prof, x0, x1, mat)

    def blade_sign(self, x, y, z0, h, w=1.2, mat="sign_red"):
        self.box_xy(x - 0.15, y - w, x + 0.15, y, z0, z0 + h, mat)

    def columns(self, x0, x1, y, z0, z1, n, r=0.45, mat="trim_stone"):
        for i in range(n):
            x = x0 + (x1 - x0) * i / max(1, n - 1)
            self.cylinder(x, y, r, z0, z1, mat, n=10)
            self.box(x, y, r * 2.6, r * 2.6, z0, z0 + 0.4, mat)
            self.box(x, y, r * 2.6, r * 2.6, z1 - 0.4, z1, mat)

    def pediment(self, x0, x1, y0, y1, z, h, mat="trim_stone"):
        xm = (x0 + x1) / 2
        self.extrude_y([(x0, z), (x1, z), (xm, z + h)], y0, y1, mat)

    def steps(self, x0, x1, y_front, z_top, n=5, depth=0.45, mat="trim_stone"):
        for i in range(n):
            self.box_xy(x0, y_front - (n - i) * depth, x1, y_front, 0, z_top * (i + 1) / n, mat)

    # -- export ------------------------------------------------------------
    def stats(self):
        tris = sum(len(f) - 2 for f in self.faces)
        return dict(faces=len(self.faces), tris=tris)

    def bbox(self):
        xs = [v[0] for v in self.verts]
        ys = [v[1] for v in self.verts]
        zs = [v[2] for v in self.verts]
        return (min(xs), min(ys), min(zs)), (max(xs), max(ys), max(zs))

    def to_blender(self, name, get_material):
        import bpy
        me = bpy.data.meshes.new(name)
        me.from_pydata(self.verts, [], self.faces)
        uvl = me.uv_layers.new(name="UVMap")
        flat = [uv for f in self.uvs for uv in f]
        uvl.data.foreach_set("uv", [c for uv in flat for c in uv])
        for m in self.mat_names:
            me.materials.append(get_material(m))
        me.polygons.foreach_set("material_index", self.fmat)
        me.update()
        obj = bpy.data.objects.new(name, me)
        bpy.context.scene.collection.objects.link(obj)
        return obj


def frange(a, b, step):
    x = a
    while x <= b + 1e-9:
        yield x
        x += step
