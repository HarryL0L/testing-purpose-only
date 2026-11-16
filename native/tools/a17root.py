#!/usr/bin/env python3
"""Build the full Android 17 root for native mode, on the phone (Termux) or a PC.

    python3 a17root.py IMG_ZIP MINI_DIR OUT_DIR

IMG_ZIP:  the Cuttlefish image zip (super.img inside), as UMLDroid uses.
MINI_DIR: an unpacked a17-mini (our libc, linker, nlogd/nprops/nbinder, and
          the image's fsck.erofs, which unpacks the erofs images natively).
OUT_DIR:  gets root/ (the system and vendor partitions, every APEX unpacked
          under /apex, our runtime on top) and run.sh. Needs about 3 GB, plus
          about 1 GB while it works.

Nothing but Python's standard library is needed (Termux: pkg install python).
The system partition is streamed out of super.img inside the zip; ext4 APEX
payloads are read by ext4x.py; erofs images are unpacked by the image's own
fsck.erofs, run from MINI_DIR. Then, as init and apexd would at boot:
apex-info-list.xml, the linker configuration (linkerconfig) and the class
paths (derive_classpath), all run from the new root itself.
"""
import os
import shutil
import struct
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import erofsmeta  # noqa: E402
import ext4x  # noqa: E402

EROFS_MAGIC = 0xE0F5E1E2


def log(*a):
    print("a17root:", *a, flush=True)


# ------------------------------------------------- super.img -> one partition

