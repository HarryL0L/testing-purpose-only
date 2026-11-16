/*
 * Path prefixing for running an Android system image from an app's folder
 * (UMLDroid "native" mode, Termux-style).
 *
 * When ANDROID_NATIVE_ROOT is set (an absolute host directory holding the
 * image's /system, /vendor, /apex, ...), absolute paths passed to libc are
 * looked up under it: "/system/bin/sh" becomes "$ANDROID_NATIVE_ROOT/system/bin/sh".
 * /proc, /sys and /dev stay as they are (except Android's property area and
 * sockets, which belong to the image). Paths that libc hands back (readlink,
 * getcwd) have the prefix removed again. When ANDROID_NATIVE_ROOT is unset,
 * every function here behaves exactly like the stock one.
 *
 * Each wrapper replaces a system call stub from SYSCALLS.TXT; the stubs
 * themselves are renamed to __nrp_<name> by native/bionic-paths/apply.py.
 *
 * execve() also starts dynamic programs through the image's own linker
 * ("linker64 PROGRAM ARGS..."), because the kernel would otherwise load the
 * host's /system/bin/linker64, and handles "#!" scripts the same way.
 *
 * /dev/binder and /dev/vndbinder are nbinder's (native_binder.cpp): open,
 * ioctl, mmap and close on them go there, never to the phone's binder.
 * /sys/fs/selinux is the root's: a stand-in that tells libselinux SELinux is
 * permissive (an app can't consult the phone's policy for its own services).
 *
 * User and group ids are virtual, as with proot -0: programs see root (or
 * the ids in ANDROID_NATIVE_IDS) instead of the app's uid, set*id() change
 * the virtual ids, files owned by the app look owned by root and chown() of
 * them succeeds. Android's own services check these ids (an app
 * uid may not register services, for one); the kernel still checks the real
 * ones.
 */

#include <alloca.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <stddef.h>
#include <linux/netlink.h>
#include <linux/sync_file.h>
#include <stdio.h>
#include <sys/eventfd.h>
#include <sys/inotify.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/types.h>
#include <sys/syscall.h>
#include <sys/utsname.h>
#include <sys/xattr.h>
#include <time.h>
#include <unistd.h>

#include "native_bpf.h"
#include "nbinder_proto.h"

extern "C" {
int __nrp_openat(int, const char*, int, int);
int __nrp_faccessat(int, const char*, int);
int __nrp_fchmodat(int, const char*, mode_t);
int __nrp_fchownat(int, const char*, uid_t, gid_t, int);
int __nrp_fstatat64(int, const char*, struct stat*, int);
int __nrp_linkat(int, const char*, int, const char*, int);
int __nrp_mkdirat(int, const char*, mode_t);
int __nrp_mknodat(int, const char*, mode_t, dev_t);
ssize_t __nrp_readlinkat(int, const char*, char*, size_t);
int __nrp_renameat2(int, const char*, int, const char*, unsigned);
int __nrp_symlinkat(const char*, int, const char*);
int __nrp_unlinkat(int, const char*, int);
int __nrp_utimensat(int, const char*, const struct timespec[2], int);
int __nrp_truncate(const char*, off_t);
#if !defined(__LP64__)
int __nrp_truncate64(const char*, off64_t);
int __nrp_statfs64(const char*, size_t, struct statfs*);
#else
int __nrp_statfs(const char*, struct statfs*);
#endif
int __nrp_chdir(const char*);
int __nrp_getcwd(char*, size_t);
int __nrp_setxattr(const char*, const char*, const void*, size_t, int);
int __nrp_lsetxattr(const char*, const char*, const void*, size_t, int);
int __nrp_fsetxattr(int, const char*, const void*, size_t, int);
ssize_t __nrp_fgetxattr(int, const char*, void*, size_t);
ssize_t __nrp_getxattr(const char*, const char*, void*, size_t);
ssize_t __nrp_lgetxattr(const char*, const char*, void*, size_t);
ssize_t __nrp_listxattr(const char*, char*, size_t);
ssize_t __nrp_llistxattr(const char*, char*, size_t);
int __nrp_removexattr(const char*, const char*);
int __nrp_lremovexattr(const char*, const char*);
int __nrp_statx(int, const char*, int, unsigned, struct statx*);
int __nrp_inotify_add_watch(int, const char*, uint32_t);
int __nrp_timerfd_create(clockid_t, int);
int __nrp_execve(const char*, char* const*, char* const*);
int __nrp_bind(int, const struct sockaddr*, socklen_t);
int __nrp_connect(int, const struct sockaddr*, socklen_t);
int __nrp_getsockname(int, struct sockaddr*, socklen_t*);
int __nrp_getsockopt(int, int, int, void*, socklen_t*);
int __nrp_socket(int, int, int);
int __nrp_getpeername(int, struct sockaddr*, socklen_t*);
int __nrp_ioctl(int, int, void*);
uid_t __nrp_getuid();
uid_t __nrp_geteuid();
gid_t __nrp_getgid();
gid_t __nrp_getegid();
int __nrp_getresuid(uid_t*, uid_t*, uid_t*);
int __nrp_getresgid(gid_t*, gid_t*, gid_t*);
int __nrp_getgroups(int, gid_t*);
int __nrp_setuid(uid_t);
int __nrp_setgid(gid_t);
int __nrp_setreuid(uid_t, uid_t);
int __nrp_setregid(gid_t, gid_t);
int __nrp_setresuid(uid_t, uid_t, uid_t);
int __nrp_setresgid(gid_t, gid_t, gid_t);
int __nrp_setgroups(int, const gid_t*);
int __nrp_fchown(int, uid_t, gid_t);
int __nrp_fstat64(int, struct stat*);
int __nrp_uname(struct utsname*);
long __nrp_syscall(long, ...);
int __nrp_capget(void*, void*);
int __nrp_capset(void*, const void*);
int __nrp_prctl(int, unsigned long, unsigned long, unsigned long, unsigned long);
int __nrp_mount(const char*, const char*, const char*, unsigned long, const void*);
int __nrp_umount2(const char*, int);
int __nrp_unshare(int);
int __nrp_setns(int, int);
int __nrp_close(int);
#if defined(__LP64__)
void* __nrp_mmap(void*, size_t, int, int, int, off_t);
#else
void* __nrp_mmap2(void*, size_t, int, int, int, long);
#endif
}

// native_binder.cpp
bool nb_is_binder(int fd);
const char* nb_device(const char* path);
int nb_open(const char* device, int flags);
void* nb_mmap(int fd, void* addr, size_t size, int prot, int flags);
int nb_ioctl(int fd, unsigned long request, void* arg);
void nb_forget(int fd);

static constexpr char kRootVar[] = "ANDROID_NATIVE_ROOT";

// The host root, cached on first use. 0: not looked up yet, 1: set, 2: unset.
static char g_root[PATH_MAX / 2];
static size_t g_root_len;
static int g_root_state;

static const char* nr_root(size_t* len) {
  if (g_root_state == 0) {
    // Before libc has set up environ (very early start-up), look again later.
    if (environ == nullptr) return nullptr;
    const char* e = getenv(kRootVar);
    size_t n = (e != nullptr && e[0] == '/') ? strlen(e) : 0;
    while (n > 1 && e[n - 1] == '/') n--;
    if (n > 1 && n < sizeof(g_root)) {
      memcpy(g_root, e, n);
      g_root[n] = '\0';
      g_root_len = n;
      g_root_state = 1;
    } else {
      g_root_state = 2;
    }
  }
  if (g_root_state != 1) return nullptr;
  *len = g_root_len;
  return g_root;
}

// Whether `p` is `dir` or something under it.
static bool nr_under(const char* p, const char* dir, size_t dir_len) {
  return strncmp(p, dir, dir_len) == 0 && (p[dir_len] == '\0' || p[dir_len] == '/');
}

static bool nr_host_path(const char* p) {
  if (nr_under(p, "/proc", 5)) return true;
  if (nr_under(p, "/sys", 4)) {
    // The phone's tracefs: an app may open trace_marker but not stat it, and
    // zygote stats every fd before forking. Tracing stays off in the root.
    // Cgroups too: Android puts each process in its own group, which an app
    // may not create in the phone's hierarchy; in the root they're folders.
    return !nr_under(p, "/sys/fs/selinux", 15) && !nr_under(p, "/sys/kernel/tracing", 19) &&
           !nr_under(p, "/sys/kernel/debug", 17) && !nr_under(p, "/sys/fs/cgroup", 14) &&
           // the suspend service must never put the phone to sleep
           !nr_under(p, "/sys/power", 10) &&
           // BPF objects are files in the root (native_bpf.cpp)
           !nr_under(p, "/sys/fs/bpf", 11);
  }
  if (nr_under(p, "/dev", 4)) {
    // Android's property area and sockets come from the image; binderfs
    // (driver feature files) isn't the phone's either.
    // /dev/kmsg is a file in the root (android::base's KernelLogger writes there).
    // /dev/input: the root's own (input reaches Android through the viewer;
    // the phone's devices aren't the app's to read).
    return !nr_under(p, "/dev/__properties__", 19) && !nr_under(p, "/dev/socket", 11) &&
           !nr_under(p, "/dev/input", 10) &&
           !nr_under(p, "/dev/binderfs", 13) && strcmp(p, "/dev/kmsg") != 0 &&
           !nr_under(p, "/dev/blkio", 10) && !nr_under(p, "/dev/cpuctl", 11) &&
           !nr_under(p, "/dev/cpuset", 11) && !nr_under(p, "/dev/stune", 10) &&
           !nr_under(p, "/dev/memcg", 10);
  }
  return false;
}

