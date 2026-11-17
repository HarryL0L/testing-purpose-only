/*
 * mapper.native.so: the stable-C AIMapper (v5) of native mode, for the
 * memfd buffers nalloc hands out (see nmem.h).
 */
#include <android/hardware/graphics/mapper/IMapper.h>
#include <android/hardware/graphics/mapper/utils/IMapperMetadataTypes.h>
#include <android/hardware/graphics/mapper/utils/IMapperProvider.h>
#include <cutils/native_handle.h>
#include <aidl/android/hardware/graphics/common/ChromaSiting.h>
#include <aidl/android/hardware/graphics/common/Compression.h>
#include <aidl/android/hardware/graphics/common/Interlaced.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/dma-buf.h>
#include <poll.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <mutex>
#include <unordered_map>

#include "nmem.h"
#include "nmem_format.h"

using namespace ::android::hardware::graphics::mapper;
using ::aidl::android::hardware::graphics::common::ChromaSiting;
using ::aidl::android::hardware::graphics::common::Compression;
using ::aidl::android::hardware::graphics::common::Interlaced;

namespace {

constexpr const char* kStd = "android.hardware.graphics.common.StandardMetadataType";

struct Buf {
  nmem_ints h;
  nmem::Layout layout;
  uint8_t* base;
  size_t maplen;
  nmem_meta* meta;
  int locks;
};

const nmem_ints* Ints(const native_handle_t* nh) {
  if (!nh || nh->version != sizeof(native_handle_t) || nh->numFds != 1 ||
      nh->numInts != (int)NMEM_NUM_INTS)
    return nullptr;
  auto* h = reinterpret_cast<const nmem_ints*>(&nh->data[1]);
  return h->magic == NMEM_MAGIC ? h : nullptr;
}

// CPU access to a dma-buf (ION, cached) begins and ends with a cache sync, so
// the CPU sees what the GPU drew and the GPU what the CPU wrote. A memfd
// takes no such ioctl (ENOTTY), and needs none.
void SyncCpuAccess(buffer_handle_t buffer, uint64_t flags) {
  struct dma_buf_sync sync = {flags};
  while (ioctl(buffer->data[0], DMA_BUF_IOCTL_SYNC, &sync) < 0 && errno == EINTR) {
  }
}

void WaitFence(int fd) {
  if (fd < 0) return;
  struct pollfd p = {fd, POLLIN, 0};
  while (poll(&p, 1, -1) < 0 && errno == EINTR) {
  }
  close(fd);
}

using SMT = StandardMetadataType;

constexpr SMT kGettable[] = {
    SMT::BUFFER_ID, SMT::NAME, SMT::WIDTH, SMT::HEIGHT, SMT::LAYER_COUNT,
    SMT::PIXEL_FORMAT_REQUESTED, SMT::PIXEL_FORMAT_FOURCC, SMT::PIXEL_FORMAT_MODIFIER,
    SMT::USAGE, SMT::ALLOCATION_SIZE, SMT::PROTECTED_CONTENT, SMT::COMPRESSION,
    SMT::INTERLACED, SMT::CHROMA_SITING, SMT::PLANE_LAYOUTS, SMT::CROP, SMT::DATASPACE,
    SMT::BLEND_MODE, SMT::SMPTE2086, SMT::CTA861_3, SMT::SMPTE2094_10, SMT::SMPTE2094_40,
    SMT::STRIDE,
};

bool Settable(SMT t) {
  switch (t) {
    case SMT::CROP: case SMT::DATASPACE: case SMT::BLEND_MODE: case SMT::SMPTE2086:
    case SMT::CTA861_3: case SMT::SMPTE2094_10: case SMT::SMPTE2094_40:
      return true;
    default:
      return false;
  }
}

int DynIndex(SMT t) {
  return t == SMT::SMPTE2094_10 ? 0 : t == SMT::SMPTE2094_40 ? 1 : 2;
}

class NativeMapper final : public vendor::mapper::IMapperV5Impl {
 public:
  NativeMapper() {
    for (size_t i = 0; i < std::size(kGettable); i++) {
      mDescs[i] = AIMapper_MetadataTypeDescription{
          .metadataType = {kStd, static_cast<int64_t>(kGettable[i])},
          .description = nullptr,
          .isGettable = true,
          .isSettable = Settable(kGettable[i]),
          .reserved = {},
      };
    }
  }

