/*
 * Userspace eBPF objects for native mode.
 *
 * Android's networking (the connectivity module, netbpfload) keeps its state
 * in BPF maps pinned under /sys/fs/bpf, and checks them at boot. An app may
 * not use bpf(2) at all, so in native mode libc answers it: every map,
 * program, BTF blob or link is a file (an O_TMPFILE under
 * /sys/fs/bpf/.nbpf, which libc maps into the root), pinning is a hard link
 * of it, and map operations work on the file's contents, mapped shared, so
 * every process sees the same map. Programs are kept but never run: nothing
 * in native mode feeds packets through them, and the maps they would fill
 * stay as user space leaves them.
 *
 * Object file layout: data at offset 0 (so that mmap-able arrays and ring
 * buffers look as the kernel lays them out), then a page with nbpf_header.
 */
#include <errno.h>
#include <fcntl.h>
#include <linux/bpf.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "native_bpf.h"

namespace {

constexpr uint32_t kMagic = 0x6670626e;  // 'nbpf'
constexpr size_t kHeaderSize = 4096;
#ifdef NBPF_HOST_TEST
#define kBpfDir NBPF_HOST_TEST
#else
#define kBpfDir "/sys/fs/bpf"
#endif
constexpr char kObjDir[] = kBpfDir "/.nbpf";

enum Kind : uint32_t { KIND_MAP = 1, KIND_PROG = 2, KIND_BTF = 3, KIND_LINK = 4 };

enum Layout : uint32_t {
  LAYOUT_HASH = 1,    // open addressing: slot = state, key, value
  LAYOUT_ARRAY = 2,   // value per index
  LAYOUT_RINGBUF = 3, // consumer page, producer page, data (mapped twice)
  LAYOUT_NONE = 4,    // no user-visible data (programs, BTF, links, ...)
};

struct nbpf_header {
  uint32_t magic;
  uint32_t kind;
  uint32_t type;  // map or program type
  uint32_t layout;
  uint32_t key_size;
  uint32_t value_size;  // as user space sees it (per-cpu: all cpus)
  uint32_t max_entries;
  uint32_t map_flags;
  uint32_t id;
  uint32_t nslots;
  uint32_t slot_size;
  uint32_t count;
  uint32_t frozen;
  uint32_t btf_id;
  uint32_t btf_key_type_id;
  uint32_t btf_value_type_id;
  uint64_t data_size;
  char name[16];
  uint32_t prog_insn_cnt;
};

uint64_t round8(uint64_t v) { return (v + 7) & ~7ull; }

uint32_t possible_cpus() {
  static uint32_t n = [] {
    long c = sysconf(_SC_NPROCESSORS_CONF);
    return static_cast<uint32_t>(c > 0 ? c : 1);
  }();
  return n;
}

bool is_percpu(uint32_t type) {
  return type == BPF_MAP_TYPE_PERCPU_HASH || type == BPF_MAP_TYPE_PERCPU_ARRAY ||
         type == BPF_MAP_TYPE_LRU_PERCPU_HASH || type == BPF_MAP_TYPE_PERCPU_CGROUP_STORAGE;
}

Layout layout_of(uint32_t type) {
  switch (type) {
    case BPF_MAP_TYPE_ARRAY:
    case BPF_MAP_TYPE_PERCPU_ARRAY:
    case BPF_MAP_TYPE_PROG_ARRAY:
    case BPF_MAP_TYPE_PERF_EVENT_ARRAY:
    case BPF_MAP_TYPE_CGROUP_ARRAY:
    case BPF_MAP_TYPE_ARRAY_OF_MAPS:
    case BPF_MAP_TYPE_DEVMAP:
    case BPF_MAP_TYPE_CPUMAP:
    case BPF_MAP_TYPE_XSKMAP:
      return LAYOUT_ARRAY;
    case BPF_MAP_TYPE_RINGBUF:
    case BPF_MAP_TYPE_USER_RINGBUF:
      return LAYOUT_RINGBUF;
    default:
      return LAYOUT_HASH;  // hash, LRU, LPM trie, devmap hash, storages, ...
  }
}

// ------------------------------------------------------------ object files

struct Mapped {
  uint64_t dev = 0;
  uint64_t ino = 0;
  uint8_t* base = nullptr;
  size_t len = 0;
  bool writable = false;
};

pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
Mapped g_maps[64];
size_t g_next_slot;

nbpf_header* header_of(const Mapped& m) {
  return reinterpret_cast<nbpf_header*>(m.base + m.len - kHeaderSize);
}

// The mapping of the object `fd` refers to (cached per inode), or nullptr
// with errno EBADF when `fd` isn't a BPF object.
const Mapped* object(int fd) {
  struct stat st;
  if (fd < 0 || fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) ||
      st.st_size < static_cast<off_t>(kHeaderSize)) {
    errno = EBADF;
    return nullptr;
  }
  pthread_mutex_lock(&g_lock);
  for (const Mapped& m : g_maps) {
    if (m.base && m.dev == st.st_dev && m.ino == st.st_ino &&
        m.len == static_cast<size_t>(st.st_size)) {
      pthread_mutex_unlock(&g_lock);
      return &m;
    }
  }
  int acc = fcntl(fd, F_GETFL) & O_ACCMODE;
  bool writable = acc == O_RDWR;
  void* p = mmap(nullptr, st.st_size, writable ? PROT_READ | PROT_WRITE : PROT_READ, MAP_SHARED,
                 fd, 0);
  if (p == MAP_FAILED) {
    pthread_mutex_unlock(&g_lock);
    errno = EBADF;
    return nullptr;
  }
  Mapped m{static_cast<uint64_t>(st.st_dev), static_cast<uint64_t>(st.st_ino),
           static_cast<uint8_t*>(p), static_cast<size_t>(st.st_size),
           writable};
  if (header_of(m)->magic != kMagic) {
    munmap(p, st.st_size);
    pthread_mutex_unlock(&g_lock);
    errno = EBADF;
    return nullptr;
  }
  Mapped& slot = g_maps[g_next_slot++ % (sizeof(g_maps) / sizeof(g_maps[0]))];
  if (slot.base) munmap(slot.base, slot.len);  // an old object's: remapped on next use
  slot = m;
  pthread_mutex_unlock(&g_lock);
  return &slot;
}

