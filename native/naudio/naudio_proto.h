/*
 * naudio's protocol: one connection per playback stream on NAUDIO_SOCKET
 * (under the root: <root>/dev/socket/naudio), a naudio_hello, then the PCM,
 * interleaved, until the stream closes. The format is tinyalsa's enum
 * pcm_format.
 */
#pragma once

#include <stdint.h>

#define NAUDIO_SOCKET "/dev/socket/naudio"
#define NAUDIO_MAGIC 0x6475616e  // 'naud'

struct naudio_hello {
  uint32_t magic;
  uint32_t rate;
  uint32_t channels;
  int32_t format;
};