// libselinux only uses a selinuxfs whose statfs() says so.
static constexpr unsigned long kSelinuxMagic = 0xf97cff8c;
// So is bpffs for libbpf, which pins objects only there (native_bpf.cpp).
static constexpr unsigned long kBpfFsMagic = 0xcafe4a11;
static void nr_fake_selinuxfs(const char* path, struct statfs* buf) {
  size_t rl;
  if (path != nullptr && nr_root(&rl) != nullptr && nr_under(path, "/sys/fs/selinux", 15)) {
    buf->f_type = kSelinuxMagic;
  }
  if (path != nullptr && nr_root(&rl) != nullptr && nr_under(path, "/sys/fs/bpf", 11)) {
    buf->f_type = kBpfFsMagic;
  }
}

// Bytes needed to map `path` (see nr_map), or 0 when it stays as it is.
static size_t nr_map_size(const char* path) {
  size_t rl;
  const char* root = nr_root(&rl);
  if (root == nullptr || path == nullptr || path[0] != '/') return 0;
  if (nr_host_path(path) || nr_under(path, root, rl)) return 0;
  return rl + strlen(path) + 1;
}

// Map an absolute image path to the host path under the root, in `buf` of
// nr_map_size(path) bytes. Relative paths, host-only paths and paths already
// under the root are returned unchanged; a path too long to map gives nullptr.
static const char* nr_map(const char* path, char* buf, size_t size) {
  if (size == 0) return path;
  if (size > PATH_MAX) {
    errno = ENAMETOOLONG;
    return nullptr;
  }
  size_t rl;
  const char* root = nr_root(&rl);
  memcpy(buf, root, rl);
  memcpy(buf + rl, path, size - rl);
  return buf;
}

// Strip the root from the front of a returned path of `len` bytes (not
// necessarily NUL-terminated). Returns the new length.
static size_t nr_unmap(char* buf, size_t len) {
  size_t rl;
  const char* root = nr_root(&rl);
  if (root == nullptr || len < rl || memcmp(buf, root, rl) != 0) return len;
  if (len == rl) {
    buf[0] = '/';
    return 1;
  }
  if (buf[rl] != '/') return len;
  memmove(buf, buf + rl, len - rl);
  return len - rl;
}

// ------------------------------------------------- virtual uids and gids

struct nr_ids_t {
  uid_t r, e, s;
  gid_t gr, ge, gs;
};
static nr_ids_t g_ids;
static uid_t g_real_uid;
static gid_t g_real_gid;
static int g_ids_state;  // 0: not set up, 1: virtual, 2: real (no root)
static constexpr char kIdsVar[] = "ANDROID_NATIVE_IDS";

static void nr_publish_ids_init();

static bool nr_ids() {
  if (g_ids_state == 0) {
    size_t rl;
    if (nr_root(&rl) == nullptr) {
      if (environ == nullptr) return false;  // too early to tell
      g_ids_state = 2;
      return false;
    }
    g_real_uid = __nrp_getuid();
    g_real_gid = __nrp_getgid();
    nr_ids_t ids = {};
    // "r,e,s,gr,ge,gs" from the parent; otherwise root.
    if (const char* v = getenv(kIdsVar)) {
      unsigned long f[6];
      int n = 0;
      const char* q = v;
      while (n < 6 && *q != '\0') {
        char* end;
        f[n++] = strtoul(q, &end, 10);
        if (*end != ',') break;
        q = end + 1;
      }
      if (n == 6) {
        ids = {static_cast<uid_t>(f[0]), static_cast<uid_t>(f[1]), static_cast<uid_t>(f[2]),
               static_cast<gid_t>(f[3]), static_cast<gid_t>(f[4]), static_cast<gid_t>(f[5])};
      }
    }
    g_ids = ids;
    g_ids_state = 1;
    nr_publish_ids_init();
  }
  return g_ids_state == 1;
}

// Files the app owns look owned by root, as with proot -0 (most of an
// Android system's files are root's; zygote checks property files while
// running as uid 9999, which must still see them as root's).
static void nr_fake_owner(uid_t* uid, gid_t* gid) {
  if (!nr_ids()) return;
  if (*uid == g_real_uid) *uid = 0;
  if (*gid == g_real_gid) *gid = 0;
}

// Recorded owners and labels, as fakeroot keeps them: chown() and setting
// security.selinux, which an app may not do, are stored in user xattrs of
// the file instead, and stat()/getxattr() report them. Installers (a17root's
// fsck.erofs --preserve --xattrs) record the image's own owners and labels;
// checks like the connectivity module's on its BPF objects then pass.
static constexpr char kOwnerXattr[] = "user.nmode.owner";  // uint32_t uid, gid

// A path getxattr() can use for (dirfd, path): an fd-relative path goes
// through /proc/self/fd (NR_AT_PATH sizes its buffer on the stack). nullptr
// means `dirfd` itself.
static size_t nr_at_size(int dirfd, const char* path) {
  if (path == nullptr || path[0] == '\0' || path[0] == '/' || dirfd == AT_FDCWD) return 1;
  return strlen(path) + 32;
}

static const char* nr_at_path(int dirfd, const char* path, int flags, char* buf, size_t size) {
  if (path == nullptr || (path[0] == '\0' && (flags & AT_EMPTY_PATH))) return nullptr;
  if (path[0] == '/' || dirfd == AT_FDCWD) return path;
  snprintf(buf, size, "/proc/self/fd/%d/%s", dirfd, path);
  return buf;
}

