#!/usr/bin/env python3
"""Build the full Android 17 root for native mode, on the phone (Termux) or a PC.

    python3 a17root.py IMG_ZIP MINI_DIR OUT_DIR

IMG_ZIP:  the Cuttlefish image zip (super.img inside), as UMLDroid uses.
MINI_DIR: an unpacked a17-mini (our libc, linker, nlogd/nprops/nbinder, and
          the image's fsck.erofs, which unpacks the erofs images natively).
OUT_DIR:  gets root/ (the whole system image, every APEX unpacked under
          /apex, our runtime on top) and run.sh. Needs about 2.5 GB, plus
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


def extract_partition(img_zip, name, out_path):
    """Stream partition `name` out of super.img in the zip, without a copy of super."""
    with zipfile.ZipFile(img_zip) as z, z.open("super.img") as f:
        meta = bytearray()
        layout = None  # [(super offset, length, partition offset)]
        with open(out_path, "wb") as out:
            total = 0
            for off, data, n in Stream(f).chunks():
                if layout is None:
                    if off < (1 << 20):
                        part = (data if data is not None else b"\0" * min(n, (1 << 20) - off))
                        meta[off:off + len(part)] = part[:(1 << 20) - off]
                    if off + n >= (1 << 20):
                        pos, layout = 0, []
                        for soff, length in lp_extents(bytes(meta), name):
                            layout.append((soff, length, pos))
                            pos += length
                        total = pos
                if layout is None or data is None:
                    continue
                for soff, length, ppos in layout:
                    if soff is None:
                        continue
                    lo, hi = max(off, soff), min(off + n, soff + length)
                    if lo < hi:
                        out.seek(ppos + lo - soff)
                        out.write(data[lo - off:hi - off])
            out.truncate(total)
    log("%s: %d MB" % (name, total >> 20))


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
                            "--no-preserve", guest_img], check=True)
            if os.path.exists(dest):
                shutil.rmtree(dest)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            os.rename(out, dest)
        else:
            ext4x.Ext4(image).extract(dest)

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


def write_apex_info(root):
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<apex-info-list>"]
    apex = os.path.join(root, "apex")
    for name in sorted(os.listdir(apex)):
        mp = os.path.join(apex, name, "apex_manifest.pb")
        if not os.path.exists(mp):
            continue
        m = apex_manifest(mp)
        mod = m.get(1, name.encode()).decode()
        ver = m.get(2, 0) if isinstance(m.get(2, 0), int) else 0
        src = "/system/apex/%s.apex" % name
        if not os.path.exists(os.path.join(root, src.lstrip("/"))):
            src = "/system/apex/%s.capex" % name
        lines.append('    <apex-info moduleName="%s" modulePath="%s" preinstalledModulePath="%s" '
                     'versionCode="%d" versionName="" isFactory="true" isActive="true" '
                     'lastUpdateMillis="0" provideSharedApexLibs="false" partition="SYSTEM"/>'
                     % (mod, src, src, ver))
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
        os.chmod(dst, 0o755)
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
    sel = os.path.join(root, "sys", "fs", "selinux")
    shutil.rmtree(sel, ignore_errors=True)
    shutil.copytree(os.path.join(mini_root, "sys", "fs", "selinux"), sel)
    for d in ("dev/socket", "data/local/tmp", "data/system/environ", "data/dalvik-cache/arm64",
              "tmp", "linkerconfig"):
        os.makedirs(os.path.join(root, d), exist_ok=True)


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

    log("system partition from", os.path.basename(img_zip))
    sysimg = u.image_path("system_a.img")
    extract_partition(img_zip, "system_a", sysimg)
    u.unpack(sysimg, root)
    os.remove(sysimg)

    apex_dir = os.path.join(root, "system", "apex")
    names = sorted(f for f in os.listdir(apex_dir) if f.endswith((".apex", ".capex")))
    for i, f in enumerate(names, 1):
        name = os.path.splitext(f)[0]
        payload = u.image_path("payload.img")
        apex_payload(os.path.join(apex_dir, f), payload)
        u.unpack(payload, os.path.join(root, "apex", name))
        os.remove(payload)
        log("APEX %d/%d %s" % (i, len(names), name))
    u.done()

    log("relinked %d absolute symlinks" % relink(root, os.path.join(mini, "root")))
    overlay(os.path.join(mini, "root"), root)
    shutil.copy2(os.path.join(mini, "run.sh"), os.path.join(out, "run.sh"))
    log("apex-info-list.xml: %d APEXes" % write_apex_info(root))

    native(out, "/apex/com.android.runtime/bin/linkerconfig --target /linkerconfig")
    log("linker configuration written")
    # derive_classpath reads properties: give it a property service for a moment.
    native(out, "nprops -f >/dev/null 2>&1 & p=$!; sleep 2; "
                "/apex/com.android.sdkext/bin/derive_classpath /data/system/environ/classpath; "
                "r=$?; kill $p; exit $r")
    env = ["# Android's boot environment (init.environ.rc and derive_classpath)"]
    with open(os.path.join(root, "data", "system", "environ", "classpath")) as f:
        for line in f:
            p = line.split()
            if len(p) >= 3 and p[0] == "export":
                env.append("export %s=%s" % (p[1], p[2]))
    env.append("export ANDROID_ART_ROOT=/apex/com.android.art ANDROID_I18N_ROOT=/apex/com.android.i18n "
               "ANDROID_TZDATA_ROOT=/apex/com.android.tzdata ANDROID_STORAGE=/storage "
               "ANDROID_ASSETS=/system/app EXTERNAL_STORAGE=/sdcard")
    with open(os.path.join(root, "system", "etc", "native-env.sh"), "w") as f:
        f.write("\n".join(env) + "\n")
    log("class paths derived")
    log("done:", out)


if __name__ == "__main__":
    main()
