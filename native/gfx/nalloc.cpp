/*
 * nalloc: android.hardware.graphics.allocator.IAllocator/default (V2) of
 * native mode. Every buffer is a memfd (see nmem.h); the mapper half is
 * mapper.native.so.
 */
#include <aidl/android/hardware/graphics/allocator/AllocationError.h>
#include <aidl/android/hardware/graphics/allocator/BnAllocator.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android/log.h>
#include <fcntl.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include <atomic>

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

bool Allocate(const BufferDescriptorInfo& d, const nmem::Layout& l, NativeHandle* out) {
  uint64_t usage = static_cast<uint64_t>(d.usage);
  uint64_t reserved = d.reservedSize > 0 ? static_cast<uint64_t>(d.reservedSize) : 0;
  char name[129] = {};
  memcpy(name, d.name.data(), 128);
  int fd = memfd_create(name[0] ? name : "nmem", MFD_CLOEXEC | MFD_ALLOW_SEALING);
  if (fd < 0) {
    LOGE("memfd_create: %s", strerror(errno));
    return false;
  }
  if (ftruncate(fd, l.size + NMEM_META_SIZE + reserved) != 0) {
    LOGE("ftruncate %llu: %s", (unsigned long long)l.size, strerror(errno));
    close(fd);
    return false;
  }
  nmem_meta meta{};
  meta.magic = NMEM_MAGIC;
  meta.dataspace = 0;  // UNKNOWN
  meta.blend_mode = 0;  // INVALID
  meta.crop[2] = d.width;
  meta.crop[3] = d.height;
  for (auto& n : meta.dyn_len) n = ~0u;
  memcpy(meta.name, name, sizeof(meta.name) - 1);
  if (pwrite(fd, &meta, sizeof(meta), l.size) != (ssize_t)sizeof(meta)) {
    close(fd);
    return false;
  }
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
