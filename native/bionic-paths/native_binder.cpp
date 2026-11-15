/*
 * /dev/binder for native mode.
 *
 * An app can't get a binder context of its own from the kernel, so programs
 * running from ANDROID_NATIVE_ROOT talk to nbinder, a userspace binder driver
 * listening on the root's /dev/socket/nbinder. open("/dev/binder") returns a
 * socket connected to it; ioctl() and mmap() on that fd are carried over the
 * socket (see nbinder_proto.h). libbinder itself is unchanged.
 *
 * Built into libc (native_root.cpp routes open/ioctl/mmap/close here) and, with
 * NB_HOST defined, into nbinder's host test.
 */

#include <errno.h>
#include <fcntl.h>
#include <linux/android/binder.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>

#include "nbinder_proto.h"

#if defined(NB_HOST)
static void* nb_raw_mmap(void* addr, size_t size, int prot, int flags, int fd) {
  return mmap(addr, size, prot, flags, fd, 0);
}
static pid_t nb_gettid() { return static_cast<pid_t>(syscall(SYS_gettid)); }
#elif defined(__LP64__)
extern "C" void* __nrp_mmap(void*, size_t, int, int, int, off_t);
static void* nb_raw_mmap(void* addr, size_t size, int prot, int flags, int fd) {
  return __nrp_mmap(addr, size, prot, flags, fd, 0);
}
static pid_t nb_gettid() { return gettid(); }
#else
extern "C" void* __nrp_mmap2(void*, size_t, int, int, int, long);
static void* nb_raw_mmap(void* addr, size_t size, int prot, int flags, int fd) {
  return __nrp_mmap2(addr, size, prot, flags, fd, 0);
}
static pid_t nb_gettid() { return gettid(); }
#endif

namespace {

struct nb_proc {
  int fd;  // -1: free slot
  uint64_t id;
  char* rw;  // writable view of the buffer, for fd fixups
  size_t size;
};

struct nb_thread {
  int fd;  // the process's binder fd, -1: free slot
  pid_t tid;
  int sock;
};

constexpr int kMaxProcs = 8;
constexpr int kMaxThreads = 512;

nb_proc g_procs[kMaxProcs] = {{-1, 0, nullptr, 0}, {-1, 0, nullptr, 0}, {-1, 0, nullptr, 0},
                              {-1, 0, nullptr, 0}, {-1, 0, nullptr, 0}, {-1, 0, nullptr, 0},
                              {-1, 0, nullptr, 0}, {-1, 0, nullptr, 0}};
nb_thread g_threads[kMaxThreads];
bool g_threads_ready;
int g_nprocs;  // read without the lock, as a fast path
pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

nb_proc* find_proc_locked(int fd) {
  for (auto& p : g_procs) {
    if (p.fd == fd) return &p;
  }
  return nullptr;
}

bool send_all(int sock, const void* data, size_t len, const int* fds, size_t nfds) {
  const char* p = static_cast<const char*>(data);
  bool first = true;
  while (len > 0) {
    struct iovec iov = {const_cast<char*>(p), len};
    struct msghdr msg = {};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    alignas(struct cmsghdr) char control[CMSG_SPACE(sizeof(int) * NB_MAX_FDS)];
    if (first && nfds > 0) {
      msg.msg_control = control;
      msg.msg_controllen = CMSG_SPACE(sizeof(int) * nfds);
      struct cmsghdr* c = CMSG_FIRSTHDR(&msg);
      c->cmsg_level = SOL_SOCKET;
      c->cmsg_type = SCM_RIGHTS;
      c->cmsg_len = CMSG_LEN(sizeof(int) * nfds);
      memcpy(CMSG_DATA(c), fds, sizeof(int) * nfds);
    }
    ssize_t n = sendmsg(sock, &msg, MSG_NOSIGNAL);
    if (n < 0) {
      if (errno == EINTR) continue;
      return false;
    }
    first = false;
    p += n;
    len -= static_cast<size_t>(n);
  }
  return true;
}

// Receive exactly len bytes; fds arriving with them are appended to fds.
bool recv_all(int sock, void* data, size_t len, int* fds, size_t* nfds) {
  char* p = static_cast<char*>(data);
  while (len > 0) {
    struct iovec iov = {p, len};
    struct msghdr msg = {};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    alignas(struct cmsghdr) char control[CMSG_SPACE(sizeof(int) * NB_MAX_FDS)];
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);
    ssize_t n = recvmsg(sock, &msg, MSG_CMSG_CLOEXEC);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) {
      if (n == 0) errno = ECONNRESET;
      return false;
    }
    for (struct cmsghdr* c = CMSG_FIRSTHDR(&msg); c != nullptr; c = CMSG_NXTHDR(&msg, c)) {
      if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS) continue;
      size_t k = (c->cmsg_len - CMSG_LEN(0)) / sizeof(int);
      const int* got = reinterpret_cast<const int*>(CMSG_DATA(c));
      for (size_t i = 0; i < k; i++) {
        if (fds != nullptr && *nfds < NB_MAX_FDS) {
          fds[(*nfds)++] = got[i];
        } else {
          close(got[i]);
        }
      }
    }
    p += n;
    len -= static_cast<size_t>(n);
  }
  return true;
}