#define NR_AT_PATH(var, dirfd, path, flags)                                 \
  size_t var##_size = nr_at_size(dirfd, path);                              \
  char* var##_buf = static_cast<char*>(alloca(var##_size));                 \
  const char* var = nr_at_path(dirfd, path, flags, var##_buf, var##_size)

static ssize_t nr_raw_getxattr_at(int dirfd, const char* mapped, int flags, const char* name,
                                  void* value, size_t size) {
  NR_AT_PATH(p, dirfd, mapped, flags);
  if (p == nullptr) return __nrp_fgetxattr(dirfd, name, value, size);
  return (flags & AT_SYMLINK_NOFOLLOW) ? __nrp_lgetxattr(p, name, value, size)
                                       : __nrp_getxattr(p, name, value, size);
}

// Setting a user xattr needs write access to the file, even for its owner
// (root on a computer bypasses that; an app on the phone doesn't). bpfloader
// makes each BPF map pin read-only before it chowns and labels it, so the
// records (owner, labels) didn't stick there: a file of ours that isn't
// writable gets the owner's write bit for the moment of the set. `fd` is used
// when `p` is null; `nofollow` as for lsetxattr().
template <typename Set>
static int nr_set_writable(Set set, int fd, const char* p, bool nofollow) {
  int r = set();
  if (r == 0 || errno != EACCES || !nr_ids()) return r;
  int saved = errno;
  struct stat st;
  int sr = p == nullptr ? __nrp_fstat64(fd, &st)
                        : __nrp_fstatat64(AT_FDCWD, p, &st, nofollow ? AT_SYMLINK_NOFOLLOW : 0);
  if (sr != 0 || S_ISLNK(st.st_mode) || st.st_uid != g_real_uid || (st.st_mode & S_IWUSR)) {
    errno = saved;
    return r;
  }
  mode_t mode = st.st_mode & 07777;
  if ((p == nullptr ? fchmod(fd, mode | S_IWUSR) : __nrp_fchmodat(AT_FDCWD, p, mode | S_IWUSR)) != 0) {
    errno = saved;
    return r;
  }
  r = set();
  int e = errno;
  if (p == nullptr) {
    fchmod(fd, mode);
  } else {
    __nrp_fchmodat(AT_FDCWD, p, mode);
  }
  errno = e;
  return r;
}

static int nr_raw_setxattr_at(int dirfd, const char* mapped, int flags, const char* name,
                              const void* value, size_t size) {
  NR_AT_PATH(p, dirfd, mapped, flags);
  bool nofollow = (flags & AT_SYMLINK_NOFOLLOW) != 0;
  return nr_set_writable([&] {
    if (p == nullptr) return __nrp_fsetxattr(dirfd, name, value, size, 0);
    return nofollow ? __nrp_lsetxattr(p, name, value, size, 0)
                    : __nrp_setxattr(p, name, value, size, 0);
  }, dirfd, p, nofollow);
}

// The owner to report for a file stat() found (mapped path or fd). A file
// with no record (a pipe, a new file) leaves errno as it was: stat()
// succeeded, and programs check errno after stdio's reads (toybox's grep
// reported ENODATA at the end of an empty pipe).
static void nr_owner_at(int dirfd, const char* mapped, int flags, uid_t* uid, gid_t* gid) {
  if (!nr_ids()) return;
  int saved = errno;
  uint32_t own[2];
  if (nr_raw_getxattr_at(dirfd, mapped, flags, kOwnerXattr, own, sizeof(own)) == sizeof(own)) {
    *uid = own[0];
    *gid = own[1];
  } else {
    nr_fake_owner(uid, gid);
  }
  errno = saved;
}

// chown() in native mode: record the new owner (-1 keeps a part).
static int nr_chown_at(int dirfd, const char* mapped, int flags, uid_t owner, gid_t group) {
  int saved = errno;
  uint32_t own[2] = {0, 0};
  if (owner == static_cast<uid_t>(-1) || group == static_cast<gid_t>(-1)) {
    struct stat st;
    NR_AT_PATH(p, dirfd, mapped, flags);
    int r = p == nullptr ? __nrp_fstat64(dirfd, &st)
                         : __nrp_fstatat64(AT_FDCWD, p, &st, flags & AT_SYMLINK_NOFOLLOW);
    if (r != 0) return -1;
    uid_t u = st.st_uid;
    gid_t g = st.st_gid;
    nr_owner_at(dirfd, mapped, flags, &u, &g);
    own[0] = u;
    own[1] = g;
  }
  if (owner != static_cast<uid_t>(-1)) own[0] = owner;
  if (group != static_cast<gid_t>(-1)) own[1] = group;
  // Symlinks can't carry user xattrs: their owner isn't kept (nothing checks it).
  nr_raw_setxattr_at(dirfd, mapped, flags, kOwnerXattr, own, sizeof(own));
  errno = saved;
  return 0;
}

extern "C" uid_t getuid() {
  return nr_ids() ? g_ids.r : __nrp_getuid();
}
extern "C" uid_t geteuid() {
  return nr_ids() ? g_ids.e : __nrp_geteuid();
}
extern "C" gid_t getgid() {
  return nr_ids() ? g_ids.gr : __nrp_getgid();
}
extern "C" gid_t getegid() {
  return nr_ids() ? g_ids.ge : __nrp_getegid();
}
extern "C" int getresuid(uid_t* r, uid_t* e, uid_t* s) {
  if (!nr_ids()) return __nrp_getresuid(r, e, s);
  *r = g_ids.r;
  *e = g_ids.e;
  *s = g_ids.s;
  return 0;
}
extern "C" int getresgid(gid_t* r, gid_t* e, gid_t* s) {
  if (!nr_ids()) return __nrp_getresgid(r, e, s);
  *r = g_ids.gr;
  *e = g_ids.ge;
  *s = g_ids.gs;
  return 0;
}
extern "C" int getgroups(int size, gid_t* list) {
  if (!nr_ids()) return __nrp_getgroups(size, list);
  (void)size;
  (void)list;
  return 0;  // no supplementary groups
}
extern "C" int setgroups(size_t size, const gid_t* list) {
  if (!nr_ids()) return __nrp_setgroups(static_cast<int>(size), list);
  if (g_ids.e != 0) {
    errno = EPERM;
    return -1;
  }
  return 0;
}

// ------------------------------------------- virtual ids seen by peers
//
// Daemons check who connects through the socket's kernel credentials
// (SO_PEERCRED: zygote takes requests only from system_server, uid 1000),
// which show the app's real uid. Each process whose virtual ids aren't
// root's records them in /dev/socket/.nids/<pid> (with its start time, so a
// reused pid doesn't match), and getsockopt(SO_PEERCRED) reports those.
static constexpr char kNidsDir[] = "/dev/socket/.nids";

static unsigned long long nr_start_time(pid_t pid) {
  char path[48], buf[512];
  // The main thread's entry: the same start time as the process's, and qemu
  // (local tests) fakes only a process's own /proc/<pid>/stat, without
  // updating it after fork.
  snprintf(path, sizeof(path), "/proc/%d/task/%d/stat", pid, pid);
  int fd = __nrp_openat(AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0);
  if (fd < 0) return 0;
  ssize_t n = read(fd, buf, sizeof(buf) - 1);
  close(fd);
  if (n <= 0) return 0;
  buf[n] = '\0';
  const char* p = strrchr(buf, ')');  // the command may hold spaces
  if (p == nullptr) return 0;
  // fields after ")": state is 3; starttime is 22
  for (int field = 2; field < 22 && *p; p++) {
    if (*p == ' ') field++;
  }
  return strtoull(p, nullptr, 10);
}

static void nr_publish_ids() {
  if (g_ids_state != 1) return;
  char path[64];
  snprintf(path, sizeof(path), "%s/%d", kNidsDir, getpid());
  if (g_ids.e == 0 && g_ids.ge == 0) {
    unlink(path);
    return;
  }
  mkdir(kNidsDir, 0777);
  char rec[96];
  int len = snprintf(rec, sizeof(rec), "%llu %u %u\n", nr_start_time(getpid()), g_ids.e, g_ids.ge);
  int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
  if (fd < 0) return;
  if (write(fd, rec, static_cast<size_t>(len)) < 0) unlink(path);
  close(fd);
}

// The virtual ids of process `pid`, if it recorded any (root's otherwise).
static void nr_peer_ids(pid_t pid, uid_t* uid, gid_t* gid) {
  char path[64], rec[96];
  snprintf(path, sizeof(path), "%s/%d", kNidsDir, pid);
  int fd = open(path, O_RDONLY | O_CLOEXEC);
  *uid = 0;
  *gid = 0;
  if (fd < 0) return;
  ssize_t n = read(fd, rec, sizeof(rec) - 1);
  close(fd);
  if (n <= 0) return;
  rec[n] = '\0';
  unsigned long long start;
  unsigned u, g;
  // A few clock ticks of slack; a reused pid starts later than that.
  unsigned long long now_start = nr_start_time(pid);
  if (sscanf(rec, "%llu %u %u", &start, &u, &g) == 3 &&
      (start > now_start ? start - now_start : now_start - start) <= 200) {
    *uid = u;
    *gid = g;
  }
}

static void nr_after_fork() { nr_publish_ids(); }

static void nr_publish_ids_init() {
  pthread_atfork(nullptr, nullptr, nr_after_fork);  // children have new pids
  nr_publish_ids();
}

// The kernel's rules for the set*id() calls, applied to the virtual ids.
template <typename T>
static int nr_setres(T* r, T* e, T* s, T nr, T ne, T ns, bool privileged) {
  const T keep = static_cast<T>(-1);
  if (!privileged) {
    const T wanted[3] = {nr, ne, ns};
    for (T v : wanted) {
      if (v != keep && v != *r && v != *e && v != *s) {
        errno = EPERM;
        return -1;
      }
    }
  }
  if (nr != keep) *r = nr;
  if (ne != keep) *e = ne;
  if (ns != keep) *s = ns;
  nr_publish_ids();
  return 0;
}

extern "C" int setresuid(uid_t r, uid_t e, uid_t s) {
  if (!nr_ids()) return __nrp_setresuid(r, e, s);
  return nr_setres(&g_ids.r, &g_ids.e, &g_ids.s, r, e, s, g_ids.e == 0);
}
extern "C" int setresgid(gid_t r, gid_t e, gid_t s) {
  if (!nr_ids()) return __nrp_setresgid(r, e, s);
  return nr_setres(&g_ids.gr, &g_ids.ge, &g_ids.gs, r, e, s, g_ids.e == 0);
}
extern "C" int setreuid(uid_t r, uid_t e) {
  if (!nr_ids()) return __nrp_setreuid(r, e);
  // The saved id follows the effective one when the real one is set or the
  // effective one changes to something other than the real one.
  uid_t s = static_cast<uid_t>(-1);
  uid_t new_e = e != static_cast<uid_t>(-1) ? e : g_ids.e;
  if (r != static_cast<uid_t>(-1) || (e != static_cast<uid_t>(-1) && e != g_ids.r)) s = new_e;
  return nr_setres(&g_ids.r, &g_ids.e, &g_ids.s, r, e, s, g_ids.e == 0);
}
extern "C" int setregid(gid_t r, gid_t e) {
  if (!nr_ids()) return __nrp_setregid(r, e);
  gid_t s = static_cast<gid_t>(-1);
  gid_t new_e = e != static_cast<gid_t>(-1) ? e : g_ids.ge;
  if (r != static_cast<gid_t>(-1) || (e != static_cast<gid_t>(-1) && e != g_ids.gr)) s = new_e;
  return nr_setres(&g_ids.gr, &g_ids.ge, &g_ids.gs, r, e, s, g_ids.e == 0);
}
extern "C" int setuid(uid_t u) {
  if (!nr_ids()) return __nrp_setuid(u);
  if (g_ids.e == 0) return setresuid(u, u, u);
  return setresuid(static_cast<uid_t>(-1), u, static_cast<uid_t>(-1));
}
extern "C" int setgid(gid_t g) {
  if (!nr_ids()) return __nrp_setgid(g);
  if (g_ids.e == 0) return setresgid(g, g, g);
  return setresgid(static_cast<gid_t>(-1), g, static_cast<gid_t>(-1));
}

extern "C" int fchown(int fd, uid_t owner, gid_t group) {
  if (nr_ids()) return nr_chown_at(fd, "", AT_EMPTY_PATH, owner, group);
  return __nrp_fchown(fd, owner, group);
}

extern "C" int fstat(int fd, struct stat* st) {
  int r = __nrp_fstat64(fd, st);
  if (r == 0) nr_owner_at(fd, "", AT_EMPTY_PATH, &st->st_uid, &st->st_gid);
  return r;
}
extern "C" int fstat64(int fd, struct stat64* st) {
  return fstat(fd, reinterpret_cast<struct stat*>(st));
}

// ------------------------- capabilities, namespaces and mounts, virtually
//
// Zygote drops capabilities, makes mount namespaces and mounts storage for
// every process it starts; an app may do none of that. In native mode these
// calls succeed (capabilities are remembered, so capget() reads back what
// capset() set) and change nothing on the phone, as with proot -0.

struct nr_cap_data {  // __user_cap_data_struct
  uint32_t effective, permitted, inheritable;
};
static nr_cap_data g_caps[2] = {{0xffffffff, 0xffffffff, 0}, {0x1ff, 0x1ff, 0}};  // all, as root

extern "C" int capget(void* header, void* data) {
  if (!nr_ids()) return __nrp_capget(header, data);
  uint32_t* h = static_cast<uint32_t*>(header);
  if (h[0] == 0x19980330) {  // version 1: one 32-bit set
    if (data) memcpy(data, &g_caps[0], sizeof(g_caps[0]));
  } else {
    h[0] = 0x20080522;  // version 3
    if (data) memcpy(data, g_caps, sizeof(g_caps));
  }
  return 0;
}

extern "C" int capset(void* header, const void* data) {
  if (!nr_ids()) return __nrp_capset(header, data);
  const uint32_t* h = static_cast<const uint32_t*>(header);
  if (data == nullptr) {
    errno = EFAULT;
    return -1;
  }
  memcpy(g_caps, data, h[0] == 0x19980330 ? sizeof(g_caps[0]) : sizeof(g_caps));
  return 0;
}

extern "C" int prctl(int option, unsigned long a2, unsigned long a3, unsigned long a4,
                     unsigned long a5) {
  int r = __nrp_prctl(option, a2, a3, a4, a5);
  // Only "not allowed" is faked: EINVAL stays (zygote drops the bounding set
  // with "for (i = 0; prctl(PR_CAPBSET_READ, i) >= 0; i++)", which ends on
  // EINVAL; reading is never refused, so PR_CAPBSET_READ is left alone).
  if (r == -1 && errno == EPERM && nr_ids()) {
    switch (option) {
      case 8:   // PR_SET_KEEPCAPS
      case 24:  // PR_CAPBSET_DROP
      case 28:  // PR_SET_SECUREBITS
      case 47:  // PR_CAP_AMBIENT
        return 0;
    }
  }
  return r;
}

extern "C" int unshare(int flags) {
  int r = __nrp_unshare(flags);
  if (r == -1 && (errno == EPERM || errno == EINVAL) && nr_ids()) return 0;
  return r;
}

extern "C" int setns(int fd, int type) {
  int r = __nrp_setns(fd, type);
  if (r == -1 && (errno == EPERM || errno == EINVAL) && nr_ids()) return 0;
  return r;
}

// /proc/<self>/attr/{current,exec,fscreate,...}: setting an SELinux context
// isn't allowed in an app; writes there go nowhere (reads stay real).
static bool nr_proc_attr(const char* p) {
  if (strncmp(p, "/proc/", 6) != 0) return false;
  const char* a = strstr(p, "/attr/");
  return a != nullptr && strchr(a + 6, '/') == nullptr;
}

// /proc/<pid>/status shows a process's real uid and gid (the app's).
// ActivityManager checks a process is alive and still the one it started
// by its uid there (Process.getUidForPid), so the Uid:/Gid: lines report
// the virtual ids: the caller's own, or another process's recorded ones.
static bool nr_proc_status_pid(const char* p, pid_t* pid) {
  if (strncmp(p, "/proc/", 6) != 0) return false;
  p += 6;
  if (strncmp(p, "self/", 5) == 0 || strncmp(p, "thread-self/", 12) == 0) {
    *pid = getpid();
    p = strchr(p, '/');
  } else {
    char* end;
    long v = strtol(p, &end, 10);
    if (end == p || *end != '/' || v <= 0) return false;
    *pid = static_cast<pid_t>(v);
    p = end;
  }
  if (strncmp(p, "/task/", 6) == 0) {  // a thread's: the process's ids
    char* end;
    strtol(p + 6, &end, 10);
    if (end == p + 6 || *end != '/') return false;
    p = end;
  }
  return strcmp(p, "/status") == 0;
}

static int nr_open_proc_status(int fd, pid_t pid) {
  constexpr size_t kMax = 8192;
  char* buf = static_cast<char*>(malloc(kMax));
  if (buf == nullptr) return fd;
  size_t len = 0;
  ssize_t n;
  while (len < kMax - 1 && (n = read(fd, buf + len, kMax - 1 - len)) > 0) len += n;
  buf[len] = '\0';
  unsigned ids[2][4];
  if (pid == getpid()) {
    const unsigned u[4] = {g_ids.r, g_ids.e, g_ids.s, g_ids.e};
    const unsigned g[4] = {g_ids.gr, g_ids.ge, g_ids.gs, g_ids.ge};
    memcpy(ids[0], u, sizeof(u));
    memcpy(ids[1], g, sizeof(g));
  } else {
    uid_t u;
    gid_t g;
    nr_peer_ids(pid, &u, &g);
    for (int i = 0; i < 4; i++) ids[0][i] = u, ids[1][i] = g;
  }
  int out = memfd_create("status", MFD_CLOEXEC);
  if (out < 0) {
    free(buf);
    lseek(fd, 0, SEEK_SET);
    return fd;
  }
  for (char* line = buf; *line != '\0';) {
    char* nl = strchr(line, '\n');
    size_t ll = nl ? static_cast<size_t>(nl - line + 1) : strlen(line);
    int which = strncmp(line, "Uid:", 4) == 0 ? 0 : strncmp(line, "Gid:", 4) == 0 ? 1 : -1;
    if (which >= 0) {
      char rep[96];
      int rl = snprintf(rep, sizeof(rep), "%s\t%u\t%u\t%u\t%u\n", which ? "Gid:" : "Uid:",
                        ids[which][0], ids[which][1], ids[which][2], ids[which][3]);
      if (write(out, rep, static_cast<size_t>(rl)) < 0) {}
    } else if (write(out, line, ll) < 0) {
    }
    line += ll;
  }
  free(buf);
  lseek(out, 0, SEEK_SET);
  close(fd);
  return out;
}

// Declares `var`: `path` mapped under the root. The buffer is sized to the
// path (bionic limits stack frames to 2 KiB, so no PATH_MAX arrays).
#define NR_MAP(var, path)                                                 \
  size_t var##_size = nr_map_size(path);                                  \
  char* var##_buf = var##_size ? static_cast<char*>(alloca(var##_size)) : nullptr; \
  const char* var = nr_map(path, var##_buf, var##_size);                  \
  if (var == nullptr && (path) != nullptr) return -1

extern "C" int __openat(int fd, const char* path, int flags, int mode) {
  size_t rl;
  if (path != nullptr && path[0] == '/' && nr_root(&rl) != nullptr) {
    if (const char* dev = nb_device(path)) return nb_open(dev, flags);
    if ((flags & O_ACCMODE) != O_RDONLY && nr_proc_attr(path)) {
      return __nrp_openat(AT_FDCWD, "/dev/null", flags & ~(O_CREAT | O_TRUNC | O_EXCL), 0);
    }
    if (strcmp(path, "/dev/kmsg") == 0) {
      // Every writer appends to the same file, as to the kernel's log.
      flags |= O_APPEND | O_CREAT;
      mode = 0666;
    }
  }
  NR_MAP(p, path);
  int r = __nrp_openat(fd, p, flags, mode);
  pid_t status_pid;
  if (r >= 0 && path != nullptr && (flags & O_ACCMODE) == O_RDONLY &&
      nr_proc_status_pid(path, &status_pid) && nr_ids()) {
    int saved = errno;
    r = nr_open_proc_status(r, status_pid);
    errno = saved;
    return r;
  }
  if (r == -1 && (errno == EACCES || errno == EPERM || errno == EROFS) && path != nullptr &&
      (flags & O_ACCMODE) != O_RDONLY && nr_under(path, "/proc/sys", 9) && nr_ids()) {
    // Kernel tunables (netbpfload enables the BPF JIT, ...): an app may not
    // write them; the writes go nowhere, as for /proc/*/attr.
    return __nrp_openat(AT_FDCWD, "/dev/null", flags & ~(O_CREAT | O_TRUNC | O_EXCL), 0);
  }
  return r;
}

// In native mode mounts never happen: they "succeed" and change nothing
// (zygote remounts / as a slave and mounts storage for every process).
extern "C" int mount(const char* source, const char* target, const char* type,
                     unsigned long flags, const void* data) {
  if (nr_ids()) return 0;
  return __nrp_mount(source, target, type, flags, data);
}

extern "C" int umount2(const char* target, int flags) {
  if (nr_ids()) return 0;
  return __nrp_umount2(target, flags);
}

// The partitions a device mounts read-only.
static bool nr_read_only(const char* p) {
  static const char* const kReadOnly[] = {"/system", "/system_ext", "/product", "/vendor",
                                          "/odm", "/apex"};
  for (const char* d : kReadOnly) {
    if (nr_under(p, d, strlen(d))) return true;
  }
  return false;
}

extern "C" int __faccessat(int fd, const char* path, int mode) {
  size_t rl;
  if (path != nullptr && path[0] == '/' && nr_root(&rl) != nullptr) {
    // A binder device exists when nbinder is listening (the phone's own
    // /dev/binder doesn't matter); hwbinder never does.
    if (const char* dev = nb_device(path)) {
      if (dev[0] == '\0') {
        errno = ENOENT;
        return -1;
      }
      path = NB_SOCKET;
    }
  }
  NR_MAP(p, path);
  int r = __nrp_faccessat(fd, p, mode);
  if (r == 0 && (mode & W_OK) && path != nullptr && nr_read_only(path) && nr_ids()) {
    // The image's partitions are read-only mounts on a device; ART refuses
    // to load app code from files it could write ("Writable dex file").
    errno = EROFS;
    return -1;
  }
  return r;
}

extern "C" int __fchmodat(int fd, const char* path, mode_t mode) {
  NR_MAP(p, path);
  return __nrp_fchmodat(fd, p, mode);
}

extern "C" int fchownat(int fd, const char* path, uid_t owner, gid_t group, int flags) {
  NR_MAP(p, path);
  if (nr_ids()) {
    struct stat st;  // chown() of a missing file still fails
    if (__nrp_fstatat64(fd, p, &st, flags) != 0) return -1;
    return nr_chown_at(fd, p, flags, owner, group);
  }
  return __nrp_fchownat(fd, p, owner, group, flags);
}

extern "C" int fstatat(int fd, const char* path, struct stat* st, int flags) {
  NR_MAP(p, path);
  int r = __nrp_fstatat64(fd, p, st, flags);
  if (r == 0) nr_owner_at(fd, p, flags, &st->st_uid, &st->st_gid);
  return r;
}
extern "C" int fstatat64(int fd, const char* path, struct stat64* st, int flags) {
  return fstatat(fd, path, reinterpret_cast<struct stat*>(st), flags);
}

extern "C" int linkat(int old_fd, const char* old_path, int new_fd, const char* new_path, int flags) {
  NR_MAP(o, old_path);
  NR_MAP(n, new_path);
  return __nrp_linkat(old_fd, o, new_fd, n, flags);
}

extern "C" int mkdirat(int fd, const char* path, mode_t mode) {
  NR_MAP(p, path);
  return __nrp_mkdirat(fd, p, mode);
}

extern "C" int mknodat(int fd, const char* path, mode_t mode, dev_t dev) {
  NR_MAP(p, path);
  return __nrp_mknodat(fd, p, mode, dev);
}

extern "C" ssize_t readlinkat(int fd, const char* path, char* buf, size_t size) {
  NR_MAP(p, path);
  ssize_t n = __nrp_readlinkat(fd, p, buf, size);
  if (n > 0) n = static_cast<ssize_t>(nr_unmap(buf, static_cast<size_t>(n)));
  return n;
}

extern "C" int renameat2(int old_fd, const char* old_path, int new_fd, const char* new_path,
              unsigned flags) {
  NR_MAP(o, old_path);
  NR_MAP(n, new_path);
  return __nrp_renameat2(old_fd, o, new_fd, n, flags);
}

extern "C" int symlinkat(const char* target, int fd, const char* path) {
  // An absolute target is resolved by the kernel, so it must point under the root too.
  NR_MAP(t, target);
  NR_MAP(p, path);
  int r = __nrp_symlinkat(t, fd, p);
  if (r == -1 && errno == EEXIST && nr_ids()) {
    // Mounts are no-ops here, so links zygote makes in what would be a
    // fresh tmpfs (app data isolation: /data/user/0 -> /data/data) are
    // already there: the same link is no error.
    struct stat a, b;
    if (__nrp_fstatat64(fd, p, &a, 0) == 0 && __nrp_fstatat64(fd, t, &b, 0) == 0 &&
        a.st_dev == b.st_dev && a.st_ino == b.st_ino) {
      struct stat l;
      if (__nrp_fstatat64(fd, p, &l, AT_SYMLINK_NOFOLLOW) == 0 && S_ISLNK(l.st_mode)) return 0;
    }
    errno = EEXIST;
  }
  return r;
}

extern "C" int unlinkat(int fd, const char* path, int flags) {
  NR_MAP(p, path);
  return __nrp_unlinkat(fd, p, flags);
}

extern "C" int utimensat(int fd, const char* path, const struct timespec times[2], int flags) {
  NR_MAP(p, path);
  return __nrp_utimensat(fd, p, times, flags);
}

extern "C" int truncate(const char* path, off_t length) {
  NR_MAP(p, path);
  return __nrp_truncate(p, length);
}

#if defined(__LP64__)
extern "C" int truncate64(const char* path, off64_t length) {
  return truncate(path, static_cast<off_t>(length));
}

extern "C" int __statfs(const char* path, struct statfs* buf) {
  NR_MAP(p, path);
  int r = __nrp_statfs(p, buf);
  if (r == 0) nr_fake_selinuxfs(path, buf);
  return r;
}
#else
extern "C" int truncate64(const char* path, off64_t length) {
  NR_MAP(p, path);
  return __nrp_truncate64(p, length);
}

extern "C" int __statfs64(const char* path, size_t size, struct statfs* buf) {
  NR_MAP(p, path);
  int r = __nrp_statfs64(p, size, buf);
  if (r == 0) nr_fake_selinuxfs(path, buf);
  return r;
}
#endif

// Binder fds (from nb_open) are sockets to nbinder: their ioctls and mmap
// go there. A quick lock-free check for every other fd.
// Signaled fences. An app can't create sync files (no sw_sync access), so
// native mode's HALs (native/gfx) hand out an eventfd with a count as an
// already-signaled fence: poll() sees it ready. Answer the two sync-file
// queries libsync makes on such fds as the kernel would for a signaled fence.
static bool nr_is_eventfd(int fd) {
  char path[32], target[32];
  snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
  ssize_t n = readlink(path, target, sizeof(target));
  return n == 20 && memcmp(target, "anon_inode:[eventfd]", 20) == 0;
}

static int nr_sync_ioctl(int fd, unsigned request, void* arg) {
  if (request == SYNC_IOC_MERGE) {
    auto* m = static_cast<sync_merge_data*>(arg);
    if (!nr_is_eventfd(fd) || !nr_is_eventfd(m->fd2)) return -2;
    int f = eventfd(1, EFD_CLOEXEC);
    if (f < 0) return -1;
    m->fence = f;
    return 0;
  }
  if (request == SYNC_IOC_FILE_INFO) {
    auto* info = static_cast<sync_file_info*>(arg);
    if (!nr_is_eventfd(fd)) return -2;
    strlcpy(info->name, "nfence", sizeof(info->name));
    info->status = 1;
    if (info->num_fences >= 1 && info->sync_fence_info) {
      auto* f = reinterpret_cast<sync_fence_info*>(static_cast<uintptr_t>(info->sync_fence_info));
      memset(f, 0, sizeof(*f));
      strlcpy(f->obj_name, "nfence", sizeof(f->obj_name));
      strlcpy(f->driver_name, "native", sizeof(f->driver_name));
      f->status = 1;
      struct timespec ts;
      clock_gettime(CLOCK_MONOTONIC, &ts);
      f->timestamp_ns = static_cast<uint64_t>(ts.tv_sec) * 1000000000ull + ts.tv_nsec;
    }
    info->num_fences = 1;
    return 0;
  }
  return -2;
}

extern "C" int __ioctl(int fd, int request, void* arg) {
  if (fd >= 0 && nb_is_binder(fd)) return nb_ioctl(fd, static_cast<unsigned>(request), arg);
  int r = __nrp_ioctl(fd, request, arg);
  if (r == -1 && errno == ENOTTY && arg &&
      (static_cast<unsigned>(request) == SYNC_IOC_MERGE ||
       static_cast<unsigned>(request) == SYNC_IOC_FILE_INFO)) {
    int saved = errno;
    int e = nr_sync_ioctl(fd, static_cast<unsigned>(request), arg);
    if (e != -2) return e;
    errno = saved;
  }
  return r;
}

extern "C" int __close(int fd) {
  if (fd >= 0 && nb_is_binder(fd)) nb_forget(fd);
  return __nrp_close(fd);
}

#if defined(__LP64__)
extern "C" void* mmap(void* addr, size_t size, int prot, int flags, int fd, off_t offset) {
  if (fd >= 0 && nb_is_binder(fd)) return nb_mmap(fd, addr, size, prot, flags);
  return __nrp_mmap(addr, size, prot, flags, fd, offset);
}

extern "C" void* mmap64(void* addr, size_t size, int prot, int flags, int fd, off64_t offset) {
  return mmap(addr, size, prot, flags, fd, static_cast<off_t>(offset));
}
#else
extern "C" void* __mmap2(void* addr, size_t size, int prot, int flags, int fd, long pgoffset) {
  if (fd >= 0 && nb_is_binder(fd)) return nb_mmap(fd, addr, size, prot, flags);
  return __nrp_mmap2(addr, size, prot, flags, fd, pgoffset);
}
#endif

extern "C" int chdir(const char* path) {
  NR_MAP(p, path);
  return __nrp_chdir(p);
}

// Like the getcwd system call: returns the length including the NUL.
extern "C" int __getcwd(char* buf, size_t size) {
  int rc = __nrp_getcwd(buf, size);
  if (rc > 0) {
    size_t len = nr_unmap(buf, strlen(buf));
    buf[len] = '\0';
    rc = static_cast<int>(len + 1);
  }
  return rc;
}

// SELinux labels and other security.* / trusted.* attributes (file
// capabilities, ...): an app may not set them, so native mode records them
// as user.nmode.<name> (see kOwnerXattr) and reports them; a file with no
// label reads as unlabeled (restorecon reads the old label first).
static bool nr_is_kept(const char* name) {
  return name != nullptr &&
         (strncmp(name, "security.", 9) == 0 || strncmp(name, "trusted.", 8) == 0) && nr_ids();
}

static const char* nr_kept_name(const char* name, char* buf, size_t size) {
  snprintf(buf, size, "user.nmode.%s", name);
  return buf;
}

// The label the kernel's policy gives BPF filesystem entries by path
// (genfscon bpf /net_shared u:object_r:fs_bpf_net_shared:s0, ...), for
// native_bpf.cpp's objects: fs_bpf, or fs_bpf_<first directory>.
static bool nr_bpf_label(const char* path, char* out, size_t size) {
  if (path == nullptr || !nr_under(path, "/sys/fs/bpf", 11)) return false;
  const char* sub = path[11] == '/' ? path + 12 : "";
  size_t n = strcspn(sub, "/");
  if (n == 0 || sub[0] == '.') {
    snprintf(out, size, "u:object_r:fs_bpf:s0");
  } else {
    snprintf(out, size, "u:object_r:fs_bpf_%.*s:s0", static_cast<int>(n), sub);
  }
  return true;
}

static ssize_t nr_get_kept(ssize_t (*raw)(const char*, const char*, void*, size_t), const char* p,
                           int fd, const char* name, void* value, size_t size,
                           const char* orig = nullptr) {
  char kept[300];
  nr_kept_name(name, kept, sizeof(kept));
  ssize_t r = p ? raw(p, kept, value, size) : __nrp_fgetxattr(fd, kept, value, size);
  if (r >= 0 || errno == ERANGE) return r;
  r = p ? raw(p, name, value, size) : __nrp_fgetxattr(fd, name, value, size);
  if (r >= 0 || (errno != ENODATA && errno != ENOTSUP) || strcmp(name, "security.selinux") != 0) {
    return r;
  }
  char label[96] = "u:object_r:unlabeled:s0";
  nr_bpf_label(orig, label, sizeof(label));
  size_t len = strlen(label) + 1;
  if (size == 0) return static_cast<ssize_t>(len);
  if (size < len) {
    errno = ERANGE;
    return -1;
  }
  memcpy(value, label, len);
  return static_cast<ssize_t>(len);
}

extern "C" int setxattr(const char* path, const char* name, const void* value, size_t size, int flags) {
  NR_MAP(p, path);
  if (nr_is_kept(name)) {
    char kept[300];
    const char* k = nr_kept_name(name, kept, sizeof(kept));
    nr_set_writable([&] { return __nrp_setxattr(p, k, value, size, 0); }, -1, p, false);
    return 0;
  }
  return __nrp_setxattr(p, name, value, size, flags);
}

extern "C" int lsetxattr(const char* path, const char* name, const void* value, size_t size, int flags) {
  NR_MAP(p, path);
  if (nr_is_kept(name)) {
    char kept[300];  // fails on symlinks (no user xattrs): they stay unlabeled
    const char* k = nr_kept_name(name, kept, sizeof(kept));
    nr_set_writable([&] { return __nrp_lsetxattr(p, k, value, size, 0); }, -1, p, true);
    return 0;
  }
  return __nrp_lsetxattr(p, name, value, size, flags);
}

extern "C" int __fsetxattr(int fd, const char* name, const void* value, size_t size, int flags) {
  if (nr_is_kept(name)) {
    char kept[300];
    const char* k = nr_kept_name(name, kept, sizeof(kept));
    nr_set_writable([&] { return __nrp_fsetxattr(fd, k, value, size, 0); }, fd, nullptr, false);
    return 0;
  }
  return __nrp_fsetxattr(fd, name, value, size, flags);
}

extern "C" ssize_t getxattr(const char* path, const char* name, void* value, size_t size) {
  NR_MAP(p, path);
  if (nr_is_kept(name)) return nr_get_kept(__nrp_getxattr, p, -1, name, value, size, path);
  return __nrp_getxattr(p, name, value, size);
}

extern "C" ssize_t lgetxattr(const char* path, const char* name, void* value, size_t size) {
  NR_MAP(p, path);
  if (nr_is_kept(name)) return nr_get_kept(__nrp_lgetxattr, p, -1, name, value, size, path);
  return __nrp_lgetxattr(p, name, value, size);
}

extern "C" ssize_t __fgetxattr(int fd, const char* name, void* value, size_t size) {
  if (nr_is_kept(name)) return nr_get_kept(nullptr, nullptr, fd, name, value, size);
  return __nrp_fgetxattr(fd, name, value, size);
}

extern "C" ssize_t listxattr(const char* path, char* list, size_t size) {
  NR_MAP(p, path);
  return __nrp_listxattr(p, list, size);
}

extern "C" ssize_t llistxattr(const char* path, char* list, size_t size) {
  NR_MAP(p, path);
  return __nrp_llistxattr(p, list, size);
}

extern "C" int removexattr(const char* path, const char* name) {
  NR_MAP(p, path);
  return __nrp_removexattr(p, name);
}

extern "C" int lremovexattr(const char* path, const char* name) {
  NR_MAP(p, path);
  return __nrp_lremovexattr(p, name);
}

extern "C" int statx(int fd, const char* path, int flags, unsigned mask, struct statx* buf) {
  NR_MAP(p, path);
  int r = __nrp_statx(fd, p, flags, mask, buf);
  if (r == 0) {
    uid_t u = buf->stx_uid;
    gid_t g = buf->stx_gid;
    nr_owner_at(fd, p, flags, &u, &g);
    buf->stx_uid = u;
    buf->stx_gid = g;
  }
  return r;
}

// Watching a phone directory an app may not watch (InputFlinger's EventHub
// watches /dev for new devices, and aborts if it can't) watches the root's
// directory of the same name instead.
extern "C" int inotify_add_watch(int fd, const char* path, uint32_t mask) {
  NR_MAP(p, path);
  int r = __nrp_inotify_add_watch(fd, p, mask);
  size_t rl;
  const char* root;
  if (r == -1 && (errno == EACCES || errno == EPERM) && path != nullptr && nr_host_path(path) &&
      path[0] == '/' && nr_ids() && (root = nr_root(&rl)) != nullptr) {
    size_t n = rl + strlen(path) + 1;
    char* in_root = static_cast<char*>(alloca(n));
    memcpy(in_root, root, rl);
    memcpy(in_root + rl, path, n - rl);
    int saved = errno;
    r = __nrp_inotify_add_watch(fd, in_root, mask);
    if (r == -1) errno = saved;
  }
  return r;
}

// Alarm clocks (CLOCK_REALTIME_ALARM, CLOCK_BOOTTIME_ALARM) need
// CAP_WAKE_ALARM, which an app lacks; AlarmManagerService then falls back
// to a handler path that crashes on the first alarm. They get the clock of
// the same kind instead: the timers run, they just don't wake a sleeping
// phone (the host app keeps it awake while Android runs).
extern "C" int timerfd_create(clockid_t clock, int flags) {
  int fd = __nrp_timerfd_create(clock, flags);
  if (fd == -1 && errno == EPERM && nr_ids()) {
    if (clock == CLOCK_REALTIME_ALARM) fd = __nrp_timerfd_create(CLOCK_REALTIME, flags);
    if (clock == CLOCK_BOOTTIME_ALARM) fd = __nrp_timerfd_create(CLOCK_BOOTTIME, flags);
  }
  return fd;
}

// ---- Unix domain sockets ----

// A filesystem AF_UNIX address mapped under the root (Android's /dev/socket/
// sockets belong to the image, e.g. the property service). Abstract and
// relative addresses stay as they are.
static const struct sockaddr* nr_map_sockaddr(const struct sockaddr* addr, socklen_t* len,
                                              struct sockaddr_un* out) {
  constexpr size_t kPathOff = offsetof(struct sockaddr_un, sun_path);
  // socklen_t is signed on 32-bit arm and unsigned on arm64.
  if (addr == nullptr || static_cast<int64_t>(*len) <= static_cast<int64_t>(kPathOff) ||
      addr->sa_family != AF_UNIX) {
    return addr;
  }
  const struct sockaddr_un* un = reinterpret_cast<const struct sockaddr_un*>(addr);
  if (un->sun_path[0] != '/') return addr;
  char path[sizeof(un->sun_path) + 1];
  size_t max = static_cast<size_t>(*len) - kPathOff;
  if (max > sizeof(un->sun_path)) max = sizeof(un->sun_path);
  size_t n = strnlen(un->sun_path, max);
  memcpy(path, un->sun_path, n);
  path[n] = '\0';
  size_t need = nr_map_size(path);
  if (need == 0) return addr;
  if (need > sizeof(out->sun_path)) {
    errno = ENAMETOOLONG;
    return nullptr;
  }
  out->sun_family = AF_UNIX;
  nr_map(path, out->sun_path, need);
  *len = static_cast<socklen_t>(kPathOff + need);
  return reinterpret_cast<const struct sockaddr*>(out);
}

// vsock sockets (Cuttlefish's HALs listen on one for their host side: the
// lights HAL panics without it) and uevent netlink sockets (system_server's
// UEventObserver throws without one): an app may not open them, so it gets
// a Unix socket in their place (__socket below). Binding it to a vsock or
// netlink address binds it to an unnamed abstract address, so listen(),
// accept() and recv() work and nothing arrives; connecting is refused.
static bool nr_vsock_standin(int fd, const struct sockaddr* addr) {
  if (addr == nullptr || (addr->sa_family != AF_VSOCK && addr->sa_family != AF_NETLINK) ||
      !nr_ids()) {
    return false;
  }
  int domain = 0;
  socklen_t dl = sizeof(domain);
  int saved = errno;
  bool unix_fd = __nrp_getsockopt(fd, SOL_SOCKET, SO_DOMAIN, &domain, &dl) == 0 && domain == AF_UNIX;
  errno = saved;
  return unix_fd;
}

// A Unix socket of the same type and fd flags in place of socket `fd`.
static bool nr_replace_with_unix(int fd) {
  int type = 0;
  socklen_t tl = sizeof(type);
  if (__nrp_getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &tl) != 0) return false;
  int fl = fcntl(fd, F_GETFL), fdfl = fcntl(fd, F_GETFD);
  int u = __nrp_socket(AF_UNIX, type | ((fl & O_NONBLOCK) ? SOCK_NONBLOCK : 0), 0);
  if (u < 0) return false;
  int r = dup3(u, fd, (fdfl & FD_CLOEXEC) ? O_CLOEXEC : 0);
  close(u);
  return r == fd;
}

extern "C" int bind(int fd, const struct sockaddr* addr, socklen_t len) {
  sa_family_t autobind = AF_UNIX;  // an abstract name of the kernel's choice
  if (nr_vsock_standin(fd, addr)) {
    return __nrp_bind(fd, reinterpret_cast<const struct sockaddr*>(&autobind), sizeof(autobind));
  }
  struct sockaddr_un buf;
  const struct sockaddr* a = nr_map_sockaddr(addr, &len, &buf);
  if (a == nullptr) return -1;
  int r = __nrp_bind(fd, a, len);
  if (r == -1 && addr != nullptr && addr->sa_family == AF_VSOCK && nr_ids() &&
      (errno == EACCES || errno == EPERM || errno == EADDRNOTAVAIL)) {
    // An app may open a vsock socket but not bind it (ports below 1024 are
    // privileged; the lights HAL binds port 0): the Unix stand-in instead.
    int saved = errno;
    if (!nr_replace_with_unix(fd)) {
      errno = saved;
      return -1;
    }
    r = __nrp_bind(fd, reinterpret_cast<const struct sockaddr*>(&autobind), sizeof(autobind));
  }
  return r;
}

// connect() itself goes through NetdClient's dispatch, which ends up here.
extern "C" int __connect(int fd, const struct sockaddr* addr, socklen_t len) {
  if (nr_vsock_standin(fd, addr)) {
    errno = ECONNREFUSED;  // no host side
    return -1;
  }
  struct sockaddr_un buf;
  const struct sockaddr* a = nr_map_sockaddr(addr, &len, &buf);
  if (a == nullptr) return -1;
  return __nrp_connect(fd, a, len);
}

// Socket addresses read back (getsockname, getpeername) lose the root again:
// android_get_control_socket() checks that a socket is bound to
// /dev/socket/NAME, for one.
static void nr_unmap_sockaddr(struct sockaddr* addr, socklen_t user_len, socklen_t* len) {
  constexpr size_t kPathOff = offsetof(struct sockaddr_un, sun_path);
  if (addr == nullptr || len == nullptr || addr->sa_family != AF_UNIX ||
      static_cast<int64_t>(*len) <= static_cast<int64_t>(kPathOff) ||
      static_cast<int64_t>(user_len) <= static_cast<int64_t>(kPathOff)) {
    return;
  }
  struct sockaddr_un* un = reinterpret_cast<struct sockaddr_un*>(addr);
  if (un->sun_path[0] != '/') return;
  size_t avail = static_cast<size_t>(*len < user_len ? *len : user_len) - kPathOff;
  size_t n = strnlen(un->sun_path, avail);
  size_t m = nr_unmap(un->sun_path, n);
  if (m == n) return;
  if (m < avail) un->sun_path[m] = '\0';
  *len = static_cast<socklen_t>(kPathOff + m + 1);
}

// PF_KEY sockets: the connectivity module opens and closes one only to make
// the kernel wait for RCU readers of BPF maps (BpfUtils' synchronizeKernelRCU).
// With BPF emulated there are none, and an app can't open one: any socket
// does for that. vsock and uevent sockets get a Unix one too (see
// nr_vsock_standin).
extern "C" int __socket(int domain, int type, int protocol) {
  int fd = __nrp_socket(domain, type, protocol);
  if (fd < 0 && domain == AF_KEY && nr_ids()) {
    fd = __nrp_socket(AF_UNIX, SOCK_DGRAM | (type & SOCK_CLOEXEC), 0);
  } else if (fd < 0 && (domain == AF_VSOCK || (domain == AF_NETLINK &&
                                                protocol == NETLINK_KOBJECT_UEVENT)) &&
             nr_ids() && (errno == EACCES || errno == EPERM || errno == EAFNOSUPPORT)) {
    fd = __nrp_socket(AF_UNIX, type, 0);
  }
  return fd;
}

extern "C" int getsockopt(int fd, int level, int name, void* value, socklen_t* len) {
  int r = __nrp_getsockopt(fd, level, name, value, len);
  if (r == 0 && level == SOL_SOCKET && name == SO_PEERCRED && value != nullptr && len != nullptr &&
      static_cast<size_t>(*len) >= sizeof(struct ucred) && nr_ids()) {
    auto* cred = static_cast<struct ucred*>(value);
    nr_peer_ids(cred->pid, &cred->uid, &cred->gid);
  }
  return r;
}

extern "C" int getsockname(int fd, struct sockaddr* addr, socklen_t* len) {
  socklen_t user_len = len ? *len : 0;
  int r = __nrp_getsockname(fd, addr, len);
  if (r == 0) nr_unmap_sockaddr(addr, user_len, len);
  return r;
}

extern "C" int getpeername(int fd, struct sockaddr* addr, socklen_t* len) {
  socklen_t user_len = len ? *len : 0;
  int r = __nrp_getpeername(fd, addr, len);
  if (r == 0) nr_unmap_sockaddr(addr, user_len, len);
  return r;
}

// ---- execve ----

// The ELF interpreter (PT_INTERP) of the open file, or false for a static
// executable or a non-ELF file.
template <typename Ehdr, typename Phdr>
static bool nr_elf_interp(int fd, const Ehdr* eh, char* out, size_t out_size) {
  for (unsigned i = 0; i < eh->e_phnum; i++) {
    Phdr ph;
    off64_t at = static_cast<off64_t>(eh->e_phoff) + static_cast<off64_t>(i) * eh->e_phentsize;
    if (pread64(fd, &ph, sizeof(ph), at) != static_cast<ssize_t>(sizeof(ph))) return false;
    if (ph.p_type != PT_INTERP) continue;
    if (ph.p_filesz == 0 || ph.p_filesz >= out_size) return false;
    if (pread64(fd, out, ph.p_filesz, static_cast<off64_t>(ph.p_offset)) !=
        static_cast<ssize_t>(ph.p_filesz)) {
      return false;
    }
    out[ph.p_filesz] = '\0';
    return out[0] == '/';
  }
  return false;
}

// envp, with ANDROID_NATIVE_ROOT added if the caller dropped it, so that the
// new program keeps looking up paths under the root.
// envp with ANDROID_NATIVE_ROOT and the current virtual ids (ANDROID_NATIVE_IDS),
// replacing any earlier values. `out` has room for envc + 3 entries.
static char* const* nr_env(char* const* envp, char** out, char* root_var, char* ids_var) {
  size_t rl;
  const char* root = nr_root(&rl);
  memcpy(root_var, kRootVar, sizeof(kRootVar) - 1);
  root_var[sizeof(kRootVar) - 1] = '=';
  memcpy(root_var + sizeof(kRootVar), root, rl + 1);
  size_t k = 0;
  if (envp != nullptr) {
    for (size_t i = 0; envp[i] != nullptr; i++) {
      if (strncmp(envp[i], kRootVar, sizeof(kRootVar) - 1) == 0 &&
          envp[i][sizeof(kRootVar) - 1] == '=') {
        continue;
      }
      if (strncmp(envp[i], kIdsVar, sizeof(kIdsVar) - 1) == 0 &&
          envp[i][sizeof(kIdsVar) - 1] == '=') {
        continue;
      }
      out[k++] = envp[i];
    }
  }
  out[k++] = root_var;
  if (nr_ids()) {
    // "ANDROID_NATIVE_IDS=" + 6 numbers of up to 10 digits and commas
    char* p = ids_var;
    memcpy(p, kIdsVar, sizeof(kIdsVar) - 1);
    p += sizeof(kIdsVar) - 1;
    *p++ = '=';
    const unsigned long v[6] = {g_ids.r, g_ids.e, g_ids.s, g_ids.gr, g_ids.ge, g_ids.gs};
    for (int i = 0; i < 6; i++) {
      char digits[12];
      int n = 0;
      unsigned long x = v[i];
      do {
        digits[n++] = static_cast<char>('0' + x % 10);
        x /= 10;
      } while (x != 0);
      while (n > 0) *p++ = digits[--n];
      if (i < 5) *p++ = ',';
    }
    *p = '\0';
    out[k++] = ids_var;
  }
  out[k] = nullptr;
  return out;
}

static size_t nr_count(char* const* v) {
  size_t n = 0;
  if (v != nullptr) {
    while (v[n] != nullptr) n++;
  }
  return n;
}

static int nr_execve(const char* path, char* const* argv, char* const* envp, int depth) {
  size_t rl;
  if (nr_root(&rl) == nullptr) return __nrp_execve(path, argv, envp);
  if (depth > 4) {
    errno = ELOOP;
    return -1;
  }
  NR_MAP(host, path);

  size_t argc = nr_count(argv);
  size_t envc = nr_count(envp);
  if (argc > 65536 || envc > 65536) {
    errno = E2BIG;
    return -1;
  }
  char** env_store = static_cast<char**>(alloca((envc + 3) * sizeof(char*)));
  char* root_var = static_cast<char*>(alloca(sizeof(kRootVar) + rl + 1));
  char ids_var[sizeof(kIdsVar) + 6 * 11 + 1];
  char* const* env = nr_env(envp, env_store, root_var, ids_var);

  int fd = __nrp_openat(AT_FDCWD, host, O_RDONLY | O_CLOEXEC, 0);
  if (fd == -1) return -1;
  char head[256];
  ssize_t got = pread64(fd, head, sizeof(head) - 1, 0);
  if (got < 0) got = 0;
  head[got] = '\0';

  // "#!" script: run "INTERP [ARG] PATH ARGS..." through execve again.
  if (got >= 2 && head[0] == '#' && head[1] == '!') {
    close(fd);
    char* line = head + 2;
    char* end = strchr(line, '\n');
    if (end != nullptr) *end = '\0';
    while (*line == ' ' || *line == '\t') line++;
    char* interp = line;
    while (*line != '\0' && *line != ' ' && *line != '\t') line++;
    char* arg = nullptr;
    if (*line != '\0') {
      *line++ = '\0';
      while (*line == ' ' || *line == '\t') line++;
      if (*line != '\0') {
        arg = line;
        char* t = arg + strlen(arg);
        while (t > arg && (t[-1] == ' ' || t[-1] == '\t' || t[-1] == '\r')) *--t = '\0';
      }
    }
    if (*interp == '\0') {
      errno = ENOEXEC;
      return -1;
    }
    char** args = static_cast<char**>(alloca((argc + 4) * sizeof(char*)));
    size_t i = 0;
    args[i++] = interp;
    if (arg != nullptr) args[i++] = arg;
    args[i++] = const_cast<char*>(path);
    for (size_t j = 1; j < argc; j++) args[i++] = argv[j];
    args[i] = nullptr;
    return nr_execve(interp, args, env, depth + 1);
  }

  // Dynamic ELF: run the image's own interpreter with the program as argument.
  char* interp = static_cast<char*>(alloca(PATH_MAX));
  bool dynamic = false;
  if (got >= static_cast<ssize_t>(EI_NIDENT) && memcmp(head, ELFMAG, SELFMAG) == 0) {
    if (head[EI_CLASS] == ELFCLASS64 && got >= static_cast<ssize_t>(sizeof(Elf64_Ehdr))) {
      dynamic = nr_elf_interp<Elf64_Ehdr, Elf64_Phdr>(
          fd, reinterpret_cast<const Elf64_Ehdr*>(head), interp, PATH_MAX);
    } else if (head[EI_CLASS] == ELFCLASS32 && got >= static_cast<ssize_t>(sizeof(Elf32_Ehdr))) {
      dynamic = nr_elf_interp<Elf32_Ehdr, Elf32_Phdr>(
          fd, reinterpret_cast<const Elf32_Ehdr*>(head), interp, PATH_MAX);
    }
  }
  close(fd);
  if (!dynamic) return __nrp_execve(host, argv, env);

  NR_MAP(host_interp, interp);
  char** args = static_cast<char**>(alloca((argc + 2) * sizeof(char*)));
  args[0] = interp;
  args[1] = const_cast<char*>(path);
  for (size_t j = 1; j < argc; j++) args[j + 1] = argv[j];
  args[argc < 1 ? 2 : argc + 1] = nullptr;
  return __nrp_execve(host_interp, args, env);
}

extern "C" int execve(const char* path, char* const* argv, char* const* envp) {
  return nr_execve(path, argv, envp, 0);
}

// ------------------------------------------------------ bpf(2) and uname()
//
// bpf(2) is answered in user space (native_bpf.cpp): Android's networking
// keeps its state in BPF maps an app may not create. bionic has no bpf()
// function; callers use syscall(__NR_bpf, ...), so syscall() looks first.
extern "C" long syscall(long number, ...) {
  va_list ap;
  va_start(ap, number);
  long a[6];
  for (long& v : a) v = va_arg(ap, long);
  va_end(ap);
  size_t rl;
  if (number == __NR_bpf && nr_root(&rl) != nullptr) {
    return nbpf_syscall(static_cast<int>(a[0]), reinterpret_cast<void*>(a[1]),
                        static_cast<unsigned>(a[2]));
  }
  return __nrp_syscall(number, a[0], a[1], a[2], a[3], a[4], a[5]);
}

// The kernel version programs see: at least $ANDROID_NATIVE_KERNEL (say
// 5.10.0). Android 17's BPF loader refuses older kernels, though in native
// mode no kernel BPF is used at all; features programs probe for directly
// (system calls, files) are still the phone's.
static long nr_kver(const char* s) {
  long v[3] = {0, 0, 0};
  for (int i = 0; i < 3 && *s; i++) {
    v[i] = strtol(s, const_cast<char**>(&s), 10);
    if (*s == '.') s++;
    else break;
  }
  return (v[0] << 20) | (v[1] << 10) | v[2];
}

extern "C" int uname(struct utsname* buf) {
  int r = __nrp_uname(buf);
  size_t rl;
  if (r == 0 && nr_root(&rl) != nullptr) {
    const char* want = getenv("ANDROID_NATIVE_KERNEL");
    if (want != nullptr && *want && nr_kver(buf->release) < nr_kver(want)) {
      snprintf(buf->release, sizeof(buf->release), "%s-nmode", want);
    }
  }
  return r;
}