  AIMapper_Error importBuffer(const native_handle_t* handle,
                              buffer_handle_t* outBufferHandle) override {
    const nmem_ints* h = Ints(handle);
    if (!h) return AIMAPPER_ERROR_BAD_BUFFER;
    Buf b{};
    b.h = *h;
    if (!nmem::layout(h->format, nmem_u64(h->usage_lo, h->usage_hi), h->width, h->height,
                      h->layers, &b.layout) ||
        b.layout.size != nmem_u64(h->size_lo, h->size_hi))
      return AIMAPPER_ERROR_BAD_BUFFER;
    b.maplen = b.layout.size + NMEM_META_SIZE + nmem_u64(h->reserved_lo, h->reserved_hi);
    struct stat st;
    if (fstat(handle->data[0], &st) != 0 || (uint64_t)st.st_size < b.maplen)
      return AIMAPPER_ERROR_BAD_BUFFER;
    native_handle_t* nh = native_handle_create(1, NMEM_NUM_INTS);
    if (!nh) return AIMAPPER_ERROR_NO_RESOURCES;
    nh->data[0] = fcntl(handle->data[0], F_DUPFD_CLOEXEC, 0);
    memcpy(&nh->data[1], h, sizeof(*h));
    if (nh->data[0] < 0) {
      native_handle_delete(nh);
      return AIMAPPER_ERROR_NO_RESOURCES;
    }
    void* p = mmap(nullptr, b.maplen, PROT_READ | PROT_WRITE, MAP_SHARED, nh->data[0], 0);
    if (p == MAP_FAILED) {
      native_handle_close(nh);
      native_handle_delete(nh);
      return AIMAPPER_ERROR_NO_RESOURCES;
    }
    b.base = static_cast<uint8_t*>(p);
    b.meta = reinterpret_cast<nmem_meta*>(b.base + b.layout.size);
    std::lock_guard<std::mutex> g(mLock);
    mBufs[nh] = b;
    *outBufferHandle = nh;
    return AIMAPPER_ERROR_NONE;
  }

  AIMapper_Error freeBuffer(buffer_handle_t buffer) override {
    Buf b;
    {
      std::lock_guard<std::mutex> g(mLock);
      auto it = mBufs.find(buffer);
      if (it == mBufs.end()) return AIMAPPER_ERROR_BAD_BUFFER;
      b = it->second;
      mBufs.erase(it);
    }
    munmap(b.base, b.maplen);
    auto* nh = const_cast<native_handle_t*>(buffer);
    native_handle_close(nh);
    native_handle_delete(nh);
    return AIMAPPER_ERROR_NONE;
  }

  AIMapper_Error getTransportSize(buffer_handle_t buffer, uint32_t* outNumFds,
                                  uint32_t* outNumInts) override {
    if (!Find(buffer)) return AIMAPPER_ERROR_BAD_BUFFER;
    *outNumFds = 1;
    *outNumInts = NMEM_NUM_INTS;
    return AIMAPPER_ERROR_NONE;
  }

  AIMapper_Error lock(buffer_handle_t buffer, uint64_t cpuUsage, ARect region, int acquireFence,
                      void** outData) override {
    Buf* b;
    {
      std::lock_guard<std::mutex> g(mLock);
      b = FindLocked(buffer);
      if (!b) {
        if (acquireFence >= 0) close(acquireFence);
        return AIMAPPER_ERROR_BAD_BUFFER;
      }
      if (region.left < 0 || region.top < 0 || region.right > (int)b->h.width ||
          region.bottom > (int)b->h.height || region.left > region.right ||
          region.top > region.bottom) {
        if (acquireFence >= 0) close(acquireFence);
        return AIMAPPER_ERROR_BAD_VALUE;
      }
      b->locks++;
    }
    WaitFence(acquireFence);
    uint64_t rw = 0;
    if (cpuUsage & 0x0f) rw |= DMA_BUF_SYNC_READ;    // CPU_READ_*
    if (cpuUsage & 0xf0) rw |= DMA_BUF_SYNC_WRITE;   // CPU_WRITE_*
    SyncCpuAccess(buffer, DMA_BUF_SYNC_START | (rw ? rw : DMA_BUF_SYNC_RW));
    *outData = b->base;
    return AIMAPPER_ERROR_NONE;
  }

