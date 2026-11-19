/*
 * libtinyalsav2.so for the audio HAL APEX (com.android.hardware.audio): the
 * functions its HAL uses, with the phone's speaker behind them instead of
 * /dev/snd, which an app can't open (the example HAL's primary output then
 * sits in state ERROR and drops every burst). A playback stream is a
 * connection to naudio (naudio.c, on the host side: AAudio), a header and
 * the PCM. Capture streams record silence. There are no mixers or hw_params:
 * the HAL already copes with those failing.
 *
 * Timing: the HAL paces itself on pcm_write blocking and on
 * pcm_get_htimestamp; both follow a clock that plays the written frames at
 * the stream's rate, with at most the buffer (period_size * period_count)
 * in flight.
 */
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include <tinyalsa/mixer.h>
#include <tinyalsa/pcm.h>

#include "naudio_proto.h"

#define EXPORT extern "C" __attribute__((visibility("default")))

struct pcm {  // calloc'ed
  int fd;  // naudio connection (playback), -1 if none
  bool input;
  pcm_config config;
  unsigned frame_bytes;
  uint64_t written;  // frames
  int64_t start_ns;  // when frame 0 of the current run plays
  bool running;
  char error[128];
};

static int64_t now_ns() {
  timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static unsigned buffer_frames(const pcm* p) {
  return p->config.period_size * p->config.period_count;
}

// Frames the clock has played by now (never more than were written).
static uint64_t played(const pcm* p, int64_t now) {
  if (!p->running) return p->written;
  uint64_t f = (uint64_t)((now - p->start_ns) * (int64_t)p->config.rate / 1000000000LL);
  return f < p->written ? f : p->written;
}

static unsigned bytes_per_sample(pcm_format f) {
  switch (f) {
    case PCM_FORMAT_S16_LE: return 2;
    case PCM_FORMAT_S24_3LE: return 3;
    case PCM_FORMAT_S8: return 1;
    default: return 4;  // S32, S24 in 32, float
  }
}

// Advance the clock by n frames written, blocking while more than the
// buffer would be in flight; an underrun restarts it.
static void pace(pcm* p, unsigned n) {
  int64_t now = now_ns();
  if (!p->running || played(p, now) >= p->written) {
    // underrun or first write: the new frames start playing now
    p->start_ns = now - (int64_t)(p->written * 1000000000ULL / p->config.rate);
    p->running = true;
  }
  p->written += n;
  uint64_t limit = buffer_frames(p);
  uint64_t done = played(p, now);
  if (p->written - done > limit) {
    // sleep until the frame written - limit has played
    int64_t at = p->start_ns + (int64_t)((p->written - limit) * 1000000000ULL / p->config.rate);
    int64_t wait = at - now;
    if (wait > 0) {
      timespec ts = {(time_t)(wait / 1000000000LL), (long)(wait % 1000000000LL)};
      nanosleep(&ts, nullptr);
    }
  }
}

static int connect_naudio(const pcm_config* c) {
  int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (fd < 0) return -1;
  sockaddr_un a = {};
  a.sun_family = AF_UNIX;
  strncpy(a.sun_path, NAUDIO_SOCKET, sizeof(a.sun_path) - 1);
  naudio_hello h = {NAUDIO_MAGIC, c->rate, c->channels, (int32_t)c->format};
  if (connect(fd, reinterpret_cast<sockaddr*>(&a), sizeof(a)) != 0 ||
      write(fd, &h, sizeof(h)) != (ssize_t)sizeof(h)) {
    close(fd);
    return -1;
  }
  return fd;
}

EXPORT struct pcm* pcm_open(unsigned int card, unsigned int device, unsigned int flags,
                            const struct pcm_config* config) {
  pcm* p = static_cast<pcm*>(calloc(1, sizeof(pcm)));
  if (!p) return nullptr;
  p->fd = -1;
  p->input = flags & PCM_IN;
  if (config) {
    p->config = *config;
  } else {
    p->config = {};
    p->config.channels = 2;
    p->config.rate = 48000;
    p->config.format = PCM_FORMAT_S16_LE;
  }
  if (!p->config.rate) p->config.rate = 48000;
  if (!p->config.channels) p->config.channels = 2;
  if (!p->config.period_size) p->config.period_size = p->config.rate / 100;
  if (!p->config.period_count) p->config.period_count = 4;
  p->frame_bytes = p->config.channels * bytes_per_sample(p->config.format);
  if (!p->input && (p->fd = connect_naudio(&p->config)) < 0)
    snprintf(p->error, sizeof(p->error), "cannot connect to naudio for card %u device %u: %s",
             card, device, strerror(errno));
  return p;
}

EXPORT int pcm_close(struct pcm* p) {
  if (!p) return 0;
  if (p->fd >= 0) close(p->fd);
  free(p);
  return 0;
}

EXPORT int pcm_is_ready(const struct pcm* p) { return p && p->error[0] == '\0'; }

EXPORT const char* pcm_get_error(const struct pcm* p) { return p ? p->error : "no pcm"; }

EXPORT unsigned int pcm_get_buffer_size(const struct pcm* p) { return p ? buffer_frames(p) : 0; }

EXPORT int pcm_get_htimestamp(struct pcm* p, unsigned int* avail, struct timespec* tstamp) {
  if (!p || !p->running) return -1;
  int64_t now = now_ns();
  uint64_t in_flight = p->written - played(p, now);
  unsigned buf = buffer_frames(p);
  if (avail) *avail = in_flight > buf ? 0 : (unsigned)(p->input ? in_flight : buf - in_flight);
  if (tstamp) clock_gettime(CLOCK_MONOTONIC, tstamp);
  return 0;
}

EXPORT int pcm_write(struct pcm* p, const void* data, unsigned int count) {
  if (!p || p->input || p->fd < 0) return -EBADF;
  const char* d = static_cast<const char*>(data);
  for (unsigned off = 0; off < count;) {
    ssize_t n = send(p->fd, d + off, count - off, MSG_NOSIGNAL);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) {
      snprintf(p->error, sizeof(p->error), "naudio: %s", n < 0 ? strerror(errno) : "closed");
      return -EIO;
    }
    off += n;
  }
  pace(p, count / p->frame_bytes);
  return 0;
}

