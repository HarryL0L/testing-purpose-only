/*
 * nsocket: start a program with init-style sockets, for native mode.
 *
 * init creates the sockets a service declares (socket NAME TYPE PERM ...),
 * binds them to /dev/socket/NAME and passes them in ANDROID_SOCKET_NAME; the
 * service picks them up with android_get_control_socket(). nsocket does the
 * same for programs run from the root (its /dev/socket):
 *
 *   nsocket NAME:TYPE[:PERM] ... -- PROGRAM [ARGS...]
 *
 * TYPE is stream, dgram or seqpacket; PERM is octal (default 0666). Every
 * other inherited fd except stdio is closed, as for a service init starts.
 *   nsocket zygote:stream:0660 usap_pool_primary:stream:0660 -- \
 *       /system/bin/app_process64 -Xzygote /system/bin --zygote ...
 */

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <set>
#include <string>

// Close every fd but stdio and `keep`: a service started by init has no
// others, and zygote refuses to fork with any fd it doesn't know.
static void close_others(const std::set<int>& keep) {
  DIR* d = opendir("/proc/self/fd");
  if (d == nullptr) return;
  std::set<int> fds;
  while (struct dirent* e = readdir(d)) {
    if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
    int fd = atoi(e->d_name);
    if (fd > 2 && fd != dirfd(d) && !keep.count(fd)) fds.insert(fd);
  }
  closedir(d);
  for (int fd : fds) close(fd);
}

int main(int argc, char** argv) {
  std::set<int> keep;
  int i = 1;
  for (; i < argc && strcmp(argv[i], "--") != 0; i++) {
    std::string spec = argv[i];
    size_t c1 = spec.find(':');
    if (c1 == std::string::npos) {
      fprintf(stderr, "nsocket: bad socket spec '%s'\n", argv[i]);
      return 2;
    }
    std::string name = spec.substr(0, c1);
    size_t c2 = spec.find(':', c1 + 1);
    std::string type = spec.substr(c1 + 1, c2 == std::string::npos ? std::string::npos : c2 - c1 - 1);
    mode_t perm = c2 == std::string::npos ? 0666 : static_cast<mode_t>(strtoul(spec.c_str() + c2 + 1, nullptr, 8));
    int st = type == "stream" ? SOCK_STREAM : type == "dgram" ? SOCK_DGRAM
           : type == "seqpacket" ? SOCK_SEQPACKET : -1;
    if (st == -1 || name.empty()) {
      fprintf(stderr, "nsocket: bad socket spec '%s'\n", argv[i]);
      return 2;
    }
    int fd = socket(AF_UNIX, st, 0);  // no CLOEXEC: the program inherits it
    std::string path = "/dev/socket/" + name;
    struct sockaddr_un addr = {};
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path.c_str(), sizeof(addr.sun_path) - 1);
    mkdir("/dev/socket", 0755);
    unlink(path.c_str());
    if (fd == -1 || bind(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) != 0 ||
        (st != SOCK_DGRAM && listen(fd, 128) != 0)) {
      fprintf(stderr, "nsocket: %s: %s\n", path.c_str(), strerror(errno));
      return 1;
    }
    chmod(path.c_str(), perm);
    std::string var = "ANDROID_SOCKET_" + name;
    for (char& ch : var) {
      if (ch == '-' || ch == '.') ch = '_';
    }
    setenv(var.c_str(), std::to_string(fd).c_str(), 1);
    keep.insert(fd);
  }
  close_others(keep);
  if (i + 1 >= argc) {
    fprintf(stderr, "usage: nsocket NAME:TYPE[:PERM]... -- PROGRAM [ARGS...]\n");
    return 2;
  }
  execv(argv[i + 1], argv + i + 1);
  fprintf(stderr, "nsocket: %s: %s\n", argv[i + 1], strerror(errno));
  return 127;
}
