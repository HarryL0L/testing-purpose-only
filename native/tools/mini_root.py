#!/usr/bin/env python3
"""Build a small Android 17 root for testing native mode in Termux.

    mini_root.py IMG_ZIP BUILT_DIR OUT_DIR

IMG_ZIP:   the Cuttlefish image zip UMLDroid uses (super.img inside).
BUILT_DIR: our path-prefixed bionic build (libc.so, linker64, ...).
OUT_DIR:   gets root/ (the image subset) and run.sh.

root/ holds a few programs from /system/bin (toybox and the shell), the
libraries they need (taken from /system/lib64 or from the APEX they live
in), and our libc.so and linker64 in place of the image's. Absolute
symlinks become relative ones, so they resolve inside root/ on the host.

Needs (Ubuntu): unzip, fsck.erofs (erofs-utils), debugfs (e2fsprogs), readelf.
"""
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PROGRAMS = ["toybox", "sh", "toolbox"]
TOYBOX_APPLETS = ["ls", "cat", "uname", "id", "ps", "env", "echo", "readlink", "pwd", "mkdir",
                  "rm", "cp", "mv", "ln", "touch", "head", "tail", "grep", "sed", "wc", "find"]
OURS = {  # image path -> our build's file name
    "/apex/com.android.runtime/bin/linker64": "linker64",
    "/apex/com.android.runtime/lib64/bionic/libc.so": "libc.so",
    "/apex/com.android.runtime/lib64/bionic/libdl.so": "libdl.so",
    "/apex/com.android.runtime/lib64/bionic/libm.so": "libm.so",
}
LIB_DIRS = ["/system/lib64", "/apex/com.android.runtime/lib64/bionic", "/apex/com.android.runtime/lib64"]


def run(*cmd, **kw):
    subprocess.run(cmd, check=True, **kw)


def extract_fs(image, dest):
    """Extract an erofs or ext4 image to dest."""
    os.makedirs(dest, exist_ok=True)
    with open(image, "rb") as f:
        f.seek(1024)
        magic = struct.unpack("<I", f.read(4))[0]
    if magic == 0xE0F5E1E2 and shutil.which("fsck.erofs"):
        run("fsck.erofs", "--extract=" + dest, "--overwrite", "--no-preserve", image)
    elif magic == 0xE0F5E1E2:  # no erofs-utils: mount it (needs root)
        mnt = dest + ".mnt"
        os.makedirs(mnt, exist_ok=True)
        run("mount", "-o", "loop,ro", "-t", "erofs", image, mnt)
        try:
            run("cp", "-a", mnt + "/.", dest)
        finally:
            run("umount", mnt)
    else:
        run("debugfs", "-R", "rdump / " + dest, image, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL)


class Image:
    def __init__(self, zip_path, work):
        self.work = work
        self.apexes = {}
        raw = os.path.join(work, "super.raw")
        sparse = os.path.join(work, "super.img")
        with zipfile.ZipFile(zip_path) as z, open(sparse, "wb") as out:
            shutil.copyfileobj(z.open("super.img"), out, 1 << 22)
        with open(sparse, "rb") as f:
            is_sparse = struct.unpack("<I", f.read(4))[0] == 0xED26FF3A
        if is_sparse:
            run(sys.executable, os.path.join(HERE, "unsparse.py"), sparse, raw)
            os.remove(sparse)
        else:
            os.rename(sparse, raw)
        run(sys.executable, os.path.join(HERE, "lp.py"), raw, "system_a", cwd=work)
        os.remove(raw)
        self.system = os.path.join(work, "system_root")
        extract_fs(os.path.join(work, "system_a.img"), self.system)

    def apex_dir(self, name):
        """Extracted payload of /system/apex/<name>.apex (or .capex)."""
        if name in self.apexes:
            return self.apexes[name]
        dest = os.path.join(self.work, "apex", name)
        src = None
        for ext in (".apex", ".capex"):
            p = os.path.join(self.system, "system", "apex", name + ext)
            if os.path.exists(p):
                src = p
                break
        if src is None:
            sys.exit("no APEX " + name)
        payload = os.path.join(self.work, name + ".img")
        with zipfile.ZipFile(src) as z:
            inner = "apex_payload.img"
            if inner not in z.namelist():  # compressed APEX: original_apex inside
                with z.open("original_apex") as oa, open(payload + ".zip", "wb") as out:
                    shutil.copyfileobj(oa, out)
                z = zipfile.ZipFile(payload + ".zip")
            with z.open(inner) as pi, open(payload, "wb") as out:
                shutil.copyfileobj(pi, out)
        extract_fs(payload, dest)
        self.apexes[name] = dest
        return dest

    def host(self, guest, depth=0):
        """Host path of an image path, following symlinks inside the image."""
        if depth > 16:
            sys.exit("symlink loop at " + guest)
        if guest.startswith("/apex/"):
            name, _, rest = guest[len("/apex/"):].partition("/")
            p = os.path.join(self.apex_dir(name), rest)
        else:
            p = os.path.join(self.system, guest.lstrip("/"))
        if os.path.islink(p):
            t = os.readlink(p)
            if not t.startswith("/"):
                t = os.path.normpath(os.path.join(os.path.dirname(guest), t))
            return self.host(t, depth + 1)
        return p if os.path.exists(p) else None


