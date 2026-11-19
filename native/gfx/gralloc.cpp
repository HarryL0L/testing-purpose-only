// gralloc.native.so: a gralloc0 module for Mesa's fallback gralloc (PanVK in
// SurfaceFlinger and apps). Mesa describes RGB buffers from the handle alone,
// but needs gralloc0's lock_ycbcr for YUV ones (a video decoder's YV12/NV12
// output): without a module it can't import them, and SurfaceFlinger aborted
// on the first video frame ("Failed to create a valid texture").
//
// Only lock_ycbcr does anything: called without CPU usage it returns the
// planes' offsets as pointers from NULL, which is how Mesa reads them, from
// the nmem handle's layout (nmem_format.h). Nothing here maps memory.
#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "nmem.h"
#include "nmem_format.h"

extern "C" {

// libhardware's ABI (hardware/hardware.h, hardware/gralloc.h), arm64.
struct native_handle_t {
  int version, numFds, numInts;
  int data[0];
};
struct hw_module_t;
struct hw_device_t;
struct hw_module_methods_t {
  int (*open)(const hw_module_t*, const char*, hw_device_t**);
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
  int (*registerBuffer)(const gralloc_module_t*, const native_handle_t*);
  int (*unregisterBuffer)(const gralloc_module_t*, const native_handle_t*);
  int (*lock)(const gralloc_module_t*, const native_handle_t*, int, int, int, int, int, void**);
  int (*unlock)(const gralloc_module_t*, const native_handle_t*);
  int (*perform)(const gralloc_module_t*, int, ...);
  int (*lock_ycbcr)(const gralloc_module_t*, const native_handle_t*, int, int, int, int, int,
                    android_ycbcr*);
  void* reserved_proc[6];
};

static int ok(const gralloc_module_t*, const native_handle_t*) { return 0; }

static int no_lock(const gralloc_module_t*, const native_handle_t*, int, int, int, int, int,
                   void**) {
  return -EINVAL;  // CPU access goes through the mapper
}

static int no_perform(const gralloc_module_t*, int, ...) { return -EINVAL; }

static int lock_ycbcr(const gralloc_module_t*, const native_handle_t* h, int usage, int, int,
                      int, int, android_ycbcr* out) {
  if (h == nullptr || out == nullptr || usage != 0 || h->numFds < 1 ||
      h->numInts < (int)NMEM_NUM_INTS) {
    return -EINVAL;
  }
  nmem_ints in;
  memcpy(&in, &h->data[h->numFds], sizeof(in));
  if (in.magic != NMEM_MAGIC) return -EINVAL;
  nmem::Layout l;
  if (!nmem::layout(in.real_format, nmem_u64(in.usage_lo, in.usage_hi), in.width, in.height, 1,
                    &l) ||
      l.nplanes < 2) {
    return -EINVAL;  // not YUV
  }
  memset(out, 0, sizeof(*out));
  out->y = (void*)(uintptr_t)l.plane[0].offset;
  out->ystride = l.plane[0].stride_bytes;
  out->cstride = l.plane[1].stride_bytes;
  if (l.nplanes == 2) {  // NV12/NV21/P010: interleaved chroma
    out->chroma_step = 2 * l.bpp;
    uintptr_t c = l.plane[1].offset;
    bool cr_first = l.plane[1].comp[0].type == nmem::C_CR;
    out->cb = (void*)(cr_first ? c + l.bpp : c);
    out->cr = (void*)(cr_first ? c : c + l.bpp);
  } else {  // YV12: Cr plane, then Cb
    out->chroma_step = 1;
    for (int i = 1; i < 3; i++) {
      void* p = (void*)(uintptr_t)l.plane[i].offset;
      if (l.plane[i].comp[0].type == nmem::C_CR) out->cr = p;
      else out->cb = p;
    }
  }
  return 0;
}

static hw_module_methods_t methods = {nullptr};

__attribute__((visibility("default"))) gralloc_module_t HMI = {
    {
        ('H' << 24) | ('W' << 16) | ('M' << 8) | 'T',  // HARDWARE_MODULE_TAG
        0x0003,  // GRALLOC_MODULE_API_VERSION_0_3
        0x0100,  // HARDWARE_HAL_API_VERSION
        "gralloc",
        "native mode gralloc (YUV layouts for Mesa)",
        "testing-purpose-only",
        &methods,
        nullptr,
        {},
    },
    ok,
    ok,
    no_lock,
    ok,
    no_perform,
    lock_ycbcr,
    {},
};

}  // extern "C"