bool send_msg(int sock, uint32_t type, const void* a, size_t alen, const void* b, size_t blen,
              const int* fds, size_t nfds) {
  size_t total = sizeof(nb_hdr) + alen + blen;
  char* buf = static_cast<char*>(malloc(total));
  if (buf == nullptr) return false;
  nb_hdr h = {type, static_cast<uint32_t>(alen + blen)};
  memcpy(buf, &h, sizeof(h));
  if (alen) memcpy(buf + sizeof(h), a, alen);
  if (blen) memcpy(buf + sizeof(h) + alen, b, blen);
  bool ok = send_all(sock, buf, total, fds, nfds);
  free(buf);
  return ok;
}

// Read one reply: header, then `fixed` bytes into `out`; the remaining
// payload (if any) is returned in *extra (malloc'ed) with its length.
bool recv_reply(int sock, void* out, size_t fixed, char** extra, size_t* extra_len, int* fds,
                size_t* nfds) {
  nb_hdr h;
  if (!recv_all(sock, &h, sizeof(h), fds, nfds)) return false;
  if (h.len < fixed) {
    errno = EPROTO;
    return false;
  }
  if (!recv_all(sock, out, fixed, fds, nfds)) return false;
  size_t rest = h.len - fixed;
  if (extra != nullptr) {
    *extra = nullptr;
    *extra_len = rest;
    if (rest > 0) {
      *extra = static_cast<char*>(malloc(rest));
      if (*extra == nullptr || !recv_all(sock, *extra, rest, fds, nfds)) return false;
    }
  } else if (rest > 0) {
    char sink[256];
    while (rest > 0) {
      size_t k = rest < sizeof(sink) ? rest : sizeof(sink);
      if (!recv_all(sock, sink, k, fds, nfds)) return false;
      rest -= k;
    }
  }
  return true;
}

int nb_connect(int flags) {
  int sock = socket(AF_UNIX, SOCK_STREAM | ((flags & O_CLOEXEC) ? SOCK_CLOEXEC : 0), 0);
  if (sock == -1) return -1;
  struct sockaddr_un addr = {};
  addr.sun_family = AF_UNIX;
  const char* path = NB_SOCKET;
#if defined(NB_HOST)
  if (getenv("NBINDER_SOCKET") != nullptr) path = getenv("NBINDER_SOCKET");
#endif
  strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
  if (connect(sock, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) != 0) {
    int e = errno;
    close(sock);
    errno = e;
    return -1;
  }
  return sock;
}