  AIMapper_Error unlock(buffer_handle_t buffer, int* releaseFence) override {
    std::lock_guard<std::mutex> g(mLock);
    Buf* b = FindLocked(buffer);
    if (!b) return AIMAPPER_ERROR_BAD_BUFFER;
    if (b->locks == 0) return AIMAPPER_ERROR_BAD_BUFFER;
    b->locks--;
    SyncCpuAccess(buffer, DMA_BUF_SYNC_END | DMA_BUF_SYNC_RW);
    *releaseFence = -1;
    return AIMAPPER_ERROR_NONE;
  }

  AIMapper_Error flushLockedBuffer(buffer_handle_t buffer) override {
    std::lock_guard<std::mutex> g(mLock);
    Buf* b = FindLocked(buffer);
    return !b || !b->locks ? AIMAPPER_ERROR_BAD_BUFFER : AIMAPPER_ERROR_NONE;
  }

  AIMapper_Error rereadLockedBuffer(buffer_handle_t buffer) override {
    return flushLockedBuffer(buffer);
  }

  int32_t getMetadata(buffer_handle_t buffer, AIMapper_MetadataType type, void* dest,
                      size_t size) override {
    if (type.name && strcmp(type.name, kStd) == 0)
      return getStandardMetadata(buffer, type.value, dest, size);
    return Find(buffer) ? -AIMAPPER_ERROR_UNSUPPORTED : -AIMAPPER_ERROR_BAD_BUFFER;
  }

  int32_t getStandardMetadata(buffer_handle_t buffer, int64_t type, void* dest,
                              size_t size) override {
    Buf b;
    if (!Find(buffer, &b)) return -AIMAPPER_ERROR_BAD_BUFFER;
    return provideStandardMetadata(
        static_cast<SMT>(type), dest, size, [&]<SMT T>(auto&& provide) -> int32_t {
          const nmem_ints& h = b.h;
          const nmem_meta& m = *b.meta;
          const nmem::Layout& l = b.layout;
          if constexpr (T == SMT::BUFFER_ID) {
            return provide(nmem_u64(h.id_lo, h.id_hi));
          } else if constexpr (T == SMT::NAME) {
            return provide(std::string(m.name, strnlen(m.name, sizeof(m.name))));
          } else if constexpr (T == SMT::WIDTH) {
            return provide(h.width);
          } else if constexpr (T == SMT::HEIGHT) {
            return provide(h.height);
          } else if constexpr (T == SMT::LAYER_COUNT) {
            return provide(h.layers);
          } else if constexpr (T == SMT::PIXEL_FORMAT_REQUESTED) {
            return provide(static_cast<PixelFormat>(h.format));
          } else if constexpr (T == SMT::PIXEL_FORMAT_FOURCC) {
            return provide(h.fourcc);
          } else if constexpr (T == SMT::PIXEL_FORMAT_MODIFIER) {
            return provide(0);  // DRM_FORMAT_MOD_LINEAR
          } else if constexpr (T == SMT::USAGE) {
            return provide(static_cast<BufferUsage>(nmem_u64(h.usage_lo, h.usage_hi)));
          } else if constexpr (T == SMT::ALLOCATION_SIZE) {
            return provide(l.size);
          } else if constexpr (T == SMT::PROTECTED_CONTENT) {
            return provide((nmem_u64(h.usage_lo, h.usage_hi) & (1u << 14)) ? 1 : 0);
          } else if constexpr (T == SMT::COMPRESSION) {
            return provide(ExtendableType{"android.hardware.graphics.common.Compression",
                                          static_cast<int64_t>(Compression::NONE)});
          } else if constexpr (T == SMT::INTERLACED) {
            return provide(ExtendableType{"android.hardware.graphics.common.Interlaced",
                                          static_cast<int64_t>(Interlaced::NONE)});
          } else if constexpr (T == SMT::CHROMA_SITING) {
            return provide(ExtendableType{
                "android.hardware.graphics.common.ChromaSiting",
                static_cast<int64_t>(l.nplanes > 1 ? ChromaSiting::UNKNOWN : ChromaSiting::NONE)});
          } else if constexpr (T == SMT::PLANE_LAYOUTS) {
            std::vector<PlaneLayout> out(l.nplanes);
            for (int i = 0; i < l.nplanes; i++) {
              const nmem::Plane& p = l.plane[i];
              PlaneLayout& o = out[i];
              o.offsetInBytes = p.offset;
              o.sampleIncrementInBits = p.increment_bits;
              o.strideInBytes = p.stride_bytes;
              o.widthInSamples = p.width;
              o.heightInSamples = p.height;
              o.totalSizeInBytes = p.size;
              o.horizontalSubsampling = p.hsub;
              o.verticalSubsampling = p.vsub;
              for (int c = 0; c < p.ncomp; c++) {
                PlaneLayoutComponent pc;
                pc.type = ExtendableType{
                    "android.hardware.graphics.common.PlaneLayoutComponentType", p.comp[c].type};
                pc.offsetInBits = p.comp[c].offset_bits;
                pc.sizeInBits = p.comp[c].size_bits;
                o.components.push_back(pc);
              }
            }
            return provide(out);
          } else if constexpr (T == SMT::CROP) {
            std::vector<Rect> out(l.nplanes);
            for (int i = 0; i < l.nplanes; i++) {
              if (i == 0)
                out[i] = Rect{m.crop[0], m.crop[1], m.crop[2], m.crop[3]};
              else
                out[i] = Rect{0, 0, (int)l.plane[i].width, (int)l.plane[i].height};
            }
            return provide(out);
          } else if constexpr (T == SMT::DATASPACE) {
            return provide(static_cast<Dataspace>(m.dataspace));
          } else if constexpr (T == SMT::BLEND_MODE) {
            return provide(static_cast<BlendMode>(m.blend_mode));
          } else if constexpr (T == SMT::SMPTE2086) {
            std::optional<Smpte2086> v;
            if (m.has_smpte2086) {
              const float* f = m.smpte2086;
              v = Smpte2086{XyColor{f[0], f[1]}, XyColor{f[2], f[3]}, XyColor{f[4], f[5]},
                            XyColor{f[6], f[7]}, f[8], f[9]};
            }
            return provide(v);
          } else if constexpr (T == SMT::CTA861_3) {
            std::optional<Cta861_3> v;
            if (m.has_cta861) v = Cta861_3{m.cta861[0], m.cta861[1]};
            return provide(v);
          } else if constexpr (T == SMT::SMPTE2094_10 || T == SMT::SMPTE2094_40) {
            int i = DynIndex(T);
            std::optional<std::vector<uint8_t>> v;
            if (m.dyn_len[i] != ~0u && m.dyn_len[i] <= NMEM_DYN_MAX)
              v = std::vector<uint8_t>(m.dyn[i], m.dyn[i] + m.dyn_len[i]);
            return provide(v);
          } else if constexpr (T == SMT::STRIDE) {
            return provide(h.stride);
          } else {
            return -AIMAPPER_ERROR_UNSUPPORTED;
          }
        });
  }

