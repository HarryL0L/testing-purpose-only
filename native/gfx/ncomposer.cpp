/*
 * ncomposer: android.hardware.graphics.composer3.IComposer/default (V5) of
 * native mode. One display; every layer is client-composited (SurfaceFlinger
 * draws the whole screen into its client target with RenderEngine), and each
 * presented frame goes to the viewer app over the abstract seqpacket socket
 * "ndisplay" (see nviewer.h: the client target's dma-buf, sent once per
 * buffer, then frame records naming it), and, while no viewer is connected,
 * into the frame file NFB_PATH (for screenshots, nfb2png.py):
 *
 *   [nfb_header: 64 bytes][pixels: stride * height * 4, RGBA_8888]
 *
 * `frame` is bumped after the pixels are written. The display's size comes
 * from native.display.{width,height,dpi,fps}.
 */
#include <aidl/android/hardware/graphics/composer3/BnComposer.h>
#include <aidl/android/hardware/graphics/composer3/BnComposerClient.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android/log.h>
#include <fcntl.h>
#include <linux/dma-buf.h>
#include <poll.h>
#include <stddef.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/system_properties.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include <atomic>
#include <condition_variable>
#include <map>
#include <set>
#include <mutex>
#include <thread>

#include "nmem.h"
#include "nviewer.h"

using namespace aidl::android::hardware::graphics::composer3;
using aidl::android::hardware::common::NativeHandle;
using aidl::android::hardware::graphics::common::DisplayDecorationSupport;
using aidl::android::hardware::graphics::common::DisplayHotplugEvent;
using aidl::android::hardware::graphics::common::Hdr;
using aidl::android::hardware::graphics::common::HdrConversionCapability;
using aidl::android::hardware::graphics::common::HdrConversionStrategy;
using aidl::android::hardware::graphics::common::PixelFormat;
using aidl::android::hardware::graphics::common::Transform;
using ndk::ScopedAStatus;
using ndk::ScopedFileDescriptor;

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "ncomposer", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ncomposer", __VA_ARGS__)

#define NFB_PATH "/data/vendor/nfb"
#define NFB_MAGIC 0x3062666e  // 'nfb0'

struct nfb_header {
  uint32_t magic;
  uint32_t width;
  uint32_t height;
  uint32_t stride;  // pixels
  uint32_t format;  // PixelFormat, RGBA_8888
  uint32_t frame;   // bumped after each frame is written
  uint32_t reserved[10];
};
static_assert(sizeof(nfb_header) == 64, "header");

