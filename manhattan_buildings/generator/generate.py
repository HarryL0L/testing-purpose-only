"""Build every catalog entry with Blender (bpy) -> GLB + preview PNG.

usage: python3 generate.py --out ../ [--only 3,17] [--start 0 --end 100]
                           [--no-render] [--no-export] [--samples 24]
"""
import argparse
import json
import math
import os
import random
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import bpy  # noqa: E402

from builder import Builder  # noqa: E402
from materials import MATERIALS  # noqa: E402
import catalog  # noqa: E402

TEX_DIR = None


def srgb_to_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def make_material(name):
    m = bpy.data.materials.get(name)
    if m:
        return m
    sp = MATERIALS[name]
    m = bpy.data.materials.new(name)
    try:
        m.use_nodes = True
    except Exception:
        pass
    nt = m.node_tree
    bsdf = next(n for n in nt.nodes if n.type == "BSDF_PRINCIPLED")
    if sp.get("family"):
        img = bpy.data.images.load(os.path.join(TEX_DIR, name + ".png"), check_existing=True)
        t = nt.nodes.new("ShaderNodeTexImage")
        t.image = img
        t.location = (-400, 200)
        nt.links.new(t.outputs["Color"], bsdf.inputs["Base Color"])
        m.diffuse_color = tuple(srgb_to_lin(v) for v in sp.get("color", sp.get("glass", (128, 128, 128)))) + (1,)
    else:
        col = tuple(srgb_to_lin(v) for v in sp["color"]) + (1.0,)
        bsdf.inputs["Base Color"].default_value = col
        m.diffuse_color = col
    bsdf.inputs["Roughness"].default_value = sp.get("roughness", 0.8)
    bsdf.inputs["Metallic"].default_value = sp.get("metallic", 0.0)
    if sp.get("emission"):
        bsdf.inputs["Emission Color"].default_value = tuple(sp["emission"]) + (1.0,)
        bsdf.inputs["Emission Strength"].default_value = 2.0
    return m


def reset_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)


def build_entry(entry):
    b = Builder()
    rng = random.Random(entry["seed"])
    entry["fn"](b, rng)
    return b


def setup_render(scene, samples, res):
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = samples
    scene.cycles.use_denoising = True
    scene.cycles.max_bounces = 4
    scene.render.resolution_x, scene.render.resolution_y = res
    scene.render.film_transparent = False
    scene.view_settings.view_transform = "AgX" if "AgX" in [
        i.identifier for i in scene.view_settings.bl_rna.properties["view_transform"].enum_items] else "Filmic"
    world = bpy.data.worlds.new("World")
    scene.world = world
    try:
        world.use_nodes = True
    except Exception:
        pass
    bg = next(n for n in world.node_tree.nodes if n.type == "BACKGROUND")
    bg.inputs["Color"].default_value = (0.55, 0.68, 0.85, 1)
    bg.inputs["Strength"].default_value = 0.9
    sun_data = bpy.data.lights.new("Sun", "SUN")
    sun_data.energy = 3.5
    sun_data.angle = math.radians(3)
    sun = bpy.data.objects.new("Sun", sun_data)
    sun.rotation_euler = (math.radians(50), 0, math.radians(-35))
    scene.collection.objects.link(sun)


def add_ground(size, z=0.0):
    me = bpy.data.meshes.new("ground")
    s = size
    me.from_pydata([(-s, -s, z), (s, -s, z), (s, s, z), (-s, s, z)], [], [[0, 1, 2, 3]])
    mat = bpy.data.materials.new("ground")
    mat.diffuse_color = (0.25, 0.25, 0.25, 1)
    try:
        mat.use_nodes = True
        bsdf = next(n for n in mat.node_tree.nodes if n.type == "BSDF_PRINCIPLED")
        bsdf.inputs["Base Color"].default_value = (0.22, 0.22, 0.23, 1)
        bsdf.inputs["Roughness"].default_value = 0.9
    except Exception:
        pass
    me.materials.append(mat)
    o = bpy.data.objects.new("ground", me)
    bpy.context.scene.collection.objects.link(o)
    return o


