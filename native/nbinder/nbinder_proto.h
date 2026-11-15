/*
 * The protocol between libc's /dev/binder stand-in (native_binder.cpp) and
 * nbinder, the userspace binder driver of native mode.
 *
 * Every process that opens /dev/binder connects once to NB_SOCKET (its "main"
 * connection: the fd libbinder gets, used for poll() and closed at exit), and
 * each thread that issues ioctls gets its own connection. Messages are an
 * nb_hdr plus `len` payload bytes on a SOCK_STREAM socket; file descriptors
 * ride along as SCM_RIGHTS on the header. Each request gets one reply on the
 * same connection. On the main connection nbinder only writes single wake-up
 * bytes (NB_WAKE) after the hello reply: the process has binder work waiting.
 */
#pragma once

#include <stdint.h>

#define NB_SOCKET "/dev/socket/nbinder"
#define NB_MAX_FDS 253
#define NB_WAKE 'w'

enum : uint32_t {
  NB_HELLO_PROC = 1,  // nb_hello_proc -> nb_reply_hello
  NB_HELLO_THREAD,    // nb_hello_thread -> nb_reply_status
  NB_MMAP,            // nb_mmap_req + 1 fd (the buffer) -> nb_reply_status
  NB_IOCTL,           // nb_ioctl_req + argument bytes -> nb_reply_status
  NB_WRITE_READ,      // nb_write_read_req + marshalled writes + fds -> nb_write_read_reply
};

struct nb_hdr {
  uint32_t type;
  uint32_t len;
};

struct nb_hello_proc {
  char device[32];  // "binder", "vndbinder"
  int32_t pid;
  uint32_t euid;  // the (virtual) effective uid: what binder reports to callees
};

struct nb_hello_thread {
  uint64_t proc_id;
  int32_t tid;
  uint32_t pad;
};

struct nb_reply_status {
  int32_t error;  // 0 or an errno value
  uint32_t pad;
};

struct nb_reply_hello {
  int32_t error;
  uint32_t pad;
  uint64_t proc_id;
};

struct nb_mmap_req {
  uint64_t addr;  // where the process mapped the buffer (read-only)
  uint64_t size;
};

struct nb_ioctl_req {
  uint32_t cmd;
  uint32_t size;  // argument bytes that follow
};

// The writes are the BINDER_WRITE_READ write buffer, command by command, with
// the data and offsets of each BC_TRANSACTION/BC_REPLY(_SG) copied in right
// after its binder_transaction_data(_sg), each padded to 8 bytes. The fds are
// those of the BINDER_TYPE_FD objects, in order.
struct nb_write_read_req {
  uint64_t read_size;
  uint32_t write_len;
  uint32_t nfds;
};

// Followed by read_len bytes for the read buffer, then nfixups uint64_t
// offsets into the process's buffer where the received fds (in order) go.
struct nb_write_read_reply {
  int32_t error;
  uint32_t write_cmds;  // commands consumed
  uint64_t read_len;
  uint32_t nfixups;
  uint32_t pad;
};
