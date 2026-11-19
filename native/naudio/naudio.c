/*
 * naudio: native mode's speaker. The audio HAL's streams (tinyalsa.cpp, in
 * the root) connect to ROOT/dev/socket/naudio and send their PCM; naudio plays
 * each on an AAudio stream of the phone's own audio system. It runs on the
 * host side (the phone's libc and libaaudio, loaded at run time): the app
 * runs naudio_run() on a thread of its own (built with NAUDIO_LIBRARY), and
 * nstart.py runs this as a program next to nboot.
 *
 *   naudio ROOT
 */
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include "naudio_proto.h"

// tinyalsa's enum pcm_format values naudio plays
enum { PCM_S16_LE = 0, PCM_S32_LE = 1, PCM_S24_LE = 3, PCM_S24_3LE = 4, PCM_FLOAT_LE = 9 };
// AAudio's aaudio_format_t
enum { AAUDIO_I16 = 1, AAUDIO_FLOAT = 2, AAUDIO_I24_PACKED = 3, AAUDIO_I32 = 4 };
#define AAUDIO_PERFORMANCE_MODE_NONE 10
#define AAUDIO_USAGE_MEDIA 1

typedef void* Builder;
typedef void* Stream;
static struct {
  int32_t (*createStreamBuilder)(Builder*);
  void (*setSampleRate)(Builder, int32_t);
  void (*setChannelCount)(Builder, int32_t);
  void (*setFormat)(Builder, int32_t);
  void (*setPerformanceMode)(Builder, int32_t);
  void (*setUsage)(Builder, int32_t);
  int32_t (*openStream)(Builder, Stream*);
  int32_t (*deleteBuilder)(Builder);
  int32_t (*requestStart)(Stream);
  int64_t (*write)(Stream, const void*, int32_t, int64_t);
  int32_t (*close)(Stream);
} aa;

static int load_aaudio(void) {
  void* h = dlopen("libaaudio.so", RTLD_NOW);
  if (!h) {
    fprintf(stderr, "naudio: %s\n", dlerror());
    return -1;
  }
#define SYM(field, name) \
  if (!(*(void**)&aa.field = dlsym(h, name))) return fprintf(stderr, "naudio: no %s\n", name), -1
  SYM(createStreamBuilder, "AAudio_createStreamBuilder");
  SYM(setSampleRate, "AAudioStreamBuilder_setSampleRate");
  SYM(setChannelCount, "AAudioStreamBuilder_setChannelCount");
  SYM(setFormat, "AAudioStreamBuilder_setFormat");
  SYM(setPerformanceMode, "AAudioStreamBuilder_setPerformanceMode");
  SYM(setUsage, "AAudioStreamBuilder_setUsage");
  SYM(openStream, "AAudioStreamBuilder_openStream");
  SYM(deleteBuilder, "AAudioStreamBuilder_delete");
  SYM(requestStart, "AAudioStream_requestStart");
  SYM(write, "AAudioStream_write");
  SYM(close, "AAudioStream_close");
#undef SYM
  return 0;
}

static int read_full(int fd, void* buf, size_t n) {
  for (size_t off = 0; off < n;) {
    ssize_t r = read(fd, (char*)buf + off, n - off);
    if (r < 0 && errno == EINTR) continue;
    if (r <= 0) return -1;
    off += r;
  }
  return 0;
}

// One HAL stream: hello, then PCM until the HAL closes it.
static void* serve(void* arg) {
  int fd = (int)(intptr_t)arg;
  struct naudio_hello h;
  Builder b = NULL;
  Stream s = NULL;
  if (read_full(fd, &h, sizeof(h)) || h.magic != NAUDIO_MAGIC || !h.rate || !h.channels ||
      h.channels > 8)
    goto out;
  int32_t format;
  unsigned in_sample;
  switch (h.format) {
    case PCM_S16_LE: format = AAUDIO_I16; in_sample = 2; break;
    case PCM_S32_LE: format = AAUDIO_I32; in_sample = 4; break;
    case PCM_S24_LE: format = AAUDIO_I32; in_sample = 4; break;  // shifted below
    case PCM_S24_3LE: format = AAUDIO_I24_PACKED; in_sample = 3; break;
    case PCM_FLOAT_LE: format = AAUDIO_FLOAT; in_sample = 4; break;
    default:
      fprintf(stderr, "naudio: unsupported format %d\n", h.format);
      goto out;
  }
  if (aa.createStreamBuilder(&b)) goto out;
  aa.setSampleRate(b, h.rate);
  aa.setChannelCount(b, h.channels);
  aa.setFormat(b, format);
  aa.setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_NONE);
  aa.setUsage(b, AAUDIO_USAGE_MEDIA);
  int32_t r = aa.openStream(b, &s);
  aa.deleteBuilder(b);
  if (r || aa.requestStart(s)) {
    fprintf(stderr, "naudio: can't open a %u Hz %u ch stream (%d)\n", h.rate, h.channels, r);
    goto out;
  }
  unsigned frame = h.channels * in_sample;
  char buf[16384];
  size_t have = 0;
  for (;;) {
    ssize_t n = read(fd, buf + have, sizeof(buf) - have);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) break;
    have += n;
    size_t frames = have / frame;
    if (h.format == PCM_S24_LE) {  // 24 bits in the low bytes of 32: to I32
      int32_t* v = (int32_t*)buf;
      for (size_t i = 0; i < frames * h.channels; i++) v[i] = (int32_t)((uint32_t)v[i] << 8);
    }
    if (frames && aa.write(s, buf, frames, 1000000000LL) < 0) break;
    have -= frames * frame;
    memmove(buf, buf + frames * frame, have);
  }
out:
  if (s) aa.close(s);
  close(fd);
  return NULL;
}

// Serves ROOT/dev/socket/naudio; returns only on an error.
int naudio_run(const char* root) {
  signal(SIGPIPE, SIG_IGN);
  if (load_aaudio()) return 1;
  struct sockaddr_un a = {.sun_family = AF_UNIX};
  if (snprintf(a.sun_path, sizeof(a.sun_path), "%s%s", root, NAUDIO_SOCKET) >=
      (int)sizeof(a.sun_path)) {
    fprintf(stderr, "naudio: socket path too long\n");
    return 1;
  }
  int ls = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  unlink(a.sun_path);
  if (ls < 0 || bind(ls, (struct sockaddr*)&a, sizeof(a)) || listen(ls, 8)) {
    fprintf(stderr, "naudio: %s: %s\n", a.sun_path, strerror(errno));
    return 1;
  }
  fprintf(stderr, "naudio: listening on %s\n", a.sun_path);
  for (;;) {
    int fd = accept4(ls, NULL, NULL, SOCK_CLOEXEC);
    if (fd < 0) {
      if (errno == EINTR) continue;
      return 1;
    }
    pthread_t t;
    if (pthread_create(&t, NULL, serve, (void*)(intptr_t)fd) == 0)
      pthread_detach(t);
    else
      close(fd);
  }
}

#ifndef NAUDIO_LIBRARY
int main(int argc, char** argv) {
  if (argc != 2) {
    fprintf(stderr, "usage: naudio ROOT\n");
    return 2;
  }
  return naudio_run(argv[1]);
}
#endif
