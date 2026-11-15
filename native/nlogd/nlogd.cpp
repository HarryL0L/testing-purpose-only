/*
 * nlogd: logd's write socket for native mode.
 *
 * liblog (ALOGx, android::base LOG with the default logger) and libc's crash
 * messages write datagrams to /dev/socket/logdw. In native mode there is no
 * logd, so those messages would be lost; nlogd receives them and appends them
 * as text to /data/local/tmp/log.txt (both paths inside the root):
 *   MM-DD HH:MM:SS.mmm  PID  TID P tag: message
 * Binary buffers (events, stats, security) are skipped.
 *
 *   nlogd [-f]      -f: stay in the foreground and also print to stdout
 */

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

static const char kSocket[] = "/dev/socket/logdw";
static const char kLog[] = "/data/local/tmp/log.txt";

static char prio_letter(uint8_t p) {
  static const char letters[] = "??VDIWEF";
  return p < sizeof(letters) - 1 ? letters[p] : '?';
}

int main(int argc, char** argv) {
  bool foreground = argc > 1 && strcmp(argv[1], "-f") == 0;
  if (getenv("ANDROID_NATIVE_ROOT") == nullptr) {
    fprintf(stderr, "nlogd: ANDROID_NATIVE_ROOT is not set: refusing to use the host's %s\n", kSocket);
    return 1;
  }
  mkdir("/dev/socket", 0755);
  unlink(kSocket);
  int s = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
  struct sockaddr_un addr = {};
  addr.sun_family = AF_UNIX;
  strncpy(addr.sun_path, kSocket, sizeof(addr.sun_path) - 1);
  if (s == -1 || bind(s, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) != 0) {
    fprintf(stderr, "nlogd: cannot bind %s: %s\n", kSocket, strerror(errno));
    return 1;
  }
  chmod(kSocket, 0666);
  int on = 1;
  setsockopt(s, SOL_SOCKET, SO_PASSCRED, &on, sizeof(on));
  int out = open(kLog, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
  if (out == -1) {
    fprintf(stderr, "nlogd: cannot open %s: %s\n", kLog, strerror(errno));
    return 1;
  }
  fprintf(stderr, "nlogd: logging to %s\n", kLog);
  if (!foreground && daemon(1, 1) != 0) return 1;

  while (true) {
    static uint8_t buf[8192];
    struct iovec iov = {buf, sizeof(buf) - 1};
    alignas(struct cmsghdr) char control[CMSG_SPACE(sizeof(struct ucred))];
    struct msghdr msg = {};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);
    ssize_t n = recvmsg(s, &msg, 0);
    if (n < 0) {
      if (errno == EINTR) continue;
      return 1;
    }
    // android_log_header_t: id (1), tid (2), realtime sec (4), nsec (4)
    if (n < 11 + 2) continue;
    pid_t pid = 0;
    for (struct cmsghdr* c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
      if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_CREDENTIALS) {
        struct ucred cred;
        memcpy(&cred, CMSG_DATA(c), sizeof(cred));
        pid = cred.pid;
      }
    }
    uint8_t id = buf[0];
    if (id == 2 || id == 5 || id == 6) continue;  // events, stats, security: binary
    uint16_t tid;
    uint32_t sec, nsec;
    memcpy(&tid, buf + 1, 2);
    memcpy(&sec, buf + 3, 4);
    memcpy(&nsec, buf + 7, 4);
    buf[n] = '\0';
    uint8_t prio = buf[11];
    const char* tag = reinterpret_cast<const char*>(buf + 12);
    size_t tag_len = strnlen(tag, static_cast<size_t>(n) - 12);
    const char* text = tag + tag_len + 1;
    if (text > reinterpret_cast<const char*>(buf + n)) text = reinterpret_cast<const char*>(buf + n);
    time_t t = static_cast<time_t>(sec);
    struct tm tm;
    localtime_r(&t, &tm);
    char head[96];
    snprintf(head, sizeof(head), "%02d-%02d %02d:%02d:%02d.%03u %5d %5u %c ", tm.tm_mon + 1,
             tm.tm_mday, tm.tm_hour, tm.tm_min, tm.tm_sec, nsec / 1000000, pid, tid,
             prio_letter(prio));
    // One output line per message line.
    const char* p = text;
    do {
      const char* nl = strchr(p, '\n');
      size_t len = nl ? static_cast<size_t>(nl - p) : strlen(p);
      if (len > 0 || nl == nullptr) {
        char line[9000];
        int k = snprintf(line, sizeof(line), "%s%s: %.*s\n", head, tag, static_cast<int>(len), p);
        if (k > 0) {
          size_t w = static_cast<size_t>(k) < sizeof(line) ? static_cast<size_t>(k) : sizeof(line) - 1;
          if (write(out, line, w) < 0) {
          }
          if (foreground && write(STDOUT_FILENO, line, w) < 0) {
          }
        }
      }
      p = nl ? nl + 1 : nullptr;
    } while (p != nullptr && *p != '\0');
  }
}