EXPORT int pcm_read(struct pcm* p, void* data, unsigned int count) {
  if (!p || !p->input) return -EBADF;
  memset(data, 0, count);
  // capture as the clock delivers it: the frames read can't be ahead of now
  unsigned n = count / p->frame_bytes;
  if (!p->running) {
    p->start_ns = now_ns();
    p->running = true;
  }
  p->written += n;
  int64_t at = p->start_ns + (int64_t)(p->written * 1000000000ULL / p->config.rate);
  int64_t wait = at - now_ns();
  if (wait > 0) {
    timespec ts = {(time_t)(wait / 1000000000LL), (long)(wait % 1000000000LL)};
    nanosleep(&ts, nullptr);
  }
  return 0;
}

EXPORT int pcm_stop(struct pcm* p) {
  if (!p) return -EBADF;
  p->running = false;  // the next write starts a new run
  return 0;
}

// No hw_params and no mixers (as when /dev/snd can't be opened).
EXPORT struct pcm_params* pcm_params_get(unsigned int, unsigned int, unsigned int) {
  return nullptr;
}
EXPORT void pcm_params_free(struct pcm_params*) {}
EXPORT const struct pcm_mask* pcm_params_get_mask(const struct pcm_params*, enum pcm_param) {
  return nullptr;
}
EXPORT unsigned int pcm_params_get_min(const struct pcm_params*, enum pcm_param) { return 0; }
EXPORT unsigned int pcm_params_get_max(const struct pcm_params*, enum pcm_param) { return 0; }

EXPORT struct mixer* mixer_open(unsigned int) { return nullptr; }
EXPORT void mixer_close(struct mixer*) {}
EXPORT struct mixer_ctl* mixer_get_ctl_by_name(struct mixer*, const char*) { return nullptr; }
EXPORT enum mixer_ctl_type mixer_ctl_get_type(const struct mixer_ctl*) {
  return MIXER_CTL_TYPE_UNKNOWN;
}
EXPORT unsigned int mixer_ctl_get_num_values(const struct mixer_ctl*) { return 0; }
EXPORT int mixer_ctl_get_percent(const struct mixer_ctl*, unsigned int) { return -EINVAL; }
EXPORT int mixer_ctl_set_percent(struct mixer_ctl*, unsigned int, int) { return -EINVAL; }
EXPORT int mixer_ctl_set_value(struct mixer_ctl*, unsigned int, int) { return -EINVAL; }