def frame_camera(scene, bmin, bmax, az=35, el=22, margin=1.08):
    from mathutils import Vector
    cam_data = bpy.data.cameras.new("Cam")
    cam_data.type = "ORTHO"
    cam = bpy.data.objects.new("Cam", cam_data)
    scene.collection.objects.link(cam)
    scene.camera = cam
    a, e = math.radians(az), math.radians(el)
    fwd = Vector((-math.sin(a) * math.cos(e), math.cos(a) * math.cos(e), -math.sin(e))).normalized()
    right = fwd.cross(Vector((0, 0, 1))).normalized()
    up = right.cross(fwd).normalized()
    corners = [Vector((x, y, z)) for x in (bmin[0], bmax[0]) for y in (bmin[1], bmax[1])
               for z in (bmin[2], bmax[2])]
    pr = [c.dot(right) for c in corners]
    pu = [c.dot(up) for c in corners]
    pf = [c.dot(fwd) for c in corners]
    w, h = max(pr) - min(pr), max(pu) - min(pu)
    rx, ry = scene.render.resolution_x, scene.render.resolution_y
    aspect = rx / ry
    if aspect >= 1:
        cam_data.ortho_scale = max(w, h * aspect) * margin
    else:
        cam_data.ortho_scale = max(h, w / aspect) * margin
    center = right * (max(pr) + min(pr)) / 2 + up * (max(pu) + min(pu)) / 2 + fwd * (min(pf) - 3000)
    cam.location = center
    cam.rotation_euler = fwd.to_track_quat("-Z", "Y").to_euler()
    cam_data.clip_end = 20000
    return cam


def export_glb(obj, path):
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    kw = dict(filepath=path, export_format="GLB", use_selection=True, export_apply=True,
              export_image_format="JPEG", export_yup=True)
    try:
        bpy.ops.export_scene.gltf(**kw, export_jpeg_quality=88)
    except TypeError:
        bpy.ops.export_scene.gltf(**kw)


def main():
    global TEX_DIR
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(HERE, ".."))
    ap.add_argument("--only", default="")
    ap.add_argument("--start", type=int, default=0)
    ap.add_argument("--end", type=int, default=10 ** 6)
    ap.add_argument("--no-render", action="store_true")
    ap.add_argument("--no-export", action="store_true")
    ap.add_argument("--samples", type=int, default=24)
    ap.add_argument("--res", type=int, default=480)
    a = ap.parse_args()
    out = os.path.abspath(a.out)
    TEX_DIR = os.path.join(out, "textures")
    os.makedirs(os.path.join(out, "models"), exist_ok=True)
    os.makedirs(os.path.join(out, "previews"), exist_ok=True)
    os.makedirs(os.path.join(out, "meta"), exist_ok=True)

    entries = catalog.ENTRIES
    if a.only:
        ids = {int(x) for x in a.only.split(",")}
        entries = [e for e in entries if e["id"] in ids]
    else:
        entries = [e for e in entries if a.start <= e["id"] < a.end]

    for e in entries:
        t0 = time.time()
        reset_scene()
        b = build_entry(e)
        stem = f"{e['id']:03d}_{e['key']}"
        obj = b.to_blender(stem, make_material)
        bmin, bmax = b.bbox()
        info = dict(id=e["id"], key=e["key"], name=e["name"], category=e["category"],
                    description=e["desc"], file=f"models/{stem}.glb",
                    size_m=[round(bmax[i] - bmin[i], 1) for i in range(3)],
                    materials=b.mat_names, **b.stats())
        if not a.no_export:
            export_glb(obj, os.path.join(out, "models", stem + ".glb"))
            info["glb_kb"] = round(os.path.getsize(os.path.join(out, "models", stem + ".glb")) / 1024)
        if not a.no_render:
            scene = bpy.context.scene
            setup_render(scene, a.samples, (a.res, a.res))
            add_ground(max(bmax[0] - bmin[0], bmax[1] - bmin[1], bmax[2] - bmin[2]) * 4 + 50)
            frame_camera(scene, bmin, bmax)
            scene.render.image_settings.file_format = "JPEG"
            scene.render.image_settings.quality = 90
            scene.render.filepath = os.path.join(out, "previews", stem + ".jpg")
            bpy.ops.render.render(write_still=True)
        with open(os.path.join(out, "meta", stem + ".json"), "w") as f:
            json.dump(info, f, indent=1)
        print(f"[{e['id']:03d}] {e['name']:<40} {info['tris']:>6} tris  {time.time() - t0:5.1f}s", flush=True)


if __name__ == "__main__":
    main()
