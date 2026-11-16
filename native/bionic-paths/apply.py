#!/usr/bin/env python3
"""Apply the path-prefixing change to an AOSP bionic checkout.

    apply.py BIONIC_DIR

1. In libc/SYSCALLS.TXT, rename the stubs of system calls that take paths to
   __nrp_<name>, so that native_root.cpp can provide the real names.
2. Copy native_root.cpp, native_binder.cpp (with nbinder_proto.h) and
   native_bpf.cpp to libc/bionic/ and add them to libc_bionic's sources;
   syscall() becomes __nrp_syscall (native_root.cpp answers bpf(2)).
3. Let the property readers trust files owned by the caller as well as by
   root: in an app (Termux) every file in the root belongs to the app's uid.

Idempotent: running it twice changes nothing the second time.
"""
import os
import re
import shutil
import sys

# Stub symbol before the change -> (system call, ABI tag or None for any).
# The symbol is what appears before ':' in SYSCALLS.TXT (or the call name
# when there is no ':').
RENAMES = [
    ("execve", "execve", None),
    ("__openat", "openat", None),
    ("__faccessat", "faccessat", None),
    ("__fchmodat", "fchmodat", None),
    ("fchownat", "fchownat", None),
    ("fstatat64|fstatat", "fstatat64", "lp32"),
    ("fstatat64|fstatat", "newfstatat", "lp64"),
    ("linkat", "linkat", None),
    ("mkdirat", "mkdirat", None),
    ("mknodat", "mknodat", None),
    ("readlinkat", "readlinkat", None),
    ("renameat2", "renameat2", None),
    ("symlinkat", "symlinkat", None),
    ("unlinkat", "unlinkat", None),
    ("utimensat", "utimensat", None),
    ("truncate", "truncate", "lp32"),
    ("truncate64", "truncate64", "lp32"),
    ("truncate|truncate64", "truncate", "lp64"),
    ("__statfs64", "statfs64", "lp32"),
    ("__statfs", "statfs", "lp64"),
    ("chdir", "chdir", None),
    ("__getcwd", "getcwd", None),
    ("setxattr", "setxattr", None),
    ("lsetxattr", "lsetxattr", None),
    ("getxattr", "getxattr", None),
    ("lgetxattr", "lgetxattr", None),
    ("listxattr", "listxattr", None),
    ("llistxattr", "llistxattr", None),
    ("removexattr", "removexattr", None),
    ("lremovexattr", "lremovexattr", None),
    ("statx", "statx", None),
    ("inotify_add_watch", "inotify_add_watch", None),
    ("bind", "bind", None),
    ("__connect", "connect", None),
    # binder fds (native_binder.cpp)
    ("__ioctl", "ioctl", None),
    ("__close", "close", None),
    ("mmap|mmap64", "mmap", "lp64"),
    ("__mmap2", "mmap2", "lp32"),
    # virtual uids/gids (native_root.cpp)
    ("getuid", "getuid32", "lp32"),
    ("getuid", "getuid", "lp64"),
    ("getgid", "getgid32", "lp32"),
    ("getgid", "getgid", "lp64"),
    ("geteuid", "geteuid32", "lp32"),
    ("geteuid", "geteuid", "lp64"),
    ("getegid", "getegid32", "lp32"),
    ("getegid", "getegid", "lp64"),
    ("getresuid", "getresuid32", "lp32"),
    ("getresuid", "getresuid", "lp64"),
    ("getresgid", "getresgid32", "lp32"),
    ("getresgid", "getresgid", "lp64"),
    ("getgroups", "getgroups32", "lp32"),
    ("getgroups", "getgroups", "lp64"),
    ("setgid", "setgid32", "lp32"),
    ("setgid", "setgid", "lp64"),
    ("setuid", "setuid32", "lp32"),
    ("setuid", "setuid", "lp64"),
    ("setreuid", "setreuid32", "lp32"),
    ("setreuid", "setreuid", "lp64"),
    ("setresuid", "setresuid32", "lp32"),
    ("setresuid", "setresuid", "lp64"),
    ("setresgid", "setresgid32", "lp32"),
    ("setresgid", "setresgid", "lp64"),
    ("setgroups", "setgroups32", "lp32"),
    ("setgroups", "setgroups", "lp64"),
    ("setregid", "setregid32", "lp32"),
    ("setregid", "setregid", "lp64"),
    ("fchown", "fchown32", "lp32"),
    ("fchown", "fchown", "lp64"),
    # capabilities, namespaces and mounts (native_root.cpp)
    ("capget", "capget", None),
    ("capset", "capset", None),
    ("prctl", "prctl", None),
    ("mount", "mount", None),
    ("umount2", "umount2", None),
    ("unshare", "unshare", None),
    ("setns", "setns", None),
    ("__fsetxattr", "fsetxattr", None),
    ("__fgetxattr", "fgetxattr", None),
    ("getsockname", "getsockname", None),
    ("getpeername", "getpeername", None),
    ("fstat64|fstat", "fstat64", "lp32"),
    ("fstat64|fstat", "fstat", "lp64"),
    ("uname", "uname", None),
]