// This thread's connection for binder fd `fd` (-1 and errno on failure).
int thread_sock(int fd, uint64_t proc_id) {
  pid_t tid = nb_gettid();
  pthread_mutex_lock(&g_lock);
  if (!g_threads_ready) {
    for (auto& t : g_threads) t.fd = -1;
    g_threads_ready = true;
  }
  for (auto& t : g_threads) {
    if (t.fd == fd && t.tid == tid) {
      int s = t.sock;
      pthread_mutex_unlock(&g_lock);
      return s;
    }
  }
  pthread_mutex_unlock(&g_lock);

  int sock = nb_connect(O_CLOEXEC);
  if (sock == -1) return -1;
  nb_hello_thread hello = {proc_id, tid, 0};
  nb_reply_status st;
  if (!send_msg(sock, NB_HELLO_THREAD, &hello, sizeof(hello), nullptr, 0, nullptr, 0) ||
      !recv_reply(sock, &st, sizeof(st), nullptr, nullptr, nullptr, nullptr)) {
    close(sock);
    errno = EIO;
    return -1;
  }
  if (st.error != 0) {
    close(sock);
    errno = st.error;
    return -1;
  }
  pthread_mutex_lock(&g_lock);
  for (auto& t : g_threads) {
    if (t.fd == -1) {
      t.fd = fd;
      t.tid = tid;
      t.sock = sock;
      pthread_mutex_unlock(&g_lock);
      return sock;
    }
  }
  pthread_mutex_unlock(&g_lock);
  close(sock);
  errno = EMFILE;
  return -1;
}

void drop_thread_sock(int fd, pid_t tid) {
  int sock = -1;
  pthread_mutex_lock(&g_lock);
  for (auto& t : g_threads) {
    if (t.fd == fd && t.tid == tid) {
      sock = t.sock;
      t.fd = -1;
      break;
    }
  }
  pthread_mutex_unlock(&g_lock);
  if (sock != -1) close(sock);
}

bool proc_info(int fd, uint64_t* id, char** rw, size_t* size) {
  pthread_mutex_lock(&g_lock);
  nb_proc* p = find_proc_locked(fd);
  if (p != nullptr) {
    *id = p->id;
    if (rw) *rw = p->rw;
    if (size) *size = p->size;
  }
  pthread_mutex_unlock(&g_lock);
  return p != nullptr;
}

size_t align8(size_t n) {
  return (n + 7) & ~static_cast<size_t>(7);
}

bool is_transaction_cmd(uint32_t cmd) {
  return cmd == BC_TRANSACTION || cmd == BC_REPLY || cmd == BC_TRANSACTION_SG ||
         cmd == BC_REPLY_SG;
}

struct growbuf {
  char* data = nullptr;
  size_t len = 0;
  size_t cap = 0;
  bool ok = true;
  void put(const void* p, size_t n) {
    if (!ok || n == 0) return;
    if (len + n > cap) {
      size_t c = cap ? cap * 2 : 1024;
      while (c < len + n) c *= 2;
      char* d = static_cast<char*>(realloc(data, c));
      if (d == nullptr) {
        ok = false;
        return;
      }
      data = d;
      cap = c;
    }
    memcpy(data + len, p, n);
    len += n;
  }
  // Pad a block of n bytes just put to a multiple of 8.
  void pad8(size_t n) {
    static const char zero[8] = {};
    put(zero, align8(n) - n);
  }
  ~growbuf() { free(data); }
};

