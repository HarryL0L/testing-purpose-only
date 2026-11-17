// libnviewer: the native half of native mode's app.
//
//   display  ncomposer's frames (socket @ndisplay, ../../../../gfx/nviewer.h):
//            each buffer (a dma-buf) becomes an EGLImage (EGL_EXT_image_dma_buf_
//            import) drawn into the app's surface, scaled to fit; where import
//            isn't possible (a memfd) its pixels are uploaded instead
//   input    records for ninput (socket @ninput)
//   start    Android 17's processes from the app's files: run.sh nboot, then
//            run.sh nzygote; this process adopts their orphans (some phones
//            kill apps' orphaned processes) and reaps them, as init would
//   untar    a root streamed from Termux (tar --xattrs), keeping the owner and
//            label records native mode's libc keeps in user.nmode.* xattrs
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <limits.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "nviewer.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "nviewer", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "nviewer", __VA_ARGS__)

namespace {

int ConnectAbstract(const char* name, int type) {
  int s = socket(AF_UNIX, type | SOCK_CLOEXEC, 0);
  if (s < 0) return -1;
  struct sockaddr_un addr = {};
  addr.sun_family = AF_UNIX;
  size_t n = strlen(name);
  memcpy(addr.sun_path + 1, name, n);
  socklen_t len = offsetof(struct sockaddr_un, sun_path) + 1 + n;
  if (connect(s, reinterpret_cast<struct sockaddr*>(&addr), len) != 0) {
    close(s);
    return -1;
  }
  return s;
}

// ------------------------------------------------------------------ display

std::atomic<bool> g_view_run{false};
std::thread g_view_thread;
std::mutex g_size_mu;
int g_disp_w = 0, g_disp_h = 0;  // 0: not connected

const char* kVertex =
    "attribute vec2 pos; attribute vec2 uv; varying vec2 v;"
    "void main() { v = uv; gl_Position = vec4(pos, 0.0, 1.0); }";
const char* kFragment =
    "precision mediump float; varying vec2 v; uniform sampler2D tex;"
    "void main() { gl_FragColor = texture2D(tex, v); }";

GLuint Shader(GLenum type, const char* src) {
  GLuint s = glCreateShader(type);
  glShaderSource(s, 1, &src, nullptr);
  glCompileShader(s);
  return s;
}

// The DRM fourcc of an Android PixelFormat (byte order in memory).
uint32_t Fourcc(uint32_t format) {
  switch (format) {
    case 1: return 0x34324241;  // RGBA_8888 -> DRM_FORMAT_ABGR8888 'AB24'
    case 2: return 0x34324258;  // RGBX_8888 -> DRM_FORMAT_XBGR8888 'XB24'
    case 5: return 0x34325241;  // BGRA_8888 -> DRM_FORMAT_ARGB8888 'AR24'
    default: return 0;
  }
}

struct Buffer {
  int fd = -1;
  EGLImageKHR image = EGL_NO_IMAGE_KHR;
  GLuint tex = 0;
  void* map = nullptr;  // the upload path's mapping
  size_t map_len = 0;
};

void ViewerLoop(ANativeWindow* win) {
  EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  eglInitialize(dpy, nullptr, nullptr);
  const EGLint cfg_attrs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_SURFACE_TYPE,
                              EGL_WINDOW_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
                              EGL_BLUE_SIZE, 8, EGL_NONE};
  EGLConfig cfg;
  EGLint ncfg = 0;
  eglChooseConfig(dpy, cfg_attrs, &cfg, 1, &ncfg);
  const EGLint ctx_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
  EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctx_attrs);
  EGLSurface surf = eglCreateWindowSurface(dpy, cfg, win, nullptr);
  eglMakeCurrent(dpy, surf, surf, ctx);
  // Don't wait for this phone's vsync in eglSwapBuffers: the composer waits
  // for each frame's ack, so a blocking swap held Android 17's SurfaceFlinger
  // up to a whole frame and halved its frame rate. Android's queue still
  // shows whole frames (no tearing), dropping a stale one if two arrive.
  eglSwapInterval(dpy, 0);
  auto create_image = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
  auto destroy_image = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
  auto target_tex = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
      eglGetProcAddress("glEGLImageTargetTexture2DOES"));
  const char* exts = eglQueryString(dpy, EGL_EXTENSIONS);
  bool can_import = exts && strstr(exts, "EGL_EXT_image_dma_buf_import") && create_image && target_tex;
  LOG("EGL %s; dma-buf import %s", eglQueryString(dpy, EGL_VENDOR), can_import ? "yes" : "no");

  GLuint prog = glCreateProgram();
  glAttachShader(prog, Shader(GL_VERTEX_SHADER, kVertex));
  glAttachShader(prog, Shader(GL_FRAGMENT_SHADER, kFragment));
  glBindAttribLocation(prog, 0, "pos");
  glBindAttribLocation(prog, 1, "uv");
  glLinkProgram(prog);
  glUseProgram(prog);

  std::map<uint64_t, Buffer> bufs;
  int sock = -1;
  while (g_view_run) {
    if (sock < 0) {
      sock = ConnectAbstract(NVIEWER_SOCKET, SOCK_SEQPACKET);
      if (sock < 0) {
        usleep(300000);
        continue;
      }
      nviewer_hello h;
      if (recv(sock, &h, sizeof(h), 0) != sizeof(h) || h.magic != NVIEWER_HELLO) {
        close(sock);
        sock = -1;
        continue;
      }
      std::lock_guard<std::mutex> g(g_size_mu);
      g_disp_w = h.width;
      g_disp_h = h.height;
      LOG("connected: display %ux%u", h.width, h.height);
    }
    struct pollfd p = {sock, POLLIN, 0};
    if (poll(&p, 1, 200) <= 0) continue;
    nviewer_frame f;
    struct iovec iov = {&f, sizeof(f)};
    alignas(struct cmsghdr) char control[CMSG_SPACE(sizeof(int))];
    struct msghdr msg = {};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);
    ssize_t n = recvmsg(sock, &msg, MSG_CMSG_CLOEXEC);
    if (n <= 0) {  // the composer went away (Android stopped): start over
      close(sock);
      sock = -1;
      for (auto& [id, b] : bufs) {
        if (b.image != EGL_NO_IMAGE_KHR) destroy_image(dpy, b.image);
        if (b.tex) glDeleteTextures(1, &b.tex);
        if (b.map) munmap(b.map, b.map_len);
        if (b.fd >= 0) close(b.fd);
      }
      bufs.clear();
      std::lock_guard<std::mutex> g(g_size_mu);
      g_disp_w = g_disp_h = 0;
      continue;
    }
    if (n != sizeof(f) || f.magic != NVIEWER_FRAME) continue;
    Buffer& b = bufs[f.buffer_id];
    struct cmsghdr* c = CMSG_FIRSTHDR(&msg);
    if (c && c->cmsg_type == SCM_RIGHTS) {
      if (b.fd >= 0) close(b.fd);
      memcpy(&b.fd, CMSG_DATA(c), sizeof(int));
    }
    if (b.fd < 0) continue;
    if (!b.tex) {
      glGenTextures(1, &b.tex);
      glBindTexture(GL_TEXTURE_2D, b.tex);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
      if (can_import && Fourcc(f.format)) {
        EGLint attrs[] = {EGL_WIDTH, (EGLint)f.width, EGL_HEIGHT, (EGLint)f.height,
                          EGL_LINUX_DRM_FOURCC_EXT, (EGLint)Fourcc(f.format),
                          EGL_DMA_BUF_PLANE0_FD_EXT, b.fd, EGL_DMA_BUF_PLANE0_OFFSET_EXT, 0,
                          EGL_DMA_BUF_PLANE0_PITCH_EXT, (EGLint)(f.stride * 4), EGL_NONE};
        b.image = create_image(dpy, EGL_NO_CONTEXT, EGL_LINUX_DMA_BUF_EXT, nullptr, attrs);
        if (b.image != EGL_NO_IMAGE_KHR) {
          target_tex(GL_TEXTURE_2D, b.image);
        } else {
          LOGE("dma-buf import failed (0x%x): uploading instead", eglGetError());
        }
      }
      if (b.image == EGL_NO_IMAGE_KHR) {
        b.map_len = (size_t)f.stride * f.height * 4;
        b.map = mmap(nullptr, b.map_len, PROT_READ, MAP_SHARED, b.fd, 0);
        if (b.map == MAP_FAILED) b.map = nullptr;
      }
    }
    glBindTexture(GL_TEXTURE_2D, b.tex);
    if (b.map) {  // the upload path: the buffer's pixels, row length = stride
      glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
      glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, f.stride, f.height, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                   b.map);
    }
    // Scaled to fit, centred (the touch mapping in MainActivity matches).
    EGLint sw = 0, sh = 0;
    eglQuerySurface(dpy, surf, EGL_WIDTH, &sw);
    eglQuerySurface(dpy, surf, EGL_HEIGHT, &sh);
    float scale = std::min((float)sw / f.width, (float)sh / f.height);
    float hw = f.width * scale / sw, hh = f.height * scale / sh;  // half extents in NDC
    float umax = b.map ? (float)f.width / f.stride : 1.0f;
    const GLfloat verts[] = {-hw, -hh, 0, 1, hw, -hh, umax, 1, -hw, hh, 0, 0, hw, hh, umax, 0};
    glViewport(0, 0, sw, sh);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 16, verts);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 16, verts + 2);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glFinish();  // done reading the buffer: SurfaceFlinger may draw into it again
    nviewer_ack a = {NVIEWER_ACK, f.frame};
    send(sock, &a, sizeof(a), MSG_NOSIGNAL);
    eglSwapBuffers(dpy, surf);
  }
  if (sock >= 0) close(sock);
  for (auto& [id, b] : bufs) {
    if (b.image != EGL_NO_IMAGE_KHR) destroy_image(dpy, b.image);
    if (b.map) munmap(b.map, b.map_len);
    if (b.fd >= 0) close(b.fd);
  }
  eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
  eglDestroySurface(dpy, surf);
  eglDestroyContext(dpy, ctx);
  ANativeWindow_release(win);
  std::lock_guard<std::mutex> g(g_size_mu);
  g_disp_w = g_disp_h = 0;
}

