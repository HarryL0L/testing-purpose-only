package io.github.harryl0l.a17;

import android.system.ErrnoException;
import android.system.Os;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Builds Android 17's full root for native mode in the app, as
 * tools/a17root.py does in Termux: the partitions streamed out of super.img in
 * the image zip, erofs images unpacked by the image's own fsck.erofs (run from
 * an unpacked a17-mini), ext4 APEX payloads read here; then, as init and
 * apexd would at boot, apex-info-list.xml, the linker configuration and the
 * class paths, the boot image (odrefresh) and the apps' verified code, all
 * run from the new root itself.
 */
final class RootBuilder {
    /** What the builder needs from the app: its status line and programs. */
    interface Host {
        void log(String line);
        /** Runs argv (the phone's sh, a run.sh, ...) in dir; returns its exit status. */
        int run(String dir, String[] argv);
        boolean cancelled();
    }

    static final class Cancelled extends IOException {
        Cancelled() { super("cancelled"); }
    }

    private final Host host;
    private final File mini, miniRoot, stage;
    private static final String GUEST_STAGE = "/data/local/tmp/a17stage";

    RootBuilder(Host host, File mini) {
        this.host = host;
        this.mini = mini;
        this.miniRoot = new File(mini, "root");
        this.stage = new File(miniRoot, "data/local/tmp/a17stage");
    }

    /** The whole root into out (root/ and run.sh); out must not exist yet. */
    void build(File imgZip, File out) throws IOException {
        File root = new File(out, "root");
        if (Ext4.exists(out)) throw new IOException(out + " exists: remove it first");
        out.mkdirs();
        deleteTree(stage);
        stage.mkdirs();

        host.log("Unpacking the partitions from the image");
        String[] parts = {"system_a", "system_ext_a", "product_a", "vendor_a", "odm_a"};
        Map<String, File> imgs = new LinkedHashMap<>();
        for (String p : parts) imgs.put(p, new File(stage, p + ".img"));
        Set<String> got;
        try (ZipFile z = new ZipFile(imgZip)) {
            ZipEntry e = z.getEntry("super.img");
            if (e == null) throw new IOException("the image has no super.img");
            try (InputStream in = z.getInputStream(e)) {
                Map<String, Long> sizes = SuperImage.extract(in, imgs);
                for (Map.Entry<String, Long> s : sizes.entrySet()) host.log(s.getKey() + ": " + (s.getValue() >> 20) + " MB");
                got = sizes.keySet();
            }
        }
        checkCancel();
        if (!got.contains("system_a")) throw new IOException("no system partition in super.img");
        host.log("Unpacking system");
        unpack(imgs.get("system_a"), root);
        imgs.get("system_a").delete();
        // the others go where the system image only has mount points (its /odm
        // holds links into /vendor/odm, for devices without odm)
        String[][] rest = {{"system_ext_a", "system_ext"}, {"product_a", "product"},
                           {"vendor_a", "vendor"}, {"odm_a", "odm"}};
        for (String[] p : rest) {
            if (!got.contains(p[0])) continue;
            checkCancel();
            host.log("Unpacking " + p[1]);
            File target = new File(root, p[1]);
            if (target.isDirectory() && !Files.isSymbolicLink(target.toPath())) deleteTree(target);
            unpack(imgs.get(p[0]), target);
            imgs.get(p[0]).delete();
        }

        List<File> apexes = new ArrayList<>();
        for (String part : new String[] {"system", "system_ext", "product", "vendor", "odm"}) {
            File dir = new File(root, part + "/apex");
            String[] names = dir.isDirectory() ? dir.list() : null;
            if (names == null) continue;
            List<File> here = new ArrayList<>();
            for (String n : names) if (n.endsWith(".apex") || n.endsWith(".capex")) here.add(new File(dir, n));
            here.sort(Comparator.comparing(File::getPath));
            apexes.addAll(here);
        }
        for (int i = 0; i < apexes.size(); i++) {
            checkCancel();
            String file = apexes.get(i).getName();
            String name = file.substring(0, file.lastIndexOf('.'));
            host.log("APEX " + (i + 1) + "/" + apexes.size() + " " + name);
            File payload = new File(stage, "payload.img");
            apexPayload(apexes.get(i), payload);
            unpack(payload, new File(root, "apex/" + name));
            payload.delete();
        }
        deleteTree(stage);

        host.log("relinked " + relink(root, miniRoot) + " absolute symlinks");
        overlay(miniRoot, root);
        copyFile(new File(mini, "run.sh"), new File(out, "run.sh"));
        chmod(new File(out, "run.sh"), 0755);
        host.log("apex-info-list.xml: " + writeApexInfo(root) + " APEXes");
        host.log(initDirs(root) + " directories init would create");

        checkCancel();
        host.log("Writing the linker configuration");
        nativeRun(out, "/apex/com.android.runtime/bin/linkerconfig --target /linkerconfig", "linkerconfig");
        // derive_classpath reads properties: give it a property service for a moment.
        host.log("Deriving the class paths");
        nativeRun(out, WITH_PROPS
                + "/apex/com.android.sdkext/bin/derive_classpath /data/system/environ/classpath; "
                + "r=$?; kill $p; exit $r", "derive_classpath");
        List<String> env = new ArrayList<>();
        env.add("# Android's boot environment (init.environ.rc and derive_classpath)");
        if (!exports(new File(root, "data/system/environ/classpath"), env)) {
            throw new IOException("derive_classpath wrote no class paths");
        }
        // init.environ.rc's exports (installd crashes without ASEC_MOUNTPOINT)
        if (!exports(new File(root, "init.environ.rc"), env)) {
            env.add("export ANDROID_ART_ROOT=/apex/com.android.art ANDROID_I18N_ROOT=/apex/com.android.i18n "
                    + "ANDROID_TZDATA_ROOT=/apex/com.android.tzdata ANDROID_STORAGE=/storage "
                    + "ANDROID_ASSETS=/system/app EXTERNAL_STORAGE=/sdcard ASEC_MOUNTPOINT=/mnt/asec");
        }
        writeText(new File(root, "system/etc/native-env.sh"), String.join("\n", env) + "\n");
        checkCancel();
        // The boot image in /system is built for ART's userfaultfd collector, which
        // needs kernel features (5.13+ mremap) this phone may lack; ART then runs
        // the concurrent-copying collector and needs an image built for it.
        // odrefresh compiles one into /data/misc/apexdata (a few minutes, once).
        host.log("Compiling the boot image and system_server (odrefresh; takes a while)");
        nativeRun(out, WITH_PROPS + "/apex/com.android.art/bin/odrefresh --force-compile; "
                + "echo \"odrefresh: exit status $?\"; kill $p; exit 0", "odrefresh");
        checkCancel();
        // The apps' prebuilt odex files are compiled against the /system boot image
        // and are rejected with ours, so every app would verify its whole dex at
        // start. Verify them ahead of time instead, as first-boot dexopt would.
        host.log("Verifying the apps' code (dex2oat; takes a while)");
        nativeRun(out, PRECOMPILE, "dex2oat");
        host.log("Root built");
    }