int write_read(int fd, struct binder_write_read* bwr) {
  uint64_t id;
  char* rw;
  size_t size;
  if (!proc_info(fd, &id, &rw, &size)) {
    errno = EBADF;
    return -1;
  }
  // Wake-up bytes on the main connection have done their job (poll()).
  char sink[64];
  while (recv(fd, sink, sizeof(sink), MSG_DONTWAIT) > 0) {
  }
  int sock = thread_sock(fd, id);
  if (sock == -1) return -1;

  // Marshal the write buffer.
  const char* w = reinterpret_cast<const char*>(bwr->write_buffer) + bwr->write_consumed;
  size_t wlen = bwr->write_size > bwr->write_consumed ? bwr->write_size - bwr->write_consumed : 0;
  growbuf out;
  int fds[NB_MAX_FDS];
  size_t nfds = 0;
  size_t pos = 0;
  while (pos < wlen) {
    if (wlen - pos < sizeof(uint32_t)) {
      errno = EINVAL;
      return -1;
    }
    uint32_t cmd;
    memcpy(&cmd, w + pos, sizeof(cmd));
    size_t argsz = _IOC_SIZE(cmd);
    if (wlen - pos - sizeof(cmd) < argsz) {
      errno = EINVAL;
      return -1;
    }
    out.put(w + pos, sizeof(cmd) + argsz);
    if (is_transaction_cmd(cmd)) {
      struct binder_transaction_data tr;
      memcpy(&tr, w + pos + sizeof(cmd), sizeof(tr));
      const char* data = reinterpret_cast<const char*>(tr.data.ptr.buffer);
      const char* offs = reinterpret_cast<const char*>(tr.data.ptr.offsets);
      if ((tr.data_size && data == nullptr) || (tr.offsets_size && offs == nullptr) ||
          tr.data_size > (1u << 24) || tr.offsets_size > (1u << 20)) {
        errno = EINVAL;
        return -1;
      }
      out.put(data, tr.data_size);
      out.pad8(tr.data_size);
      out.put(offs, tr.offsets_size);
      out.pad8(tr.offsets_size);
      for (size_t i = 0; i + sizeof(binder_size_t) <= tr.offsets_size; i += sizeof(binder_size_t)) {
        binder_size_t off;
        memcpy(&off, offs + i, sizeof(off));
        if (off + sizeof(struct binder_fd_object) > tr.data_size) continue;
        struct binder_fd_object fo;
        memcpy(&fo, data + off, sizeof(fo));
        if (fo.hdr.type != BINDER_TYPE_FD) continue;
        if (nfds == NB_MAX_FDS) {
          errno = EMFILE;
          return -1;
        }
        fds[nfds++] = static_cast<int>(fo.fd);
      }
    }
    pos += sizeof(cmd) + argsz;
  }
  if (!out.ok) {
    errno = ENOMEM;
    return -1;
  }

  nb_write_read_req req = {bwr->read_size > bwr->read_consumed ? bwr->read_size - bwr->read_consumed : 0,
                           static_cast<uint32_t>(out.len), static_cast<uint32_t>(nfds)};
  if (!send_msg(sock, NB_WRITE_READ, &req, sizeof(req), out.data, out.len, fds, nfds)) {
    errno = EIO;
    return -1;
  }
  nb_write_read_reply rep;
  char* extra = nullptr;
  size_t extra_len = 0;
  int* rfds = fds;  // the sent fds are no longer needed: reuse the array
  size_t nrfds = 0;
  bool ok = recv_reply(sock, &rep, sizeof(rep), &extra, &extra_len, rfds, &nrfds);
  if (!ok || rep.read_len > req.read_size ||
      extra_len != rep.read_len + rep.nfixups * sizeof(uint64_t)) {
    free(extra);
    for (size_t i = 0; i < nrfds; i++) close(rfds[i]);
    errno = EIO;
    return -1;
  }
  // Received fds go where the driver would have installed them.
  for (uint32_t i = 0; i < rep.nfixups; i++) {
    uint64_t off;
    memcpy(&off, extra + rep.read_len + i * sizeof(uint64_t), sizeof(off));
    int32_t v = i < nrfds ? rfds[i] : -1;
    if (rw != nullptr && off + sizeof(v) <= size) memcpy(rw + off, &v, sizeof(v));
  }
  for (size_t i = rep.nfixups; i < nrfds; i++) close(rfds[i]);
  if (rep.read_len) {
    memcpy(reinterpret_cast<char*>(bwr->read_buffer) + bwr->read_consumed, extra, rep.read_len);
  }
  free(extra);
  bwr->read_consumed += rep.read_len;
  // Commands consumed -> bytes of the caller's buffer.
  size_t consumed = 0;
  for (uint32_t i = 0; i < rep.write_cmds && consumed < wlen; i++) {
    uint32_t cmd;
    memcpy(&cmd, w + consumed, sizeof(cmd));
    consumed += sizeof(cmd) + _IOC_SIZE(cmd);
  }
  bwr->write_consumed += consumed;
  if (rep.error != 0) {
    errno = rep.error;
    return -1;
  }
  return 0;
}