// -------------------------------------------------------------------- input

std::mutex g_in_mu;
int g_in = -1;

void SendInput(int kind, int x, int y) {
  std::lock_guard<std::mutex> g(g_in_mu);
  int32_t rec[4] = {kind, 0, x, y};
  for (int attempt = 0; attempt < 2; attempt++) {
    if (g_in < 0) g_in = ConnectAbstract("ninput", SOCK_STREAM);
    if (g_in < 0) return;
    if (send(g_in, rec, sizeof(rec), MSG_NOSIGNAL) == sizeof(rec)) return;
    close(g_in);
    g_in = -1;
  }
}

// ---------------------------------------------------------------- processes

std::atomic<bool> g_running{false};

pid_t Spawn(const std::string& dir, const std::vector<std::string>& args, const std::string& log) {
  pid_t pid = fork();
  if (pid != 0) return pid;
  // No setsid(): nzygote execs into zygote, which starts with setpgid(0, 0),
  // and a session leader may not do that (EPERM: zygote aborted at once).
  int fd = open(log.c_str(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
  int null = open("/dev/null", O_RDONLY);
  dup2(null, 0);
  if (fd >= 0) {
    dup2(fd, 1);
    dup2(fd, 2);
  }
  chdir(dir.c_str());
  std::vector<char*> argv;
  for (auto& a : args) argv.push_back(const_cast<char*>(a.c_str()));
  argv.push_back(nullptr);
  execv(argv[0], argv.data());
  _exit(127);
}

// Remove everything under `path` (not `path` itself). Directories and files
// may have modes that refuse their owner (BPF pins: ----rw----, sticky
// directories); the app owns them all, so it gives itself the bits first.
// Returns how many entries couldn't be removed.
int ClearDir(const std::string& path, FILE* log) {
  int failed = 0;
  chmod(path.c_str(), 0700 | S_ISVTX);
  DIR* d = opendir(path.c_str());
  if (!d) {
    if (errno != ENOENT && log) fprintf(log, "app: can't open %s: %s\n", path.c_str(), strerror(errno));
    return errno == ENOENT ? 0 : 1;
  }
  while (struct dirent* e = readdir(d)) {
    if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
    std::string p = path + "/" + e->d_name;
    struct stat st;
    if (lstat(p.c_str(), &st) == 0 && S_ISDIR(st.st_mode)) {
      failed += ClearDir(p, log);
      if (rmdir(p.c_str()) != 0) {
        failed++;
        if (log) fprintf(log, "app: can't remove %s: %s\n", p.c_str(), strerror(errno));
      }
    } else if (unlink(p.c_str()) != 0) {
      failed++;
      if (log) fprintf(log, "app: can't remove %s: %s\n", p.c_str(), strerror(errno));
    }
  }
  closedir(d);
  return failed;
}

void Supervise(std::string dir, std::vector<std::string> props) {
  // Orphans of Android 17's daemons come here instead of going to init.
  prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0);
  std::string log = dir + "/nboot.log";
  // What a reboot empties: the BPF filesystem. netbpfload aborts when it finds
  // pins from an earlier run (an imported root brings some along), and
  // nboot's own cleanup doesn't reach them inside the app.
  if (FILE* f = fopen(log.c_str(), "a")) {
    std::string bpf = dir + "/root/sys/fs/bpf";
    int failed = ClearDir(bpf, f);
    chmod(bpf.c_str(), 01777);  // bpffs's root mode (ClatCoordinator checks it)
    fprintf(f, "app: BPF filesystem cleared%s\n", failed ? " (with errors)" : "");
    fclose(f);
  }
  std::vector<std::string> boot = {"/system/bin/sh", dir + "/run.sh", "nboot"};
  boot.insert(boot.end(), props.begin(), props.end());
  pid_t b = Spawn(dir, boot, log);
  int st;
  while (waitpid(b, &st, 0) < 0 && errno == EINTR) {
  }
  pid_t z = Spawn(dir, {"/system/bin/sh", dir + "/run.sh", "nzygote"}, log);
  LOG("zygote started: %d", z);
  while (true) {  // reap whatever ends up here, as init does
    pid_t p = waitpid(-1, &st, 0);
    if (p < 0) {
      if (errno == EINTR) continue;
      break;  // no children left
    }
    if (p == z) LOG("zygote ended (%d)", st);
  }
  g_running = false;
}

// SIGKILL every process whose executable is under dir/root (they show as
// /system/bin/linker64 ...).
int StopAll(const std::string& dir) {
  char real[PATH_MAX];
  std::string root = realpath((dir + "/root").c_str(), real) ? std::string(real) + "/" : dir + "/root/";
  int total = 0;
  for (int round = 0; round < 3; round++) {
    int killed = 0;
    DIR* d = opendir("/proc");
    if (!d) break;
    while (struct dirent* e = readdir(d)) {
      char* end;
      long pid = strtol(e->d_name, &end, 10);
      if (*end || pid <= 0 || pid == getpid()) continue;
      char link[64], exe[PATH_MAX];
      snprintf(link, sizeof(link), "/proc/%ld/exe", pid);
      ssize_t n = readlink(link, exe, sizeof(exe) - 1);
      if (n <= 0) continue;
      exe[n] = '\0';
      if (strncmp(exe, root.c_str(), root.size()) == 0 && kill((pid_t)pid, SIGKILL) == 0) killed++;
    }
    closedir(d);
    total += killed;
    if (!killed) break;
    usleep(500000);
  }
  return total;
}

// -------------------------------------------------------------------- untar

std::atomic<long long> g_untar_bytes{0};

bool ReadFull(int fd, void* buf, size_t len) {
  char* p = static_cast<char*>(buf);
  while (len > 0) {
    ssize_t n = read(fd, p, len);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) return false;
    p += n;
    len -= n;
    g_untar_bytes += n;
  }
  return true;
}