uint32_t next_id() {
  char path[64];
  snprintf(path, sizeof(path), "%s/next_id", kObjDir);
  int fd = open(path, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
  if (fd < 0) return 1;
  flock(fd, LOCK_EX);
  uint32_t id = 0;
  if (pread(fd, &id, sizeof(id), 0) != sizeof(id)) id = 0;
  id++;
  if (pwrite(fd, &id, sizeof(id), 0) != sizeof(id)) id = 1;
  flock(fd, LOCK_UN);
  close(fd);
  return id;
}

// A new, unpinned object with `data_size` bytes of data: returns its fd.
int create(const nbpf_header& h, uint64_t data_size) {
  mkdir(kBpfDir, 01777);
  mkdir(kObjDir, 0700);
  int fd = open(kObjDir, O_TMPFILE | O_RDWR | O_CLOEXEC, 0600);
  if (fd < 0) {
    // No O_TMPFILE: a named file, kept (pinning links it elsewhere).
    char path[96];
    snprintf(path, sizeof(path), "%s/obj-%u-%d", kObjDir, h.id, getpid());
    fd = open(path, O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (fd < 0) return -1;
  }
  nbpf_header hh = h;
  hh.data_size = (data_size + 4095) & ~4095ull;
  uint64_t len = hh.data_size + kHeaderSize;
  if (ftruncate(fd, static_cast<off_t>(len)) != 0 ||
      pwrite(fd, &hh, sizeof(hh), static_cast<off_t>(hh.data_size)) != sizeof(hh)) {
    close(fd);
    errno = ENOMEM;
    return -1;
  }
  return fd;
}

// ---------------------------------------------------------------- maps

struct Map {
  const Mapped* m;
  nbpf_header* h;
  uint8_t* data;
  int fd;
};

bool open_map(int fd, Map* out) {
  const Mapped* m = object(fd);
  if (!m) return false;
  nbpf_header* h = header_of(*m);
  if (h->kind != KIND_MAP) {
    errno = EINVAL;
    return false;
  }
  *out = Map{m, h, m->base, fd};
  return true;
}

struct Locker {
  int fd;
  Locker(int f, bool exclusive) : fd(f) { flock(fd, exclusive ? LOCK_EX : LOCK_SH); }
  ~Locker() { flock(fd, LOCK_UN); }
};

uint64_t hash_key(const void* key, uint32_t n) {
  const uint8_t* p = static_cast<const uint8_t*>(key);
  uint64_t h = 1469598103934665603ull;
  for (uint32_t i = 0; i < n; i++) h = (h ^ p[i]) * 1099511628211ull;
  return h;
}

enum : uint32_t { SLOT_EMPTY = 0, SLOT_USED = 1, SLOT_DELETED = 2 };

uint8_t* slot_at(const Map& mp, uint32_t i) { return mp.data + static_cast<uint64_t>(i) * mp.h->slot_size; }
uint32_t& slot_state(uint8_t* s) { return *reinterpret_cast<uint32_t*>(s); }
uint8_t* slot_key(uint8_t* s) { return s + 8; }
uint8_t* slot_value(const Map& mp, uint8_t* s) { return s + 8 + round8(mp.h->key_size); }

bool is_lpm(const Map& mp) { return mp.h->type == BPF_MAP_TYPE_LPM_TRIE; }

// Exact match: the slot holding `key`, or -1.
int64_t hash_find(const Map& mp, const void* key) {
  uint32_t n = mp.h->nslots;
  uint32_t start = static_cast<uint32_t>(hash_key(key, mp.h->key_size) % n);
  for (uint32_t k = 0; k < n; k++) {
    uint32_t i = (start + k) % n;
    uint8_t* s = slot_at(mp, i);
    uint32_t st = slot_state(s);
    if (st == SLOT_EMPTY) return -1;
    if (st == SLOT_USED && memcmp(slot_key(s), key, mp.h->key_size) == 0) return i;
  }
  return -1;
}

// LPM trie lookup: the longest stored prefix covering the key.
int64_t lpm_find(const Map& mp, const void* key) {
  const uint8_t* k = static_cast<const uint8_t*>(key);
  uint32_t qlen;
  memcpy(&qlen, k, 4);
  int64_t best = -1;
  uint32_t best_len = 0;
  for (uint32_t i = 0; i < mp.h->nslots; i++) {
    uint8_t* s = slot_at(mp, i);
    if (slot_state(s) != SLOT_USED) continue;
    uint32_t plen;
    memcpy(&plen, slot_key(s), 4);
    if (plen > qlen || (best >= 0 && plen < best_len)) continue;
    const uint8_t* a = slot_key(s) + 4;
    const uint8_t* b = k + 4;
    uint32_t bytes = plen / 8, bits = plen % 8;
    if (memcmp(a, b, bytes) != 0) continue;
    if (bits) {
      uint8_t mask = static_cast<uint8_t>(0xff << (8 - bits));
      if ((a[bytes] & mask) != (b[bytes] & mask)) continue;
    }
    best = i;
    best_len = plen;
  }
  return best;
}

uint32_t value_bytes(const nbpf_header* h) { return h->value_size; }

int map_lookup(const bpf_attr& a, bool and_delete) {
  Map mp;
  if (!open_map(a.map_fd, &mp)) return -1;
  void* key = reinterpret_cast<void*>(static_cast<uintptr_t>(a.key));
  void* value = reinterpret_cast<void*>(static_cast<uintptr_t>(a.value));
  Locker lock(a.map_fd, and_delete);
  if (mp.h->layout == LAYOUT_ARRAY) {
    uint32_t idx;
    memcpy(&idx, key, 4);
    if (idx >= mp.h->max_entries) {
      errno = ENOENT;
      return -1;
    }
    memcpy(value, mp.data + static_cast<uint64_t>(idx) * round8(value_bytes(mp.h)),
           value_bytes(mp.h));
    return 0;
  }
  if (mp.h->layout != LAYOUT_HASH) {
    errno = ENOTSUP;
    return -1;
  }
  int64_t i = is_lpm(mp) ? lpm_find(mp, key) : hash_find(mp, key);
  if (i < 0) {
    errno = ENOENT;
    return -1;
  }
  uint8_t* s = slot_at(mp, static_cast<uint32_t>(i));
  memcpy(value, slot_value(mp, s), value_bytes(mp.h));
  if (and_delete) {
    if (!mp.m->writable) {
      errno = EPERM;
      return -1;
    }
    slot_state(s) = SLOT_DELETED;
    mp.h->count--;
  }
  return 0;
}

int map_update(const bpf_attr& a) {
  Map mp;
  if (!open_map(a.map_fd, &mp)) return -1;
  if (!mp.m->writable || mp.h->frozen) {
    errno = EPERM;
    return -1;
  }
  const void* key = reinterpret_cast<const void*>(static_cast<uintptr_t>(a.key));
  const void* value = reinterpret_cast<const void*>(static_cast<uintptr_t>(a.value));
  uint64_t flags = a.flags & 3;  // BPF_ANY, BPF_NOEXIST, BPF_EXIST
  Locker lock(a.map_fd, true);
  if (mp.h->layout == LAYOUT_ARRAY) {
    uint32_t idx;
    memcpy(&idx, key, 4);
    if (idx >= mp.h->max_entries) {
      errno = E2BIG;
      return -1;
    }
    if (flags == BPF_NOEXIST) {
      errno = EEXIST;
      return -1;
    }
    memcpy(mp.data + static_cast<uint64_t>(idx) * round8(value_bytes(mp.h)), value,
           value_bytes(mp.h));
    return 0;
  }
  if (mp.h->layout != LAYOUT_HASH) {
    errno = EINVAL;
    return -1;
  }
  int64_t found = hash_find(mp, key);
  if (found >= 0) {
    if (flags == BPF_NOEXIST) {
      errno = EEXIST;
      return -1;
    }
    memcpy(slot_value(mp, slot_at(mp, static_cast<uint32_t>(found))), value, value_bytes(mp.h));
    return 0;
  }
  if (flags == BPF_EXIST) {
    errno = ENOENT;
    return -1;
  }
  bool lru = mp.h->type == BPF_MAP_TYPE_LRU_HASH || mp.h->type == BPF_MAP_TYPE_LRU_PERCPU_HASH;
  uint32_t n = mp.h->nslots;
  uint32_t start = static_cast<uint32_t>(hash_key(key, mp.h->key_size) % n);
  if (mp.h->count >= mp.h->max_entries) {
    if (!lru) {
      errno = E2BIG;
      return -1;
    }
    // Evict the first entry found from the key's place: LRU enough here.
    for (uint32_t k = 0; k < n; k++) {
      uint8_t* s = slot_at(mp, (start + k) % n);
      if (slot_state(s) == SLOT_USED) {
        slot_state(s) = SLOT_DELETED;
        mp.h->count--;
        break;
      }
    }
  }
  for (uint32_t k = 0; k < n; k++) {
    uint8_t* s = slot_at(mp, (start + k) % n);
    if (slot_state(s) != SLOT_USED) {
      memcpy(slot_key(s), key, mp.h->key_size);
      memcpy(slot_value(mp, s), value, value_bytes(mp.h));
      slot_state(s) = SLOT_USED;
      mp.h->count++;
      return 0;
    }
  }
  errno = E2BIG;
  return -1;
}

int map_delete(const bpf_attr& a) {
  Map mp;
  if (!open_map(a.map_fd, &mp)) return -1;
  if (!mp.m->writable || mp.h->frozen) {
    errno = EPERM;
    return -1;
  }
  if (mp.h->layout != LAYOUT_HASH) {
    errno = EINVAL;  // arrays can't delete
    return -1;
  }
  Locker lock(a.map_fd, true);
  int64_t i = hash_find(mp, reinterpret_cast<const void*>(static_cast<uintptr_t>(a.key)));
  if (i < 0) {
    errno = ENOENT;
    return -1;
  }
  slot_state(slot_at(mp, static_cast<uint32_t>(i))) = SLOT_DELETED;
  mp.h->count--;
  return 0;
}

int map_next_key(const bpf_attr& a) {
  Map mp;
  if (!open_map(a.map_fd, &mp)) return -1;
  const void* key = reinterpret_cast<const void*>(static_cast<uintptr_t>(a.key));
  void* next = reinterpret_cast<void*>(static_cast<uintptr_t>(a.next_key));
  Locker lock(a.map_fd, false);
  if (mp.h->layout == LAYOUT_ARRAY) {
    uint32_t idx = 0;
    if (key) {
      memcpy(&idx, key, 4);
      idx = idx >= mp.h->max_entries ? 0 : idx + 1;
    }
    if (idx >= mp.h->max_entries) {
      errno = ENOENT;
      return -1;
    }
    memcpy(next, &idx, 4);
    return 0;
  }
  if (mp.h->layout != LAYOUT_HASH) {
    errno = EINVAL;
    return -1;
  }
  uint32_t from = 0;
  if (key) {
    int64_t i = hash_find(mp, key);
    from = i < 0 ? 0 : static_cast<uint32_t>(i) + 1;  // missing key: start over, as the kernel
  }
  for (uint32_t i = from; i < mp.h->nslots; i++) {
    uint8_t* s = slot_at(mp, i);
    if (slot_state(s) == SLOT_USED) {
      memcpy(next, slot_key(s), mp.h->key_size);
      return 0;
    }
  }
  errno = ENOENT;
  return -1;
}

int map_create(const bpf_attr& a) {
  if (a.key_size > 512 || a.value_size > (1u << 20) || a.max_entries == 0 ||
      a.max_entries > (1u << 24)) {
    errno = EINVAL;
    return -1;
  }
  nbpf_header h{};
  h.magic = kMagic;
  h.kind = KIND_MAP;
  h.type = a.map_type;
  h.layout = layout_of(a.map_type);
  h.key_size = a.key_size;
  h.value_size = is_percpu(a.map_type) ? static_cast<uint32_t>(round8(a.value_size) * possible_cpus())
                                       : a.value_size;
  h.max_entries = a.max_entries;
  h.map_flags = a.map_flags;
  h.id = next_id();
  h.btf_id = 0;
  h.btf_key_type_id = a.btf_key_type_id;
  h.btf_value_type_id = a.btf_value_type_id;
  memcpy(h.name, a.map_name, sizeof(h.name));
  uint64_t data = 0;
  switch (h.layout) {
    case LAYOUT_ARRAY:
      data = round8(h.value_size) * h.max_entries;
      break;
    case LAYOUT_RINGBUF:
      // consumer page, producer page, then the data twice (as the kernel maps it)
      data = 2 * 4096 + 2 * static_cast<uint64_t>(h.max_entries);
      break;
    default:
      h.nslots = h.max_entries * 2 + 1;
      h.slot_size = static_cast<uint32_t>(8 + round8(h.key_size) + round8(h.value_size));
      data = static_cast<uint64_t>(h.nslots) * h.slot_size;
  }
  if (data > (1ull << 31)) {
    errno = ENOMEM;
    return -1;
  }
  return create(h, data);
}

int other_create(Kind kind, uint32_t type, const char* name, uint32_t insns) {
  nbpf_header h{};
  h.magic = kMagic;
  h.kind = kind;
  h.type = type;
  h.layout = LAYOUT_NONE;
  h.id = next_id();
  h.prog_insn_cnt = insns;
  if (name) memcpy(h.name, name, sizeof(h.name));
  return create(h, 0);
}

int obj_pin(const bpf_attr& a) {
  if (!object(a.bpf_fd)) return -1;
  const char* path = reinterpret_cast<const char*>(static_cast<uintptr_t>(a.pathname));
  char self[32];
  snprintf(self, sizeof(self), "/proc/self/fd/%d", a.bpf_fd);
  if (linkat(AT_FDCWD, self, AT_FDCWD, path, AT_SYMLINK_FOLLOW) == 0) return 0;
  if (errno == EEXIST) return -1;
  // A named object (no O_TMPFILE) links the same way; if linking isn't
  // possible at all, copy: the pinned file then is the object from here on.
  return -1;
}

int obj_get(const bpf_attr& a) {
  const char* path = reinterpret_cast<const char*>(static_cast<uintptr_t>(a.pathname));
  int mode = (a.file_flags & BPF_F_RDONLY) ? O_RDONLY : O_RDWR;
  int fd = open(path, mode | O_CLOEXEC);
  if (fd < 0 && errno == EACCES && mode == O_RDWR) fd = open(path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return -1;
  if (!object(fd)) {
    close(fd);
    errno = EINVAL;
    return -1;
  }
  return fd;
}

int obj_info(const bpf_attr& a) {
  const Mapped* m = object(a.info.bpf_fd);
  if (!m) return -1;
  const nbpf_header* h = header_of(*m);
  void* out = reinterpret_cast<void*>(static_cast<uintptr_t>(a.info.info));
  uint32_t len = a.info.info_len;
  if (h->kind == KIND_MAP) {
    bpf_map_info info{};
    info.type = h->type;
    info.id = h->id;
    info.key_size = h->key_size;
    info.value_size = is_percpu(h->type) ? h->value_size / possible_cpus() : h->value_size;
    info.max_entries = h->max_entries;
    info.map_flags = h->map_flags;
    memcpy(info.name, h->name, sizeof(info.name));
    info.btf_key_type_id = h->btf_key_type_id;
    info.btf_value_type_id = h->btf_value_type_id;
    memcpy(out, &info, len < sizeof(info) ? len : sizeof(info));
  } else if (h->kind == KIND_PROG) {
    bpf_prog_info info{};
    info.type = h->type;
    info.id = h->id;
    memcpy(info.name, h->name, sizeof(info.name));
    memcpy(out, &info, len < sizeof(info) ? len : sizeof(info));
  } else if (h->kind == KIND_BTF) {
    bpf_btf_info info{};
    info.id = h->id;
    memcpy(out, &info, len < sizeof(info) ? len : sizeof(info));
  } else {
    bpf_link_info info{};
    info.id = h->id;
    memcpy(out, &info, len < sizeof(info) ? len : sizeof(info));
  }
  return 0;
}

}  // namespace

long nbpf_syscall(int cmd, void* uattr, unsigned int size) {
  bpf_attr a;
  memset(&a, 0, sizeof(a));
  if (uattr) memcpy(&a, uattr, size < sizeof(a) ? size : sizeof(a));
  int saved = errno;
  long r;
  switch (cmd) {
    case BPF_MAP_CREATE: r = map_create(a); break;
    case BPF_MAP_LOOKUP_ELEM: r = map_lookup(a, false); break;
    case BPF_MAP_LOOKUP_AND_DELETE_ELEM: r = map_lookup(a, true); break;
    case BPF_MAP_UPDATE_ELEM: r = map_update(a); break;
    case BPF_MAP_DELETE_ELEM: r = map_delete(a); break;
    case BPF_MAP_GET_NEXT_KEY: r = map_next_key(a); break;
    case BPF_MAP_FREEZE: {
      Map mp;
      r = open_map(a.map_fd, &mp) ? (mp.h->frozen = 1, 0) : -1;
      break;
    }
    case BPF_PROG_LOAD:
      r = other_create(KIND_PROG, a.prog_type, a.prog_name, a.insn_cnt);
      break;
    case BPF_BTF_LOAD: r = other_create(KIND_BTF, 0, nullptr, 0); break;
    case BPF_LINK_CREATE:
    case BPF_RAW_TRACEPOINT_OPEN:
      r = other_create(KIND_LINK, 0, nullptr, 0);
      break;
    case BPF_OBJ_PIN: r = obj_pin(a); break;
    case BPF_OBJ_GET: r = obj_get(a); break;
    case BPF_OBJ_GET_INFO_BY_FD:
      r = obj_info(a);
      if (r == 0 && uattr && size >= offsetof(bpf_attr, info) + sizeof(a.info)) {
        memcpy(uattr, &a, offsetof(bpf_attr, info) + sizeof(a.info));
      }
      break;
    case BPF_PROG_ATTACH:
    case BPF_PROG_DETACH:
    case BPF_LINK_UPDATE:
    case BPF_LINK_DETACH:
    case BPF_PROG_BIND_MAP:
      r = 0;  // nothing runs programs here
      break;
    case BPF_PROG_QUERY: {
      // No programs attached anywhere.
      a.query.prog_cnt = 0;
      if (uattr && size >= offsetof(bpf_attr, query) + sizeof(a.query)) {
        memcpy(uattr, &a, offsetof(bpf_attr, query) + sizeof(a.query));
      }
      r = 0;
      break;
    }
    case BPF_MAP_GET_NEXT_ID:
    case BPF_PROG_GET_NEXT_ID:
    case BPF_BTF_GET_NEXT_ID:
    case BPF_LINK_GET_NEXT_ID:
      errno = ENOENT;
      r = -1;
      break;
    default:
      errno = EINVAL;
      r = -1;
  }
  if (r >= 0) errno = saved;
  return r;
}