  AIMapper_Error setMetadata(buffer_handle_t buffer, AIMapper_MetadataType type,
                             const void* metadata, size_t size) override {
    if (type.name && strcmp(type.name, kStd) == 0)
      return setStandardMetadata(buffer, type.value, metadata, size);
    return Find(buffer) ? AIMAPPER_ERROR_UNSUPPORTED : AIMAPPER_ERROR_BAD_BUFFER;
  }

  AIMapper_Error setStandardMetadata(buffer_handle_t buffer, int64_t type, const void* metadata,
                                     size_t size) override {
    Buf b;
    if (!Find(buffer, &b)) return AIMAPPER_ERROR_BAD_BUFFER;
    nmem_meta& m = *b.meta;
    return applyStandardMetadata(
        static_cast<SMT>(type), metadata, size, [&]<SMT T>(auto&& value) -> AIMapper_Error {
          if constexpr (T == SMT::DATASPACE) {
            m.dataspace = static_cast<int32_t>(value);
          } else if constexpr (T == SMT::BLEND_MODE) {
            m.blend_mode = static_cast<int32_t>(value);
          } else if constexpr (T == SMT::CROP) {
            if (value.empty()) return AIMAPPER_ERROR_BAD_VALUE;
            m.crop[0] = value[0].left;
            m.crop[1] = value[0].top;
            m.crop[2] = value[0].right;
            m.crop[3] = value[0].bottom;
          } else if constexpr (T == SMT::SMPTE2086) {
            m.has_smpte2086 = value.has_value();
            if (value) {
              const Smpte2086& s = *value;
              float f[10] = {s.primaryRed.x, s.primaryRed.y, s.primaryGreen.x, s.primaryGreen.y,
                             s.primaryBlue.x, s.primaryBlue.y, s.whitePoint.x, s.whitePoint.y,
                             s.maxLuminance, s.minLuminance};
              memcpy(m.smpte2086, f, sizeof(f));
            }
          } else if constexpr (T == SMT::CTA861_3) {
            m.has_cta861 = value.has_value();
            if (value) {
              m.cta861[0] = value->maxContentLightLevel;
              m.cta861[1] = value->maxFrameAverageLightLevel;
            }
          } else if constexpr (T == SMT::SMPTE2094_10 || T == SMT::SMPTE2094_40) {
            int i = DynIndex(T);
            if (!value) {
              m.dyn_len[i] = ~0u;
            } else {
              if (value->size() > NMEM_DYN_MAX) return AIMAPPER_ERROR_BAD_VALUE;
              memcpy(m.dyn[i], value->data(), value->size());
              m.dyn_len[i] = value->size();
            }
          } else {
            (void)value;
            return AIMAPPER_ERROR_UNSUPPORTED;
          }
          return AIMAPPER_ERROR_NONE;
        });
  }

