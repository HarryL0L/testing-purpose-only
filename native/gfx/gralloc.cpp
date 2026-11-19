/*
 * gralloc.default.so in the Vulkan driver APEX's hw/ (found by hardware.cpp):
 * a gralloc0 module (hardware/gralloc.h) for native mode's buffers (nmem.h),
 * for the one user left: Mesa's fallback u_gralloc in PanVK. It describes YUV
 * buffers only through lock_ycbcr ("No gralloc hwmodule detected (video
 * buffers won't be supported)" without one), and SurfaceFlinger aborts on the
 * first video frame it can't import. With usage 0 Mesa reads the plane
 * pointers as offsets into the buffer's fd, so that is all lock_ycbcr
 * returns; nothing is mapped.
 */
#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "nmem.h"
#include "nmem_format.h"

// The gralloc0 ABI (hardware/hardware.h, hardware/gralloc.h, system/graphics.h,
// cutils/native_handle.h), declared here: those headers pull in system/window.h.
struct native_handle_t {
  int version, numFds, numInts;
  int data[0];
};
typedef const native_handle_t* buffer_handle_t;
struct hw_module_t;
struct hw_device_t;
struct hw_module_methods_t {
  int (*open)(const hw_module_t* module, const char* id, hw_device_t** device);
};
struct hw_module_t {
  uint32_t tag;
  uint16_t module_api_version, hal_api_version;
  const char *id, *name, *author;
  hw_module_methods_t* methods;
  void* dso;
  uint64_t reserved[32 - 7];
};
struct android_ycbcr {
  void *y, *cb, *cr;
  size_t ystride, cstride, chroma_step;
  uint32_t reserved[8];
};
struct gralloc_module_t {
  hw_module_t common;
  int (*registerBuffer)(const gralloc_module_t*, buffer_handle_t);
  int (*unregisterBuffer)(const gralloc_module_t*, buffer_handle_t);
  int (*lock)(const gralloc_module_t*, buffer_handle_t, int usage, int l, int t, int w, int h,
              void** vaddr);
  int (*unlock)(const gralloc_module_t*, buffer_handle_t);
  int (*perform)(const gralloc_module_t*, int operation, ...);
  int (*lock_ycbcr)(const gralloc_module_t*, buffer_handle_t, int usage, int l, int t, int w,
                    int h, android_ycbcr* ycbcr);
  int (*lockAsync)(const gralloc_module_t*, buffer_handle_t, int, int, int, int, int, void**,
                   int);
  int (*unlockAsync)(const gralloc_module_t*, buffer_handle_t, int*);
  int (*lockAsync_ycbcr)(const gralloc_module_t*, buffer_handle_t, int, int, int, int, int,
                         android_ycbcr*, int);
  int32_t (*getTransportSize)(const gralloc_module_t*, buffer_handle_t, uint32_t*, uint32_t*);
  int32_t (*validateBufferSize)(const gralloc_module_t*, buffer_handle_t, uint32_t, uint32_t,
                                int32_t, int, uint32_t);
  void* reserved_proc[1];
};
static_assert(sizeof(hw_module_t) == 248, "hw_module_t");
static_assert(offsetof(gralloc_module_t, lock_ycbcr) == 288, "gralloc_module_t");
static_assert(sizeof(android_ycbcr) == 80, "android_ycbcr");

#define HARDWARE_MODULE_TAG 0x48574d54  // 'HWMT'
#define GRALLOC_MODULE_API_VERSION_0_3 3

namespace {

// The handle's nmem_ints, or null for a handle that isn't ours.
const nmem_ints* ints(buffer_handle_t h) {
  if (!h || h->numFds < 1 || h->numInts < (int)NMEM_NUM_INTS) return nullptr;
  const nmem_ints* n = reinterpret_cast<const nmem_ints*>(&h->data[h->numFds]);
  return n->magic == NMEM_MAGIC ? n : nullptr;
}

int lock_ycbcr(const gralloc_module_t*, buffer_handle_t h, int usage, int, int, int, int,
               android_ycbcr* out) {
  const nmem_ints* n = ints(h);
  if (!n || !out) return -EINVAL;
  // Plane pointers in the buffer: only offsets (usage 0) are supported.
  if (usage) return -EINVAL;
  nmem::Layout l;
  if (!nmem::layout(n->format, nmem_u64(n->usage_lo, n->usage_hi), n->width, n->height,
                    n->layers, &l) ||
      l.nplanes < 2)
    return -EINVAL;  // not YUV: Mesa takes it as RGB
  memset(out, 0, sizeof(*out));
  int64_t found = 0;  // the Y plane is at offset 0: a null pointer
  for (int i = 0; i < l.nplanes; i++) {
    const nmem::Plane& p = l.plane[i];
    for (int c = 0; c < p.ncomp; c++) {
      void* at = reinterpret_cast<void*>(p.offset + p.comp[c].offset_bits / 8);
      found |= p.comp[c].type;
      if (p.comp[c].type == nmem::C_Y) {
        out->y = at;
        out->ystride = p.stride_bytes;
      } else if (p.comp[c].type == nmem::C_CB || p.comp[c].type == nmem::C_CR) {
        (p.comp[c].type == nmem::C_CB ? out->cb : out->cr) = at;
        out->cstride = p.stride_bytes;
        out->chroma_step = p.increment_bits / 8;
      }
    }
  }
  return found == (nmem::C_Y | nmem::C_CB | nmem::C_CR) ? 0 : -EINVAL;
}

int unlock(const gralloc_module_t*, buffer_handle_t) { return 0; }

// Buffers are imported and mapped through the mapper (mapper.native.so).
int registerBuffer(const gralloc_module_t*, buffer_handle_t h) { return ints(h) ? 0 : -EINVAL; }
int unregisterBuffer(const gralloc_module_t*, buffer_handle_t) { return 0; }
int lock(const gralloc_module_t*, buffer_handle_t, int, int, int, int, int, void**) {
  return -EINVAL;
}

hw_module_methods_t methods = {nullptr};

}  // namespace

extern "C" __attribute__((visibility("default"))) gralloc_module_t HMI = {
    .common =
        {
            .tag = HARDWARE_MODULE_TAG,
            .module_api_version = GRALLOC_MODULE_API_VERSION_0_3,
            .hal_api_version = 0,
            .id = "gralloc",
            .name = "native mode gralloc0 (nmem)",
            .author = "native mode",
            .methods = &methods,
        },
    .registerBuffer = registerBuffer,
    .unregisterBuffer = unregisterBuffer,
    .lock = lock,
    .unlock = unlock,
    .lock_ycbcr = lock_ycbcr,
};
