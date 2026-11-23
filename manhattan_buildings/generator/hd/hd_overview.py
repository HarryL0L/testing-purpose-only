"""Place the 30 HD buildings on a Manhattan-style street grid, save an editable
.blend (textures packed) and render an aerial view + a street-level close-up.

usage: python3 hd_overview.py --out <kit_dir>/hd [--samples 96] [--no-blend]
"""
import argparse
import math
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))

import bpy  # noqa: E402
from mathutils import Vector  # noqa: E402

import hd_catalog  # noqa: E402
import hd_generate as G  # noqa: E402
from builder import rect_xy  # noqa: E402
from hd_builder import HDBuilder  # noqa: E402

LOT_W, LOT_D = 62.0, 70.0      # lot pitch (x, y)
AVENUE, STREET = 30.0, 18.0     # road widths
COLS = 6


def look(cam, target):
    d = Vector(target) - cam.location
    cam.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--samples", type=int, default=96)
    ap.add_argument("--no-blend", action="store_true")
    a = ap.parse_args()
    out = os.path.abspath(a.out)
    G.TEX = os.path.join(out, "textures")
    G.TEX_SIZE = 1024
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    col = bpy.data.collections.new("HD Buildings")
    scene.collection.children.link(col)

    entries = list(hd_catalog.ENTRIES)
    # taller buildings toward the back rows
    built = []
    for e in entries:
        b = HDBuilder(0)
        e["fn"](b, random.Random(e["seed"]))
        built.append((e, b, b.bbox()))
    built.sort(key=lambda t: t[2][1][2])
    rows = (len(built) + COLS - 1) // COLS
    pitch_x, pitch_y = LOT_W + AVENUE, LOT_D + STREET
    ox = -(COLS - 1) * pitch_x / 2
    oy = -(rows - 1) * pitch_y / 2
    for k, (e, b, (bmin, bmax)) in enumerate(built):
        r, c = k // COLS, k % COLS
        if r % 2:
            c = COLS - 1 - c
        obj = b.to_blender(f"hd{e['id']:02d}_{e['key']}", G.make_material)
        scene.collection.objects.unlink(obj)
        col.objects.link(obj)
        cx, cy = (bmin[0] + bmax[0]) / 2, (bmin[1] + bmax[1]) / 2
        obj.location = (ox + c * pitch_x - cx, oy + r * pitch_y - cy, 0)

    # streets: asphalt everywhere, raised sidewalk blocks per lot row
    s = HDBuilder(0)
    s.cap(rect_xy(-6000, -6000, 6000, 6000), -0.15, "hd_asphalt")
    for r in range(rows):
        y = oy + r * pitch_y
        for c in range(COLS):
            x = ox + c * pitch_x
            s.solid(rect_xy(x - LOT_W / 2 - 4, y - LOT_D / 2 - 4, x + LOT_W / 2 + 4, y + LOT_D / 2 + 4), -0.15, 0.0,
                    "hd_sidewalk", top="hd_sidewalk")
            for t in range(-10, 11):  # crosswalk at the front of each block
                xx = x + t * 1.3
                s.face_auto([(xx, y - LOT_D / 2 - 11, -0.14), (xx + 0.6, y - LOT_D / 2 - 11, -0.14),
                             (xx + 0.6, y - LOT_D / 2 - 6, -0.14), (xx, y - LOT_D / 2 - 6, -0.14)], "hd_frame_white")
            # street trees + lamps along the front sidewalk
            for t in range(5):
                tx = x - LOT_W / 2 + 6 + t * (LOT_W - 12) / 4
                ty = y - LOT_D / 2 - 2.5
                s.cylinder(tx, ty, 0.18, 0, 3.0, "hd_frame_brown", n=6)
                s.dome(tx, ty, 2.2, 2.4, "hd_planter", n=10, rings=3, squash=1.2)
            for t in (0, 1):
                lx = x - LOT_W / 2 + 2 + t * (LOT_W - 4)
                ly = y - LOT_D / 2 - 3.6
                s.beam((lx, ly, 0), (lx, ly, 7.5), 0.18, "hd_steel_dark")
                s.beam((lx, ly, 7.5), (lx + 1.5, ly, 7.8), 0.1, "hd_steel_dark")
                s.box(lx + 1.6, ly, 0.6, 0.3, 7.6, 7.8, "hd_lamp")
    street = s.to_blender("streets", G.make_material)

    if not a.no_blend:
        G.setup_scene(scene, a.samples, (1920, 1080))
        cam_data = bpy.data.cameras.new("Aerial")
        cam_data.lens = 32
        cam_data.clip_end = 30000
        cam = bpy.data.objects.new("Aerial", cam_data)
        scene.collection.objects.link(cam)
        scene.camera = cam
        cam.location = (340, -620, 330)
        look(cam, (-20, 40, 40))
        bpy.ops.file.pack_all()
        p = os.path.join(out, "manhattan_hd_kit.blend")
        bpy.ops.wm.save_as_mainfile(filepath=p, compress=True)
        print("saved", p)
    else:
        G.setup_scene(scene, a.samples, (1920, 1080))
        cam_data = bpy.data.cameras.new("Aerial")
        cam_data.lens = 32
        cam_data.clip_end = 30000
        cam = bpy.data.objects.new("Aerial", cam_data)
        scene.collection.objects.link(cam)
        scene.camera = cam
        cam.location = (340, -620, 330)
        look(cam, (-20, 40, 40))
    scene.render.image_settings.file_format = "JPEG"
    scene.render.image_settings.quality = 92
    scene.render.filepath = os.path.join(out, "overview_hd.jpg")
    bpy.ops.render.render(write_still=True)
    print("rendered aerial")
    # street-level view down the front avenue
    cam2_data = bpy.data.cameras.new("Street")
    cam2_data.lens = 24
    cam2_data.clip_end = 30000
    cam2 = bpy.data.objects.new("Street", cam2_data)
    scene.collection.objects.link(cam2)
    y_front = oy - LOT_D / 2 - 9
    cam2.location = (ox - pitch_x * 0.5 + 6, y_front, 1.7)
    look(cam2, (ox + pitch_x * 1.8, y_front + 40, 22))
    scene.camera = cam2
    scene.render.filepath = os.path.join(out, "street_view_hd.jpg")
    bpy.ops.render.render(write_still=True)
    print("rendered street")
    del street


def s_circle(cx, cy, r, n):
    return [(cx + r * math.cos(2 * math.pi * i / n), cy + r * math.sin(2 * math.pi * i / n)) for i in range(n)]


if __name__ == "__main__":
    main()