def needed(path):
    out = subprocess.run(["readelf", "-d", path], capture_output=True, text=True).stdout
    return [l.split("[", 1)[1].split("]", 1)[0] for l in out.splitlines() if "(NEEDED)" in l]


def main():
    img_zip, built, out = sys.argv[1:4]
    root = os.path.join(out, "root")
    os.makedirs(root, exist_ok=True)
    work = tempfile.mkdtemp(prefix="a17-", dir=os.environ.get("RUNNER_TEMP"))
    img = Image(img_zip, work)

    def place(guest, src):
        dst = os.path.join(root, guest.lstrip("/"))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)
        os.chmod(dst, 0o755)
        return dst

    def symlink(guest, target_guest):
        """guest -> target_guest, as a relative link inside root/."""
        dst = os.path.join(root, guest.lstrip("/"))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        rel = os.path.relpath(target_guest, os.path.dirname(guest))
        if os.path.lexists(dst):
            os.remove(dst)
        os.symlink(rel, dst)

    # Our libc and linker in the runtime APEX's place, and the image's paths to them.
    for guest, name in OURS.items():
        src = os.path.join(built, name)
        if os.path.exists(src):
            place(guest, src)
        else:
            place(guest, img.host(guest))
            print("from the image (not built):", guest)
    symlink("/system/bin/linker64", "/apex/com.android.runtime/bin/linker64")
    for lib in ("libc.so", "libdl.so", "libm.so"):
        symlink("/system/lib64/" + lib, "/apex/com.android.runtime/lib64/bionic/" + lib)
    # ld-android.so is provided by the linker itself; the image ships a stub file.
    place("/system/lib64/ld-android.so", img.host("/apex/com.android.runtime/lib64/bionic/ld-android.so")
          or img.host("/system/lib64/ld-android.so"))

    done = {"libc.so", "libdl.so", "libm.so", "ld-android.so"}
    todo = []
    nprops = os.path.join(built, "nprops")
    if os.path.exists(nprops):
        todo.append(place("/system/bin/nprops", nprops))
    for prog in PROGRAMS:
        src = img.host("/system/bin/" + prog)
        if src is None:
            sys.exit("no /system/bin/" + prog)
        todo.append(place("/system/bin/" + prog, src))
    while todo:
        for lib in needed(todo.pop()):
            if lib in done:
                continue
            done.add(lib)
            for d in LIB_DIRS:
                src = img.host(d + "/" + lib)
                if src is not None:
                    todo.append(place("/system/lib64/" + lib, src))
                    break
            else:
                print("library not found:", lib)

    for applet in TOYBOX_APPLETS:
        symlink("/system/bin/" + applet, "/system/bin/toybox")
    for tool in ("getprop", "setprop"):
        symlink("/system/bin/" + tool, "/system/bin/toolbox")
    # Properties: the image's contexts and build.prop for nprops to load.
    for f in ("/system/etc/selinux/plat_property_contexts", "/system/build.prop"):
        src = img.host(f)
        if src:
            place(f, src)
    mkshrc = img.host("/system/etc/mkshrc")
    if mkshrc:
        place("/system/etc/mkshrc", mkshrc)
    # A minimal linker configuration (on a device, linkerconfig generates this at boot).
    os.makedirs(os.path.join(root, "linkerconfig"), exist_ok=True)
    with open(os.path.join(root, "linkerconfig", "ld.config.txt"), "w") as f:
        f.write("dir.system = /system/bin/\n"
                "[system]\n"
                "additional.namespaces =\n"
                "namespace.default.isolated = false\n"
                "namespace.default.search.paths = /system/${LIB}\n")
    for d in ("/dev/socket", "/data/local/tmp", "/tmp"):
        os.makedirs(os.path.join(root, d.lstrip("/")), exist_ok=True)

    with open(os.path.join(out, "run.sh"), "w") as f:
        f.write("""#!/bin/sh
# Run an Android 17 program from root/ directly on this phone's kernel.
#   ./run.sh toybox uname -a      ./run.sh sh
cd "$(dirname "$0")"
export ANDROID_NATIVE_ROOT="$PWD/root"
# Termux preloads its own helper (libtermux-exec); Android 17's linker would look for it in root/
unset LD_PRELOAD LD_LIBRARY_PATH
# Android's own environment (Termux's PATH points at folders the image doesn't have)
export PATH=/system/bin:/system/xbin HOME=/data/local/tmp TMPDIR=/data/local/tmp \
  ANDROID_ROOT=/system ANDROID_DATA=/data
prog="$1"; shift
# Start it through the image's shell: its execve() runs dynamic programs through
# the image's linker and static ones (nprops) directly.
exec "$ANDROID_NATIVE_ROOT/apex/com.android.runtime/bin/linker64" /system/bin/sh -c 'exec "$@"' sh "/system/bin/$prog" "$@"
""")
    os.chmod(os.path.join(out, "run.sh"), 0o755)
    shutil.rmtree(work, ignore_errors=True)
    print("root:", sum(len(f) for _, _, f in os.walk(root)), "files")


if __name__ == "__main__":
    main()
