/*
 * nalloc: android.hardware.graphics.allocator.IAllocator/default (V2) of
 * native mode. Every buffer is one fd (see nmem.h): a dma-buf from the
 * phone's ION system heap, which the GPU (Mali kbase, PanVK) can import, or
 * a memfd where there's no ION (a computer). The mapper half is
 * mapper.native.so.
 */
#include <aidl/android/hardware/graphics/allocator/AllocationError.h>
#include <aidl/android/hardware/graphics/allocator/BnAllocator.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android/log.h>
#include <fcntl.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#include <atomic>
#include <mutex>

#include "nmem.h"
#include "nmem_format.h"

using aidl::android::hardware::common::NativeHandle;
using aidl::android::hardware::graphics::allocator::AllocationError;
using aidl::android::hardware::graphics::allocator::AllocationResult;
using aidl::android::hardware::graphics::allocator::BnAllocator;
using aidl::android::hardware::graphics::allocator::BufferDescriptorInfo;

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "nalloc", __VA_ARGS__)

namespace {

std::atomic<uint64_t> g_next_id{1};

ndk::ScopedAStatus Error(AllocationError e) {
  return ndk::ScopedAStatus::fromServiceSpecificError(static_cast<int32_t>(e));
}

// ---- ION (kernels before dma-buf heaps; MediaTek's 4.19 on the phone) ----
// Heaps are listed with the 4.12+ query; MediaTek's ION allocates only with
// the older interface (a handle, then ION_IOC_SHARE for its dma-buf), so
// both are tried.
struct IonAllocData {  // 4.12+
  uint64_t len;
  uint32_t heap_id_mask;
  uint32_t flags;
  uint32_t fd;
  uint32_t unused;
};
struct IonAllocDataLegacy {
  size_t len;
  size_t align;
  unsigned int heap_id_mask;
  unsigned int flags;
  int handle;
};
struct IonFdData {
  int handle;
  int fd;
};
struct IonHandleData {
  int handle;
};
struct IonHeapData {
  char name[32];
  uint32_t type;
  uint32_t heap_id;
  uint32_t reserved0, reserved1, reserved2;
};
struct IonHeapQuery {
  uint32_t cnt;
  uint32_t reserved0;
  uint64_t heaps;
  uint32_t reserved1, reserved2;
};
#define ION_IOC_ALLOC _IOWR('I', 0, IonAllocData)
#define ION_IOC_HEAP_QUERY _IOWR('I', 8, IonHeapQuery)
#define ION_IOC_ALLOC_LEGACY _IOWR('I', 0, IonAllocDataLegacy)
#define ION_IOC_FREE_LEGACY _IOWR('I', 1, IonHandleData)
#define ION_IOC_SHARE_LEGACY _IOWR('I', 4, IonFdData)
constexpr uint32_t kIonHeapTypeSystem = 0;
constexpr uint32_t kIonFlagCached = 1;  // CPU access goes through the cache (the mapper syncs)

int g_ion = -2;  // -2: not opened yet, -1: none
uint32_t g_ion_heap_mask;

void OpenIon() {
  g_ion = open("/dev/ion", O_RDONLY | O_CLOEXEC);
  if (g_ion < 0) return;
  g_ion_heap_mask = 1u << 0;  // the system heap's usual id
  IonHeapQuery q{};
  IonHeapData heaps[32] = {};
  if (ioctl(g_ion, ION_IOC_HEAP_QUERY, &q) == 0 && q.cnt > 0) {
    q.cnt = q.cnt > 32 ? 32 : q.cnt;
    q.heaps = reinterpret_cast<uintptr_t>(heaps);
    if (ioctl(g_ion, ION_IOC_HEAP_QUERY, &q) == 0) {
      for (uint32_t i = 0; i < q.cnt; i++) {
        if (heaps[i].type == kIonHeapTypeSystem) g_ion_heap_mask = 1u << heaps[i].heap_id;
      }
    }
  }
}

// A dma-buf of `size` bytes from ION's system heap, or -1.
int IonAlloc(uint64_t size) {
  static std::once_flag once;
  std::call_once(once, OpenIon);
  if (g_ion < 0) return -1;
  size = (size + 4095) & ~4095ull;
  IonAllocData a{size, g_ion_heap_mask, kIonFlagCached, 0, 0};
  if (ioctl(g_ion, ION_IOC_ALLOC, &a) == 0) return static_cast<int>(a.fd);
  IonAllocDataLegacy l{static_cast<size_t>(size), 4096, g_ion_heap_mask, kIonFlagCached, 0};
  if (ioctl(g_ion, ION_IOC_ALLOC_LEGACY, &l) != 0) {
    LOGE("ION alloc %llu: %s", (unsigned long long)size, strerror(errno));
    return -1;
  }
  IonFdData share{l.handle, -1};
  int r = ioctl(g_ion, ION_IOC_SHARE_LEGACY, &share);
  IonHandleData h{l.handle};
  ioctl(g_ion, ION_IOC_FREE_LEGACY, &h);  // the dma-buf keeps the memory
  if (r != 0) {
    LOGE("ION share: %s", strerror(errno));
    return -1;
  }
  fcntl(share.fd, F_SETFD, FD_CLOEXEC);
  return share.fd;
}

bool Allocate(const BufferDescriptorInfo& d, const nmem::Layout& l, NativeHandle* out) {
  uint64_t usage = static_cast<uint64_t>(d.usage);
  uint64_t reserved = d.reservedSize > 0 ? static_cast<uint64_t>(d.reservedSize) : 0;
  char name[129] = {};
  memcpy(name, d.name.data(), 128);
  uint64_t total = l.size + NMEM_META_SIZE + reserved;
  int fd = IonAlloc(total);
  if (fd < 0) {
    fd = memfd_create(name[0] ? name : "nmem", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (fd < 0) {
      LOGE("memfd_create: %s", strerror(errno));
      return false;
    }
    if (ftruncate(fd, total) != 0) {
      LOGE("ftruncate %llu: %s", (unsigned long long)l.size, strerror(errno));
      close(fd);
      return false;
    }
  }
  nmem_meta meta{};
  meta.magic = NMEM_MAGIC;
  meta.dataspace = 0;  // UNKNOWN
  meta.blend_mode = 0;  // INVALID
  meta.crop[2] = d.width;
  meta.crop[3] = d.height;
  for (auto& n : meta.dyn_len) n = ~0u;
  memcpy(meta.name, name, sizeof(meta.name) - 1);
  // Through a mapping: a dma-buf takes no pwrite(). The metadata page starts
  // at l.size, which needn't be page-aligned.
  uint64_t map_off = l.size & ~4095ull;
  uint64_t map_len = l.size - map_off + NMEM_META_SIZE;
  void* p = mmap(nullptr, map_len, PROT_READ | PROT_WRITE, MAP_SHARED, fd,
                 static_cast<off_t>(map_off));
  if (p == MAP_FAILED) {
    LOGE("mmap for metadata: %s", strerror(errno));
    close(fd);
    return false;
  }
  memcpy(static_cast<uint8_t*>(p) + (l.size - map_off), &meta, sizeof(meta));
  munmap(p, map_len);
  uint64_t id = (static_cast<uint64_t>(getpid()) << 40) | g_next_id++;
  nmem_ints h{};
  h.magic = NMEM_MAGIC;
  h.width = d.width;
  h.height = d.height;
  h.layers = d.layerCount ? d.layerCount : 1;
  h.format = static_cast<int32_t>(d.format);
  h.real_format = l.format;
  h.stride = l.stride;
  h.bpp = l.bpp;
  h.usage_lo = (uint32_t)usage;
  h.usage_hi = (uint32_t)(usage >> 32);
  h.size_lo = (uint32_t)l.size;
  h.size_hi = (uint32_t)(l.size >> 32);
  h.reserved_lo = (uint32_t)reserved;
  h.reserved_hi = (uint32_t)(reserved >> 32);
  h.id_lo = (uint32_t)id;
  h.id_hi = (uint32_t)(id >> 32);
  h.fourcc = l.fourcc;
  h.pid = getpid();
  out->fds.clear();
  out->fds.emplace_back(fd);
  out->ints.resize(NMEM_NUM_INTS);
  memcpy(out->ints.data(), &h, sizeof(h));
  return true;
}

class Allocator : public BnAllocator {
 public:
  ndk::ScopedAStatus allocate(const std::vector<uint8_t>&, int32_t, AllocationResult*) override {
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
  }

  ndk::ScopedAStatus allocate2(const BufferDescriptorInfo& d, int32_t count,
                               AllocationResult* result) override {
    nmem::Layout l;
    if (count < 1 || count > 64 || d.reservedSize < 0 || d.reservedSize > (1 << 24))
      return Error(AllocationError::BAD_DESCRIPTOR);
    if (!nmem::layout(static_cast<int32_t>(d.format), static_cast<uint64_t>(d.usage), d.width,
                      d.height, d.layerCount, &l))
      return Error(AllocationError::UNSUPPORTED);
    result->stride = l.stride;
    result->buffers.resize(count);
    for (int i = 0; i < count; i++) {
      if (!Allocate(d, l, &result->buffers[i])) {
        result->buffers.clear();
        return Error(AllocationError::NO_RESOURCES);
      }
    }
    return ndk::ScopedAStatus::ok();
  }

  ndk::ScopedAStatus isSupported(const BufferDescriptorInfo& d, bool* out) override {
    nmem::Layout l;
    *out = nmem::layout(static_cast<int32_t>(d.format), static_cast<uint64_t>(d.usage), d.width,
                        d.height, d.layerCount, &l);
    return ndk::ScopedAStatus::ok();
  }

  ndk::ScopedAStatus getIMapperLibrarySuffix(std::string* out) override {
    *out = "native";
    return ndk::ScopedAStatus::ok();
  }
};

}  // namespace

int main() {
  ABinderProcess_setThreadPoolMaxThreadCount(4);
  ABinderProcess_startThreadPool();
  auto service = ndk::SharedRefBase::make<Allocator>();
  std::string name = std::string(Allocator::descriptor) + "/default";
  binder_status_t st = AServiceManager_addService(service->asBinder().get(), name.c_str());
  if (st != STATUS_OK) {
    LOGE("addService %s: %d", name.c_str(), st);
    return 1;
  }
  ABinderProcess_joinThreadPool();
  return 1;
}