    private void checkCancel() throws Cancelled {
        if (host.cancelled()) throw new Cancelled();
    }

    /** A shell command in the new root, through its own run.sh. */
    private void nativeRun(File out, String script, String what) throws IOException {
        String dir = out.getAbsolutePath();
        int st = host.run(dir, new String[] {"/system/bin/sh", dir + "/run.sh", "sh", "-c", script});
        if (st != 0) throw new IOException(what + " failed (exit status " + st + ")");
    }

    // "export NAME VALUE" lines of an .rc-style file as shell exports; false if unreadable.
    private static boolean exports(File f, List<String> env) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] p = line.trim().split("\\s+");
                if (p.length >= 3 && p[0].equals("export")) env.add("export " + p[1] + "=" + p[2]);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------ unpacking

    /** An erofs or ext4 image file (inside the stage) to dest. */
    void unpack(File image, File dest) throws IOException {
        int magic;
        try (RandomAccessFile r = new RandomAccessFile(image, "r")) {
            r.seek(1024);
            magic = Integer.reverseBytes(r.readInt());
        }
        if (magic == Erofs.MAGIC) {
            File out = new File(stage, "out");
            deleteTree(out);
            String m = mini.getAbsolutePath();
            int st = host.run(m, new String[] {"/system/bin/sh", m + "/run.sh", "fsck.erofs",
                    "--extract=" + GUEST_STAGE + "/out", "--overwrite",
                    // exact permissions (setuid, ...); owners and labels
                    // are recorded below (an app can't set them)
                    "--preserve-perms", GUEST_STAGE + "/" + image.getName()});
            if (st != 0 || !out.isDirectory()) {
                throw new IOException("fsck.erofs failed on " + image.getName() + " (exit status " + st + ")");
            }
            if (Ext4.exists(dest)) deleteTree(dest);
            dest.getParentFile().mkdirs();
            rename(out, dest);
            // The image's owners and SELinux labels, kept as libc reports them
            Erofs.record(image, dest.getPath());
            // The image's own modes now: let the app (each file's real owner)
            // still change every directory, as root could.
            walk(dest, true, f -> {
                if (Files.isSymbolicLink(f.toPath()) || !f.isDirectory()) return;
                int mode = mode(f);
                if ((mode & 0700) != 0700) chmod(f, (mode & 07777) | 0700);
            });
        } else {
            try (Ext4 fs = new Ext4(image)) {
                fs.extract(dest);
            }
        }
    }

    static void apexPayload(File apex, File dest) throws IOException {
        try (ZipFile z = new ZipFile(apex)) {
            ZipEntry p = z.getEntry("apex_payload.img");
            if (p != null) {
                copy(z.getInputStream(p), dest);
                return;
            }
            // a compressed APEX: the original inside
            ZipEntry orig = z.getEntry("original_apex");
            if (orig == null) throw new IOException(apex.getName() + " has no payload");
            File inner = new File(dest.getPath() + ".zip");
            copy(z.getInputStream(orig), inner);
            try (ZipFile z2 = new ZipFile(inner)) {
                ZipEntry p2 = z2.getEntry("apex_payload.img");
                if (p2 == null) throw new IOException(apex.getName() + " has no payload");
                copy(z2.getInputStream(p2), dest);
            } finally {
                inner.delete();
            }
        }
    }

    // ------------------------------------------------------------- fix-ups

    /**
     * Absolute symlinks -> relative ones, so they resolve inside root.
     * fsck.erofs ran in native mode, where libc puts the unpacker's root in
     * front of absolute symlink targets: that comes off again first (the path
     * as given or as the kernel resolves it: run.sh's $PWD may be either).
     */
    static int relink(File root, File unpackerRoot) throws IOException {
        List<String> prefixes = new ArrayList<>();
        prefixes.add(trimSlash(unpackerRoot.getAbsolutePath()) + "/");
        String canonical = trimSlash(unpackerRoot.getCanonicalPath()) + "/";
        if (!prefixes.contains(canonical)) prefixes.add(canonical);
        String top = root.getPath();
        int[] n = {0};
        walk(root, false, p -> {
            if (!Files.isSymbolicLink(p.toPath())) return;
            String t = readlink(p);
            for (String prefix : prefixes) {
                if (t.startsWith(prefix)) {
                    t = t.substring(prefix.length() - 1);
                    break;
                }
            }
            if (!t.startsWith("/")) return;
            String parent = p.getParent();
            String guest = parent.equals(top) ? "/" : parent.substring(top.length());
            p.delete();
            symlink(relpath(t, guest), p);
            n[0]++;
        });
        return n[0];
    }

    private static String trimSlash(String s) {
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** os.path.relpath of two absolute paths: lexical, as the guest sees them. */
    static String relpath(String target, String start) {
        List<String> t = normalize(target), s = normalize(start);
        int i = 0;
        while (i < t.size() && i < s.size() && t.get(i).equals(s.get(i))) i++;
        List<String> out = new ArrayList<>();
        for (int k = i; k < s.size(); k++) out.add("..");
        out.addAll(t.subList(i, t.size()));
        return out.isEmpty() ? "." : String.join("/", out);
    }

    private static List<String> normalize(String p) {
        List<String> out = new ArrayList<>();
        for (String c : p.split("/")) {
            if (c.isEmpty() || c.equals(".")) continue;
            if (c.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
            } else {
                out.add(c);
            }
        }
        return out;
    }

    /** apex_manifest.pb's first value of each field (1: name, 2: version). */
    static Map<Integer, Object> apexManifest(byte[] b) {
        Map<Integer, Object> out = new TreeMap<>();
        int[] i = {0};
        while (i[0] < b.length) {
            long key = varint(b, i);
            int field = (int) (key >>> 3), type = (int) (key & 7);
            Object v;
            if (type == 0) {
                v = varint(b, i);
            } else if (type == 2) {
                int len = (int) varint(b, i);
                v = Arrays.copyOfRange(b, i[0], Math.min(b.length, i[0] + len));
                i[0] += len;
            } else if (type == 5) {
                v = Arrays.copyOfRange(b, i[0], Math.min(b.length, i[0] + 4));
                i[0] += 4;
            } else if (type == 1) {
                v = Arrays.copyOfRange(b, i[0], Math.min(b.length, i[0] + 8));
                i[0] += 8;
            } else {
                break;
            }
            out.putIfAbsent(field, v);
        }
        return out;
    }

    private static long varint(byte[] b, int[] i) {
        long v = 0;
        int s = 0;
        while (i[0] < b.length) {
            int c = b[i[0]++] & 0xff;
            v |= (long) (c & 0x7F) << s;
            s += 7;
            if (c < 0x80) break;
        }
        return v;
    }

    /**
     * Which of several APEXes of one module to activate (on a device the
     * bootloader names it): ones that need no special hardware first.
     */
    static final Comparator<String> APEX_PREFERENCE = Comparator
            .comparingInt((String n) -> n.contains("nonsecure") ? 0 : 1)
            .thenComparingInt(n -> n.contains("v4l2") || n.contains("remote") ? 1 : 0)
            .thenComparingInt(String::length)
            .thenComparing(n -> n);

    // Vendor APEXes to activate. system_server waits for every HAL the active
    // manifests declare, so only those whose HALs native mode runs (nzygote starts
    // them), and ones without HALs that the system needs. The rest (radio, wifi,
    // camera, ...) wait for their bridges to the host app; the composer and
    // gralloc ones are replaced by native/gfx.
    static final Set<String> ACTIVE_VENDOR_APEXES = new HashSet<>(Arrays.asList(
            "com.google.cf.health", "com.android.hardware.keymint", "com.android.hardware.gatekeeper",
            "com.android.hardware.authsecret", "com.android.hardware.power",
            "com.android.hardware.vibrator", "com.google.cf.light", "com.android.hardware.thermal",
            "com.android.hardware.memtrack", "com.google.cf.vulkan", "com.google.cf.input.config",
            // the example audio HAL (software streams; audio to the host app comes later)
            "com.android.hardware.audio"));
    // Vendor manifest fragments of HALs native mode doesn't run yet.
    static final String[] INACTIVE_VENDOR_MANIFESTS = {"android.hardware.sensors-multihal.xml",
            "face-default.xml", "fingerprint-default.xml", "motioncontext-service-default.xml",
            "mapper.minigbm.xml"};

    static int writeApexInfo(File root) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        lines.add("<apex-info-list>");
        File apex = new File(root, "apex");
        // Inactive APEXes live outside /apex: the system reads every folder there
        // (features in etc/permissions, ...), as on a device it holds only active ones.
        File inactive = new File(root, "apex-inactive");
        String[] old = inactive.isDirectory() ? inactive.list() : null;
        if (old != null) {
            for (String name : old) {
                if (!Ext4.exists(new File(apex, name))) rename(new File(inactive, name), new File(apex, name));
            }
        }
        String[] names = apex.list();
        if (names == null) throw new IOException("no " + apex);
        for (String name : names) {
            if (Files.isSymbolicLink(new File(apex, name).toPath())) new File(apex, name).delete();
        }
        names = apex.list();
        Arrays.sort(names);
        // module name -> [dir name, version, path, partition]
        Map<String, List<Object[]>> modules = new TreeMap<>();
        String[][] parts = {{"system", "SYSTEM"}, {"system_ext", "SYSTEM_EXT"}, {"product", "PRODUCT"},
                            {"vendor", "VENDOR"}, {"odm", "ODM"}};
        for (String name : names) {
            File mp = new File(apex, name + "/apex_manifest.pb");
            if (Files.isSymbolicLink(new File(apex, name).toPath()) || !mp.exists()) continue;
            Map<Integer, Object> m = apexManifest(Files.readAllBytes(mp.toPath()));
            Object modName = m.get(1);
            String mod = modName instanceof byte[]
                    ? new String((byte[]) modName, StandardCharsets.UTF_8) : name;
            long ver = m.get(2) instanceof Long ? (Long) m.get(2) : 0;
            String src = "/system/apex/" + name + ".capex", part = "SYSTEM";
            for (String[] d : parts) {
                for (String ext : new String[] {".apex", ".capex"}) {
                    String cand = "/" + d[0] + "/apex/" + name + ext;
                    if (new File(root, cand.substring(1)).exists()) {
                        src = cand;
                        part = d[1];
                    }
                }
            }
            modules.computeIfAbsent(mod, k -> new ArrayList<>()).add(new Object[] {name, ver, src, part});
        }
        for (Map.Entry<String, List<Object[]>> e : modules.entrySet()) {
            String mod = e.getKey();
            Object[] best = e.getValue().get(0);
            for (Object[] o : e.getValue()) {
                if (APEX_PREFERENCE.compare((String) o[0], (String) best[0]) < 0) best = o;
            }
            String name = (String) best[0], src = (String) best[2], part = (String) best[3];
            if ((part.equals("VENDOR") || part.equals("ODM")) && !ACTIVE_VENDOR_APEXES.contains(mod)) name = null;
            for (Object[] other : e.getValue()) {
                if (!other[0].equals(name)) {
                    inactive.mkdirs();
                    rename(new File(apex, (String) other[0]), new File(inactive, (String) other[0]));
                }
            }
            if (name == null) continue;
            // apexd mounts the active one at /apex/<module name>
            if (!name.equals(mod)) {
                File link = new File(apex, mod);
                Files.deleteIfExists(link.toPath());
                symlink(name, link);
            }
            lines.add("    <apex-info moduleName=\"" + mod + "\" modulePath=\"" + src
                    + "\" preinstalledModulePath=\"" + src + "\" versionCode=\"" + best[1]
                    + "\" versionName=\"\" isFactory=\"true\" isActive=\"true\" lastUpdateMillis=\"0\" "
                    + "provideSharedApexLibs=\"false\" partition=\"" + part + "\"/>");
        }
        lines.add("</apex-info-list>");
        writeText(new File(apex, "apex-info-list.xml"), String.join("\n", lines) + "\n");
        return lines.size() - 3;
    }

    static final String[] RUNTIME = {  // our build, from a17-mini, over the image's
        "apex/com.android.runtime/bin/linker64",
        "apex/com.android.runtime/lib64/bionic/libc.so",
        "apex/com.android.runtime/lib64/bionic/libdl.so",
        "apex/com.android.runtime/lib64/bionic/libm.so",
        "system/bin/nlogd",
        "system/bin/nprops",
        "system/bin/nbinder",
        "system/bin/nboot",
        "system/bin/nsocket",
        "system/bin/nlmkd",
        "system/bin/nzygote",
        "system/framework/napexd.jar",
        // native/gfx: the graphics HALs
        "vendor/bin/nalloc",
        "vendor/lib64/hw/mapper.native.so",
        "system/bin/ncomposer",
        "vendor/etc/vintf/manifest/native-gfx.xml",
        "vendor/etc/permissions/native-unavailable-features.xml",
    };

    void overlay(File miniRoot, File root) throws IOException {
        for (String rel : RUNTIME) {
            File src = new File(miniRoot, rel), dst = new File(root, rel);
            if (!src.exists()) {
                host.log("missing in a17-mini: " + rel);
                continue;
            }
            dst.getParentFile().mkdirs();
            Files.deleteIfExists(dst.toPath());
            copyFile(src, dst);
            chmod(dst, rel.endsWith(".jar") || rel.endsWith(".xml") ? 0644 : 0755);
        }
        // HALs native mode doesn't run (our mapper replaces minigbm's): kept, but
        // out of the manifest directory.
        File vintf = new File(root, "vendor/etc/vintf");
        for (String f : INACTIVE_VENDOR_MANIFESTS) {
            File src = new File(vintf, "manifest/" + f);
            if (src.exists()) {
                new File(vintf, "inactive").mkdirs();
                Files.move(src.toPath(), new File(vintf, "inactive/" + f).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
        // bootstrap programs (servicemanager, ...) use the same linker and libc
        String[][] links = {
            {"system/bin/bootstrap/linker64", "../../../apex/com.android.runtime/bin/linker64"},
            {"system/lib64/bootstrap/libc.so", "../../../apex/com.android.runtime/lib64/bionic/libc.so"},
            {"system/lib64/bootstrap/libdl.so", "../../../apex/com.android.runtime/lib64/bionic/libdl.so"},
            {"system/lib64/bootstrap/libm.so", "../../../apex/com.android.runtime/lib64/bionic/libm.so"},
        };
        for (String[] l : links) {
            File p = new File(root, l[0]);
            p.getParentFile().mkdirs();
            Files.deleteIfExists(p.toPath());
            symlink(l[1], p);
        }
        // /sys/power is the root's (libc maps it): files the suspend service uses
        File power = new File(root, "sys/power");
        power.mkdirs();
        String[][] files = {{"state", "freeze mem\n"}, {"wakeup_count", "0\n"}, {"wake_lock", ""},
                            {"wake_unlock", ""}, {"autosleep", "off\n"}};
        for (String[] f : files) writeText(new File(power, f[0]), f[1]);
        File sel = new File(root, "sys/fs/selinux");
        deleteTree(sel);
        copyTree(new File(miniRoot, "sys/fs/selinux"), sel);
        for (String d : new String[] {"dev/socket", "dev/input", "data/local/tmp", "data/system/environ",
                "data/dalvik-cache/arm64", "data/misc/apexdata/com.android.art", "data/misc/odrefresh", "tmp",
                "linkerconfig",
                // cgroups (cgroups.json), as plain folders: libc maps them here
                "sys/fs/cgroup/system", "sys/fs/cgroup/apps", "dev/blkio", "dev/cpuctl",
                "dev/cpuset", "dev/stune", "dev/memcg"}) {
            new File(root, d).mkdirs();
        }
        // bpffs as the kernel mounts it (libc keeps BPF objects there)
        File bpf = new File(root, "sys/fs/bpf");
        bpf.mkdirs();
        chmod(bpf, 01777);
        // The kernel configuration libc gives where the phone's /proc/config.gz
        // can't be read (system_server checks CONFIG_VMAP_STACK)
        new File(root, "proc").mkdirs();
        try (OutputStream o = new GZIPOutputStream(new FileOutputStream(new File(root, "proc/config.gz")))) {
            o.write(KERNEL_CONFIG.getBytes(StandardCharsets.UTF_8));
        }
    }

    static final String KERNEL_CONFIG = "CONFIG_64BIT=y\n"
            + "CONFIG_ARM64=y\n"
            + "CONFIG_MMU=y\n"
            + "CONFIG_VMAP_STACK=y\n"
            + "CONFIG_ANDROID_BINDER_IPC=y\n"
            + "CONFIG_ANDROID_BINDERFS=y\n"
            + "CONFIG_BPF_SYSCALL=y\n"
            + "CONFIG_BPF_JIT=y\n"
            + "CONFIG_PSI=y\n"
            + "CONFIG_MEMCG=y\n"
            + "CONFIG_UPROBES=y\n";

    // Start nprops for one command, and wait until its properties are there.
    static final String WITH_PROPS = "nprops -f >/dev/null 2>&1 & p=$!; i=0; "
            + "while [ \"$(getprop native.nprops.ready 2>/dev/null)\" != 1 ] && [ $i -lt 300 ]; "
            + "do sleep 0.1; i=$((i + 1)); done; ";

    static final String[] USER0_DIRS = {"data/data", "data/user_de/0", "data/misc_de/0", "data/misc_ce/0",
            "data/system_de/0", "data/system_ce/0", "data/vendor_de/0", "data/vendor_ce/0",
            "data/media/0", "data/misc/user/0", "data/misc/profiles/cur/0",
            "data/misc/profiles/ref", "data/app", "data/user_de", "data/user"};

    /** Creates the directories init's .rc files make at boot (mkdir lines). */
    static int initDirs(File root) throws IOException {
        int[] n = {0};
        for (String base : new String[] {"system/etc/init", "system_ext/etc/init", "product/etc/init",
                "vendor/etc/init", "odm/etc/init"}) {
            File top = new File(root, base);
            if (!top.isDirectory()) continue;
            walk(top, false, f -> {
                if (!f.getName().endsWith(".rc") || isRealDir(f)) return;
                for (String line : readLines(f)) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length >= 2 && p[0].equals("mkdir") && p[1].startsWith("/") && !p[1].contains("$")) {
                        File target = new File(root, p[1].substring(1));
                        if (!Ext4.exists(target) && target.mkdirs()) n[0]++;
                    }
                }
            });
        }
        // init's bind mounts into /data_mirror (zygote opens app data through it):
        // here they're symlinks to their sources.
        for (String base : new String[] {"system/etc/init/hw", "system/etc/init"}) {
            File top = new File(root, base);
            String[] names = top.isDirectory() ? top.list() : null;
            if (names == null) continue;
            Arrays.sort(names);
            for (String f : names) {
                if (!f.endsWith(".rc")) continue;
                File rc = new File(top, f);
                if (rc.isDirectory()) continue;
                for (String line : readLines(rc)) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length >= 5 && p[0].equals("mount") && p[1].equals("none") && p[4].equals("bind")
                            && p[3].startsWith("/data_mirror/") && p[2].startsWith("/") && !line.contains("$")) {
                        File dst = new File(root, p[3].substring(1));
                        String[] inside = dst.list();
                        if (isRealDir(dst) && inside != null && inside.length == 0) dst.delete();
                        if (!Ext4.exists(dst)) {
                            dst.getParentFile().mkdirs();
                            String parentGuest = "/" + p[3].substring(1, p[3].lastIndexOf('/') + 1);
                            symlink(relpath(p[2], parentGuest), dst);
                            n[0]++;
                        }
                    }
                }
            }
        }
        // User 0's storage: init links /data/user/0 to /data/data; vold (which
        // can't run in an app: it mounts) makes the rest when the user starts.
        File link = new File(root, "data/user/0");
        if (!Ext4.exists(link)) {
            link.getParentFile().mkdirs();
            symlink("../data", link);
        }
        for (String d : USER0_DIRS) {
            File target = new File(root, d);
            if (!Ext4.exists(target) && target.mkdirs()) n[0]++;
        }
        return n[0];
    }

    private static boolean isRealDir(File f) {
        return Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    private static List<String> readLines(File f) {
        try {
            // errors="replace": bytes that aren't UTF-8 don't stop the file
            return Arrays.asList(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)
                    .split("\r\n|\r|\n|\u000b|\u000c|\u001c|\u001d|\u001e|\u0085| | "));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    // One dex2oat "verify" pass per APK into /data/dalvik-cache (where ART looks
    // when the APK's own oat/ directory is unusable), four at a time.
    static final String PRECOMPILE = "\n"
            + "cd /\n"
            + "find /system/app /system/priv-app /system_ext/app /system_ext/priv-app /product/app \\\n"
            + "     /product/priv-app /vendor/app /odm/app /apex -name '*.apk' 2>/dev/null |\n"
            + "xargs -P 4 -n 1 sh -c '\n"
            + "  o=/data/dalvik-cache/arm64/$(echo \"${1#/}\" | tr / @)@classes.dex\n"
            + "  [ -s \"$o\" ] || /apex/com.android.art/bin/dex2oat64 --dex-file=\"$1\" --dex-location=\"$1\" "
            + "--oat-file=\"$o\" \\\n"
            + "      --instruction-set=arm64 --compiler-filter=verify >/dev/null 2>&1 || rm -f \"$o\"\n"
            + "' sh\n"
            + "echo \"apps verified: $(ls /data/dalvik-cache/arm64 | grep -c 'classes.dex$')\"\n";

    // ------------------------------------------------------------ a17-mini

    /**
     * A tar or .tar.gz (a17-mini) into dest, its first path component dropped,
     * with modes and symlinks (hard links become copies: an app may not make them).
     */
    static void untar(InputStream raw, File dest) throws IOException {
        InputStream in = new java.io.BufferedInputStream(raw, 1 << 16);
        in.mark(2);
        boolean gz = in.read() == 0x1f && in.read() == 0x8b;
        in.reset();
        if (gz) in = new GZIPInputStream(in, 1 << 16);
        byte[] h = new byte[512];
        String longName = null, longLink = null;
        Map<String, String> pax = new TreeMap<>();
        List<Object[]> dirModes = new ArrayList<>();
        dest.mkdirs();
        while (readFull(in, h, 512)) {
            boolean zero = true;
            for (byte b : h) zero &= b == 0;
            if (zero) break;
            long size = octal(h, 124, 12);
            char type = (char) h[156];
            if (type == 'x' || type == 'g' || type == 'L' || type == 'K') {
                byte[] data = readBlock(in, size);
                if (type == 'L') longName = cstr(data, 0, data.length);
                if (type == 'K') longLink = cstr(data, 0, data.length);
                if (type == 'x') parsePax(data, pax);
                continue;
            }
            String name = longName != null ? longName : pax.containsKey("path") ? pax.get("path") : null;
            if (name == null) {
                String prefix = cstr(h, 345, 155);
                name = cstr(h, 0, 100);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
            }
            String link = longLink != null ? longLink
                    : pax.containsKey("linkpath") ? pax.get("linkpath") : cstr(h, 157, 100);
            if (pax.containsKey("size")) size = Long.parseLong(pax.get("size"));
            longName = longLink = null;
            pax.clear();
            int mode = (int) octal(h, 100, 8) & 07777;
            String rel = strip(name);
            File path = rel == null ? null : new File(dest, rel);
            if (path != null) path.getParentFile().mkdirs();
            if (path != null && type == '5') {
                path.mkdirs();
                dirModes.add(new Object[] {path, mode});
            } else if (path != null && type == '2') {
                Files.deleteIfExists(path.toPath());
                symlink(link, path);
            } else if (path != null && type == '1') {
                String src = strip(link);
                if (src != null) {
                    Files.copy(new File(dest, src).toPath(), path.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    chmod(path, mode);
                }
            } else if (type == '0' || type == '\0' || type == '7') {
                OutputStream out = path != null ? new FileOutputStream(path) : null;
                try {
                    byte[] buf = new byte[1 << 16];
                    for (long left = size; left > 0; ) {
                        int k = (int) Math.min(left, buf.length);
                        if (!readFull(in, buf, k)) throw new IOException("a17-mini: truncated");
                        if (out != null) out.write(buf, 0, k);
                        left -= k;
                    }
                } finally {
                    if (out != null) out.close();
                }
                skip(in, ((size + 511) & ~511L) - size);
                if (path != null) chmod(path, mode);
                continue;
            }
            skip(in, (size + 511) & ~511L);
        }
        // Directories get their modes last, deepest first, still u+rwx.
        for (int i = dirModes.size() - 1; i >= 0; i--) {
            chmod((File) dirModes.get(i)[0], (Integer) dirModes.get(i)[1] | 0700);
        }
    }

    // "a17-mini/run.sh" -> "run.sh"; null for the top folder itself or an unsafe path.
    private static String strip(String name) {
        while (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        if (name.startsWith("./")) name = name.substring(2);
        int slash = name.indexOf('/');
        if (slash < 0 || name.startsWith("/")) return null;
        String rel = name.substring(slash + 1);
        for (String c : rel.split("/")) if (c.equals("..")) return null;
        return rel.isEmpty() ? null : rel;
    }

    private static void parsePax(byte[] data, Map<String, String> out) {
        String s = new String(data, StandardCharsets.UTF_8);
        int i = 0;
        while (i < s.length()) {
            int sp = s.indexOf(' ', i);
            if (sp < 0) break;
            int len;
            try {
                len = Integer.parseInt(s.substring(i, sp));
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0 || i + len > s.length()) break;
            String rec = s.substring(sp + 1, i + len - 1);
            int eq = rec.indexOf('=');
            if (eq > 0) out.put(rec.substring(0, eq), rec.substring(eq + 1));
            i += len;
        }
    }

    private static byte[] readBlock(InputStream in, long size) throws IOException {
        if (size > (16 << 20)) throw new IOException("a17-mini: oversized header");
        byte[] data = new byte[(int) ((size + 511) & ~511L)];
        if (!readFull(in, data, data.length)) throw new IOException("a17-mini: truncated");
        return Arrays.copyOf(data, (int) size);
    }

    private static String cstr(byte[] b, int off, int max) {
        int n = 0;
        while (n < max && off + n < b.length && b[off + n] != 0) n++;
        return new String(b, off, n, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int off, int n) {
        if ((b[off] & 0x80) != 0) {  // GNU base-256
            long v = 0;
            for (int i = 1; i < n; i++) v = v << 8 | (b[off + i] & 0xff);
            return v;
        }
        long v = 0;
        for (int i = 0; i < n && b[off + i] != 0; i++) {
            if (b[off + i] >= '0' && b[off + i] <= '7') v = v * 8 + (b[off + i] - '0');
        }
        return v;
    }

    private static boolean readFull(InputStream in, byte[] b, int n) throws IOException {
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r < 0) return false;
            got += r;
        }
        return true;
    }

    private static void skip(InputStream in, long n) throws IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int k = (int) Math.min(n, buf.length);
            if (!readFull(in, buf, k)) throw new IOException("a17-mini: truncated");
            n -= k;
        }
    }

    // ------------------------------------------------------------- helpers

    interface FileVisitor { void visit(File f) throws IOException; }

    /**
     * Every entry under dir (dirs too), not following symlinks; with
     * withSelf, dir itself first.
     */
    static void walk(File dir, boolean withSelf, FileVisitor v) throws IOException {
        if (withSelf) v.visit(dir);
        String[] names = dir.list();
        if (names == null) return;
        for (String n : names) {
            File f = new File(dir, n);
            v.visit(f);
            if (isRealDir(f)) walk(f, false, v);
        }
    }

    /**
     * Removes path and everything under it, not following symlinks.
     * Directories may refuse their owner (r-x, BPF pins): the app owns them
     * all, so it gives itself the bits first.
     */
    static void deleteTree(File path) throws IOException {
        if (!Ext4.exists(path)) return;
        if (isRealDir(path)) {
            try {
                Os.chmod(path.getPath(), 0700);
            } catch (ErrnoException e) {
                // listing below says whether it matters
            }
            String[] names = path.list();
            if (names != null) for (String n : names) deleteTree(new File(path, n));
        }
        if (!path.delete() && Ext4.exists(path)) throw new IOException("can't remove " + path);
    }

    static void rename(File from, File to) throws IOException {
        if (!from.renameTo(to)) throw new IOException("can't rename " + from + " to " + to);
    }

    static void chmod(File f, int mode) throws IOException {
        try {
            Os.chmod(f.getPath(), mode);
        } catch (ErrnoException e) {
            throw new IOException("chmod " + f + ": " + e.getMessage(), e);
        }
    }

    static int mode(File f) throws IOException {
        try {
            return Os.lstat(f.getPath()).st_mode;
        } catch (ErrnoException e) {
            throw new IOException("stat " + f + ": " + e.getMessage(), e);
        }
    }

    static void symlink(String target, File link) throws IOException {
        try {
            Os.symlink(target, link.getPath());
        } catch (ErrnoException e) {
            throw new IOException("symlink " + link + ": " + e.getMessage(), e);
        }
    }

    static String readlink(File link) throws IOException {
        try {
            return Os.readlink(link.getPath());
        } catch (ErrnoException e) {
            throw new IOException("readlink " + link + ": " + e.getMessage(), e);
        }
    }

    /** A file's bytes and permission bits (shutil.copy2, less the times). */
    static void copyFile(File src, File dst) throws IOException {
        Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        chmod(dst, mode(src) & 07777);
    }

    private static void copyTree(File src, File dst) throws IOException {
        dst.mkdirs();
        String[] names = src.list();
        if (names == null) throw new IOException("no " + src);
        for (String n : names) {
            File s = new File(src, n), d = new File(dst, n);
            if (s.isDirectory()) copyTree(s, d);
            else copyFile(s, d);
        }
        chmod(dst, mode(src) & 07777);
    }

    static void copy(InputStream in, File dest) throws IOException {
        try (InputStream i = in; OutputStream o = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 20];
            for (int n; (n = i.read(buf)) > 0; ) o.write(buf, 0, n);
        }
    }

    static void writeText(File f, String s) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
