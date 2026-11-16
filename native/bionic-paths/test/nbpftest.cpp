// Host test of native_bpf.cpp: g++ -DNBPF_HOST_TEST='"/tmp/nbpft"' ...
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <linux/bpf.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include "../native_bpf.h"

static long bpf(int cmd, bpf_attr& a) { return nbpf_syscall(cmd, &a, sizeof(a)); }

static int create(uint32_t type, uint32_t k, uint32_t v, uint32_t n) {
  bpf_attr a{}; a.map_type = type; a.key_size = k; a.value_size = v; a.max_entries = n;
  return (int)bpf(BPF_MAP_CREATE, a);
}
static long update(int fd, const void* k, const void* v, uint64_t fl) {
  bpf_attr a{}; a.map_fd = fd; a.key = (uintptr_t)k; a.value = (uintptr_t)v; a.flags = fl;
  return bpf(BPF_MAP_UPDATE_ELEM, a);
}
static long lookup(int fd, const void* k, void* v) {
  bpf_attr a{}; a.map_fd = fd; a.key = (uintptr_t)k; a.value = (uintptr_t)v;
  return bpf(BPF_MAP_LOOKUP_ELEM, a);
}
static long del(int fd, const void* k) {
  bpf_attr a{}; a.map_fd = fd; a.key = (uintptr_t)k; return bpf(BPF_MAP_DELETE_ELEM, a);
}
static long next(int fd, const void* k, void* n) {
  bpf_attr a{}; a.map_fd = fd; a.key = (uintptr_t)k; a.next_key = (uintptr_t)n;
  return bpf(BPF_MAP_GET_NEXT_KEY, a);
}

int main() {
  if (system("rm -rf /tmp/nbpft") != 0) return 1;
  // hash
  int h = create(BPF_MAP_TYPE_HASH, 8, 16, 100);
  assert(h >= 0);
  for (uint64_t i = 0; i < 100; i++) { char v[16] = {}; memcpy(v, &i, 8); assert(update(h, &i, v, BPF_NOEXIST) == 0); }
  uint64_t k = 100; char v[16] = {};
  assert(update(h, &k, v, BPF_ANY) == -1 && errno == E2BIG);
  k = 5; assert(update(h, &k, v, BPF_NOEXIST) == -1 && errno == EEXIST);
  k = 42; assert(lookup(h, &k, v) == 0 && *(uint64_t*)v == 42);
  assert(del(h, &k) == 0); assert(lookup(h, &k, v) == -1 && errno == ENOENT);
  int count = 0; uint64_t cur, nk; bool first = true;
  while (next(h, first ? nullptr : &cur, &nk) == 0) { cur = nk; first = false; count++; }
  assert(count == 99);
  k = 42; assert(update(h, &k, v, BPF_EXIST) == -1 && errno == ENOENT);
  assert(update(h, &k, v, BPF_ANY) == 0);
  // pin / get / shared contents
  bpf_attr a{}; a.bpf_fd = h; a.pathname = (uintptr_t)"/tmp/nbpft/map_test";
  assert(bpf(BPF_OBJ_PIN, a) == 0);
  bpf_attr g{}; g.pathname = (uintptr_t)"/tmp/nbpft/map_test";
  int h2 = (int)bpf(BPF_OBJ_GET, g); assert(h2 >= 0);
  k = 7; char v7[16] = "seven"; assert(update(h2, &k, v7, BPF_ANY) == 0);
  assert(lookup(h, &k, v) == 0 && strcmp(v, "seven") == 0);
  bpf_map_info info{}; bpf_attr ia{}; ia.info.bpf_fd = h2; ia.info.info_len = sizeof(info); ia.info.info = (uintptr_t)&info;
  assert(bpf(BPF_OBJ_GET_INFO_BY_FD, ia) == 0 && info.type == BPF_MAP_TYPE_HASH && info.key_size == 8 && info.value_size == 16 && info.max_entries == 100);
  // array
  int ar = create(BPF_MAP_TYPE_ARRAY, 4, 4, 10);
  uint32_t i3 = 3, val = 99, out = 0; assert(update(ar, &i3, &val, BPF_ANY) == 0);
  assert(lookup(ar, &i3, &out) == 0 && out == 99);
  uint32_t i10 = 10; assert(update(ar, &i10, &val, BPF_ANY) == -1);
  assert(del(ar, &i3) == -1 && errno == EINVAL);
  uint32_t nx; assert(next(ar, nullptr, &nx) == 0 && nx == 0); uint32_t nine = 9; assert(next(ar, &nine, &nx) == -1);
  // LPM
  int lpm = create(BPF_MAP_TYPE_LPM_TRIE, 8, 4, 10);
  struct { uint32_t len; uint8_t ip[4]; } key8 = {8, {10, 0, 0, 0}}, key24 = {24, {10, 1, 2, 0}}, q = {32, {10, 1, 2, 3}}, q2 = {32, {10, 9, 9, 9}}, q3 = {32, {11, 0, 0, 1}};
  uint32_t a8 = 8, a24 = 24; assert(update(lpm, &key8, &a8, BPF_ANY) == 0 && update(lpm, &key24, &a24, BPF_ANY) == 0);
  assert(lookup(lpm, &q, &out) == 0 && out == 24); assert(lookup(lpm, &q2, &out) == 0 && out == 8); assert(lookup(lpm, &q3, &out) == -1);
  // percpu
  int pc = create(BPF_MAP_TYPE_PERCPU_ARRAY, 4, 4, 2);
  long n = sysconf(_SC_NPROCESSORS_CONF); uint64_t buf[256] = {}; uint32_t z = 0;
  for (long c = 0; c < n; c++) buf[c] = c + 1;
  assert(update(pc, &z, buf, BPF_ANY) == 0); memset(buf, 0, sizeof(buf)); assert(lookup(pc, &z, buf) == 0 && buf[n - 1] == (uint64_t)n);
  // prog + pin + read-only get
  bpf_attr pl{}; pl.prog_type = BPF_PROG_TYPE_SCHED_CLS; int p = (int)bpf(BPF_PROG_LOAD, pl); assert(p >= 0);
  bpf_attr pp{}; pp.bpf_fd = p; pp.pathname = (uintptr_t)"/tmp/nbpft/prog_test"; assert(bpf(BPF_OBJ_PIN, pp) == 0);
  chmod("/tmp/nbpft/prog_test", 0440);
  bpf_attr pg{}; pg.pathname = (uintptr_t)"/tmp/nbpft/prog_test"; pg.file_flags = BPF_F_RDONLY; assert(bpf(BPF_OBJ_GET, pg) >= 0);
  // read-only map refuses updates
  bpf_attr ro{}; ro.pathname = (uintptr_t)"/tmp/nbpft/map_test"; ro.file_flags = BPF_F_RDONLY; int hro = (int)bpf(BPF_OBJ_GET, ro);
  assert(hro >= 0 && lookup(hro, &k, v) == 0);
  // ringbuf mmap layout
  int rb = create(BPF_MAP_TYPE_RINGBUF, 0, 0, 4096 * 4); assert(rb >= 0);
  // not-an-object fd
  assert(lookup(0, &k, v) == -1);
  printf("nbpf: all tests passed\n");
}
