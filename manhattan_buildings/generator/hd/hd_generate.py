"""Build the HD buildings with Blender: GLB (LOD0 + LOD1 in one file) + beauty render.

usage: python3 hd_generate.py --out <kit_dir>/hd [--only 1,5] [--samples 64] [--res 1000]
       [--no-render] [--no-export] [--tex-size 1024]
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
sys.path.insert(0, os.path.dirname(HERE))

import bpy  # noqa: E402
from mathutils import Vector  # noqa: E402

import hd_catalog  # noqa: E402
from hd_builder import HDBuilder  # noqa: E402
from materials import MATERIALS  # noqa: E402

TEX = None
TEX_SIZE = 1024


def lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def load_img(name, noncolor=False):
    path = os.path.join(TEX, name + ".png")
    img = bpy.data.images.load(path, check_existing=True)
    if noncolor:
        img.colorspace_settings.name = "Non-Color"
    if TEX_SIZE and img.size[0] > TEX_SIZE and not img.get("_scaled"):
        img.scale(TEX_SIZE, TEX_SIZE)
        img["_scaled"] = True
    return img


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
    bsdf.inputs["Metallic"].default_value = sp.get("metallic", 0.0)
    if sp.get("pbr"):
        base = sp["pbr"]
        tc = nt.nodes.new("ShaderNodeTexImage")
        tc.image = load_img(base + "_col")
        tc.location = (-600, 300)
        nt.links.new(tc.outputs["Color"], bsdf.inputs["Base Color"])
        tr = nt.nodes.new("ShaderNodeTexImage")
        tr.image = load_img(base + "_rgh", True)
        tr.location = (-600, 0)
        nt.links.new(tr.outputs["Color"], bsdf.inputs["Roughness"])
        tn = nt.nodes.new("ShaderNodeTexImage")
        tn.image = load_img(base + "_nrm", True)
        tn.location = (-600, -300)
        nm = nt.nodes.new("ShaderNodeNormalMap")
        nm.location = (-300, -300)
        nm.inputs["Strength"].default_value = 1.0
        nt.links.new(tn.outputs["Color"], nm.inputs["Color"])
        nt.links.new(nm.outputs["Normal"], bsdf.inputs["Normal"])
        m.diffuse_color = (0.5, 0.5, 0.5, 1)
    elif sp.get("atlas"):
        tc = nt.nodes.new("ShaderNodeTexImage")
        tc.image = load_img(sp["atlas"] + "_col")
        tc.extension = "EXTEND"
        nt.links.new(tc.outputs["Color"], bsdf.inputs["Base Color"])
        bsdf.inputs["Roughness"].default_value = sp.get("roughness", 0.1)
        if sp.get("emission"):
            nt.links.new(tc.outputs["Color"], bsdf.inputs["Emission Color"])
            bsdf.inputs["Emission Strength"].default_value = sp["emission"]
        try:
            bsdf.inputs["Specular IOR Level"].default_value = 0.6
        except KeyError:
            pass
    else:
        col = tuple(lin(v) for v in sp["color"]) + (1.0,)
        bsdf.inputs["Base Color"].default_value = col
        bsdf.inputs["Roughness"].default_value = sp.get("roughness", 0.5)
        m.diffuse_color = col
        if sp.get("alpha", 1.0) < 1.0:
            bsdf.inputs["Alpha"].default_value = sp["alpha"]
            for attr, val in (("surface_render_method", "BLENDED"), ("blend_method", "BLEND")):
                try:
                    setattr(m, attr, val)
                except Exception:
                    pass
        if sp.get("emission"):
            bsdf.inputs["Emission Color"].default_value = tuple(sp["emission"]) + (1.0,)
            bsdf.inputs["Emission Strength"].default_value = 3.0
    return m


def setup_scene(scene, samples, res):
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = samples
    scene.cycles.use_denoising = True
    scene.cycles.max_bounces = 6
    scene.render.resolution_x, scene.render.resolution_y = res
    vt = [i.identifier for i in scene.view_settings.bl_rna.properties["view_transform"].enum_items]
    scene.view_settings.view_transform = "AgX" if "AgX" in vt else "Filmic"
    try:
        scene.view_settings.look = "AgX - Medium High Contrast"
    except Exception:
        pass
    scene.view_settings.exposure = -0.3
    world = bpy.data.worlds.new("Sky")
    scene.world = world
    try:
        world.use_nodes = True
    except Exception:
        pass
    nt = world.node_tree
    bg = next(n for n in nt.nodes if n.type == "BACKGROUND")
    sky = nt.nodes.new("ShaderNodeTexSky")
    sun_el, sun_az = math.radians(38), math.radians(148)
    try:
        sky.sky_type = "MULTIPLE_SCATTERING"
        sky.sun_elevation = sun_el
        sky.sun_rotation = sun_az
        sky.air_density = 1.0
        sky.aerosol_density = 1.2
        bg.inputs["Strength"].default_value = 0.075
    except Exception:
        sky.sky_type = "HOSEK_WILKIE"
        bg.inputs["Strength"].default_value = 0.6
    nt.links.new(sky.outputs["Color"], bg.inputs["Color"])
    sun_data = bpy.data.lights.new("Sun", "SUN")
    sun_data.energy = 3.6
    sun_data.angle = math.radians(0.6)
    sun_data.color = (1.0, 0.95, 0.88)
    sun = bpy.data.objects.new("Sun", sun_data)
    # point the light from the same direction as the sky's sun
    d = Vector((math.sin(sun_az) * math.cos(sun_el), math.cos(sun_az) * math.cos(sun_el), math.sin(sun_el)))
    sun.rotation_euler = (-d).to_track_quat("-Z", "Y").to_euler()
    scene.collection.objects.link(sun)


def add_street(bmin, bmax):
    """Asphalt + sidewalk around the building for context in renders."""
    from builder import rect_xy
    b = HDBuilder(1)
    x0, y0, x1, y1 = bmin[0] - 6, bmin[1] - 6, bmax[0] + 6, bmax[1] + 6
    b.solid(rect_xy(x0, y0, x1, y1), -0.15, 0.0, "hd_sidewalk", top="hd_sidewalk")
    s = 6000
    b.cap(rect_xy(-s, -s, s, s), -0.15, "hd_asphalt")
    for k in range(-12, 13):  # crosswalk-ish lane marks in front
        x = (x0 + x1) / 2 + k * 1.2
        b.face_auto([(x, y0 - 4.5, -0.14), (x + 0.6, y0 - 4.5, -0.14), (x + 0.6, y0 - 1.5, -0.14),
                     (x, y0 - 1.5, -0.14)], "hd_frame_white")
    return b.to_blender("street", make_material)


def frame_camera(scene, bmin, bmax, az=32, el=10, lens=35):
    cam_data = bpy.data.cameras.new("Cam")
    cam_data.lens = lens
    cam_data.clip_end = 20000
    cam = bpy.data.objects.new("Cam", cam_data)
    scene.collection.objects.link(cam)
    scene.camera = cam
    c = Vector(((bmin[0] + bmax[0]) / 2, (bmin[1] + bmax[1]) / 2, (bmin[2] + bmax[2]) / 2))
    a, e = math.radians(az), math.radians(el)
    fwd = Vector((-math.sin(a) * math.cos(e), math.cos(a) * math.cos(e), -math.sin(e))).normalized()
    corners = [Vector((x, y, z)) for x in (bmin[0], bmax[0]) for y in (bmin[1], bmax[1]) for z in (bmin[2], bmax[2])]
    rx, ry = scene.render.resolution_x, scene.render.resolution_y
    sensor = 36.0
    fov_h = 2 * math.atan(sensor / 2 / lens) if rx >= ry else 2 * math.atan(sensor / 2 / lens * rx / ry)
    fov_v = 2 * math.atan(math.tan(fov_h / 2) * ry / rx)
    right = fwd.cross(Vector((0, 0, 1))).normalized()
    up = right.cross(fwd).normalized()
    dist = 10.0
    for _ in range(40):  # grow distance until every corner fits
        pos = c - fwd * dist
        ok = True
        for p in corners:
            v = p - pos
            z = v.dot(fwd)
            if z <= 0.1 or abs(v.dot(right) / z) > math.tan(fov_h / 2) * 0.94 or \
                    abs(v.dot(up) / z) > math.tan(fov_v / 2) * 0.94:
                ok = False
                break
        if ok:
            break
        dist *= 1.15
    cam.location = c - fwd * dist
    cam.rotation_euler = fwd.to_track_quat("-Z", "Y").to_euler()
    if cam.location.z < 1.7:
        cam.location.z = 1.7
    return cam


def export_glb(objs, path):
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    kw = dict(filepath=path, export_format="GLTF_SEPARATE", export_texture_dir="textures", use_selection=True,
              export_apply=True, export_image_format="JPEG", export_yup=True)
    try:
        bpy.ops.export_scene.gltf(**kw, export_jpeg_quality=82)
    except TypeError:
        bpy.ops.export_scene.gltf(**kw)


def main():
    global TEX, TEX_SIZE
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--only", default="")
    ap.add_argument("--samples", type=int, default=64)
    ap.add_argument("--res", type=int, default=1000)
    ap.add_argument("--tex-size", type=int, default=1024)
    ap.add_argument("--no-render", action="store_true")
    ap.add_argument("--no-export", action="store_true")
    a = ap.parse_args()
    out = os.path.abspath(a.out)
    TEX = os.path.join(out, "textures")
    TEX_SIZE = a.tex_size
    for sub in ("models", "previews", "meta"):
        os.makedirs(os.path.join(out, sub), exist_ok=True)
    entries = hd_catalog.ENTRIES
    if a.only:
        ids = {int(x) for x in a.only.split(",")}
        entries = [e for e in entries if e["id"] in ids]
    for e in entries:
        t0 = time.time()
        bpy.ops.wm.read_factory_settings(use_empty=True)
        stem = f"hd{e['id']:02d}_{e['key']}"
        objs, stats = [], {}
        bb = None
        for lod in (0, 1):
            b = HDBuilder(lod)
            e["fn"](b, random.Random(e["seed"]))
            o = b.to_blender(f"{stem}_LOD{lod}", make_material)
            objs.append(o)
            stats[f"lod{lod}_tris"] = b.stats()["tris"]
            if lod == 0:
                bb = b.bbox()
                mats = b.mat_names
        bmin, bmax = bb
        info = dict(id=e["id"], key=e["key"], name=e["name"], description=e["desc"],
                    file=f"models/{stem}.gltf", size_m=[round(bmax[i] - bmin[i], 1) for i in range(3)],
                    materials=mats, **stats)
        if not a.no_export:
            p = os.path.join(out, "models", stem + ".gltf")
            export_glb(objs, p)
            info["glb_kb"] = round((os.path.getsize(p) + os.path.getsize(p[:-5] + ".bin")) / 1024)
        if not a.no_render:
            scene = bpy.context.scene
            objs[1].hide_render = True
            setup_scene(scene, a.samples, (a.res, int(a.res * 1.25)))
            add_street(bmin, bmax)
            frame_camera(scene, bmin, bmax)
            scene.render.image_settings.file_format = "JPEG"
            scene.render.image_settings.quality = 92
            scene.render.filepath = os.path.join(out, "previews", stem + ".jpg")
            bpy.ops.render.render(write_still=True)
        with open(os.path.join(out, "meta", stem + ".json"), "w") as f:
            json.dump(info, f, indent=1)
        print(f"[HD{e['id']:02d}] {e['name']:<36} LOD0 {stats['lod0_tris']:>7}  LOD1 {stats['lod1_tris']:>6}  "
              f"{info.get('glb_kb', 0):>6} KB  {time.time() - t0:5.1f}s", flush=True)


if __name__ == "__main__":
    main()