class Stream:
    """Logical bytes of a (sparse or raw) image read from a file object."""

    def __init__(self, f):
        self.f = f

    def chunks(self):
        """Yields (logical offset, bytes or None for zeros, length)."""
        head = self.f.read(28)
        magic = struct.unpack_from("<I", head, 0)[0]
        if magic != 0xED26FF3A:  # raw image
            off = 0
            data = head
            while data:
                yield off, data, len(data)
                off += len(data)
                data = self.f.read(1 << 22)
            return
        _m, _maj, _mnr, fhs, chs, bs, _tb, tc, _ = struct.unpack("<IHHHHIIII", head)
        self.f.read(fhs - 28)
        off = 0
        for _ in range(tc):
            ct, _r, sz, _total = struct.unpack("<HHII", self.f.read(chs)[:12])
            n = sz * bs
            if ct == 0xCAC1:  # raw
                left = n
                while left:
                    b = self.f.read(min(left, 1 << 22))
                    if not b:
                        raise EOFError("truncated sparse image")
                    yield off, b, len(b)
                    off += len(b)
                    left -= len(b)
            elif ct == 0xCAC2:  # fill
                fill = self.f.read(4)
                if fill == b"\0\0\0\0":
                    yield off, None, n
                else:
                    yield off, fill * (n // 4), n
                off += n
            elif ct == 0xCAC3:  # don't care
                yield off, None, n
                off += n
            elif ct == 0xCAC4:  # crc
                self.f.read(4)


def lp_extents(meta, name):
    """Linear extents [(offset, length) in bytes] of partition `name`, from the
    first 1 MiB of super (LP geometry at 4096, primary metadata at 12288)."""
    if struct.unpack_from("<I", meta, 4096)[0] != 0x616C4467:
        raise ValueError("no LP geometry in super.img")
    h = meta[12288:12288 + 256]
    if struct.unpack_from("<I", h, 0)[0] != 0x414C5030:
        raise ValueError("no LP metadata header")
    hsize = struct.unpack_from("<I", h, 8)[0]
    tsize = struct.unpack_from("<I", h, 44)[0]
    tables = meta[12288 + hsize:12288 + hsize + tsize]

    def desc(o):
        return struct.unpack_from("<III", h, o)

    P, E = desc(80), desc(92)
    ext = []
    for i in range(E[1]):
        e = tables[E[0] + i * E[2]:E[0] + (i + 1) * E[2]]
        ext.append(struct.unpack_from("<QIQ", e, 0))
    for i in range(P[1]):
        e = tables[P[0] + i * P[2]:P[0] + (i + 1) * P[2]]
        pname = e[:36].split(b"\0")[0].decode()
        _attrs, first, num, _grp = struct.unpack_from("<IIII", e, 36)
        if pname == name:
            out = []
            for ns, tt, data in ext[first:first + num]:
                out.append((data * 512 if tt == 0 else None, ns * 512))
            return out
    raise KeyError("no partition %s in super.img" % name)


def extract_partitions(img_zip, wanted):
    """Stream partitions {name: out_path} out of super.img in the zip, in one
    pass and without a copy of super. Partitions super lacks are skipped;
    returns the names extracted."""
    with zipfile.ZipFile(img_zip) as z, z.open("super.img") as f:
        meta = bytearray()
        layouts = None  # name -> [(super offset, length, partition offset)]
        outs = {name: open(path, "wb") for name, path in wanted.items()}
        totals = {}
        try:
            for off, data, n in Stream(f).chunks():
                if layouts is None:
                    if off < (1 << 20):
                        part = (data if data is not None else b"\0" * min(n, (1 << 20) - off))
                        meta[off:off + len(part)] = part[:(1 << 20) - off]
                    if off + n >= (1 << 20):
                        layouts = {}
                        for name in wanted:
                            try:
                                extents = lp_extents(bytes(meta), name)
                            except KeyError:
                                continue
                            pos, layout = 0, []
                            for soff, length in extents:
                                layout.append((soff, length, pos))
                                pos += length
                            layouts[name] = layout
                            totals[name] = pos
                if layouts is None or data is None:
                    continue
                for name, layout in layouts.items():
                    for soff, length, ppos in layout:
                        if soff is None:
                            continue
                        lo, hi = max(off, soff), min(off + n, soff + length)
                        if lo < hi:
                            outs[name].seek(ppos + lo - soff)
                            outs[name].write(data[lo - off:hi - off])
        finally:
            for name, out in outs.items():
                if name in totals:
                    out.truncate(totals[name])
                out.close()
    for name in wanted:
        if name in totals:
            log("%s: %d MB" % (name, totals[name] >> 20))
        else:
            os.remove(wanted[name])
    return set(totals)


# ---------------------------------------------------------------- unpacking

def magic_of(path):
    with open(path, "rb") as f:
        f.seek(1024)
        return struct.unpack("<I", f.read(4))[0]


class Unpacker:
    def __init__(self, mini):
        self.mini = os.path.abspath(mini)
        self.stage = os.path.join(self.mini, "root", "data", "local", "tmp", "a17stage")
        self.guest_stage = "/data/local/tmp/a17stage"
        shutil.rmtree(self.stage, ignore_errors=True)
        os.makedirs(self.stage)

    def image_path(self, name):
        return os.path.join(self.stage, name)

    def unpack(self, image, dest):
        """Unpack an erofs or ext4 image file (inside the stage) to dest."""
        if magic_of(image) == EROFS_MAGIC:
            guest_img = self.guest_stage + "/" + os.path.basename(image)
            out = os.path.join(self.stage, "out")
            shutil.rmtree(out, ignore_errors=True)
            subprocess.run([os.path.join(self.mini, "run.sh"), "fsck.erofs",
                            "--extract=" + self.guest_stage + "/out", "--overwrite",
                            # exact permissions (setuid, ...); owners and labels
                            # are recorded below (an app can't set them)
                            "--preserve-perms", guest_img], check=True)
            if os.path.exists(dest):
                shutil.rmtree(dest)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            os.rename(out, dest)
            # The image's owners and SELinux labels, kept as libc reports them
            erofsmeta.record(image, dest)
            # The image's own modes now: let the app (each file's real owner)
            # still change every directory, as root could.
            for d, _dirs, _files in os.walk(dest):
                m = os.lstat(d).st_mode
                if m & 0o700 != 0o700:
                    os.chmod(d, m | 0o700)
        else:
            fs = ext4x.Ext4(image)
            fs.keep_meta = True  # owners and labels, as for erofs
            fs.extract(dest)
            fs.record(fs.inode(2), dest)

    def done(self):
        shutil.rmtree(self.stage, ignore_errors=True)


def apex_payload(apex_file, dest):
    with zipfile.ZipFile(apex_file) as z:
        if "apex_payload.img" not in z.namelist():  # compressed APEX
            inner = dest + ".zip"
            with z.open("original_apex") as a, open(inner, "wb") as o:
                shutil.copyfileobj(a, o, 1 << 22)
            with zipfile.ZipFile(inner) as z2, z2.open("apex_payload.img") as p, \
                    open(dest, "wb") as o:
                shutil.copyfileobj(p, o, 1 << 22)
            os.remove(inner)
        else:
            with z.open("apex_payload.img") as p, open(dest, "wb") as o:
                shutil.copyfileobj(p, o, 1 << 22)


# ------------------------------------------------------------------ fix-ups

def relink(root, unpacker_root):
    """Absolute symlinks -> relative ones, so they resolve inside root.

    fsck.erofs ran in native mode, where libc puts the unpacker's root in
    front of absolute symlink targets: take it off again first."""
    n = 0
    prefix = unpacker_root.rstrip("/") + "/"
    for d, dirs, files in os.walk(root):
        for x in dirs + files:
            p = os.path.join(d, x)
            if os.path.islink(p):
                t = os.readlink(p)
                if t.startswith(prefix):
                    t = t[len(prefix) - 1:]
                if t.startswith("/"):
                    guest = "/" + os.path.relpath(d, root)
                    os.remove(p)
                    os.symlink(os.path.relpath(t, "/" if guest == "/." else guest), p)
                    n += 1
    return n


def apex_manifest(path):
    b = open(path, "rb").read()
    i, out = 0, {}

    def varint():
        nonlocal i
        v = s = 0
        while True:
            c = b[i]
            i += 1
            v |= (c & 0x7F) << s
            s += 7
            if c < 0x80:
                return v

    while i < len(b):
        key = varint()
        f, t = key >> 3, key & 7
        if t == 0:
            v = varint()
        elif t == 2:
            ln = varint()
            v = b[i:i + ln]
            i += ln
        elif t == 5:
            v = b[i:i + 4]
            i += 4
        elif t == 1:
            v = b[i:i + 8]
            i += 8
        else:
            break
        out.setdefault(f, v)
    return out


def apex_preference(name):
    """Which of several APEXes of one module to activate (on a device the
    bootloader names it): ones that need no special hardware first."""
    return (0 if "nonsecure" in name else 1, "v4l2" in name or "remote" in name, len(name), name)


# Vendor APEXes to activate. system_server waits for every HAL the active
# manifests declare, so only those whose HALs native mode runs (nzygote starts
# them), and ones without HALs that the system needs. The rest (radio, wifi,
# camera, ...) wait for their bridges to the host app; the composer and
# gralloc ones are replaced by native/gfx.
ACTIVE_VENDOR_APEXES = {
    "com.google.cf.health", "com.android.hardware.keymint", "com.android.hardware.gatekeeper",
    "com.android.hardware.authsecret", "com.android.hardware.power",
    "com.android.hardware.vibrator", "com.google.cf.light", "com.android.hardware.thermal",
    "com.android.hardware.memtrack", "com.google.cf.vulkan", "com.google.cf.input.config",
    # the example audio HAL (software streams; audio to the host app comes later)
    "com.android.hardware.audio",
}
# Vendor manifest fragments of HALs native mode doesn't run yet.
INACTIVE_VENDOR_MANIFESTS = ["android.hardware.sensors-multihal.xml", "face-default.xml",
                             "fingerprint-default.xml", "motioncontext-service-default.xml",
                             "mapper.minigbm.xml"]


def write_apex_info(root):
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<apex-info-list>"]
    apex = os.path.join(root, "apex")
    # Inactive APEXes live outside /apex: the system reads every folder there
    # (features in etc/permissions, ...), as on a device it holds only active ones.
    inactive = os.path.join(root, "apex-inactive")
    if os.path.isdir(inactive):
        for name in os.listdir(inactive):
            if not os.path.lexists(os.path.join(apex, name)):
                os.rename(os.path.join(inactive, name), os.path.join(apex, name))
    for name in os.listdir(apex):
        if os.path.islink(os.path.join(apex, name)):
            os.remove(os.path.join(apex, name))
    modules = {}  # module name -> [(dir name, version, path, partition)]
    for name in sorted(os.listdir(apex)):
        mp = os.path.join(apex, name, "apex_manifest.pb")
        if os.path.islink(os.path.join(apex, name)) or not os.path.exists(mp):
            continue
        m = apex_manifest(mp)
        mod = m.get(1, name.encode()).decode()
        ver = m.get(2, 0) if isinstance(m.get(2, 0), int) else 0
        src, part = "/system/apex/%s.capex" % name, "SYSTEM"
        for d, pt in (("system", "SYSTEM"), ("vendor", "VENDOR"), ("odm", "ODM")):
            for ext in (".apex", ".capex"):
                cand = "/%s/apex/%s%s" % (d, name, ext)
                if os.path.exists(os.path.join(root, cand.lstrip("/"))):
                    src, part = cand, pt
        modules.setdefault(mod, []).append((name, ver, src, part))
    for mod in sorted(modules):
        name, ver, src, part = min(modules[mod], key=lambda e: apex_preference(e[0]))
        if part in ("VENDOR", "ODM") and mod not in ACTIVE_VENDOR_APEXES:
            name = None
        for other in modules[mod]:
            if other[0] != name:
                os.makedirs(inactive, exist_ok=True)
                os.rename(os.path.join(apex, other[0]), os.path.join(inactive, other[0]))
        if name is None:
            continue
        # apexd mounts the active one at /apex/<module name>
        if name != mod:
            link = os.path.join(apex, mod)
            if os.path.lexists(link):
                os.remove(link)
            os.symlink(name, link)
        lines.append('    <apex-info moduleName="%s" modulePath="%s" preinstalledModulePath="%s" '
                     'versionCode="%d" versionName="" isFactory="true" isActive="true" '
                     'lastUpdateMillis="0" provideSharedApexLibs="false" partition="%s"/>'
                     % (mod, src, src, ver, part))
    lines.append("</apex-info-list>")
    with open(os.path.join(apex, "apex-info-list.xml"), "w") as f:
        f.write("\n".join(lines) + "\n")
    return len(lines) - 3


RUNTIME = [  # our build, from MINI_DIR, over the image's
    "apex/com.android.runtime/bin/linker64",
    "apex/com.android.runtime/lib64/bionic/libc.so",
    "apex/com.android.runtime/lib64/bionic/libdl.so",
    "apex/com.android.runtime/lib64/bionic/libm.so",
    "system/bin/nlogd",
    "system/bin/nprops",
    "system/bin/nbinder",
    "system/bin/nboot",
    "system/bin/nsocket",
    "system/bin/nzygote",
    "system/framework/napexd.jar",
    # native/gfx: the graphics HALs
    "vendor/bin/nalloc",
    "vendor/lib64/hw/mapper.native.so",
    "system/bin/ncomposer",
    "vendor/etc/vintf/manifest/native-gfx.xml",
    "vendor/etc/permissions/native-unavailable-features.xml",
]


def overlay(mini_root, root):
    for rel in RUNTIME:
        src = os.path.join(mini_root, rel)
        dst = os.path.join(root, rel)
        if not os.path.exists(src):
            log("missing in a17-mini:", rel)
            continue
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if os.path.lexists(dst):
            os.remove(dst)
        shutil.copy2(src, dst)
        os.chmod(dst, 0o644 if rel.endswith((".jar", ".xml")) else 0o755)
    # HALs native mode doesn't run (our mapper replaces minigbm's): kept, but
    # out of the manifest directory.
    vintf = os.path.join(root, "vendor", "etc", "vintf")
    for f in INACTIVE_VENDOR_MANIFESTS:
        src = os.path.join(vintf, "manifest", f)
        if os.path.exists(src):
            os.makedirs(os.path.join(vintf, "inactive"), exist_ok=True)
            os.replace(src, os.path.join(vintf, "inactive", f))
    # bootstrap programs (servicemanager, ...) use the same linker and libc
    for rel, target in (("system/bin/bootstrap/linker64", "../../../apex/com.android.runtime/bin/linker64"),
                        ("system/lib64/bootstrap/libc.so", "../../../apex/com.android.runtime/lib64/bionic/libc.so"),
                        ("system/lib64/bootstrap/libdl.so", "../../../apex/com.android.runtime/lib64/bionic/libdl.so"),
                        ("system/lib64/bootstrap/libm.so", "../../../apex/com.android.runtime/lib64/bionic/libm.so")):
        p = os.path.join(root, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        if os.path.lexists(p):
            os.remove(p)
        os.symlink(target, p)
    # /sys/power is the root's (libc maps it): files the suspend service uses
    power = os.path.join(root, "sys", "power")
    os.makedirs(power, exist_ok=True)
    for name, data in (("state", "freeze mem\n"), ("wakeup_count", "0\n"), ("wake_lock", ""),
                       ("wake_unlock", ""), ("autosleep", "off\n")):
        with open(os.path.join(power, name), "w") as f:
            f.write(data)
    sel = os.path.join(root, "sys", "fs", "selinux")
    shutil.rmtree(sel, ignore_errors=True)
    shutil.copytree(os.path.join(mini_root, "sys", "fs", "selinux"), sel)
    for d in ("dev/socket", "data/local/tmp", "data/system/environ", "data/dalvik-cache/arm64",
              "data/misc/apexdata/com.android.art", "data/misc/odrefresh", "tmp", "linkerconfig",
              # cgroups (cgroups.json), as plain folders: libc maps them here
              "sys/fs/cgroup/system", "sys/fs/cgroup/apps", "dev/blkio", "dev/cpuctl",
              "dev/cpuset", "dev/stune", "dev/memcg"):
        os.makedirs(os.path.join(root, d), exist_ok=True)
    # bpffs as the kernel mounts it (libc keeps BPF objects there)
    bpf = os.path.join(root, "sys", "fs", "bpf")
    os.makedirs(bpf, exist_ok=True)
    os.chmod(bpf, 0o1777)


# Start nprops for one command, and wait until its properties are there.
WITH_PROPS = ("nprops -f >/dev/null 2>&1 & p=$!; i=0; "
              "while [ \"$(getprop native.nprops.ready 2>/dev/null)\" != 1 ] && [ $i -lt 300 ]; do sleep 0.1; i=$((i + 1)); done; ")


USER0_DIRS = ["data/data", "data/user_de/0", "data/misc_de/0", "data/misc_ce/0",
              "data/system_de/0", "data/system_ce/0", "data/vendor_de/0", "data/vendor_ce/0",
              "data/media/0", "data/misc/user/0", "data/misc/profiles/cur/0",
              "data/misc/profiles/ref", "data/app", "data/user_de", "data/user"]


def init_dirs(root):
    """Create the directories init's .rc files make at boot (mkdir lines)."""
    n = 0
    for base in ("system/etc/init", "system_ext/etc/init", "vendor/etc/init"):
        top = os.path.join(root, base)
        for d, _dirs, files in os.walk(top):
            for f in files:
                if not f.endswith(".rc"):
                    continue
                try:
                    lines = open(os.path.join(d, f), errors="replace").read().splitlines()
                except OSError:
                    continue
                for line in lines:
                    parts = line.split()
                    if len(parts) >= 2 and parts[0] == "mkdir" and parts[1].startswith("/") \
                            and "$" not in parts[1]:
                        target = os.path.join(root, parts[1].lstrip("/"))
                        if not os.path.lexists(target):
                            try:
                                os.makedirs(target, exist_ok=True)
                                n += 1
                            except OSError:
                                pass
    # User 0's storage: init links /data/user/0 to /data/data; vold (which
    # can't run in an app: it mounts) makes the rest when the user starts.
    link = os.path.join(root, "data", "user", "0")
    if not os.path.lexists(link):
        os.makedirs(os.path.dirname(link), exist_ok=True)
        os.symlink("../data", link)
    for d in USER0_DIRS:
        target = os.path.join(root, d)
        if not os.path.lexists(target):
            os.makedirs(target, exist_ok=True)
            n += 1
    return n


def native(out, script):
    """Run a shell command in the new root (through its own run.sh)."""
    subprocess.run([os.path.join(out, "run.sh"), "sh", "-c", script], check=True)


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    img_zip, mini, out = (os.path.abspath(a) for a in sys.argv[1:4])
    root = os.path.join(out, "root")
    if os.path.exists(root):
        sys.exit("%s exists: remove it first" % root)
    os.makedirs(out, exist_ok=True)
    u = Unpacker(mini)

    log("system, vendor and odm partitions from", os.path.basename(img_zip))
    imgs = {p: u.image_path(p + ".img") for p in ("system_a", "vendor_a", "odm_a")}
    got = extract_partitions(img_zip, imgs)
    u.unpack(imgs["system_a"], root)
    os.remove(imgs["system_a"])
    # vendor and odm go where the system image only has mount points (its
    # /odm holds links into /vendor/odm, for devices without odm)
    for p, dest in (("vendor_a", "vendor"), ("odm_a", "odm")):
        if p in got:
            target = os.path.join(root, dest)
            if os.path.isdir(target) and not os.path.islink(target):
                shutil.rmtree(target)
            u.unpack(imgs[p], target)
            os.remove(imgs[p])

    names = []
    for part in ("system", "vendor", "odm"):
        apex_dir = os.path.join(root, part, "apex")
        if os.path.isdir(apex_dir):
            names += sorted(os.path.join(apex_dir, f) for f in os.listdir(apex_dir)
                            if f.endswith((".apex", ".capex")))
    for i, path in enumerate(names, 1):
        name = os.path.splitext(os.path.basename(path))[0]
        payload = u.image_path("payload.img")
        apex_payload(path, payload)
        u.unpack(payload, os.path.join(root, "apex", name))
        os.remove(payload)
        log("APEX %d/%d %s" % (i, len(names), name))
    u.done()

    log("relinked %d absolute symlinks" % relink(root, os.path.join(mini, "root")))
    overlay(os.path.join(mini, "root"), root)
    shutil.copy2(os.path.join(mini, "run.sh"), os.path.join(out, "run.sh"))
    log("apex-info-list.xml: %d APEXes" % write_apex_info(root))
    log("%d directories init would create" % init_dirs(root))

    native(out, "/apex/com.android.runtime/bin/linkerconfig --target /linkerconfig")
    log("linker configuration written")
    # derive_classpath reads properties: give it a property service for a moment.
    native(out, WITH_PROPS +
                "/apex/com.android.sdkext/bin/derive_classpath /data/system/environ/classpath; "
                "r=$?; kill $p; exit $r")
    env = ["# Android's boot environment (init.environ.rc and derive_classpath)"]
    with open(os.path.join(root, "data", "system", "environ", "classpath")) as f:
        for line in f:
            p = line.split()
            if len(p) >= 3 and p[0] == "export":
                env.append("export %s=%s" % (p[1], p[2]))
    # init.environ.rc's exports (installd crashes without ASEC_MOUNTPOINT)
    try:
        for line in open(os.path.join(root, "init.environ.rc")):
            p = line.split()
            if len(p) >= 3 and p[0] == "export":
                env.append("export %s=%s" % (p[1], p[2]))
    except OSError:
        env.append("export ANDROID_ART_ROOT=/apex/com.android.art ANDROID_I18N_ROOT=/apex/com.android.i18n "
                   "ANDROID_TZDATA_ROOT=/apex/com.android.tzdata ANDROID_STORAGE=/storage "
                   "ANDROID_ASSETS=/system/app EXTERNAL_STORAGE=/sdcard ASEC_MOUNTPOINT=/mnt/asec")
    with open(os.path.join(root, "system", "etc", "native-env.sh"), "w") as f:
        f.write("\n".join(env) + "\n")
    log("class paths derived")
    # The boot image in /system is built for ART's userfaultfd collector, which
    # needs kernel features (5.13+ mremap) this phone may lack; ART then runs
    # the concurrent-copying collector and needs an image built for it.
    # odrefresh compiles one into /data/misc/apexdata (a few minutes, once).
    log("compiling the boot image and system_server (odrefresh; takes a while)")
    native(out, WITH_PROPS +
                "/apex/com.android.art/bin/odrefresh --force-compile; "
                "echo \"odrefresh: exit status $?\"; kill $p; exit 0")
    log("done:", out)


if __name__ == "__main__":
    main()
