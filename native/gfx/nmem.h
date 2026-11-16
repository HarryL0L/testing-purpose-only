/*
 * Graphics buffers of native mode: one memfd per buffer, shared by the
 * allocator (nalloc), the mapper (mapper.native.so) and the composer.
 *
 * memfd layout:  [pixels: size bytes][nmem_meta: NMEM_META_SIZE][reserved region]
 * The handle is a native_handle_t with that one fd and the nmem_ints below.
 * Mutable metadata (name, dataspace, blend mode, HDR data, crop) lives in the
 * memfd so that every process sees one copy.
 */
#pragma once

#include <stdint.h>

#define NMEM_MAGIC 0x6e6d656d  // 'nmem'
#define NMEM_META_SIZE 8192
#define NMEM_DYN_MAX 2048

struct nmem_ints {
  uint32_t magic;
  uint32_t width;
  uint32_t height;
  uint32_t layers;
  int32_t format;     // the requested PixelFormat
  int32_t real_format;  // IMPLEMENTATION_DEFINED etc. resolved
  uint32_t stride;    // in pixels (bytes for BLOB: == width)
  uint32_t bpp;       // bytes per pixel of plane 0 (1 for BLOB and YUV)
  uint32_t usage_lo;
  uint32_t usage_hi;
  uint32_t size_lo;   // pixel bytes
  uint32_t size_hi;
  uint32_t reserved_lo;
  uint32_t reserved_hi;
  uint32_t id_lo;
  uint32_t id_hi;
  uint32_t fourcc;
  uint32_t pid;       // allocator pid, for dumps
};

#define NMEM_NUM_INTS (sizeof(struct nmem_ints) / sizeof(int32_t))

struct nmem_meta {
  uint32_t magic;
  int32_t dataspace;
  int32_t blend_mode;
  uint32_t has_smpte2086;
  float smpte2086[10];  // r,g,b,white xy pairs, max, min luminance
  uint32_t has_cta861;
  float cta861[2];
  int32_t crop[4];  // left, top, right, bottom
  uint32_t dyn_len[3];  // SMPTE2094_10, _40, _50; ~0u = not set
  char name[128];
  uint8_t dyn[3][NMEM_DYN_MAX];
};

static_assert(sizeof(struct nmem_meta) <= NMEM_META_SIZE, "meta too big");

static inline uint64_t nmem_u64(uint32_t lo, uint32_t hi) {
  return (uint64_t)lo | ((uint64_t)hi << 32);
}
