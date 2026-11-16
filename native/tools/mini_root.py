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
PROGRAMS = ["toybox", "sh", "toolbox", "servicemanager", "service", "fsck.erofs"]
TOYBOX_APPLETS = ["ls", "cat", "uname", "id", "ps", "env", "echo", "readlink", "pwd", "mkdir",
                  "rm", "cp", "mv", "ln", "touch", "head", "tail", "grep", "sed", "wc", "find",
                  "sleep", "pkill", "kill", "timeout"]
BUILT_PROGRAMS = ["nlogd", "nprops", "nbinder", "nsocket"]  # static, from our build

# Started by ./run.sh nboot: what init starts first on a device.
NBOOT = """#!/system/bin/sh
# Native mode's first services, as init starts them on a device: logging
# (nlogd), properties (nprops), binder (nbinder) and servicemanager.
#   ./run.sh nboot [NAME=VALUE...]      NAME=VALUE: extra properties
# Logs: /data/local/tmp/log.txt (liblog), /dev/kmsg (KernelLogger),
# /data/local/tmp/nbinder.log, /data/local/tmp/servicemanager.log
cd /data/local/tmp
: > /dev/kmsg
nlogd || exit 1
nprops "$@" || exit 1
nbinder -v 2> /data/local/tmp/nbinder.log || { cat /data/local/tmp/nbinder.log; exit 1; }
servicemanager > /data/local/tmp/servicemanager.log 2>&1 &
i=0
while [ "$(getprop servicemanager.ready)" != true ] && [ $i -lt 50 ]; do sleep 0.1; i=$((i + 1)); done
r=$(getprop servicemanager.ready)
echo "servicemanager.ready=$r"
if [ "$r" != true ]; then
  echo "--- servicemanager.log"; cat /data/local/tmp/servicemanager.log
  echo "--- /dev/kmsg"; tail -n 30 /dev/kmsg
  echo "--- log.txt"; tail -n 40 /data/local/tmp/log.txt
  echo "--- nbinder.log"; tail -n 30 /data/local/tmp/nbinder.log
fi
"""

# Zygote, as init.zygote64.rc starts it (needs the full root from a17root.py).
NZYGOTE = """#!/system/bin/sh
# Start zygote and, through it, system_server (run nboot first). Its output
# goes to the log (/data/local/tmp/log.txt); stdio is /dev/null, as from init
# (zygote refuses to fork with fds it doesn't know).
# First the services init starts before zygote that system_server waits for:
# apexservice (napexd, our stand-in: the APEXes are unpacked already), the
# suspend service and installd.
CLASSPATH=/system/framework/services.jar:/system/framework/napexd.jar \\
    app_process64 /system/bin nmode.ApexService </dev/null >/dev/null 2>&1 &
/system/bin/hw/android.system.suspend-service </dev/null >/dev/null 2>&1 &
installd </dev/null >/dev/null 2>&1 &
# The display (native/gfx): graphics memory (nalloc), the composer HAL
# (ncomposer, frames to /data/vendor/nfb) and SurfaceFlinger.
/vendor/bin/nalloc </dev/null >/dev/null 2>&1 &
ncomposer </dev/null >/dev/null 2>&1 &
surfaceflinger </dev/null >/dev/null 2>&1 &
i=0
while ! service check apexservice | grep -q ': found' && [ $i -lt 100 ]; do sleep 0.2; i=$((i + 1)); done
exec nsocket zygote:stream:0660 usap_pool_primary:stream:0660 -- \\
    /system/bin/app_process64 -Xzygote /system/bin --zygote --start-system-server \\
    --socket-name=zygote </dev/null >/dev/null 2>&1
"""