bool Skip(int fd, unsigned long long len) {
  char buf[65536];
  while (len > 0) {
    size_t k = len < sizeof(buf) ? len : sizeof(buf);
    if (!ReadFull(fd, buf, k)) return false;
    len -= k;
  }
  return true;
}

unsigned long long Octal(const char* p, size_t n) {
  if ((unsigned char)p[0] & 0x80) {  // GNU base-256 for big sizes
    unsigned long long v = 0;
    for (size_t i = 1; i < n; i++) v = (v << 8) | (unsigned char)p[i];
    return v;
  }
  unsigned long long v = 0;
  for (size_t i = 0; i < n && p[i]; i++) {
    if (p[i] >= '0' && p[i] <= '7') v = v * 8 + (p[i] - '0');
  }
  return v;
}

bool SafePath(const std::string& p) {
  if (p.empty() || p[0] == '/') return false;
  size_t i = 0;
  while (i <= p.size()) {
    size_t j = p.find('/', i);
    if (j == std::string::npos) j = p.size();
    if (p.compare(i, j - i, "..") == 0) return false;
    i = j + 1;
  }
  return true;
}

void MakeParents(const std::string& path) {
  for (size_t i = 1; i < path.size(); i++) {
    if (path[i] == '/') {
      std::string d = path.substr(0, i);
      mkdir(d.c_str(), 0755);
    }
  }
}

