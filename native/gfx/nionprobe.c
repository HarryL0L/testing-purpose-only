// nionprobe: can this app allocate GPU-shareable buffers from ION?
//
//   nionprobe
//
// Kernels before dma-buf heaps (4.19 and older) hand out dma-bufs through
// /dev/ion. This lists ION's heaps (the 4.12+ interface), allocates a buffer
// from each, and checks it maps and holds data: what native mode's buffer
// allocator would do for buffers the GPU (Mali kbase) can import. Plain C;
// Termux's clang builds it: clang -O2 nionprobe.c -o nionprobe
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

struct ion_allocation_data {
  uint64_t len;
  uint32_t heap_id_mask;
  uint32_t flags;
  uint32_t fd;
  uint32_t unused;
};

struct ion_heap_data {
  char name[32];
  uint32_t type;
  uint32_t heap_id;
  uint32_t reserved0, reserved1, reserved2;
};

struct ion_heap_query {
  uint32_t cnt;
  uint32_t reserved0;
  uint64_t heaps;
  uint32_t reserved1, reserved2;
};

#define ION_IOC_ALLOC _IOWR('I', 0, struct ion_allocation_data)
#define ION_IOC_HEAP_QUERY _IOWR('I', 8, struct ion_heap_query)

static const char* type_name(uint32_t t) {
  switch (t) {
    case 0: return "system";
    case 1: return "system_contig";
    case 2: return "carveout";
    case 3: return "chunk";
    case 4: return "dma";
    default: return "vendor";
  }
}

static void try_alloc(int ion, uint32_t heap_id, const char* name) {
  struct ion_allocation_data a = {1 << 20, 1u << heap_id, 0, 0, 0};
  if (ioctl(ion, ION_IOC_ALLOC, &a) != 0) {
    printf("  FAIL alloc 1 MiB from heap %u (%s): %s\n", heap_id, name, strerror(errno));
    return;
  }
  int fd = (int)a.fd;
  unsigned char* p = mmap(NULL, a.len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  if (p == MAP_FAILED) {
    printf("  FAIL heap %u (%s): allocated (fd %d) but mmap: %s\n", heap_id, name, fd,
           strerror(errno));
    close(fd);
    return;
  }
  memset(p, 0x5a, a.len);
  int good = p[0] == 0x5a && p[a.len - 1] == 0x5a;
  munmap(p, a.len);
  close(fd);
  printf("  %s heap %u (%s): 1 MiB allocated, mapped, %s\n", good ? "ok  " : "FAIL", heap_id, name,
         good ? "holds data" : "data didn't stick");
}

int main(void) {
  int ion = open("/dev/ion", O_RDONLY | O_CLOEXEC);
  if (ion < 0) {
    printf("FAIL open /dev/ion: %s\n", strerror(errno));
    return 1;
  }
  printf("ok   /dev/ion opened\n");
  struct ion_heap_query q = {0};
  if (ioctl(ion, ION_IOC_HEAP_QUERY, &q) != 0) {
    printf("FAIL heap query: %s (an older ION interface?); trying heaps 0..31\n", strerror(errno));
    for (uint32_t id = 0; id < 32; id++) try_alloc(ion, id, "?");
    return 0;
  }
  struct ion_heap_data heaps[32];
  memset(heaps, 0, sizeof(heaps));
  if (q.cnt > 32) q.cnt = 32;
  q.heaps = (uint64_t)(uintptr_t)heaps;
  if (ioctl(ion, ION_IOC_HEAP_QUERY, &q) != 0) {
    printf("FAIL heap list: %s\n", strerror(errno));
    return 1;
  }
  printf("ok   %u heaps\n", q.cnt);
  for (uint32_t i = 0; i < q.cnt; i++) {
    printf("heap %u: %s (%s)\n", heaps[i].heap_id, heaps[i].name, type_name(heaps[i].type));
    try_alloc(ion, heaps[i].heap_id, heaps[i].name);
  }
  return 0;
}
