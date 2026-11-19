#!/usr/bin/env python3
"""Start native-mode Android 17 and stay the parent of all its processes.

    python3 nstart.py A17_DIR [NAME=VALUE...]
    python3 nstart.py --stop A17_DIR

A17_DIR is a17root.py's output (root/ and run.sh); NAME=VALUE are extra
properties for nboot. Runs nboot (logging, properties, binder,
servicemanager), then nzygote (the HALs and daemons, zygote, system_server),
and waits. nboot's daemons put themselves in the background, which leaves
them orphans on a device without a parent to adopt them, and some phones
(realme) kill orphaned processes of apps. This process takes them in instead
(PR_SET_CHILD_SUBREAPER), as init does on a device. Its own output and
nboot's go to A17_DIR/nboot.log. --stop ends every process running from
A17_DIR's root (they show as /system/bin/linker64 ..., so pkill -f misses them)
and A17_DIR/naudio.
"""
import ctypes
import os
import signal
import subprocess
import sys
import time

PR_SET_CHILD_SUBREAPER = 36


def stop(a17):
    """SIGKILL every process whose executable is under A17_DIR (and nstart)."""
    root = os.path.realpath(os.path.join(a17, "root")) + "/"
    me = os.getpid()
    for _ in range(3):  # parents may restart children: a few rounds
        killed = 0
        for pid in os.listdir("/proc"):
            if not pid.isdigit() or int(pid) == me:
                continue
            try:
                exe = os.readlink("/proc/%s/exe" % pid)
                cmd = open("/proc/%s/cmdline" % pid, "rb").read()
            except OSError:
                continue
            if exe.startswith(root) or exe == os.path.realpath(os.path.join(a17, "naudio")) or \
                    b"nstart.py\0" + os.fsencode(a17) in cmd or \
                    (b"nstart.py" in cmd and os.fsencode(os.path.basename(a17)) in cmd):
                try:
                    os.kill(int(pid), signal.SIGKILL)
                    killed += 1
                except OSError:
                    pass
        print("nstart: stopped %d processes" % killed, flush=True)
        if not killed:
            break
        time.sleep(1)


def main():
    if len(sys.argv) >= 3 and sys.argv[1] == "--stop":
        stop(os.path.abspath(sys.argv[2]))
        return
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    a17 = os.path.abspath(sys.argv[1])
    run = os.path.join(a17, "run.sh")
    if not os.path.exists(run):
        sys.exit("no run.sh in %s" % a17)
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) != 0:
        print("nstart: can't adopt orphans (prctl: %s)" % os.strerror(ctypes.get_errno()))
    log = open(os.path.join(a17, "nboot.log"), "ab", buffering=0)
    print("nstart: nboot (output in %s)" % log.name, flush=True)
    subprocess.run([run, "nboot"] + sys.argv[2:], stdin=subprocess.DEVNULL, stdout=log,
                   stderr=log)
    # naudio (native/naudio): the audio HAL's streams to the phone's speaker,
    # when built into A17_DIR ("clang -O2 -o naudio naudio.c -ldl -lpthread")
    naudio = os.path.join(a17, "naudio")
    if os.access(naudio, os.X_OK):
        subprocess.Popen([naudio, os.path.join(a17, "root")], stdin=subprocess.DEVNULL,
                         stdout=log, stderr=log)
    zygote = subprocess.Popen([run, "nzygote"], stdin=subprocess.DEVNULL, stdout=log, stderr=log)
    print("nstart: zygote started (pid %d); log: %s" %
          (zygote.pid, os.path.join(a17, "root/data/local/tmp/log.txt")), flush=True)
    # Reap whatever ends up here, as init does, until nothing is left.
    while True:
        try:
            pid, status = os.wait()
        except ChildProcessError:
            break
        except InterruptedError:
            continue
        if pid == zygote.pid:
            print("nstart: zygote ended (%s)" % describe(status), flush=True)
    print("nstart: all processes ended", flush=True)


def describe(status):
    if os.WIFSIGNALED(status):
        return "signal %d" % os.WTERMSIG(status)
    return "exit %d" % os.WEXITSTATUS(status)


if __name__ == "__main__":
    main()
