#!/usr/bin/env python3
"""The libraries native/gfx links against, from an Android 17 image zip:

    image_libs.py IMG_ZIP OUT      ->  OUT/system/lib64, OUT/vendor/lib64
"""
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

TOOLS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "tools")
sys.path.insert(0, TOOLS)
import mini_root  # noqa: E402


def main():
    img_zip, out = (os.path.abspath(a) for a in sys.argv[1:3])
    work = tempfile.mkdtemp(prefix="gfx-", dir=os.environ.get("RUNNER_TEMP"))
    raw = os.path.join(work, "super.raw")
    sparse = os.path.join(work, "super.img")
    with zipfile.ZipFile(img_zip) as z, open(sparse, "wb") as o:
        shutil.copyfileobj(z.open("super.img"), o, 1 << 22)
    with open(sparse, "rb") as f:
        is_sparse = struct.unpack("<I", f.read(4))[0] == 0xED26FF3A
    if is_sparse:
        subprocess.run([sys.executable, os.path.join(TOOLS, "unsparse.py"), sparse, raw], check=True)
        os.remove(sparse)
    else:
        os.rename(sparse, raw)
    for part, sub in (("system_a", "system/lib64"), ("vendor_a", "lib64")):
        subprocess.run([sys.executable, os.path.join(TOOLS, "lp.py"), raw, part], cwd=work,
                       check=True)
        tree = os.path.join(work, part)
        mini_root.extract_fs(os.path.join(work, part + ".img"), tree)
        os.remove(os.path.join(work, part + ".img"))
        dest = os.path.join(out, "system" if part == "system_a" else "vendor", "lib64")
        shutil.rmtree(dest, ignore_errors=True)
        shutil.copytree(os.path.join(tree, sub), dest, symlinks=True)
        shutil.rmtree(tree)
        print(part, len(os.listdir(dest)), "libraries")
    shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
