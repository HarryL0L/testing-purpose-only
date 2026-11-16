/* Pixel format layouts of native-mode graphics buffers (see nmem.h). */
#pragma once

#include <stdint.h>

namespace nmem {

enum : int32_t {
  RGBA_8888 = 1, RGBX_8888 = 2, RGB_888 = 3, RGB_565 = 4, BGRA_8888 = 5,
  YCRCB_420_SP = 0x11, RGBA_FP16 = 0x16, RAW16 = 0x20, BLOB = 0x21,
  IMPLEMENTATION_DEFINED = 0x22, YCBCR_420_888 = 0x23, RGBA_1010102 = 0x2B,
  DEPTH_16 = 0x30, DEPTH_24 = 0x31, DEPTH_24_STENCIL_8 = 0x32, DEPTH_32F = 0x33,
  DEPTH_32F_STENCIL_8 = 0x34, STENCIL_8 = 0x35, YCBCR_P010 = 0x36, R_8 = 0x38,
  R_16_UINT = 0x39, RG_1616_UINT = 0x3a, RGBA_10101010 = 0x3b,
  Y8 = 0x20203859, Y16 = 0x20363159, YV12 = 0x32315659,
};

enum : int64_t { C_Y = 1, C_CB = 2, C_CR = 4, C_R = 1024, C_G = 2048, C_B = 4096,
                 C_RAW = 1048576, C_A = 1073741824 };

constexpr uint32_t fourcc(char a, char b, char c, char d) {
  return (uint32_t)a | ((uint32_t)b << 8) | ((uint32_t)c << 16) | ((uint32_t)d << 24);
}

struct Component { int64_t type, offset_bits, size_bits; };
struct Plane {
  uint64_t offset, stride_bytes, increment_bits, width, height, size, hsub, vsub;
  int ncomp;
  Component comp[4];
};
struct Layout {
  int32_t format;  // resolved
  uint32_t stride;  // pixels
  uint32_t bpp;
  uint32_t fourcc;
  uint64_t size;
  int nplanes;
  Plane plane[3];
};

static inline uint64_t align(uint64_t v, uint64_t a) { return (v + a - 1) / a * a; }

static inline int32_t resolve(int32_t format, uint64_t usage) {
  if (format == IMPLEMENTATION_DEFINED)
    return (usage & ((1u << 16) | (1u << 17) | (1u << 22))) ? YCBCR_420_888 : RGBA_8888;
  return format;
}

static inline void rgb_plane(Layout* l, uint32_t w, uint32_t h, uint32_t bpp, uint32_t fcc,
                             int ncomp, const Component* comp) {
  l->bpp = bpp;
  l->stride = (uint32_t)align(w, 16);
  l->fourcc = fcc;
  Plane& p = l->plane[0];
  p.offset = 0;
  p.stride_bytes = (uint64_t)l->stride * bpp;
  p.increment_bits = bpp * 8;
  p.width = w;
  p.height = h;
  p.size = p.stride_bytes * h;
  p.hsub = p.vsub = 1;
  p.ncomp = ncomp;
  for (int i = 0; i < ncomp; i++) p.comp[i] = comp[i];
  l->nplanes = 1;
  l->size = p.size;
}

// false: unsupported format.
static inline bool layout(int32_t requested, uint64_t usage, uint32_t w, uint32_t h,
                          uint32_t layers, Layout* l) {
  *l = Layout{};
  if (w == 0 || h == 0 || w > 16384 || h > 16384) return false;
  if (layers == 0) layers = 1;
  int32_t f = resolve(requested, usage);
  l->format = f;
  switch (f) {
    case RGBA_8888: {
      Component c[] = {{C_R, 0, 8}, {C_G, 8, 8}, {C_B, 16, 8}, {C_A, 24, 8}};
      rgb_plane(l, w, h, 4, fourcc('A', 'B', '2', '4'), 4, c);
      break;
    }
    case RGBX_8888: {
      Component c[] = {{C_R, 0, 8}, {C_G, 8, 8}, {C_B, 16, 8}};
      rgb_plane(l, w, h, 4, fourcc('X', 'B', '2', '4'), 3, c);
      break;
    }
    case BGRA_8888: {
      Component c[] = {{C_B, 0, 8}, {C_G, 8, 8}, {C_R, 16, 8}, {C_A, 24, 8}};
      rgb_plane(l, w, h, 4, fourcc('A', 'R', '2', '4'), 4, c);
      break;
    }
    case RGB_888: {
      Component c[] = {{C_R, 0, 8}, {C_G, 8, 8}, {C_B, 16, 8}};
      rgb_plane(l, w, h, 3, fourcc('B', 'G', '2', '4'), 3, c);
      break;
    }
    case RGB_565: {
      Component c[] = {{C_R, 11, 5}, {C_G, 5, 6}, {C_B, 0, 5}};
      rgb_plane(l, w, h, 2, fourcc('R', 'G', '1', '6'), 3, c);
      break;
    }
    case RGBA_FP16: {
      Component c[] = {{C_R, 0, 16}, {C_G, 16, 16}, {C_B, 32, 16}, {C_A, 48, 16}};
      rgb_plane(l, w, h, 8, fourcc('A', 'B', '4', 'H'), 4, c);
      break;
    }
    case RGBA_1010102: {
      Component c[] = {{C_R, 0, 10}, {C_G, 10, 10}, {C_B, 20, 10}, {C_A, 30, 2}};
      rgb_plane(l, w, h, 4, fourcc('A', 'B', '3', '0'), 4, c);
      break;
    }
    case RGBA_10101010: {
      Component c[] = {{C_R, 6, 10}, {C_G, 22, 10}, {C_B, 38, 10}, {C_A, 54, 10}};
      rgb_plane(l, w, h, 8, 0, 4, c);
      break;
    }
    case R_8:
    case Y8: {
      Component c[] = {{f == R_8 ? C_R : C_Y, 0, 8}};
      rgb_plane(l, w, h, 1, fourcc('R', '8', ' ', ' '), 1, c);
      break;
    }
    case R_16_UINT:
    case Y16:
    case RAW16:
    case DEPTH_16: {
      Component c[] = {{f == Y16 ? C_Y : f == R_16_UINT ? C_R : C_RAW, 0, 16}};
      rgb_plane(l, w, h, 2, fourcc('R', '1', '6', ' '), 1, c);
      break;
    }
    case RG_1616_UINT: {
      Component c[] = {{C_R, 0, 16}, {C_G, 16, 16}};
      rgb_plane(l, w, h, 4, fourcc('G', 'R', '3', '2'), 2, c);
      break;
    }
    case DEPTH_24:
    case DEPTH_24_STENCIL_8:
    case DEPTH_32F:
    case DEPTH_32F_STENCIL_8: {
      Component c[] = {{C_RAW, 0, 32}};
      rgb_plane(l, w, h, f == DEPTH_32F_STENCIL_8 ? 8 : 4, 0, 1, c);
      break;
    }
    case STENCIL_8: {
      Component c[] = {{C_RAW, 0, 8}};
      rgb_plane(l, w, h, 1, 0, 1, c);
      break;
    }
    case BLOB: {
      if (h != 1) return false;
      Component c[] = {{C_RAW, 0, 8}};
      rgb_plane(l, w, 1, 1, 0, 1, c);
      l->stride = w;
      l->plane[0].stride_bytes = w;
      l->size = l->plane[0].size = w;
      break;
    }
    case YCBCR_420_888:
    case YCRCB_420_SP:
    case YCBCR_P010: {
      bool p010 = f == YCBCR_P010;
      uint32_t bps = p010 ? 2 : 1;
      l->bpp = bps;
      l->stride = (uint32_t)align(w, 16);
      l->fourcc = p010 ? fourcc('P', '0', '1', '0')
                       : f == YCRCB_420_SP ? fourcc('N', 'V', '2', '1') : fourcc('N', 'V', '1', '2');
      uint64_t ys = (uint64_t)l->stride * bps;
      Plane& y = l->plane[0];
      y = Plane{0, ys, bps * 8, w, h, ys * h, 1, 1, 1, {{C_Y, p010 ? 6 : 0, p010 ? 10 : 8}}};
      uint64_t ch = (h + 1) / 2;
      Plane& c = l->plane[1];
      c = Plane{ys * h, ys, bps * 16, (w + 1) / 2, ch, ys * ch, 2, 2, 2, {}};
      int64_t first = f == YCRCB_420_SP ? C_CR : C_CB, second = first == C_CB ? C_CR : C_CB;
      c.comp[0] = {first, p010 ? 6 : 0, p010 ? 10 : 8};
      c.comp[1] = {second, p010 ? 22 : 8, p010 ? 10 : 8};
      l->nplanes = 2;
      l->size = y.size + c.size;
      break;
    }
    case YV12: {
      l->bpp = 1;
      l->stride = (uint32_t)align(w, 16);
      l->fourcc = fourcc('Y', 'V', '1', '2');
      uint64_t ys = l->stride, cs = align(ys / 2, 16), ch = (h + 1) / 2;
      l->plane[0] = Plane{0, ys, 8, w, h, ys * h, 1, 1, 1, {{C_Y, 0, 8}}};
      l->plane[1] = Plane{ys * h, cs, 8, (w + 1) / 2, ch, cs * ch, 2, 2, 1, {{C_CR, 0, 8}}};
      l->plane[2] = Plane{ys * h + cs * ch, cs, 8, (w + 1) / 2, ch, cs * ch, 2, 2, 1,
                          {{C_CB, 0, 8}}};
      l->nplanes = 3;
      l->size = ys * h + 2 * cs * ch;
      break;
    }
    default:
      return false;
  }
  // Layers are stacked whole images.
  l->size *= layers;
  l->size = align(l->size, 4096);
  return true;
}

}  // namespace nmem
