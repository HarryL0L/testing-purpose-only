// fusetest: MediaProvider's own libfuse driven over a socket pair the way
// the vold stand-in drives MediaProvider (FUSE_INIT, then nothing), with
// MediaProvider's options (-odebug, -omax_read, clone_fd, 100 threads, a
// write_buf handler). Run once at start on the phone's kernel: libfuse says
// on stderr why a session ends, which MediaProvider's stderr doesn't show.
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
struct fuse_args { int argc; char **argv; int allocated; };
struct libfuse_version { uint32_t major, minor, hotfix, padding; };
extern void *_fuse_session_new_317(struct fuse_args*, const void*, size_t, struct libfuse_version*, void*);
extern void *fuse_loop_cfg_create(void);
extern void fuse_loop_cfg_set_clone_fd(void*, unsigned);
extern void fuse_loop_cfg_set_max_threads(void*, unsigned);
extern void fuse_loop_cfg_set_idle_threads(void*, unsigned);
extern int fuse_session_loop_mt_312(void*, void*);
#define MAX_READ (128 * 1024)
static int fds[2];
static void on_init(void *ud, void *conn) {
  *(uint32_t*)((char*)conn + 0xc) = MAX_READ;  // fuse_conn_info.max_read, as MediaProvider sets it
  // As MediaProvider's init: every optional feature libfuse offers, but
  // splice writes (want |= capable & ~SPLICE_WRITE).
  uint64_t cap = *(uint64_t*)((char*)conn + 0x18), *want = (uint64_t*)((char*)conn + 0x20);
  if (getenv("FT_PLAIN") == NULL) *want |= cap & ~0x80ull;
  fprintf(stderr, "fusetest: init called, capable 0x%llx, want 0x%llx\n", (unsigned long long)cap, (unsigned long long)*want);
}
static void on_write_buf(void) {}
static void *kernel(void *a) {
  unsigned char b[104] = {0}; uint32_t *w = (uint32_t*)b;
  w[0] = 104; w[1] = 26; w[2] = 1; w[10] = 7; w[11] = 31; w[12] = 128 * 1024; if (!getenv("FT_NOMAXPAGES")) w[13] = 1 << 22;  // FUSE_MAX_PAGES
  fprintf(stderr, "fusetest: FUSE_INIT written: %zd\n", write(fds[0], b, 104));
  char r[4096]; ssize_t n;
  while ((n = read(fds[0], r, sizeof r)) > 0) fprintf(stderr, "fusetest: reply %zd bytes, error %d\n", n, ((int32_t*)r)[1]);
  fprintf(stderr, "fusetest: our end read %zd (%s)\n", n, strerror(errno));
  return 0;
}
static void on_alarm(int s) {
  const char m[] = "fusetest: session still running after 1 s: OK\n";
  write(2, m, sizeof m - 1);
  _exit(0);
}
int main(void) {
  signal(SIGALRM, on_alarm);
  alarm(1);
  if (socketpair(AF_UNIX, getenv("FT_STREAM") ? SOCK_STREAM : SOCK_SEQPACKET, 0, fds) != 0) { perror("fusetest: socketpair"); return 1; }
  char opt[32]; snprintf(opt, sizeof opt, "-omax_read=%d", MAX_READ);
  char *av[] = {"/storage/emulated", "-odebug", opt, NULL};
  struct fuse_args args = {3, av, 0};
  static char ops[376];
  void (*f)(void*, void*) = on_init; memcpy(ops, &f, 8);
  void (*g)(void) = on_write_buf; memcpy(ops + 36 * 8, &g, 8);
  struct libfuse_version v = {3, 17, 0, 0};
  char *se = _fuse_session_new_317(&args, ops, sizeof ops, &v, NULL);
  if (!se) { fprintf(stderr, "fusetest: no session\n"); return 1; }
  *(int*)(se + 0xc) = fds[1];
  *(char**)se = strdup("/storage/emulated");
  pthread_t t; pthread_create(&t, 0, kernel, 0);
  void *cfg = fuse_loop_cfg_create();
  fuse_loop_cfg_set_clone_fd(cfg, 1); fuse_loop_cfg_set_max_threads(cfg, 100); fuse_loop_cfg_set_idle_threads(cfg, 10);
  int r = fuse_session_loop_mt_312(se, cfg);
  fprintf(stderr, "fusetest: session ended: %d (%s)\n", r, strerror(r < 0 ? -r : r));
  return 0;
}