namespace {

constexpr int64_t kDisplay = 0;

ScopedAStatus Err(int32_t e) { return ScopedAStatus::fromServiceSpecificError(e); }
ScopedAStatus Unsupported() { return Err(IComposerClient::EX_UNSUPPORTED); }

int64_t Now() {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

int Prop(const char* name, int def) {
  char v[PROP_VALUE_MAX] = {};
  if (__system_property_get(name, v) > 0) {
    int n = atoi(v);
    if (n > 0) return n;
  }
  return def;
}

void WaitFence(int fd) {
  if (fd < 0) return;
  struct pollfd p = {fd, POLLIN, 0};
  while (poll(&p, 1, 3000) < 0 && errno == EINTR) {
  }
}

// An already-signaled "fence" for PresentFence (which can't be -1): an app
// can't create sync files, but an eventfd with a count polls as ready, which
// is what waiting on a fence comes down to.
int SignaledFence() {
  static int efd = eventfd(1, EFD_CLOEXEC | EFD_NONBLOCK);
  return efd < 0 ? -1 : fcntl(efd, F_DUPFD_CLOEXEC, 0);
}

// One imported memfd buffer (see nmem.h), mapped read-only.
struct Mapped {
  int fd = -1;
  nmem_ints h{};
  uint8_t* base = nullptr;
  size_t len = 0;
  ~Mapped() {
    if (base) munmap(base, len);
    if (fd >= 0) close(fd);
  }
};

std::shared_ptr<Mapped> Import(const NativeHandle& nh) {
  if (nh.fds.size() != 1 || nh.ints.size() != NMEM_NUM_INTS) return nullptr;
  auto m = std::make_shared<Mapped>();
  memcpy(&m->h, nh.ints.data(), sizeof(m->h));
  if (m->h.magic != NMEM_MAGIC) return nullptr;
  m->fd = fcntl(nh.fds[0].get(), F_DUPFD_CLOEXEC, 0);
  m->len = nmem_u64(m->h.size_lo, m->h.size_hi);
  void* p = mmap(nullptr, m->len, PROT_READ, MAP_SHARED, m->fd, 0);
  if (p == MAP_FAILED) {
    LOGE("mmap client target: %s", strerror(errno));
    return nullptr;
  }
  m->base = static_cast<uint8_t*>(p);
  return m;
}

// The viewer app: one client at a time on "ndisplay" (a new one replaces
// the old). Each presented frame is offered to it, and the composer waits
// (briefly) for the viewer to be done with the buffer before SurfaceFlinger
// may draw into it again.
class Viewer {
 public:
  void Start(int32_t w, int32_t h, int32_t dpi, int32_t fps) {
    hello_ = {NVIEWER_HELLO, NVIEWER_VERSION, (uint32_t)w, (uint32_t)h, (uint32_t)dpi,
              (uint32_t)fps};
    std::thread([this] { Listen(); }).detach();
  }

  bool connected() {
    std::lock_guard<std::mutex> g(mu_);
    return fd_ >= 0;
  }

  // Sends one frame; true if the viewer took it.
  bool Offer(const Mapped& m, uint32_t frame) {
    std::lock_guard<std::mutex> g(mu_);
    if (fd_ < 0) return false;
    uint64_t id = nmem_u64(m.h.id_lo, m.h.id_hi);
    nviewer_frame f = {NVIEWER_FRAME, frame, id, m.h.width, m.h.height, m.h.stride,
                       (uint32_t)m.h.real_format, 0};
    bool first = sent_.insert(id).second;
    f.has_fd = first ? 1 : 0;
    struct iovec iov = {&f, sizeof(f)};
    struct msghdr msg = {};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    alignas(struct cmsghdr) char control[CMSG_SPACE(sizeof(int))];
    if (first) {
      msg.msg_control = control;
      msg.msg_controllen = sizeof(control);
      struct cmsghdr* c = CMSG_FIRSTHDR(&msg);
      c->cmsg_level = SOL_SOCKET;
      c->cmsg_type = SCM_RIGHTS;
      c->cmsg_len = CMSG_LEN(sizeof(int));
      memcpy(CMSG_DATA(c), &m.fd, sizeof(int));
    }
    if (sendmsg(fd_, &msg, MSG_NOSIGNAL) != (ssize_t)sizeof(f)) {
      DropLocked();
      return false;
    }
    // Wait for the viewer to be done with this frame (it has drawn it).
    struct pollfd p = {fd_, POLLIN, 0};
    while (poll(&p, 1, 100) > 0) {
      nviewer_ack a;
      ssize_t n = recv(fd_, &a, sizeof(a), MSG_DONTWAIT);
      if (n <= 0) {
        if (n < 0 && (errno == EAGAIN || errno == EINTR)) continue;
        DropLocked();
        return false;
      }
      if (n == sizeof(a) && a.magic == NVIEWER_ACK && a.frame == frame) break;
    }
    return true;
  }

 private:
  void Listen() {
    int s = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    struct sockaddr_un addr = {};
    addr.sun_family = AF_UNIX;
    memcpy(addr.sun_path + 1, NVIEWER_SOCKET, strlen(NVIEWER_SOCKET));  // abstract
    socklen_t len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(NVIEWER_SOCKET);
    if (s < 0 || bind(s, reinterpret_cast<struct sockaddr*>(&addr), len) != 0 || listen(s, 2) != 0) {
      LOGE("viewer socket: %s", strerror(errno));
      return;
    }
    LOG("waiting for a viewer on @%s", NVIEWER_SOCKET);
    while (true) {
      int c = accept4(s, nullptr, nullptr, SOCK_CLOEXEC);
      if (c < 0) continue;
      if (send(c, &hello_, sizeof(hello_), MSG_NOSIGNAL) != (ssize_t)sizeof(hello_)) {
        close(c);
        continue;
      }
      std::lock_guard<std::mutex> g(mu_);
      DropLocked();
      fd_ = c;
      LOG("viewer connected");
    }
  }

  void DropLocked() {
    if (fd_ >= 0) {
      close(fd_);
      LOG("viewer gone");
    }
    fd_ = -1;
    sent_.clear();
  }

  std::mutex mu_;
  int fd_ = -1;
  std::set<uint64_t> sent_;  // buffers whose fd this viewer has
  nviewer_hello hello_ = {};
};

class Display {
 public:
  Display() {
    width = Prop("native.display.width", 1080);
    height = Prop("native.display.height", 2400);
    dpi = Prop("native.display.dpi", 420);
    fps = Prop("native.display.fps", 60);
    period = 1000000000 / fps;
    viewer_.Start(width, height, dpi, fps);
  }

  int32_t width, height, dpi, fps, period;

  // Hands the client target to the viewer, or copies it into the frame file.
  void Present(const Mapped& m) {
    if (m.h.real_format != 1 && m.h.real_format != 2 && m.h.real_format != 5) return;
    frames_++;
    if (frames_ == 1 || frames_ % 600 == 0) LOG("presented frame %u", frames_);
    if (viewer_.Offer(m, frames_)) return;
    uint32_t stride = m.h.stride;
    size_t bytes = (size_t)stride * m.h.height * 4;
    if (m.h.real_format != 1 && m.h.real_format != 2 && m.h.real_format != 5) return;
    if (!Ensure(m.h.width, m.h.height, stride, m.h.real_format, bytes)) return;
    // A dma-buf (ION) the GPU drew into: read it through a cache sync. A
    // memfd refuses the ioctl and needs none.
    struct dma_buf_sync sync = {DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ};
    ioctl(m.fd, DMA_BUF_IOCTL_SYNC, &sync);
    memcpy(fb_ + sizeof(nfb_header), m.base, std::min(bytes, m.len));
    sync.flags = DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ;
    ioctl(m.fd, DMA_BUF_IOCTL_SYNC, &sync);
    auto* hdr = reinterpret_cast<nfb_header*>(fb_);
    __atomic_add_fetch(&hdr->frame, 1, __ATOMIC_RELEASE);
  }

 private:
  bool Ensure(uint32_t w, uint32_t h, uint32_t stride, int32_t format, size_t bytes) {
    size_t len = sizeof(nfb_header) + bytes;
    if (fb_ && len == fb_len_) return true;
    if (fb_) munmap(fb_, fb_len_);
    fb_ = nullptr;
    int fd = open(NFB_PATH, O_RDWR | O_CREAT | O_CLOEXEC, 0644);
    if (fd < 0 || ftruncate(fd, len) != 0) {
      LOGE("%s: %s", NFB_PATH, strerror(errno));
      if (fd >= 0) close(fd);
      return false;
    }
    void* p = mmap(nullptr, len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (p == MAP_FAILED) return false;
    fb_ = static_cast<uint8_t*>(p);
    fb_len_ = len;
    auto* hdr = reinterpret_cast<nfb_header*>(fb_);
    hdr->magic = NFB_MAGIC;
    hdr->width = w;
    hdr->height = h;
    hdr->stride = stride;
    hdr->format = format;
    return true;
  }

  uint8_t* fb_ = nullptr;
  size_t fb_len_ = 0;
  uint32_t frames_ = 0;
  Viewer viewer_;
};

class Client : public BnComposerClient {
 public:
  Client() : vsync_thread_([this] { VsyncLoop(); }) {}
  ~Client() override {
    {
      std::lock_guard<std::mutex> g(lock_);
      quit_ = true;
    }
    cv_.notify_all();
    vsync_thread_.join();
  }

  ScopedAStatus createLayer(int64_t display, int32_t, int64_t* out) override {
    if (display != kDisplay) return Err(IComposerClient::EX_BAD_DISPLAY);
    std::lock_guard<std::mutex> g(lock_);
    *out = next_layer_++;
    layers_[*out] = Composition::INVALID;
    return ScopedAStatus::ok();
  }
  ScopedAStatus destroyLayer(int64_t display, int64_t layer) override {
    if (display != kDisplay) return Err(IComposerClient::EX_BAD_DISPLAY);
    std::lock_guard<std::mutex> g(lock_);
    return layers_.erase(layer) ? ScopedAStatus::ok() : Err(IComposerClient::EX_BAD_LAYER);
  }
  ScopedAStatus createVirtualDisplay(int32_t, int32_t, PixelFormat, int32_t,
                                     VirtualDisplay*) override {
    return Err(IComposerClient::EX_NO_RESOURCES);
  }
  ScopedAStatus destroyVirtualDisplay(int64_t) override {
    return Err(IComposerClient::EX_BAD_DISPLAY);
  }

  ScopedAStatus executeCommands(const std::vector<DisplayCommand>& commands,
                                std::vector<CommandResultPayload>* results) override {
    std::lock_guard<std::mutex> g(lock_);
    if (getenv("NCOMPOSER_DEBUG")) {
      for (const DisplayCommand& c : commands)
        LOG("display %lld: %zu layers target=%d validate=%d accept=%d present=%d pov=%d",
            (long long)c.display, c.layers.size(), c.clientTarget.has_value(), c.validateDisplay,
            c.acceptDisplayChanges, c.presentDisplay, c.presentOrValidateDisplay);
    }
    int32_t index = 0;
    for (const DisplayCommand& c : commands) {
      if (c.display != kDisplay) {
        results->emplace_back(CommandError{index, IComposerClient::EX_BAD_DISPLAY});
        index++;
        continue;
      }
      for (const LayerCommand& l : c.layers) {
        auto it = layers_.find(l.layer);
        if (it == layers_.end()) {
          if (l.layerLifecycleBatchCommandType == LayerLifecycleBatchCommandType::CREATE) {
            layers_[l.layer] = Composition::INVALID;
            it = layers_.find(l.layer);
          } else {
            continue;
          }
        }
        if (l.layerLifecycleBatchCommandType == LayerLifecycleBatchCommandType::DESTROY) {
          layers_.erase(it);
          continue;
        }
        if (l.composition) it->second = l.composition->composition;
      }
      if (c.clientTarget) {
        const Buffer& b = c.clientTarget->buffer;
        if (b.handle) {
          auto m = Import(*b.handle);
          if (m) slots_[b.slot] = m;
        }
        target_ = slots_.count(b.slot) ? slots_[b.slot] : nullptr;
        WaitFence(b.fence.get());
      }
      if (c.validateDisplay || c.presentOrValidateDisplay) {
        Validate(results);
        if (c.presentOrValidateDisplay)
          results->emplace_back(
              PresentOrValidate{kDisplay, PresentOrValidate::Result::Validated});
      }
      if (c.acceptDisplayChanges) {
        for (auto& [id, comp] : layers_)
          if (comp != Composition::CLIENT) comp = Composition::CLIENT;
      }
      if (c.presentDisplay) {
        if (target_) display_.Present(*target_);
        results->emplace_back(PresentFence{kDisplay, ScopedFileDescriptor(SignaledFence()), std::nullopt});
        ReleaseFences rf;
        rf.display = kDisplay;
        results->emplace_back(std::move(rf));
      }
      index++;
    }
    return ScopedAStatus::ok();
  }

  ScopedAStatus getActiveConfig(int64_t display, int32_t* out) override {
    if (display != kDisplay) return Err(IComposerClient::EX_BAD_DISPLAY);
    *out = 0;
    return ScopedAStatus::ok();
  }
  ScopedAStatus getColorModes(int64_t, std::vector<ColorMode>* out) override {
    *out = {ColorMode::NATIVE};
    return ScopedAStatus::ok();
  }
  ScopedAStatus getDataspaceSaturationMatrix(
      aidl::android::hardware::graphics::common::Dataspace, std::vector<float>* out) override {
    *out = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    return ScopedAStatus::ok();
  }
  ScopedAStatus getDisplayAttribute(int64_t display, int32_t config, DisplayAttribute attr,
                                    int32_t* out) override {
    if (display != kDisplay) return Err(IComposerClient::EX_BAD_DISPLAY);
    if (config != 0) return Err(IComposerClient::EX_BAD_CONFIG);
    switch (attr) {
      case DisplayAttribute::WIDTH: *out = display_.width; break;
      case DisplayAttribute::HEIGHT: *out = display_.height; break;
      case DisplayAttribute::VSYNC_PERIOD: *out = display_.period; break;
      case DisplayAttribute::DPI_X:
      case DisplayAttribute::DPI_Y: *out = display_.dpi * 1000; break;
      case DisplayAttribute::CONFIG_GROUP: *out = 0; break;
      default: return Err(IComposerClient::EX_BAD_PARAMETER);
    }
    return ScopedAStatus::ok();
  }
  ScopedAStatus getDisplayCapabilities(int64_t, std::vector<DisplayCapability>* out) override {
    out->clear();
    return ScopedAStatus::ok();
  }
  ScopedAStatus getDisplayConfigs(int64_t display, std::vector<int32_t>* out) override {
    if (display != kDisplay) return Err(IComposerClient::EX_BAD_DISPLAY);
    *out = {0};
    return ScopedAStatus::ok();
  }
  ScopedAStatus getDisplayConfigurations(int64_t display, int32_t,
                                         std::vector<DisplayConfiguration>* out) override {
    if (display != kDisplay) return Err(IComposerClient::EX_BAD_DISPLAY);
    DisplayConfiguration c;
    c.configId = 0;
    c.width = display_.width;
    c.height = display_.height;
    c.dpi = DisplayConfiguration::Dpi{(float)display_.dpi, (float)display_.dpi};
    c.configGroup = 0;
    c.vsyncPeriod = display_.period;
    *out = {c};
    return ScopedAStatus::ok();
  }
  ScopedAStatus getDisplayConnectionType(int64_t, DisplayConnectionType* out) override {
    *out = DisplayConnectionType::INTERNAL;
    return ScopedAStatus::ok();
  }
  ScopedAStatus getDisplayIdentificationData(int64_t, DisplayIdentification*) override {
    return Unsupported();
  }
  ScopedAStatus getDisplayName(int64_t, std::string* out) override {
    *out = "native";
    return ScopedAStatus::ok();
  }
  ScopedAStatus getDisplayVsyncPeriod(int64_t, int32_t* out) override {
    *out = display_.period;
    return ScopedAStatus::ok();
  }
  ScopedAStatus getDisplayedContentSample(int64_t, int64_t, int64_t,
                                          DisplayContentSample*) override {
    return Unsupported();
  }
  ScopedAStatus getDisplayedContentSamplingAttributes(
      int64_t, DisplayContentSamplingAttributes*) override {
    return Unsupported();
  }
  ScopedAStatus getDisplayPhysicalOrientation(int64_t, Transform* out) override {
    *out = Transform::NONE;
    return ScopedAStatus::ok();
  }
  ScopedAStatus getHdrCapabilities(int64_t, HdrCapabilities* out) override {
    *out = HdrCapabilities{};
    return ScopedAStatus::ok();
  }
  ScopedAStatus getMaxVirtualDisplayCount(int32_t* out) override {
    *out = 0;
    return ScopedAStatus::ok();
  }
  ScopedAStatus getPerFrameMetadataKeys(int64_t, std::vector<PerFrameMetadataKey>*) override {
    return Unsupported();
  }
  ScopedAStatus getReadbackBufferAttributes(int64_t, ReadbackBufferAttributes*) override {
    return Unsupported();
  }
  ScopedAStatus getReadbackBufferFence(int64_t, ScopedFileDescriptor*) override {
    return Unsupported();
  }
  ScopedAStatus getRenderIntents(int64_t, ColorMode, std::vector<RenderIntent>* out) override {
    *out = {RenderIntent::COLORIMETRIC};
    return ScopedAStatus::ok();
  }
  ScopedAStatus getSupportedContentTypes(int64_t, std::vector<ContentType>* out) override {
    out->clear();
    return ScopedAStatus::ok();
  }
  ScopedAStatus getDisplayDecorationSupport(
      int64_t, std::optional<DisplayDecorationSupport>* out) override {
    out->reset();
    return ScopedAStatus::ok();
  }
  ScopedAStatus registerCallback(const std::shared_ptr<IComposerCallback>& cb) override {
    {
      std::lock_guard<std::mutex> g(lock_);
      callback_ = cb;
    }
    // SurfaceFlinger expects the primary display before registerCallback
    // returns (its binder threads take the call meanwhile).
    auto st = cb->onHotplugEvent(kDisplay, DisplayHotplugEvent::CONNECTED);
    if (!st.isOk()) cb->onHotplug(kDisplay, true);
    LOG("display %lld connected", (long long)kDisplay);
    return ScopedAStatus::ok();
  }
  ScopedAStatus setActiveConfig(int64_t display, int32_t config) override {
    if (display != kDisplay) return Err(IComposerClient::EX_BAD_DISPLAY);
    return config == 0 ? ScopedAStatus::ok() : Err(IComposerClient::EX_BAD_CONFIG);
  }
  ScopedAStatus setActiveConfigWithConstraints(int64_t display, int32_t config,
                                               const VsyncPeriodChangeConstraints&,
                                               VsyncPeriodChangeTimeline* out) override {
    if (display != kDisplay) return Err(IComposerClient::EX_BAD_DISPLAY);
    if (config != 0) return Err(IComposerClient::EX_BAD_CONFIG);
    out->newVsyncAppliedTimeNanos = Now();
    out->refreshRequired = false;
    out->refreshTimeNanos = 0;
    return ScopedAStatus::ok();
  }
  ScopedAStatus setBootDisplayConfig(int64_t, int32_t) override { return Unsupported(); }
  ScopedAStatus clearBootDisplayConfig(int64_t) override { return Unsupported(); }
  ScopedAStatus getPreferredBootDisplayConfig(int64_t, int32_t*) override {
    return Unsupported();
  }
  ScopedAStatus setAutoLowLatencyMode(int64_t, bool) override { return Unsupported(); }
  ScopedAStatus setClientTargetSlotCount(int64_t, int32_t) override {
    return ScopedAStatus::ok();
  }
  ScopedAStatus setColorMode(int64_t, ColorMode mode, RenderIntent) override {
    return mode == ColorMode::NATIVE ? ScopedAStatus::ok() : Unsupported();
  }
  ScopedAStatus setContentType(int64_t, ContentType type) override {
    return type == ContentType::NONE ? ScopedAStatus::ok() : Unsupported();
  }
  ScopedAStatus setDisplayedContentSamplingEnabled(int64_t, bool, FormatColorComponent,
                                                   int64_t) override {
    return Unsupported();
  }
  ScopedAStatus setPowerMode(int64_t display, PowerMode) override {
    return display == kDisplay ? ScopedAStatus::ok() : Err(IComposerClient::EX_BAD_DISPLAY);
  }
  ScopedAStatus setReadbackBuffer(int64_t, const NativeHandle&,
                                  const ScopedFileDescriptor&) override {
    return Unsupported();
  }
  ScopedAStatus setVsyncEnabled(int64_t display, bool enabled) override {
    if (display != kDisplay) return Err(IComposerClient::EX_BAD_DISPLAY);
    {
      std::lock_guard<std::mutex> g(lock_);
      vsync_ = enabled;
    }
    cv_.notify_all();
    return ScopedAStatus::ok();
  }
  ScopedAStatus setIdleTimerEnabled(int64_t, int32_t) override { return Unsupported(); }
  ScopedAStatus getOverlaySupport(OverlayProperties* out) override {
    *out = OverlayProperties{};
    return ScopedAStatus::ok();
  }
  ScopedAStatus getHdrConversionCapabilities(std::vector<HdrConversionCapability>* out) override {
    out->clear();
    return ScopedAStatus::ok();
  }
  ScopedAStatus setHdrConversionStrategy(const HdrConversionStrategy&, Hdr*) override {
    return Unsupported();
  }
  ScopedAStatus setRefreshRateChangedCallbackDebugEnabled(int64_t, bool) override {
    return ScopedAStatus::ok();
  }
  ScopedAStatus notifyExpectedPresent(int64_t, const ClockMonotonicTimestamp&,
                                      int32_t) override {
    return ScopedAStatus::ok();
  }
  ScopedAStatus getMaxLayerPictureProfiles(int64_t, int32_t*) override {
    return Unsupported();
  }
  ScopedAStatus startHdcpNegotiation(int64_t,
                                     const aidl::android::hardware::drm::HdcpLevels&) override {
    return Unsupported();
  }
  ScopedAStatus getLuts(int64_t, const std::vector<Buffer>&, std::vector<Luts>*) override {
    return Unsupported();
  }
  ScopedAStatus getDisplayKnownVsyncSample(int64_t, VsyncSample*) override {
    return Unsupported();
  }

 private:
  void Validate(std::vector<CommandResultPayload>* results) {
    ChangedCompositionTypes changed;
    changed.display = kDisplay;
    for (auto& [id, comp] : layers_)
      if (comp != Composition::CLIENT) changed.layers.push_back({id, Composition::CLIENT});
    if (!changed.layers.empty()) results->emplace_back(std::move(changed));
    DisplayRequest req;
    req.display = kDisplay;
    results->emplace_back(std::move(req));
    ClientTargetPropertyWithBrightness ct;
    ct.display = kDisplay;
    ct.clientTargetProperty.pixelFormat = PixelFormat::RGBA_8888;
    ct.clientTargetProperty.dataspace =
        aidl::android::hardware::graphics::common::Dataspace::UNKNOWN;
    ct.brightness = 1.0f;
    results->emplace_back(std::move(ct));
  }

  void VsyncLoop() {
    int64_t next = Now();
    std::unique_lock<std::mutex> g(lock_);
    while (!quit_) {
      if (!vsync_ || !callback_) {
        cv_.wait(g);
        next = Now();
        continue;
      }
      next += display_.period;
      int64_t now = Now();
      if (next < now) next = now + display_.period - (now - next) % display_.period;
      auto cb = callback_;
      int32_t period = display_.period;
      g.unlock();
      struct timespec ts = {(time_t)(next / 1000000000), (long)(next % 1000000000)};
      clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, nullptr);
      cb->onVsync(kDisplay, next, period);
      g.lock();
    }
  }

  std::mutex lock_;
  std::condition_variable cv_;
  bool quit_ = false;
  bool vsync_ = false;
  std::shared_ptr<IComposerCallback> callback_;
  int64_t next_layer_ = 1;
  std::map<int64_t, Composition> layers_;
  std::map<int32_t, std::shared_ptr<Mapped>> slots_;
  std::shared_ptr<Mapped> target_;
  Display display_;
  std::thread vsync_thread_;
};

class Composer : public BnComposer {
 public:
  ScopedAStatus createClient(std::shared_ptr<IComposerClient>* out) override {
    auto c = client_.lock();
    if (c) return Err(IComposer::EX_NO_RESOURCES);
    auto n = ndk::SharedRefBase::make<Client>();
    client_ = n;
    *out = n;
    return ScopedAStatus::ok();
  }
  ScopedAStatus getCapabilities(std::vector<Capability>* out) override {
    out->clear();
    return ScopedAStatus::ok();
  }

 private:
  std::weak_ptr<Client> client_;
};

}  // namespace

int main() {
  ABinderProcess_setThreadPoolMaxThreadCount(4);
  ABinderProcess_startThreadPool();
  auto service = ndk::SharedRefBase::make<Composer>();
  std::string name = std::string(Composer::descriptor) + "/default";
  binder_status_t st = AServiceManager_addService(service->asBinder().get(), name.c_str());
  if (st != STATUS_OK) {
    LOGE("addService %s: %d", name.c_str(), st);
    return 1;
  }
  LOG("serving %s", name.c_str());
  ABinderProcess_joinThreadPool();
  return 1;
}
