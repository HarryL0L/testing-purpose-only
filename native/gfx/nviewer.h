/*
 * The display protocol between ncomposer and native mode's viewer app, on
 * the abstract SOCK_SEQPACKET socket "ndisplay" (one message per record):
 *
 *   composer -> viewer   nviewer_hello once, then one nviewer_frame per
 *                        presented frame; the first frame naming a buffer
 *                        carries its fd (SCM_RIGHTS: the client target's
 *                        dma-buf, or a memfd without ION), later ones only
 *                        its id. A frame may also carry the GPU's acquire
 *                        fence (a sync file, after the buffer fd): the viewer
 *                        waits for it before reading the buffer, so the
 *                        composer needn't (has_fd bits below)
 *   viewer -> composer   nviewer_ack when it is done with a frame (drawn);
 *                        the composer waits up to 100 ms for it
 *
 * Pixels: format is the Android PixelFormat (1 RGBA_8888, 2 RGBX_8888,
 * 5 BGRA_8888), stride in pixels, the buffer starting at offset 0.
 */
#pragma once

#include <stdint.h>

#define NVIEWER_SOCKET "ndisplay"
#define NVIEWER_VERSION 1
#define NVIEWER_HELLO 0x3070646e  // 'ndp0'
#define NVIEWER_FRAME 0x3072666e  // 'nfr0'
#define NVIEWER_ACK 0x6b63616e    // 'nack'

struct nviewer_hello {
  uint32_t magic;
  uint32_t version;
  uint32_t width;
  uint32_t height;
  uint32_t dpi;
  uint32_t fps;
};

struct nviewer_frame {
  uint32_t magic;
  uint32_t frame;
  uint64_t buffer_id;
  uint32_t width;
  uint32_t height;
  uint32_t stride;
  uint32_t format;
  uint32_t has_fd;  // NVIEWER_FD_BUFFER | NVIEWER_FD_FENCE, in that order
};

#define NVIEWER_FD_BUFFER 1
#define NVIEWER_FD_FENCE 2

struct nviewer_ack {
  uint32_t magic;
  uint32_t frame;
};
