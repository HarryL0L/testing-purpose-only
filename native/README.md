# native

Running Android 17 directly on the phone's kernel, the way Termux runs its
programs: Android's core libraries are rebuilt from AOSP source for the app's
folder, instead of a second kernel (UMLDroid's User-Mode Linux) or runtime
interception.

Step 1 (this commit): build Android 17's own bionic (`libc.so`, `linker64`),
unchanged, from a minimal AOSP checkout on GitHub Actions
(`.github/workflows/bionic.yml`, projects in `bionic-projects.txt`).

`rust-shims/` holds stand-ins for two small no_std Rust helpers that Android
17's libc and linker link (`libbionic_panic_abort`: abort on panic;
`liblibc_alloc_rust`: Rust allocations via malloc/free). Their real
definitions aren't in the public `android-17.0.0_r1` source tree.

Status: step 1 works. Run 24 built Android 17's `libc.so` (1.28 MB) and
`linker64` (2.42 MB) for arm64 (artifact `bionic-android17-arm64`).

Step 2 (`bionic-paths/`): libc and the linker look up absolute paths under
`$ANDROID_NATIVE_ROOT` (the folder holding the image's /system, /vendor,
/apex, ...); /proc, /sys and /dev stay host paths (except /dev/__properties__
and /dev/socket). execve() starts dynamic programs through the image's own
linker. Without ANDROID_NATIVE_ROOT, libc behaves like stock. `apply.py`
renames the path-taking system call stubs in SYSCALLS.TXT and adds
`native_root.cpp` to libc.