// PAX records "len key=value\n"; a malformed one ends the parse.
void ParsePax(const std::string& data, std::map<std::string, std::string>* out) {
  size_t i = 0;
  while (i < data.size()) {
    size_t sp = data.find(' ', i);
    if (sp == std::string::npos) break;
    long len = strtol(data.c_str() + i, nullptr, 10);
    if (len <= 0 || i + len > data.size()) break;
    std::string rec = data.substr(sp + 1, i + len - sp - 2);  // without the newline
    size_t eq = rec.find('=');
    if (eq != std::string::npos) (*out)[rec.substr(0, eq)] = rec.substr(eq + 1);
    i += len;
  }
}

std::string Untar(int fd, const std::string& dest) {
  g_untar_bytes = 0;
  std::map<std::string, std::string> global, pax;
  std::string longname, longlink;
  std::vector<std::pair<std::string, mode_t>> dir_modes;
  char h[512];
  long files = 0;
  while (true) {
    if (!ReadFull(fd, h, 512)) return files ? std::string() : "the stream ended before any file";
    bool zero = true;
    for (char ch : h) zero = zero && ch == 0;
    if (zero) break;  // end of archive
    unsigned long long size = Octal(h + 124, 12);
    char type = h[156];
    unsigned long long padded = (size + 511) & ~511ull;
    if (type == 'x' || type == 'g' || type == 'L' || type == 'K') {
      if (size > (64u << 20)) return "an oversized extended header";
      std::string data(padded, '\0');
      if (!ReadFull(fd, data.data(), padded)) return "a truncated header";
      data.resize(size);
      if (type == 'x') ParsePax(data, &pax);
      if (type == 'g') ParsePax(data, &global);
      if (type == 'L') longname = data.c_str();
      if (type == 'K') longlink = data.c_str();
      continue;
    }
    std::map<std::string, std::string> attrs = global;
    for (auto& kv : pax) attrs[kv.first] = kv.second;
    std::string name;
    if (!longname.empty()) {
      name = longname;
    } else if (attrs.count("path")) {
      name = attrs["path"];
    } else {
      std::string prefix(h + 345, strnlen(h + 345, 155));
      name = std::string(h, strnlen(h, 100));
      if (!prefix.empty()) name = prefix + "/" + name;
    }
    std::string link = !longlink.empty() ? longlink
                       : attrs.count("linkpath") ? attrs["linkpath"]
                                                  : std::string(h + 157, strnlen(h + 157, 100));
    if (attrs.count("size")) size = strtoull(attrs["size"].c_str(), nullptr, 10);
    padded = (size + 511) & ~511ull;
    pax.clear();
    longname.clear();
    longlink.clear();
    while (!name.empty() && name.back() == '/') name.pop_back();
    if (!SafePath(name)) {
      if (!Skip(fd, padded)) return "truncated";
      continue;
    }
    std::string path = dest + "/" + name;
    mode_t mode = (mode_t)Octal(h + 100, 8) & 07777;
    MakeParents(path);
    bool ok = true;
    if (type == '5') {
      mkdir(path.c_str(), 0700);
      chmod(path.c_str(), mode | 0700);  // writable while files go in
      dir_modes.push_back({path, mode});
    } else if (type == '2') {
      unlink(path.c_str());
      ok = symlink(link.c_str(), path.c_str()) == 0;
    } else if (type == '1') {
      // Hard links: an app may not make them (SELinux); a copy instead.
      if (!SafePath(link)) continue;
      std::string src = dest + "/" + link;
      int in = open(src.c_str(), O_RDONLY | O_CLOEXEC);
      unlink(path.c_str());
      int out = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
      char buf[65536];
      ssize_t n;
      while (in >= 0 && out >= 0 && (n = read(in, buf, sizeof(buf))) > 0) {
        if (write(out, buf, n) != n) break;
      }
      if (in >= 0) close(in);
      if (out >= 0) close(out);
      struct stat st;
      if (stat(src.c_str(), &st) == 0) chmod(path.c_str(), st.st_mode & 07777);
    } else if (type == '0' || type == '\0' || type == '7') {
      unlink(path.c_str());
      int out = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
      if (out < 0) return "can't create " + name + ": " + strerror(errno);
      unsigned long long left = size;
      char buf[65536];
      while (left > 0) {
        size_t k = left < sizeof(buf) ? left : sizeof(buf);
        if (!ReadFull(fd, buf, k)) {
          close(out);
          return "truncated in " + name;
        }
        if (write(out, buf, k) != (ssize_t)k) {
          close(out);
          return "writing " + name + ": " + strerror(errno);
        }
        left -= k;
      }
      close(out);
      if (!Skip(fd, padded - size)) return "truncated";
      padded = 0;
      files++;
    }
    if (padded && !Skip(fd, padded)) return "truncated";
    if (!ok) continue;
    // The owner and label records (user.nmode.*), before the final mode:
    // setting an xattr needs write access. Symlinks take none.
    if (type != '2') {
      for (auto& kv : attrs) {
        static const char kPrefix[] = "SCHILY.xattr.";
        if (kv.first.compare(0, sizeof(kPrefix) - 1, kPrefix) != 0) continue;
        std::string xname = kv.first.substr(sizeof(kPrefix) - 1);
        if (xname.compare(0, 5, "user.") != 0) continue;
        lsetxattr(path.c_str(), xname.c_str(), kv.second.data(), kv.second.size(), 0);
      }
      if (type != '5' && type != '1') chmod(path.c_str(), mode);
    }
  }
  // Directories get their own modes last, deepest first (still u+rwx: the
  // root's directories are the app's to manage, as a17root.py leaves them).
  for (auto it = dir_modes.rbegin(); it != dir_modes.rend(); ++it) {
    chmod(it->first.c_str(), it->second | 0700);
  }
  LOG("untar: %ld files", files);
  return std::string();
}

}  // namespace