# The internal name each renamed stub gets (matches native_root.cpp).
def new_symbol(old, call):
    base = old.split("|")[0].lstrip("_")
    if base.startswith("statfs"):
        return "__nrp_" + base
    if old.startswith("fstatat64"):
        return "__nrp_fstatat64"
    if old.startswith("fstat64"):
        return "__nrp_fstat64"
    return "__nrp_" + base


def patch_syscalls(path):
    with open(path) as f:
        lines = f.readlines()
    done = 0
    for i, line in enumerate(lines):
        if line.startswith("#") or not line.strip():
            continue
        m = re.match(r"([A-Za-z0-9_|]+)(?::([A-Za-z0-9_]+))?\(", line)
        if not m:
            continue
        sym, call = m.group(1), m.group(2) or m.group(1).split("|")[0]
        if sym.startswith("__nrp_"):
            done += 1
            continue
        for old, want_call, abi in RENAMES:
            if sym != old or call != want_call:
                continue
            if abi is not None and not re.search(r"\b%s\b" % abi, line[line.index(")"):]):
                continue
            lines[i] = "%s:%s%s" % (new_symbol(old, call), call, line[m.end() - 1:])
            done += 1
            break
    if done != len(RENAMES):
        sys.exit("SYSCALLS.TXT: renamed %d stubs, expected %d" % (done, len(RENAMES)))
    with open(path, "w") as f:
        f.writelines(lines)


def patch_android_bp(path):
    with open(path) as f:
        s = f.read()
    anchor = '        "bionic/open.cpp",\n'
    if s.count(anchor) != 1:
        sys.exit("libc/Android.bp: expected one bionic/open.cpp entry")
    for src in ("native_bpf.cpp", "native_binder.cpp", "native_root.cpp"):
        entry = '        "bionic/%s",\n' % src
        if entry not in s:
            s = s.replace(anchor, anchor + entry)
    with open(path, "w") as f:
        f.write(s)


def patch_syscall_s(bionic):
    """syscall() becomes __nrp_syscall: native_root.cpp's syscall() answers
    bpf(2) (native_bpf.cpp) and passes the rest on."""
    for arch in ("arm", "arm64", "riscv64", "x86", "x86_64"):
        path = os.path.join(bionic, "libc", "arch-" + arch, "bionic", "syscall.S")
        if not os.path.exists(path):
            continue
        with open(path) as f:
            s = f.read()
        s = s.replace("ENTRY(syscall)", "ENTRY(__nrp_syscall)").replace("END(syscall)",
                                                                      "END(__nrp_syscall)")
        with open(path, "w") as f:
            f.write(s)


OWNER_OLD = "(fd_stat.st_uid != 0) || (fd_stat.st_gid != 0)"
OWNER_NEW = ("(fd_stat.st_uid != 0 && fd_stat.st_uid != getuid()) || "
             "(fd_stat.st_gid != 0 && fd_stat.st_gid != getgid())")


def patch_owner_check(path):
    with open(path) as f:
        s = f.read()
    if OWNER_NEW in s:
        return
    if s.count(OWNER_OLD) != 1:
        sys.exit("%s: expected one root-owner check" % path)
    with open(path, "w") as f:
        f.write(s.replace(OWNER_OLD, OWNER_NEW))


def main():
    bionic = sys.argv[1]
    here = os.path.dirname(os.path.abspath(__file__))
    patch_syscalls(os.path.join(bionic, "libc", "SYSCALLS.TXT"))
    patch_syscall_s(bionic)
    for f in ("native_root.cpp", "native_binder.cpp", "native_bpf.cpp", "native_bpf.h"):
        shutil.copy(os.path.join(here, f), os.path.join(bionic, "libc", "bionic", f))
    shutil.copy(os.path.join(here, "..", "nbinder", "nbinder_proto.h"),
                os.path.join(bionic, "libc", "bionic", "nbinder_proto.h"))
    patch_android_bp(os.path.join(bionic, "libc", "Android.bp"))
    patch_owner_check(os.path.join(bionic, "libc", "system_properties", "prop_area.cpp"))
    patch_owner_check(os.path.join(os.path.dirname(os.path.abspath(bionic)), "system", "core",
                                   "property_service", "libpropertyinfoparser",
                                   "property_info_parser.cpp"))
    print("bionic: path prefixing applied")


if __name__ == "__main__":
    main()
