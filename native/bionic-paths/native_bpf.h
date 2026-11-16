/* Userspace eBPF objects for native mode (native_bpf.cpp). */
#pragma once

// bpf(2) as the kernel would answer it, for an app that may not call it.
long nbpf_syscall(int cmd, void* attr, unsigned int size);
