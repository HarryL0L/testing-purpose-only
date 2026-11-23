"""Lay out all 100 buildings on a street grid, save a .blend kit and render an aerial overview.

usage: python3 overview.py --out ../ [--samples 64] [--no-render]
"""
import argparse
import math
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import bpy  # noqa: E402

import catalog  # noqa: E402
import generate as G  # noqa: E402
from builder import Builder  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(HERE, ".."))
    ap.add_argument("--samples", type=int, default=64)
    ap.add_argument("--no-render", action="store_true")
    a = ap.parse_args()
    out = os.path.abspath(a.out)
    G.TEX_DIR = os.path.join(out, "textures")
    G.reset_scene()
    scene = bpy.context.scene

    built = []
    for e in catalog.ENTRIES:
        b = Builder()
        e["fn"](b, random.Random(e["seed"]))
        (x0, y0, _), (x1, y1, z1) = b.bbox()
        built.append((e, b, x0, y0, x1, y1, z1))
    # short buildings in front rows, towers at the back
    built.sort(key=lambda t: t[6])

    row_w, gap, street = 620.0, 12.0, 30.0
    x, y, row_d = 0.0, 0.0, 0.0
    placed = []
    for e, b, x0, y0, x1, y1, z1 in built:
        w, d = x1 - x0, y1 - y0
        if x + w > row_w:
            x, y, row_d = 0.0, y + row_d + street, 0.0
        placed.append((e, b, x - x0, y - y0))
        x += w + gap
        row_d = max(row_d, d)
    total_d = y + row_d

    col = bpy.data.collections.new("Manhattan Kit")
    scene.collection.children.link(col)
    for e, b, ox, oy in placed:
        obj = b.to_blender(f"{e['id']:03d}_{e['key']}", G.make_material)
        scene.collection.objects.unlink(obj)
        col.objects.link(obj)
        obj.location = (ox - row_w / 2, oy - total_d / 2, 0)

    G.add_ground(8000, -0.02)
    blend = os.path.join(out, "manhattan_kit.blend")
    bpy.ops.file.pack_all()
    bpy.ops.wm.save_as_mainfile(filepath=blend, compress=True)
    print("saved", blend)

    if a.no_render:
        return
    G.setup_render(scene, a.samples, (1920, 1080))
    cam_data = bpy.data.cameras.new("Overview")
    cam_data.lens = 35
    cam_data.clip_end = 20000
    cam = bpy.data.objects.new("Overview", cam_data)
    scene.collection.objects.link(cam)
    scene.camera = cam
    cam.location = (560, -total_d / 2 - 820, 560)
    target = (-10, 60, 70)
    from mathutils import Vector
    d = Vector(target) - cam.location
    cam.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()
    scene.render.image_settings.file_format = "JPEG"
    scene.render.image_settings.quality = 90
    scene.render.filepath = os.path.join(out, "overview.jpg")
    bpy.ops.render.render(write_still=True)
    print("rendered overview")


if __name__ == "__main__":
    main()
