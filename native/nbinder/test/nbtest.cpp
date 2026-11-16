// Host test for nbinder + libc's native_binder.cpp (built with NB_HOST).
//
// A server (polling, like servicemanager) becomes the context manager. A
// client calls it with an fd, gets back a binder object, calls that oneway,
// asks for a death notification and sees the server die.
//
//   g++ -std=c++17 -DNB_HOST -I.. nbtest.cpp ../../bionic-paths/native_binder.cpp -o nbtest -lpthread
//   ./nbtest /path/to/nbinder

#include <errno.h>
#include <fcntl.h>
#include <linux/android/binder.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <unistd.h>

#include <string>
#include <vector>

bool nb_is_binder(int fd);
int nb_open(const char* device, int flags);
void* nb_mmap(int fd, void* addr, size_t size, int prot, int flags);
int nb_ioctl(int fd, unsigned long request, void* arg);
void nb_forget(int fd);

#define CHECK(c)                                                              \
  do {                                                                        \
    if (!(c)) {                                                               \
      fprintf(stderr, "[%d] CHECK failed at %d: %s (errno %d)\n", getpid(), __LINE__, #c, errno); \
      exit(1);                                                                \
    }                                                                         \
  } while (0)

static const char* who = "?";

struct Out {
  std::string b;
  template <typename T>
  void put(const T& v) { b.append(reinterpret_cast<const char*>(&v), sizeof(v)); }
};

struct Cmd {
  uint32_t cmd;
  const char* arg;
};

// One BINDER_WRITE_READ; returns the parsed returns.
static std::vector<Cmd> talk(int fd, const std::string& w, bool read, char* rbuf, size_t rsize) {
  struct binder_write_read bwr = {};
  bwr.write_buffer = reinterpret_cast<uintptr_t>(w.data());
  bwr.write_size = w.size();
  if (read) {
    bwr.read_buffer = reinterpret_cast<uintptr_t>(rbuf);
    bwr.read_size = rsize;
  }
  CHECK(nb_ioctl(fd, BINDER_WRITE_READ, &bwr) == 0);
  CHECK(bwr.write_consumed == w.size());
  std::vector<Cmd> out;
  size_t pos = 0;
  while (pos + 4 <= bwr.read_consumed) {
    uint32_t c;
    memcpy(&c, rbuf + pos, 4);
    out.push_back({c, rbuf + pos + 4});
    pos += 4 + _IOC_SIZE(c);
  }
  return out;
}

static int open_binder() {
  int fd = nb_open("binder", O_CLOEXEC);
  CHECK(fd >= 0);
  CHECK(nb_is_binder(fd));
  struct binder_version v;
  CHECK(nb_ioctl(fd, BINDER_VERSION, &v) == 0 && v.protocol_version == BINDER_CURRENT_PROTOCOL_VERSION);
  uint32_t max = 4;
  CHECK(nb_ioctl(fd, BINDER_SET_MAX_THREADS, &max) == 0);
  void* m = nb_mmap(fd, nullptr, 1 << 20, PROT_READ, MAP_PRIVATE | MAP_NORESERVE);
  CHECK(m != MAP_FAILED);
  return fd;
}

static void server(int ready_fd) {
  who = "server";
  int fd = open_binder();
  struct flat_binder_object obj = {};
  obj.flags = FLAT_BINDER_FLAG_TXN_SECURITY_CTX | FLAT_BINDER_FLAG_ACCEPTS_FDS;
  CHECK(nb_ioctl(fd, BINDER_SET_CONTEXT_MGR_EXT, &obj) == 0);
  CHECK(nb_ioctl(fd, BINDER_SET_CONTEXT_MGR_EXT, &obj) == -1 && errno == EBUSY);
  {  // setupPolling()
    Out o;
    o.put<uint32_t>(BC_ENTER_LOOPER);
    char r[256];
    talk(fd, o.b, false, r, sizeof(r));
  }
  CHECK(write(ready_fd, "r", 1) == 1);
  bool got_acquire = false;
  while (true) {
    struct pollfd p = {fd, POLLIN, 0};
    CHECK(poll(&p, 1, 10000) == 1);
    char r[256];
    Out o;
    for (const Cmd& c : talk(fd, "", true, r, sizeof(r))) {
      switch (c.cmd) {
        case BR_NOOP:
        case BR_TRANSACTION_COMPLETE:
        case BR_SPAWN_LOOPER:  // we don't start more threads
          break;
        case BR_INCREFS:
        case BR_ACQUIRE: {
          struct binder_ptr_cookie pc;
          memcpy(&pc, c.arg, sizeof(pc));
          CHECK(pc.ptr == 0x1234 && pc.cookie == 0x5678);
          o.put<uint32_t>(c.cmd == BR_INCREFS ? BC_INCREFS_DONE : BC_ACQUIRE_DONE);
          o.put(pc);
          if (c.cmd == BR_ACQUIRE) got_acquire = true;
          printf("server: %s for node 0x1234\n", c.cmd == BR_INCREFS ? "BR_INCREFS" : "BR_ACQUIRE");
          break;
        }
        case BR_TRANSACTION:
        case BR_TRANSACTION_SEC_CTX: {
          struct binder_transaction_data_secctx trs = {};
          memcpy(&trs, c.arg, _IOC_SIZE(c.cmd));
          struct binder_transaction_data& tr = trs.transaction_data;
          const char* data = reinterpret_cast<const char*>(tr.data.ptr.buffer);
          if (tr.code == 1) {
            // SEC_CTX only where the sender's SELinux context can be read.
            CHECK(tr.target.ptr == 0);
            printf("server: code 1 from pid %d, secctx '%s', %llu data bytes\n", tr.sender_pid,
                   c.cmd == BR_TRANSACTION_SEC_CTX ? reinterpret_cast<const char*>(trs.secctx) : "",
                   static_cast<unsigned long long>(tr.data_size));
            CHECK(memcmp(data, "hello\0\0\0", 8) == 0);
            struct binder_fd_object fo;
            memcpy(&fo, data + 8, sizeof(fo));
            CHECK(fo.hdr.type == BINDER_TYPE_FD);
            char buf[16] = {};
            CHECK(read(static_cast<int>(fo.fd), buf, sizeof(buf)) == 9);
            CHECK(strcmp(buf, "pipe-data") == 0);
            printf("server: read '%s' from the received fd %d\n", buf, fo.fd);
            close(static_cast<int>(fo.fd));
            // Reply: a binder object and 42.
            static char rdata[sizeof(struct flat_binder_object) + 4];
            static binder_size_t roffs[1] = {0};
            struct flat_binder_object fb = {};
            fb.hdr.type = BINDER_TYPE_BINDER;
            fb.flags = FLAT_BINDER_FLAG_ACCEPTS_FDS;
            fb.binder = 0x1234;
            fb.cookie = 0x5678;
            memcpy(rdata, &fb, sizeof(fb));
            uint32_t v = 42;
            memcpy(rdata + sizeof(fb), &v, 4);
            o.put<uint32_t>(BC_FREE_BUFFER);
            o.put<binder_uintptr_t>(tr.data.ptr.buffer);
            struct binder_transaction_data rt = {};
            rt.data_size = sizeof(rdata);
            rt.offsets_size = sizeof(roffs);
            rt.data.ptr.buffer = reinterpret_cast<uintptr_t>(rdata);
            rt.data.ptr.offsets = reinterpret_cast<uintptr_t>(roffs);
            o.put<uint32_t>(BC_REPLY);
            o.put(rt);
          } else if (tr.code == 2) {
            CHECK(tr.target.ptr == 0x1234 && tr.cookie == 0x5678 && (tr.flags & TF_ONE_WAY));
            CHECK(got_acquire);
            printf("server: oneway code 2 on node 0x1234: '%.*s'; exiting\n",
                   static_cast<int>(tr.data_size), data);
            o.put<uint32_t>(BC_FREE_BUFFER);
            o.put<binder_uintptr_t>(tr.data.ptr.buffer);
            talk(fd, o.b, false, r, sizeof(r));
            exit(0);  // the client should get BR_DEAD_BINDER
          }
          break;
        }
        default:
          fprintf(stderr, "server: unexpected return %x\n", c.cmd);
          exit(1);
      }
    }
    if (!o.b.empty()) talk(fd, o.b, false, r, sizeof(r));  // flushCommands()
  }
}

static std::vector<Cmd> wait_for(int fd, uint32_t want, char* r, size_t rsize, const std::string& w = "") {
  std::string pending = w;
  for (int i = 0; i < 20; i++) {
    std::vector<Cmd> cmds = talk(fd, pending, true, r, rsize);
    pending.clear();
    for (const Cmd& c : cmds) {
      if (c.cmd == want) return cmds;
    }
  }
  fprintf(stderr, "client: never got %x\n", want);
  exit(1);
}

static void client() {
  who = "client";
  int fd = open_binder();
  int p[2];
  CHECK(pipe(p) == 0);
  CHECK(write(p[1], "pipe-data", 9) == 9);
  close(p[1]);

  char data[8 + sizeof(struct binder_fd_object)] = "hello";
  struct binder_fd_object fo = {};
  fo.hdr.type = BINDER_TYPE_FD;
  fo.fd = static_cast<__u32>(p[0]);
  memcpy(data + 8, &fo, sizeof(fo));
  binder_size_t offs[1] = {8};
  struct binder_transaction_data tr = {};
  tr.target.handle = 0;
  tr.code = 1;
  tr.flags = TF_ACCEPT_FDS;
  tr.data_size = sizeof(data);
  tr.offsets_size = sizeof(offs);
  tr.data.ptr.buffer = reinterpret_cast<uintptr_t>(data);
  tr.data.ptr.offsets = reinterpret_cast<uintptr_t>(offs);
  Out o;
  o.put<uint32_t>(BC_TRANSACTION);
  o.put(tr);
  char r[256];
  std::vector<Cmd> cmds = wait_for(fd, BR_REPLY, r, sizeof(r), o.b);
  close(p[0]);
  struct binder_transaction_data rt;
  for (const Cmd& c : cmds) {
    if (c.cmd == BR_REPLY) memcpy(&rt, c.arg, sizeof(rt));
  }
  CHECK(rt.data_size == sizeof(struct flat_binder_object) + 4);
  struct flat_binder_object fb;
  memcpy(&fb, reinterpret_cast<const char*>(rt.data.ptr.buffer), sizeof(fb));
  uint32_t v;
  memcpy(&v, reinterpret_cast<const char*>(rt.data.ptr.buffer) + sizeof(fb), 4);
  CHECK(fb.hdr.type == BINDER_TYPE_HANDLE && v == 42);
  uint32_t h = fb.handle;
  printf("client: reply has handle %u and %u\n", h, v);
  CHECK(h == 1);

  // Hold our own references (as BpBinder does), free the reply, ask for a
  // death notification and call the new object oneway.
  Out o2;
  o2.put<uint32_t>(BC_INCREFS);
  o2.put<uint32_t>(h);
  o2.put<uint32_t>(BC_ACQUIRE);
  o2.put<uint32_t>(h);
  o2.put<uint32_t>(BC_FREE_BUFFER);
  o2.put<binder_uintptr_t>(rt.data.ptr.buffer);
  struct binder_handle_cookie hc = {h, 0xdead};
  o2.put<uint32_t>(BC_REQUEST_DEATH_NOTIFICATION);
  o2.put(hc);
  static char d2[] = "ping";
  struct binder_transaction_data t2 = {};
  t2.target.handle = h;
  t2.code = 2;
  t2.flags = TF_ONE_WAY;
  t2.data_size = 4;
  t2.data.ptr.buffer = reinterpret_cast<uintptr_t>(d2);
  o2.put<uint32_t>(BC_TRANSACTION);
  o2.put(t2);
  wait_for(fd, BR_TRANSACTION_COMPLETE, r, sizeof(r), o2.b);
  printf("client: oneway call sent\n");

  // Death notifications go to the process's looper threads, as with the driver.
  Out loop;
  loop.put<uint32_t>(BC_ENTER_LOOPER);
  cmds = wait_for(fd, BR_DEAD_BINDER, r, sizeof(r), loop.b);
  for (const Cmd& c : cmds) {
    if (c.cmd == BR_DEAD_BINDER) {
      binder_uintptr_t cookie;
      memcpy(&cookie, c.arg, sizeof(cookie));
      CHECK(cookie == 0xdead);
    }
  }
  printf("client: got BR_DEAD_BINDER\n");
  Out o3;
  o3.put<uint32_t>(BC_DEAD_BINDER_DONE);
  o3.put<binder_uintptr_t>(0xdead);
  o3.put<uint32_t>(BC_CLEAR_DEATH_NOTIFICATION);
  o3.put(hc);
  wait_for(fd, BR_CLEAR_DEATH_NOTIFICATION_DONE, r, sizeof(r), o3.b);
  printf("client: death notification cleared\n");

  Out o4;
  o4.put<uint32_t>(BC_RELEASE);
  o4.put<uint32_t>(h);
  o4.put<uint32_t>(BC_DECREFS);
  o4.put<uint32_t>(h);
  struct binder_transaction_data t3 = {};
  t3.target.handle = 0;
  t3.code = 3;
  o4.put<uint32_t>(BC_TRANSACTION);
  o4.put(t3);
  wait_for(fd, BR_DEAD_REPLY, r, sizeof(r), o4.b);
  printf("client: call to the dead context manager: BR_DEAD_REPLY\n");
  close(fd);
  nb_forget(fd);
}

int main(int argc, char** argv) {
  if (argc < 2) return 2;
  char sock[] = "/tmp/nbtest-XXXXXX";
  CHECK(mkdtemp(sock) != nullptr);
  std::string path = std::string(sock) + "/nbinder";
  setenv("NBINDER_SOCKET", path.c_str(), 1);
  pid_t d = fork();
  if (d == 0) {
    execl(argv[1], argv[1], "-f", "-s", path.c_str(), argc > 2 ? "-v" : nullptr, nullptr);
    _exit(127);
  }
  for (int i = 0; i < 100 && access(path.c_str(), F_OK) != 0; i++) usleep(10000);
  int rp[2];
  CHECK(pipe(rp) == 0);
  pid_t s = fork();
  if (s == 0) {
    close(rp[0]);
    server(rp[1]);
  }
  close(rp[1]);
  char c;
  CHECK(read(rp[0], &c, 1) == 1);
  pid_t cl = fork();
  if (cl == 0) {
    client();
    exit(0);
  }
  int st;
  bool ok = true;
  waitpid(cl, &st, 0);
  ok &= WIFEXITED(st) && WEXITSTATUS(st) == 0;
  waitpid(s, &st, 0);
  ok &= WIFEXITED(st) && WEXITSTATUS(st) == 0;
  kill(d, SIGTERM);
  waitpid(d, &st, 0);
  printf(ok ? "PASS\n" : "FAIL\n");
  return ok ? 0 : 1;
}
