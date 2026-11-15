#!/usr/bin/env python3
"""Apply the path-prefixing change to an AOSP bionic checkout.

    apply.py BIONIC_DIR

1. In libc/SYSCALLS.TXT, rename the stubs of system calls that take paths to
   __nrp_<name>, so that native_root.cpp can provide the real names.
2. Copy native_root.cpp to libc/bionic/ and add it to libc_bionic's sources.

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
]

# The internal name each renamed stub gets (matches native_root.cpp).
def new_symbol(old, call):
    base = old.split("|")[0].lstrip("_")
    if base.startswith("statfs"):
        return "__nrp_" + base
    if old.startswith("fstatat64"):
        return "__nrp_fstatat64"
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
    if '"bionic/native_root.cpp"' in s:
        return
    anchor = '        "bionic/open.cpp",\n'
    if s.count(anchor) != 1:
        sys.exit("libc/Android.bp: expected one bionic/open.cpp entry")
    s = s.replace(anchor, anchor + '        "bionic/native_root.cpp",\n')
    with open(path, "w") as f:
        f.write(s)


def main():
    bionic = sys.argv[1]
    here = os.path.dirname(os.path.abspath(__file__))
    patch_syscalls(os.path.join(bionic, "libc", "SYSCALLS.TXT"))
    shutil.copy(os.path.join(here, "native_root.cpp"),
                os.path.join(bionic, "libc", "bionic", "native_root.cpp"))
    patch_android_bp(os.path.join(bionic, "libc", "Android.bp"))
    print("bionic: path prefixing applied")


if __name__ == "__main__":
    main()
