#!/usr/bin/env python3
"""Start native-mode Android 17 and stay the parent of all its processes.

    python3 nstart.py A17_DIR [NAME=VALUE...]

A17_DIR is a17root.py's output (root/ and run.sh); NAME=VALUE are extra
properties for nboot. Runs nboot (logging, properties, binder,
servicemanager), then nzygote (the HALs and daemons, zygote, system_server),
and waits. nboot's daemons put themselves in the background, which leaves
them orphans on a device without a parent to adopt them, and some phones
(realme) kill orphaned processes of apps. This process takes them in instead
(PR_SET_CHILD_SUBREAPER), as init does on a device. Its own output and
nboot's go to A17_DIR/nboot.log. Stop everything with: pkill -f a17/root
"""
import ctypes
import os
import subprocess
import sys

PR_SET_CHILD_SUBREAPER = 36


def main():
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
