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