// ---------------------------------------------------------------------- JNI

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_io_github_harryl0l_a17_Native_##name

JNI(void, viewerStart)(JNIEnv* env, jclass, jobject surface) {
  if (g_view_run.exchange(true)) return;
  ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
  g_view_thread = std::thread(ViewerLoop, win);
}

JNI(void, viewerStop)(JNIEnv*, jclass) {
  if (!g_view_run.exchange(false)) return;
  if (g_view_thread.joinable()) g_view_thread.join();
}

JNI(jintArray, displaySize)(JNIEnv* env, jclass) {
  std::lock_guard<std::mutex> g(g_size_mu);
  if (g_disp_w <= 0) return nullptr;
  jintArray a = env->NewIntArray(2);
  jint v[2] = {g_disp_w, g_disp_h};
  env->SetIntArrayRegion(a, 0, 2, v);
  return a;
}

JNI(void, input)(JNIEnv*, jclass, jint kind, jint x, jint y) { SendInput(kind, x, y); }

JNI(jboolean, start)(JNIEnv* env, jclass, jstring jdir, jobjectArray jprops) {
  if (g_running.exchange(true)) return JNI_FALSE;
  const char* d = env->GetStringUTFChars(jdir, nullptr);
  std::string dir = d;
  env->ReleaseStringUTFChars(jdir, d);
  std::vector<std::string> props;
  for (jsize i = 0; i < env->GetArrayLength(jprops); i++) {
    auto s = static_cast<jstring>(env->GetObjectArrayElement(jprops, i));
    const char* c = env->GetStringUTFChars(s, nullptr);
    props.emplace_back(c);
    env->ReleaseStringUTFChars(s, c);
  }
  StopAll(dir);  // leftovers of an earlier run
  std::thread(Supervise, dir, props).detach();
  return JNI_TRUE;
}

JNI(jint, stop)(JNIEnv* env, jclass, jstring jdir) {
  const char* d = env->GetStringUTFChars(jdir, nullptr);
  int n = StopAll(d);
  env->ReleaseStringUTFChars(jdir, d);
  return n;
}

JNI(jboolean, running)(JNIEnv*, jclass) { return g_running ? JNI_TRUE : JNI_FALSE; }

JNI(jstring, untar)(JNIEnv* env, jclass, jint fd, jstring jdir) {
  const char* d = env->GetStringUTFChars(jdir, nullptr);
  std::string err = Untar(fd, d);
  env->ReleaseStringUTFChars(jdir, d);
  close(fd);
  return err.empty() ? nullptr : env->NewStringUTF(err.c_str());
}

JNI(jlong, untarBytes)(JNIEnv*, jclass) { return g_untar_bytes; }