int simple_ioctl(int fd, unsigned cmd, const void* arg, size_t size) {
  uint64_t id;
  if (!proc_info(fd, &id, nullptr, nullptr)) {
    errno = EBADF;
    return -1;
  }
  int sock = thread_sock(fd, id);
  if (sock == -1) return -1;
  nb_ioctl_req req = {cmd, static_cast<uint32_t>(arg ? size : 0)};
  nb_reply_status st;
  if (!send_msg(sock, NB_IOCTL, &req, sizeof(req), arg, arg ? size : 0, nullptr, 0) ||
      !recv_reply(sock, &st, sizeof(st), nullptr, nullptr, nullptr, nullptr)) {
    errno = EIO;
    return -1;
  }
  if (st.error != 0) {
    errno = st.error;
    return -1;
  }
  return 0;
}

}  // namespace

// Whether fd is a binder fd from nb_open. Lock-free: called for every
// ioctl/mmap/close in the process.
bool nb_is_binder(int fd) {
  if (__atomic_load_n(&g_nprocs, __ATOMIC_ACQUIRE) == 0) return false;
  for (auto& p : g_procs) {
    if (__atomic_load_n(&p.fd, __ATOMIC_RELAXED) == fd) return true;
  }
  return false;
}

// "binder" for /dev/binder and /dev/binderfs/binder; nullptr for other paths.
// hwbinder (HIDL) is not supported: "" (the open fails).
const char* nb_device(const char* path) {
  const char* name = nullptr;
  if (strncmp(path, "/dev/binderfs/", 14) == 0) {
    name = path + 14;
  } else if (strncmp(path, "/dev/", 5) == 0) {
    name = path + 5;
  } else {
    return nullptr;
  }
  if (strcmp(name, "binder") == 0 || strcmp(name, "vndbinder") == 0) return name;
  if (strcmp(name, "hwbinder") == 0) return "";
  return nullptr;
}

int nb_open(const char* device, int flags) {
  if (device[0] == '\0' || strlen(device) >= sizeof(nb_hello_proc::device)) {
    errno = ENOENT;
    return -1;
  }
  int sock = nb_connect(flags);
  if (sock == -1) {
    errno = ENOENT;  // nbinder isn't running: like a device that isn't there
    return -1;
  }
  nb_hello_proc hello = {};
  strcpy(hello.device, device);
  hello.pid = getpid();
  hello.euid = geteuid();  // native_root.cpp's virtual uid
  nb_reply_hello rep = {};
  if (!send_msg(sock, NB_HELLO_PROC, &hello, sizeof(hello), nullptr, 0, nullptr, 0) ||
      !recv_reply(sock, &rep, sizeof(rep), nullptr, nullptr, nullptr, nullptr) || rep.error != 0) {
    int e = rep.error ? rep.error : EIO;
    close(sock);
    errno = e;
    return -1;
  }
  pthread_mutex_lock(&g_lock);
  nb_proc* slot = find_proc_locked(-1);
  if (slot == nullptr) {
    pthread_mutex_unlock(&g_lock);
    close(sock);
    errno = EMFILE;
    return -1;
  }
  slot->id = rep.proc_id;
  slot->rw = nullptr;
  slot->size = 0;
  __atomic_store_n(&slot->fd, sock, __ATOMIC_RELEASE);
  __atomic_add_fetch(&g_nprocs, 1, __ATOMIC_RELEASE);
  pthread_mutex_unlock(&g_lock);
  return sock;
}