  AIMapper_Error listSupportedMetadataTypes(const AIMapper_MetadataTypeDescription** outList,
                                            size_t* outCount) override {
    *outList = mDescs;
    *outCount = std::size(kGettable);
    return AIMAPPER_ERROR_NONE;
  }

  AIMapper_Error dumpBuffer(buffer_handle_t buffer, AIMapper_DumpBufferCallback cb,
                            void* context) override {
    if (!Find(buffer)) return AIMAPPER_ERROR_BAD_BUFFER;
    std::vector<uint8_t> tmp(4096);
    for (SMT t : kGettable) {
      int32_t n = getStandardMetadata(buffer, static_cast<int64_t>(t), tmp.data(), tmp.size());
      if (n > (int32_t)tmp.size()) {
        tmp.resize(n);
        n = getStandardMetadata(buffer, static_cast<int64_t>(t), tmp.data(), tmp.size());
      }
      if (n >= 0) cb(context, AIMapper_MetadataType{kStd, static_cast<int64_t>(t)}, tmp.data(), n);
    }
    return AIMAPPER_ERROR_NONE;
  }

  AIMapper_Error dumpAllBuffers(AIMapper_BeginDumpBufferCallback begin,
                                AIMapper_DumpBufferCallback cb, void* context) override {
    std::vector<buffer_handle_t> all;
    {
      std::lock_guard<std::mutex> g(mLock);
      for (auto& [k, v] : mBufs) all.push_back(k);
    }
    for (buffer_handle_t h : all) {
      begin(context);
      dumpBuffer(h, cb, context);
    }
    return AIMAPPER_ERROR_NONE;
  }

  AIMapper_Error getReservedRegion(buffer_handle_t buffer, void** outRegion,
                                   uint64_t* outSize) override {
    Buf b;
    if (!Find(buffer, &b)) return AIMAPPER_ERROR_BAD_BUFFER;
    uint64_t n = nmem_u64(b.h.reserved_lo, b.h.reserved_hi);
    *outSize = n;
    *outRegion = n ? b.base + b.layout.size + NMEM_META_SIZE : nullptr;
    return AIMAPPER_ERROR_NONE;
  }

 private:
  Buf* FindLocked(buffer_handle_t h) {
    auto it = mBufs.find(h);
    return it == mBufs.end() ? nullptr : &it->second;
  }
  bool Find(buffer_handle_t h, Buf* out = nullptr) {
    std::lock_guard<std::mutex> g(mLock);
    Buf* b = FindLocked(h);
    if (b && out) *out = *b;
    return b != nullptr;
  }

  std::mutex mLock;
  std::unordered_map<buffer_handle_t, Buf> mBufs;
  AIMapper_MetadataTypeDescription mDescs[std::size(kGettable)];
};

vendor::mapper::IMapperProvider<NativeMapper> gProvider;

}  // namespace

extern "C" __attribute__((visibility("default"))) uint32_t ANDROID_HAL_STABLEC_VERSION = AIMAPPER_VERSION_5;
extern "C" __attribute__((visibility("default"))) uint32_t ANDROID_HAL_MAPPER_VERSION = AIMAPPER_VERSION_5;

extern "C" __attribute__((visibility("default"))) AIMapper_Error AIMapper_loadIMapper(AIMapper** outImplementation) {
  return gProvider.load(outImplementation);
}