Tested on the phone (RMX3031, Android 13, kernel 4.19) in Termux: Android 17's
toybox and mksh run from `a17-mini` with no root and no second kernel. `ls`
shows the image's /system/bin, cd/pwd, file writes, and programs starting
other programs (through the image's linker) all work; no system call was
blocked by the app sandbox.

Step 3 (`nprops/`): Android properties. nprops does init's part: it builds
/dev/__properties__ from the image's property_contexts, loads build.prop and
answers setprop on /dev/socket/property_service (init's protocol, ro.* stays
read-only). libc now also maps AF_UNIX socket paths (bind/connect), so
setprop reaches nprops instead of the phone's own property service.
Property files in an app belong to the app's uid, so the property readers
accept files owned by the caller as well as by root. Tested on the phone:
113 properties loaded, getprop/setprop work.

Step 4 (`nbinder/`): binder. An app can't get a binder context from the
kernel, so nbinder is a userspace binder driver (drivers/android/binder.c's
commands, reference counting, nested calls, oneway ordering and death
notifications), and libc's `native_binder.cpp` turns open/ioctl/mmap on
/dev/binder into messages to it: libbinder runs unchanged. Transaction data
goes straight into the receiver's buffer (a memfd shared with nbinder); fds
travel as SCM_RIGHTS. HIDL's scatter-gather objects are not supported.
`/sys/fs/selinux` is a permissive stand-in in the root, so servicemanager's
SELinux checks pass. `./run.sh nboot` starts nlogd (logd's socket, to /data/local/tmp/log.txt), nprops, nbinder and the
image's servicemanager; `./run.sh service list` asks it for its services.
`nbinder/test/nbtest.cpp` runs both halves against each other on the CI
runner.
Checked on the CI-built root under qemu-aarch64: Android 17's servicemanager
becomes the context manager and `service list` gets
`0 manager: [android.os.IServiceManager]` back through nbinder.
On the phone servicemanager came up but registered nothing: Android's
services check the caller's uid, and an app uid may not register services.
libc now virtualizes user and group ids like proot -0: programs see root
(ids passed on through ANDROID_NATIVE_IDS), set*id() change the virtual
ids, files owned by the app look owned by them and chown() succeeds; libc
tells nbinder its virtual euid, which binder reports to callees.
Tested on the phone (Termux): `./run.sh nboot` brings up nlogd, nprops,
nbinder and servicemanager (`servicemanager.ready=true`); `./run.sh service
list` prints `0 manager: [android.os.IServiceManager]`; `./run.sh id` gives
`uid=0(root)`. Binder works natively in an app, without root.

Step 5 (`tools/a17root.py`): the full root, built on the phone. From the
image zip it streams the system partition out of super.img, unpacks it with
the image's own fsck.erofs (run natively from a17-mini), unpacks every APEX
under /apex (ext4 payloads with `ext4x.py`, pure Python), makes absolute
symlinks relative, puts our runtime on top, then does what init and apexd
do at boot: apex-info-list.xml, linkerconfig, derive_classpath (the boot
environment goes to /system/etc/native-env.sh, which run.sh loads). nprops
now derives ro.product.*, ro.build.fingerprint and ro.product.cpu.abilist*
like init. Checked under qemu: Android 17's ART runs Java from the framework
(`abx` converts XML to binary XML and back; `am` runs until it needs
system_server).
Tested on the phone: a17root.py builds the full root in Termux (system
914 MB, 38 APEXes, linkerconfig, derive_classpath); `./run.sh nboot` and
Android 17's ART running abx (XML to binary XML and back) work natively.

Step 6 (in progress): zygote and system_server. Under qemu, Android 17's
zygote starts, loads the framework and preloads classes. Learned so far:
- ART needs device properties a vendor partition would set (heap sizes,
  ro.zygote, ISA variant): nprops sets defaults when nothing else does.
- The image's boot image is built for the userfaultfd (CMC) collector; on a
  kernel without MREMAP_DONTUNMAP ART uses the concurrent-copying collector,
  which can't use it. odrefresh compiles a fitting one into /data at the end
  of a17root.py (trusted through odsign.verification.success=true).
- libc now also fakes what zygote does for each process it forks: capget/
  capset (remembered), capability prctls, mount/umount2/unshare/setns, SELinux
  context writes to /proc/<pid>/attr/* and security.selinux xattrs.
- nsocket creates init-style service sockets; nzygote starts zygote with
  them, as init.zygote64.rc does.
Phone, step 6: zygote reaches nativeForkSystemServer (the fork qemu can't
do), and the log works (crash_dump's backtrace lands in log.txt). Zygote's
fd check (fd_utils.cpp: only /dev/null, its sockets, framework jars...)
refused the redirected stdout; nzygote now gives it /dev/null as init does,
and nsocket closes every other inherited fd.
Phone: zygote forks system_server (pid created, zygote accepting command
connections), after 0.76 s of zygote start-up. system_server then spun in
zygote's bounding-set loop (the PR_CAPBSET_READ fake turned the ending
EINVAL into "present"); only EPERM is faked now.
With ART's single-threaded check patched in a local copy (qemu-only: qemu
adds a thread to every process), system_server runs under qemu too: it
starts its bootstrap and core services (activity manager, package-related
services, power, thermal, lights, recovery) and stops at the display
manager, which waits for SurfaceFlinger (step 8). On the way: init's
directories, cgroups as folders, memfd instead of ashmem, napexd (an
apexservice stand-in: the APEXes are unpacked already), the image's own
suspend service and installd, init.environ.rc's environment, hwbinder as an
empty binder context.
Phone: same as qemu - system_server runs the core services natively and
waits at the display manager for SurfaceFlinger (the watchdog then kills
it, as expected without a display).

Step 8 (display), plan: the Cuttlefish vendor graphics HALs (ranchu and
drm_hwcomposer composers, minigbm allocator) all need /dev/dri, which an app
can't open; the image does have ANGLE (system) and SwiftShader
(vulkan.pastel.so in vendor's com.google.cf.vulkan), so rendering can run on
the CPU. Needed: the vendor partition in the root, a memfd-backed allocator
service + stable-C mapper, and a composer3 service that takes SurfaceFlinger's
client target and hands the frames to a viewer.
Under qemu, with native/gfx (nalloc, mapper.native, ncomposer writing
frames to /data/vendor/nfb, nfb2png.py to view them), Android 17 boots to
the home screen: SurfaceFlinger reports "Boot is finished" after 90 s,
user 0 unlocks and the launcher, wallpaper, status and navigation bars
draw (2.5 min for the launcher under emulation). Missing pieces filled in
on the way: stand-ins for vold, netd and dnsresolver (StubService), lmkd
(nlmkd), userspace BPF in libc for the network stack's maps and programs,
the vendor HALs (health, keymint, gatekeeper, power, audio, ...) and
daemons (audioserver, keystore2, gatekeeperd, idmap2d, tombstoned),
SO_PEERCRED and /proc/<pid>/status reporting the virtual ids, and a
dex2oat verify pass over the apps (their prebuilt odex files don't match
the concurrent-copying boot image odrefresh builds).

Phone (RMX3031, Android 13, kernel 4.19, unrooted, in Termux): the same
boot reaches the home screen natively, "Boot is finished" after 15 s
(python3 a17-mini/nstart.py a17; the lock screen is turned off with
./run.sh locksettings set-disabled true). Running as an app, not root,
needed: Unix-socket stand-ins for vsock and uevent sockets, the root's
/dev/input and kernel config, plain clocks for the alarm clocks, BPF pins
copied where SELinux forbids hard links, owner bits added for a moment
where owner/label records and pins need them, the image's labels for files
without a record, and nstart.py as the parent of every process (realme
kills orphaned processes).

GPU (phone): PanVK (Mesa's Vulkan driver for Mali on the kbase kernel
driver) works as the image's Vulkan driver: copied into the root as
/apex/com.google.cf.vulkan/lib64/hw/vulkan.panvk.so and selected with
ro.hardware.vulkan=panvk (in /data/local/native.prop to keep it), ANGLE
runs OpenGL ES on it and SurfaceFlinger and apps render on the Mali-G77
(the launcher draws in 2 s instead of minutes). Graphics buffers come from
ION's ion_mm_heap (nalloc; MediaTek's 4.19 ION, legacy allocation
interface): kbase refused buffers from the system heap.
native/gfx/nvkprobe checks a Vulkan driver inside the root,
native/gfx/nionprobe what ION offers an app.