void* nb_mmap(int fd, void* addr, size_t size, int /*prot*/, int flags) {
  uint64_t id;
  char* old_rw;
  size_t old_size;
  if (!proc_info(fd, &id, &old_rw, &old_size)) {
    errno = EBADF;
    return MAP_FAILED;
  }
  if (old_rw != nullptr || size == 0 || size > (4u << 20)) {
    errno = EINVAL;
    return MAP_FAILED;
  }
  int mfd = memfd_create("nbinder", MFD_CLOEXEC);
  if (mfd == -1) return MAP_FAILED;
  if (ftruncate(mfd, static_cast<off_t>(size)) != 0) {
    int e = errno;
    close(mfd);
    errno = e;
    return MAP_FAILED;
  }
  // Shared, so that nbinder's writes show up; read-only, as from the driver.
  void* ro = nb_raw_mmap(addr, size, PROT_READ, MAP_SHARED | (flags & MAP_FIXED), mfd);
  void* rw = nb_raw_mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, mfd);
  if (ro == MAP_FAILED || rw == MAP_FAILED) {
    int e = errno;
    if (ro != MAP_FAILED) munmap(ro, size);
    if (rw != MAP_FAILED) munmap(rw, size);
    close(mfd);
    errno = e;
    return MAP_FAILED;
  }
  int sock = thread_sock(fd, id);
  nb_mmap_req req = {reinterpret_cast<uintptr_t>(ro), size};
  nb_reply_status st = {EIO, 0};
  bool ok = sock != -1 &&
            send_msg(sock, NB_MMAP, &req, sizeof(req), nullptr, 0, &mfd, 1) &&
            recv_reply(sock, &st, sizeof(st), nullptr, nullptr, nullptr, nullptr) && st.error == 0;
  close(mfd);
  if (!ok) {
    munmap(ro, size);
    munmap(rw, size);
    errno = st.error ? st.error : EIO;
    return MAP_FAILED;
  }
  pthread_mutex_lock(&g_lock);
  nb_proc* p = find_proc_locked(fd);
  if (p != nullptr) {
    p->rw = static_cast<char*>(rw);
    p->size = size;
  }
  pthread_mutex_unlock(&g_lock);
  return ro;
}

int nb_ioctl(int fd, unsigned long request, void* arg) {
  unsigned cmd = static_cast<unsigned>(request);
  switch (cmd) {
    case BINDER_VERSION:
      if (arg == nullptr) {
        errno = EFAULT;
        return -1;
      }
      static_cast<struct binder_version*>(arg)->protocol_version = BINDER_CURRENT_PROTOCOL_VERSION;
      return 0;
    case BINDER_WRITE_READ:
      if (arg == nullptr) {
        errno = EFAULT;
        return -1;
      }
      return write_read(fd, static_cast<struct binder_write_read*>(arg));
    case BINDER_SET_MAX_THREADS:
    case BINDER_SET_CONTEXT_MGR:
    case BINDER_SET_CONTEXT_MGR_EXT:
    case BINDER_ENABLE_ONEWAY_SPAM_DETECTION:
      if (arg == nullptr) {
        errno = EFAULT;
        return -1;
      }
      return simple_ioctl(fd, cmd, arg, _IOC_SIZE(cmd));
    case BINDER_THREAD_EXIT: {
      int r = simple_ioctl(fd, cmd, nullptr, 0);
      drop_thread_sock(fd, nb_gettid());
      return r;
    }
    case BINDER_GET_EXTENDED_ERROR:
      if (arg == nullptr) {
        errno = EFAULT;
        return -1;
      }
      memset(arg, 0, sizeof(struct binder_extended_error));
      return 0;
    default:
      errno = EINVAL;
      return -1;
  }
}

// The binder fd is being closed: forget it and its thread connections.
void nb_forget(int fd) {
  pthread_mutex_lock(&g_lock);
  nb_proc* p = find_proc_locked(fd);
  if (p != nullptr) {
    if (p->rw != nullptr) munmap(p->rw, p->size);
    p->rw = nullptr;
    __atomic_store_n(&p->fd, -1, __ATOMIC_RELEASE);
    __atomic_sub_fetch(&g_nprocs, 1, __ATOMIC_RELEASE);
    if (g_threads_ready) {
      for (auto& t : g_threads) {
        if (t.fd == fd) {
          close(t.sock);  // not a binder fd: close() doesn't come back here
          t.fd = -1;
        }
      }
    }
  }
  pthread_mutex_unlock(&g_lock);
}
