// nlmkd: native mode's stand-in for lmkd, the low-memory killer daemon.
//
// lmkd needs the kernel's pressure-stall monitors (/proc/pressure) or memory
// cgroups, which an app has neither of. ActivityManager still needs someone on
// /dev/socket/lmkd: until it reaches one, every process priority update fails,
// and it then treats content providers' processes as dying ("is crashing").
//
// This speaks lmkd's control protocol (system/memory/lmkd/include/lmkd.h):
// priority updates are applied to /proc/<pid>/oom_score_adj where allowed (so
// the host kernel's own killer picks background processes first) and the
// commands that expect an answer get lmkd's "success" reply. It never kills
// on its own: on a phone the host's lmkd already does that for the whole app.
//
//   nlmkd            (socket from init/nsocket: $ANDROID_SOCKET_lmkd)
//   nlmkd --bind     (creates /dev/socket/lmkd itself)
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

namespace {

enum : int {  // enum lmk_cmd
  LMK_TARGET = 0, LMK_PROCPRIO, LMK_PROCREMOVE, LMK_PROCPURGE, LMK_GETKILLCNT,
  LMK_SUBSCRIBE, LMK_PROCKILL, LMK_UPDATE_PROPS, LMK_STAT_KILL_OCCURRED,
  LMK_START_MONITORING, LMK_BOOT_COMPLETED, LMK_PROCS_PRIO,
};
constexpr int kMaxClients = 8;
constexpr size_t kPacketInts = 6 * 2 + 1 + 64;  // longest: LMK_TARGET (6 targets), room to spare

void SetOomAdj(int pid, int adj) {
  char path[64];
  snprintf(path, sizeof(path), "/proc/%d/oom_score_adj", pid);
  int fd = open(path, O_WRONLY | O_CLOEXEC);
  if (fd == -1) return;
  char val[16];
  int n = snprintf(val, sizeof(val), "%d", adj);
  if (write(fd, val, n) < 0) {}  // lowering needs privilege the app lacks: best effort
  close(fd);
}

void Reply(int fd, int cmd, int value) {
  int pkt[2] = {static_cast<int>(htonl(cmd)), static_cast<int>(htonl(value))};
  if (send(fd, pkt, sizeof(pkt), MSG_NOSIGNAL) < 0) {}
}

// Returns false when the client went away.
bool Handle(int fd) {
  int pkt[kPacketInts];
  ssize_t len = recv(fd, pkt, sizeof(pkt), 0);
  if (len <= 0) return len < 0 && (errno == EINTR || errno == EAGAIN);
  int words = static_cast<int>(len / sizeof(int));
  int cmd = static_cast<int>(ntohl(pkt[0]));
  auto arg = [&](int i) { return i < words ? static_cast<int>(ntohl(pkt[i])) : 0; };
  switch (cmd) {
    case LMK_PROCPRIO:  // pid, uid, oomadj[, type]
      SetOomAdj(arg(1), arg(3));
      break;
    case LMK_PROCS_PRIO:  // records of pid, uid, oomadj, type
      for (int i = 1; i + 3 < words; i += 4) SetOomAdj(arg(i), arg(i + 2));
      break;
    case LMK_GETKILLCNT:
      Reply(fd, LMK_GETKILLCNT, 0);
      break;
    case LMK_UPDATE_PROPS:
    case LMK_BOOT_COMPLETED:
      Reply(fd, cmd, 0);
      break;
    default:  // TARGET, PROCREMOVE, PROCPURGE, SUBSCRIBE, START_MONITORING: nothing to do
      break;
  }
  return true;
}

int ControlSocket(bool bind_it) {
  const char* env = getenv("ANDROID_SOCKET_lmkd");
  if (!bind_it && env != nullptr) return atoi(env);
  int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
  struct sockaddr_un addr = {};
  addr.sun_family = AF_UNIX;
  strncpy(addr.sun_path, "/dev/socket/lmkd", sizeof(addr.sun_path) - 1);
  unlink(addr.sun_path);
  if (fd == -1 || bind(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) != 0) {
    fprintf(stderr, "nlmkd: %s: %s\n", addr.sun_path, strerror(errno));
    return -1;
  }
  chmod(addr.sun_path, 0660);
  return fd;
}

}  // namespace

int main(int argc, char** argv) {
  int ctrl = ControlSocket(argc > 1 && strcmp(argv[1], "--bind") == 0);
  if (ctrl < 0 || listen(ctrl, kMaxClients) != 0) {
    fprintf(stderr, "nlmkd: no control socket: %s\n", strerror(errno));
    return 1;
  }
  struct pollfd fds[kMaxClients + 1];
  int n = 1;
  fds[0] = {ctrl, POLLIN, 0};
  for (;;) {
    if (poll(fds, n, -1) < 0) {
      if (errno == EINTR) continue;
      return 1;
    }
    for (int i = n - 1; i >= 1; i--) {
      if (fds[i].revents == 0) continue;
      if ((fds[i].revents & POLLIN) && Handle(fds[i].fd)) continue;
      close(fds[i].fd);
      fds[i] = fds[--n];
    }
    if (fds[0].revents & POLLIN) {
      int c = accept4(ctrl, nullptr, nullptr, SOCK_CLOEXEC);
      if (c == -1) continue;
      if (n == kMaxClients + 1) {  // lmkd's limit too; the oldest client yields
        close(fds[1].fd);
        fds[1] = fds[--n];
      }
      fds[n++] = {c, POLLIN, 0};
    }
  }
}
