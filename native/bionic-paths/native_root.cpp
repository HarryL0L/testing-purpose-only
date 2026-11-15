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
 */

#include <alloca.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/types.h>
#include <sys/xattr.h>
#include <unistd.h>

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
ssize_t __nrp_getxattr(const char*, const char*, void*, size_t);
ssize_t __nrp_lgetxattr(const char*, const char*, void*, size_t);
ssize_t __nrp_listxattr(const char*, char*, size_t);
ssize_t __nrp_llistxattr(const char*, char*, size_t);
int __nrp_removexattr(const char*, const char*);
int __nrp_lremovexattr(const char*, const char*);
int __nrp_statx(int, const char*, int, unsigned, struct statx*);
int __nrp_inotify_add_watch(int, const char*, uint32_t);
int __nrp_execve(const char*, char* const*, char* const*);
}

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
  if (nr_under(p, "/proc", 5) || nr_under(p, "/sys", 4)) return true;
  if (nr_under(p, "/dev", 4)) {
    // Android's property area and sockets come from the image.
    return !nr_under(p, "/dev/__properties__", 19) && !nr_under(p, "/dev/socket", 11);
  }
  return false;
}

// Map an absolute image path to the host path under the root. `buf` holds
// PATH_MAX bytes. Relative paths, host-only paths and paths already under
// the root are returned unchanged; a path too long to map gives nullptr.
static const char* nr_map(const char* path, char* buf) {
  size_t rl;
  const char* root = nr_root(&rl);
  if (root == nullptr || path == nullptr || path[0] != '/') return path;
  if (nr_host_path(path) || nr_under(path, root, rl)) return path;
  size_t pl = strlen(path);
  if (rl + pl + 1 > PATH_MAX) {
    errno = ENAMETOOLONG;
    return nullptr;
  }
  memcpy(buf, root, rl);
  memcpy(buf + rl, path, pl + 1);
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

#define NR_MAP(var, path)                      \
  char var##_buf[PATH_MAX];                    \
  const char* var = nr_map(path, var##_buf);   \
  if (var == nullptr && (path) != nullptr) return -1

extern "C" int __openat(int fd, const char* path, int flags, int mode) {
  NR_MAP(p, path);
  return __nrp_openat(fd, p, flags, mode);
}

extern "C" int __faccessat(int fd, const char* path, int mode) {
  NR_MAP(p, path);
  return __nrp_faccessat(fd, p, mode);
}

extern "C" int __fchmodat(int fd, const char* path, mode_t mode) {
  NR_MAP(p, path);
  return __nrp_fchmodat(fd, p, mode);
}

extern "C" int fchownat(int fd, const char* path, uid_t owner, gid_t group, int flags) {
  NR_MAP(p, path);
  return __nrp_fchownat(fd, p, owner, group, flags);
}

extern "C" int fstatat(int fd, const char* path, struct stat* st, int flags) {
  NR_MAP(p, path);
  return __nrp_fstatat64(fd, p, st, flags);
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
  return __nrp_symlinkat(t, fd, p);
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
  return __nrp_statfs(p, buf);
}
#else
extern "C" int truncate64(const char* path, off64_t length) {
  NR_MAP(p, path);
  return __nrp_truncate64(p, length);
}

extern "C" int __statfs64(const char* path, size_t size, struct statfs* buf) {
  NR_MAP(p, path);
  return __nrp_statfs64(p, size, buf);
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

extern "C" int setxattr(const char* path, const char* name, const void* value, size_t size, int flags) {
  NR_MAP(p, path);
  return __nrp_setxattr(p, name, value, size, flags);
}

extern "C" int lsetxattr(const char* path, const char* name, const void* value, size_t size, int flags) {
  NR_MAP(p, path);
  return __nrp_lsetxattr(p, name, value, size, flags);
}

extern "C" ssize_t getxattr(const char* path, const char* name, void* value, size_t size) {
  NR_MAP(p, path);
  return __nrp_getxattr(p, name, value, size);
}

extern "C" ssize_t lgetxattr(const char* path, const char* name, void* value, size_t size) {
  NR_MAP(p, path);
  return __nrp_lgetxattr(p, name, value, size);
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
  return __nrp_statx(fd, p, flags, mask, buf);
}

extern "C" int inotify_add_watch(int fd, const char* path, uint32_t mask) {
  NR_MAP(p, path);
  return __nrp_inotify_add_watch(fd, p, mask);
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
static char* const* nr_env(char* const* envp, char** out, size_t out_count, char* var_buf) {
  size_t n = 0;
  if (envp != nullptr) {
    for (; envp[n] != nullptr; n++) {
      if (strncmp(envp[n], kRootVar, sizeof(kRootVar) - 1) == 0 &&
          envp[n][sizeof(kRootVar) - 1] == '=') {
        return envp;
      }
    }
  }
  if (n + 2 > out_count) return envp;
  size_t rl;
  const char* root = nr_root(&rl);
  memcpy(var_buf, kRootVar, sizeof(kRootVar) - 1);
  var_buf[sizeof(kRootVar) - 1] = '=';
  memcpy(var_buf + sizeof(kRootVar), root, rl + 1);
  for (size_t i = 0; i < n; i++) out[i] = envp[i];
  out[n] = var_buf;
  out[n + 1] = nullptr;
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
  char** env_store = static_cast<char**>(alloca((envc + 2) * sizeof(char*)));
  char* env_var = static_cast<char*>(alloca(sizeof(kRootVar) + rl + 1));
  char* const* env = nr_env(envp, env_store, envc + 2, env_var);

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
  char interp[PATH_MAX];
  bool dynamic = false;
  if (got >= static_cast<ssize_t>(EI_NIDENT) && memcmp(head, ELFMAG, SELFMAG) == 0) {
    if (head[EI_CLASS] == ELFCLASS64 && got >= static_cast<ssize_t>(sizeof(Elf64_Ehdr))) {
      dynamic = nr_elf_interp<Elf64_Ehdr, Elf64_Phdr>(
          fd, reinterpret_cast<const Elf64_Ehdr*>(head), interp, sizeof(interp));
    } else if (head[EI_CLASS] == ELFCLASS32 && got >= static_cast<ssize_t>(sizeof(Elf32_Ehdr))) {
      dynamic = nr_elf_interp<Elf32_Ehdr, Elf32_Phdr>(
          fd, reinterpret_cast<const Elf32_Ehdr*>(head), interp, sizeof(interp));
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