# selinux_status_t: version 1, sequence 0, enforcing 0, policyload 0, deny_unknown 0
SELINUX_STATUS = struct.pack("<5I", 1, 0, 0, 0, 0).ljust(4096, b"\0")
OURS = {  # image path -> our build's file name
    "/apex/com.android.runtime/bin/linker64": "linker64",
    "/apex/com.android.runtime/lib64/bionic/libc.so": "libc.so",
    "/apex/com.android.runtime/lib64/bionic/libdl.so": "libdl.so",
    "/apex/com.android.runtime/lib64/bionic/libm.so": "libm.so",
}
GFX = {  # image path -> file of native/gfx
    "/vendor/bin/nalloc": "nalloc",
    "/vendor/lib64/hw/mapper.native.so": "mapper.native.so",
    "/system/bin/ncomposer": "ncomposer",
    "/vendor/etc/vintf/manifest/native-gfx.xml": "native-gfx.xml",
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

    def find_lib(self, lib):
        """A 64-bit library: /system/lib64, the runtime APEX, then any APEX."""
        for d in LIB_DIRS:
            src = self.host(d + "/" + lib)
            if src is not None:
                return src
        apex_root = os.path.join(self.system, "system", "apex")
        for f in sorted(os.listdir(apex_root)):
            name, ext = os.path.splitext(f)
            if ext not in (".apex", ".capex"):
                continue
            try:
                p = os.path.join(self.apex_dir(name), "lib64", lib)
            except (zipfile.BadZipFile, KeyError, subprocess.CalledProcessError):
                continue
            if os.path.exists(p) and not os.path.islink(p):
                print("library %s from APEX %s" % (lib, name))
                return p
        return None

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


def interp(path):
    """PT_INTERP of an ELF file, or None."""
    if os.path.islink(path):
        return None
    out = subprocess.run(["readelf", "-lW", path], capture_output=True, text=True).stdout
    for l in out.splitlines():
        if "Requesting program interpreter:" in l:
            return l.split(":", 1)[1].strip().rstrip("]").strip()
    return None


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
    if os.path.exists(os.path.join(built, "napexd.jar")):
        place("/system/framework/napexd.jar", os.path.join(built, "napexd.jar"))
    # The graphics HALs (native/gfx/build.sh) and their VINTF manifest, for
    # a17root.py to put over the image's vendor files.
    for guest, name in GFX.items():
        src = os.path.join(built, "gfx", name) if name != "native-gfx.xml" else \
            os.path.join(HERE, "..", "gfx", name)
        if os.path.exists(src):
            place(guest, src)
            if guest.endswith(".xml"):
                os.chmod(os.path.join(root, guest.lstrip("/")), 0o644)
        else:
            print("not built:", name)
    for prog in BUILT_PROGRAMS:
        src = os.path.join(built, prog)
        if os.path.exists(src):
            todo.append(place("/system/bin/" + prog, src))
        else:
            print("not built:", prog)
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
            src = img.find_lib(lib)
            if src is not None:
                todo.append(place("/system/lib64/" + lib, src))
            else:
                print("library not found:", lib)

    # Programs ask for their linker by path: most for /system/bin/linker64,
    # "bootstrap" ones (servicemanager, ...) for /system/bin/bootstrap/linker64.
    # All of them get ours.
    bindir = os.path.join(root, "system", "bin")
    for f in sorted(os.listdir(bindir)):
        ip = interp(os.path.join(bindir, f))
        if ip and not os.path.lexists(os.path.join(root, ip.lstrip("/"))):
            print("linker path", ip, "for", f)
            symlink(ip, "/apex/com.android.runtime/bin/linker64")
    for lib in ("libc.so", "libdl.so", "libm.so"):
        symlink("/system/lib64/bootstrap/" + lib, "/apex/com.android.runtime/lib64/bionic/" + lib)

    for applet in TOYBOX_APPLETS:
        symlink("/system/bin/" + applet, "/system/bin/toybox")
    for tool in ("getprop", "setprop"):
        symlink("/system/bin/" + tool, "/system/bin/toolbox")
    # Properties: the image's contexts and build.prop for nprops to load;
    # service contexts for servicemanager.
    for f in ("/system/etc/selinux/plat_property_contexts", "/system/build.prop",
              "/system/etc/selinux/plat_service_contexts"):
        src = img.host(f)
        if src:
            place(f, src)
        else:
            print("not in the image:", f)
    # A permissive selinuxfs stand-in (libc maps /sys/fs/selinux here and
    # gives it selinuxfs's statfs type): SELinux checks in the image's
    # programs pass, since an app can't ask the phone's policy about them.
    sel = os.path.join(root, "sys", "fs", "selinux")
    os.makedirs(sel, exist_ok=True)
    for name, data in (("status", SELINUX_STATUS), ("enforce", b"0"), ("deny_unknown", b"0"),
                       ("reject_unknown", b"0"), ("mls", b"1"), ("policyvers", b"33"),
                       ("checkreqprot", b"0"), ("context", b"")):
        with open(os.path.join(sel, name), "wb") as f:
            f.write(data)
    os.chmod(os.path.join(sel, "context"), 0o666)
    for name, text in (("nboot", NBOOT), ("nzygote", NZYGOTE)):
        with open(os.path.join(root, "system", "bin", name), "w") as f:
            f.write(text)
        os.chmod(os.path.join(root, "system", "bin", name), 0o755)
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
#   ./run.sh toybox uname -a      ./run.sh sh      ./run.sh nboot
cd "$(dirname "$0")"
export ANDROID_NATIVE_ROOT="$PWD/root"
# Termux preloads its own helper (libtermux-exec); Android 17's linker would look for it in root/
unset LD_PRELOAD LD_LIBRARY_PATH
# Android's own environment (Termux's PATH points at folders the image doesn't have)
export PATH=/system/bin:/system/xbin HOME=/data/local/tmp TMPDIR=/data/local/tmp \
  ANDROID_ROOT=/system ANDROID_DATA=/data
# Android's boot environment in a full root (BOOTCLASSPATH, ...; a17root.py writes it)
[ -f "$ANDROID_NATIVE_ROOT/system/etc/native-env.sh" ] && . "$ANDROID_NATIVE_ROOT/system/etc/native-env.sh"
prog="$1"; shift
case "$prog" in /*) ;; *) prog="/system/bin/$prog" ;; esac
# Start it through the image's shell: its execve() runs dynamic programs through
# the image's linker and static ones (nprops) directly.
exec "$ANDROID_NATIVE_ROOT/apex/com.android.runtime/bin/linker64" /system/bin/sh -c 'exec "$@"' sh "$prog" "$@"
""")
    os.chmod(os.path.join(out, "run.sh"), 0o755)
    # The full-root builder, to run on the phone (python3 a17root.py ...).
    for tool in ("a17root.py", "ext4x.py", "nfb2png.py"):
        shutil.copy2(os.path.join(HERE, tool), os.path.join(out, tool))
    shutil.rmtree(work, ignore_errors=True)
    print("root:", sum(len(f) for _, _, f in os.walk(root)), "files")


if __name__ == "__main__":
    main()
