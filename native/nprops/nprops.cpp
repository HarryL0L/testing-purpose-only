/*
 * nprops: Android's property service for native mode.
 *
 * On a device, init owns properties: it builds /dev/__properties__ from the
 * image's property_contexts, loads the build.prop files and answers setprop
 * requests on /dev/socket/property_service. In native mode there is no init
 * yet, so nprops does those three things. Run it with ANDROID_NATIVE_ROOT
 * set, like every program from the image; its paths then land in the root.
 *
 *   nprops [-f] [NAME=VALUE...]
 *
 * NAME=VALUE arguments are set after the build.prop files (later wins).
 * Without -f, nprops goes to the background once the properties are ready.
 */

#define _REALLY_INCLUDE_SYS__SYSTEM_PROPERTIES_H_
#include <sys/_system_properties.h>

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <signal.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#include <algorithm>
#include <map>
#include <string>
#include <vector>

#include <android-base/file.h>
#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <property_info_serializer/property_info_serializer.h>

using android::base::ReadFileToString;
using android::base::StartsWith;
using android::base::Trim;
using android::base::unique_fd;
using android::base::WriteStringToFile;
using android::properties::BuildTrie;
using android::properties::ParsePropertyInfoFile;
using android::properties::PropertyInfoEntry;

static void msg(const char* fmt, const std::string& a = "", const std::string& b = "") {
  fprintf(stderr, "nprops: ");
  fprintf(stderr, fmt, a.c_str(), b.c_str());
  fprintf(stderr, "\n");
}

// /dev/__properties__/property_info, as init's CreateSerializedPropertyInfo().
static bool WritePropertyInfo() {
  static const char* const kContexts[] = {
      "/system/etc/selinux/plat_property_contexts",
      "/system_ext/etc/selinux/system_ext_property_contexts",
      "/vendor/etc/selinux/vendor_property_contexts",
      "/product/etc/selinux/product_property_contexts",
      "/odm/etc/selinux/odm_property_contexts",
  };
  std::vector<PropertyInfoEntry> infos;
  bool have_plat = false;
  for (const char* path : kContexts) {
    std::string contents;
    if (!ReadFileToString(path, &contents)) continue;
    std::vector<std::string> errors;
    ParsePropertyInfoFile(contents, true, &infos, &errors);
    for (const auto& e : errors) msg("%s: %s", path, e);
    if (strstr(path, "plat_")) have_plat = true;
  }
  if (!have_plat) msg("no plat_property_contexts; every property gets the default context");
  std::string trie, error;
  if (!BuildTrie(infos, "u:object_r:default_prop:s0", "string", &trie, &error)) {
    msg("cannot serialize property contexts: %s", error);
    return false;
  }
  if (!WriteStringToFile(trie, PROP_DIRNAME "/property_info", 0444, getuid(), getgid(), false)) {
    msg("cannot write %s", PROP_DIRNAME "/property_info");
    return false;
  }
  return true;
}

// key=value lines, as init's LoadProperties() (imports and filters left out).
static void LoadPropFile(const char* path, std::map<std::string, std::string>* props) {
  std::string contents;
  if (!ReadFileToString(path, &contents)) return;
  for (const auto& raw : android::base::Split(contents, "\n")) {
    std::string line = Trim(raw);
    if (line.empty() || line[0] == '#' || StartsWith(line, "import ")) continue;
    size_t eq = line.find('=');
    if (eq == std::string::npos) continue;
    std::string key = Trim(line.substr(0, eq));
    std::string value = Trim(line.substr(eq + 1));
    if (key.empty()) continue;
    // ro.* keeps its first value, except that a later partition may not
    // override one from an earlier file: same rule as init.
    if (StartsWith(key, "ro.") && props->count(key)) continue;
    (*props)[key] = value;
  }
}

// What init derives from the partitions' build.prop files
// (property_initialize_ro_product_props, property_derive_build_fingerprint,
// ro.product.cpu.abilist*), for the properties that aren't set yet.
static void DeriveProperties(std::map<std::string, std::string>* props) {
  auto get = [&](const std::string& k) {
    auto it = props->find(k);
    return it == props->end() ? std::string() : it->second;
  };
  static const char* const kPartitions[] = {"product", "odm", "vendor", "system_ext", "system"};
  for (const char* item : {"brand", "device", "manufacturer", "model", "name"}) {
    std::string key = std::string("ro.product.") + item;
    if (!get(key).empty()) continue;
    for (const char* part : kPartitions) {
      std::string v = get(std::string("ro.product.") + part + "." + item);
      if (!v.empty()) {
        (*props)[key] = v;
        break;
      }
    }
  }
  if (get("ro.build.fingerprint").empty()) {
    (*props)["ro.build.fingerprint"] =
        get("ro.product.brand") + "/" + get("ro.product.name") + "/" + get("ro.product.device") +
        ":" + get("ro.build.version.release_or_codename") + "/" + get("ro.build.id") + "/" +
        get("ro.build.version.incremental") + ":" + get("ro.build.type") + "/" +
        get("ro.build.tags");
  }
  for (const char* suffix : {"", "32", "64"}) {
    std::string key = std::string("ro.product.cpu.abilist") + suffix;
    if (!get(key).empty()) continue;
    bool found = false;
    for (const char* part : {"vendor", "odm", "product", "system_ext", "system"}) {
      std::string src = std::string("ro.") + part + ".product.cpu.abilist" + suffix;
      if (props->count(src)) {
        (*props)[key] = get(src);
        found = true;
        break;
      }
    }
    if (!found) (*props)[key] = strcmp(suffix, "32") == 0 ? "" : "arm64-v8a";
  }
  // ro.vendor.api_level (init's property_initialize_ro_vendor_api_level): the
  // board's API level, capped by the product's first one (SDK 35 and later
  // count as YYYYMM vendor API levels: 35 -> 202404, 36 -> 202504, ...).
  if (get("ro.vendor.api_level").empty()) {
    auto num = [&](const char* k, long def) {
      std::string v = get(k);
      return v.empty() ? def : strtol(v.c_str(), nullptr, 10);
    };
    constexpr long kMax = 1000000;
    long vendor = num("ro.board.first_api_level", kMax);
    if (vendor != kMax) vendor = num("ro.board.api_level", vendor);
    long first = num("ro.product.first_api_level", num("ro.build.version.sdk", 10000));
    long first_vendor = first >= 35 && first < 10000 ? 202404 + (first - 35) * 100 : first;
    long level = std::min(first_vendor, vendor);
    if (level < kMax) (*props)["ro.vendor.api_level"] = std::to_string(level);
  }
}

// What a device's vendor and product partitions set and native mode lacks.
// Only used when nothing else sets them.
static const std::pair<const char*, const char*> kDeviceDefaults[] = {
    {"ro.zygote", "zygote64"},
    {"dalvik.vm.isa.arm64.variant", "generic"},
    {"dalvik.vm.isa.arm64.features", "default"},
    {"dalvik.vm.heapstartsize", "8m"},
    {"dalvik.vm.heapgrowthlimit", "192m"},
    {"dalvik.vm.heapsize", "512m"},
    {"dalvik.vm.heaptargetutilization", "0.75"},
    {"dalvik.vm.heapminfree", "512k"},
    {"dalvik.vm.heapmaxfree", "8m"},
    {"ro.sf.lcd_density", "420"},
    // Shared memory through memfd, not /dev/ashmem (an app can't count on
    // ashmem; the policy check libcutils does instead needs selinuxfs).
    {"sys.use_memfd", "true"},
    // No GPU driver yet: zygote must not preload one.
    {"ro.zygote.disable_gl_preload", "true"},
    // The boot image odrefresh compiles in /data (the image in /system is for
    // a collector that needs kernel features this phone lacks); there is no
    // odsign here to verify it, so trust it as odsign would.
    {"odsign.verification.success", "true"},
    // Graphics (native/gfx): CPU rendering, GLES through ANGLE on SwiftShader
    // Vulkan from the vendor APEX, until a GPU path exists.
    {"ro.hardware.egl", "angle"},
    {"ro.hardware.vulkan", "pastel"},
    {"ro.vulkan.apex", "com.google.cf.vulkan"},
    {"ro.opengles.version", "196610"},
    // As apexd says once every APEX is active (they are unpacked already):
    // libvintf reads the vendor APEXes' HAL manifests only then.
    {"apex.all.ready", "true"},
};

// ---------------------------------------------------------------- services
//
// init starts services on request: ctl.start/stop/restart NAME, and
// ctl.interface_start aidl/NAME for HALs started on demand (servicemanager
// asks when a client waits for a declared service that isn't running).
// nprops answers those from the image's own .rc service definitions.

struct Service {
  std::vector<std::string> argv;
  std::vector<std::string> sockets;  // nsocket specs: name:type:perm
  pid_t pid = 0;
};
static std::map<std::string, Service> g_services;
static std::map<std::string, std::string> g_interfaces;  // interface -> service

static void ParseRcFile(const std::string& path) {
  std::string contents;
  if (!ReadFileToString(path, &contents)) return;
  Service* cur = nullptr;
  std::string cur_name;
  for (const auto& raw : android::base::Split(contents, "\n")) {
    std::string line = Trim(raw);
    if (line.empty() || line[0] == '#') continue;
    auto words = android::base::Split(line, " ");
    words.erase(std::remove(words.begin(), words.end(), ""), words.end());
    bool indented = !raw.empty() && (raw[0] == ' ' || raw[0] == '\t');
    if (!indented) {
      cur = nullptr;
      if (words[0] == "service" && words.size() >= 3) {
        cur_name = words[1];
        auto it = g_services.find(cur_name);
        // APEX .rc files mark redefinitions "override"; otherwise the first wins
        bool override_it = contents.find("override") != std::string::npos;
        if (it != g_services.end() && !override_it) continue;
        Service svc;
        svc.argv.assign(words.begin() + 2, words.end());
        g_services[cur_name] = svc;
        cur = &g_services[cur_name];
      }
      continue;
    }
    if (cur == nullptr) continue;
    if (words[0] == "interface" && words.size() >= 3) {
      g_interfaces[words[2]] = cur_name;  // aidl NAME/instance, or a HIDL name
    } else if (words[0] == "socket" && words.size() >= 4) {
      cur->sockets.push_back(words[1] + ":" + words[2] + ":" + words[3]);
    }
  }
}

static void LoadServices() {
  for (const char* dir : {"/system/etc/init/hw", "/system/etc/init", "/system_ext/etc/init",
                          "/product/etc/init", "/vendor/etc/init", "/odm/etc/init"}) {
    DIR* d = opendir(dir);
    if (d == nullptr) continue;
    while (struct dirent* e = readdir(d)) {
      std::string n = e->d_name;
      if (n.size() > 3 && n.compare(n.size() - 3, 3, ".rc") == 0) ParseRcFile(std::string(dir) + "/" + n);
    }
    closedir(d);
  }
  if (DIR* d = opendir("/apex")) {  // active APEXes' .rc files (etc/*.rc)
    while (struct dirent* e = readdir(d)) {
      if (e->d_name[0] == '.' || strchr(e->d_name, '@')) continue;
      std::string etc = std::string("/apex/") + e->d_name + "/etc";
      if (DIR* ed = opendir(etc.c_str())) {
        while (struct dirent* f = readdir(ed)) {
          std::string n = f->d_name;
          if (n.size() > 3 && n.compare(n.size() - 3, 3, ".rc") == 0) ParseRcFile(etc + "/" + n);
        }
        closedir(ed);
      }
    }
    closedir(d);
  }
}

static uint32_t SetProperty(const std::string& name, const std::string& value);

static void ReapServices() {
  int status;
  pid_t pid;
  while ((pid = waitpid(-1, &status, WNOHANG)) > 0) {
    for (auto& [name, svc] : g_services) {
      if (svc.pid == pid) {
        svc.pid = 0;
        SetProperty("init.svc." + name, "stopped");
      }
    }
  }
}

static void StartService(const std::string& name) {
  auto it = g_services.find(name);
  if (it == g_services.end()) {
    msg("ctl.start: no service %s", name.c_str());
    return;
  }
  Service& svc = it->second;
  if (svc.pid > 0 && kill(svc.pid, 0) == 0) return;  // running
  std::vector<std::string> args;
  if (!svc.sockets.empty()) {  // init-style sockets, made by nsocket
    args.push_back("/system/bin/nsocket");
    args.insert(args.end(), svc.sockets.begin(), svc.sockets.end());
    args.push_back("--");
  }
  args.insert(args.end(), svc.argv.begin(), svc.argv.end());
  pid_t pid = fork();
  if (pid == 0) {
    setsid();
    int null_fd = open("/dev/null", O_RDWR);
    if (null_fd >= 0) {
      dup2(null_fd, 0);
      dup2(null_fd, 1);
      dup2(null_fd, 2);
      if (null_fd > 2) close(null_fd);
    }
    std::vector<char*> cargv;
    for (auto& a : args) cargv.push_back(a.data());
    cargv.push_back(nullptr);
    execv(cargv[0], cargv.data());
    _exit(127);
  }
  if (pid > 0) {
    svc.pid = pid;
    SetProperty("init.svc." + name, "running");
  }
}

static void StopService(const std::string& name) {
  auto it = g_services.find(name);
  if (it != g_services.end() && it->second.pid > 0) kill(it->second.pid, SIGTERM);
}

// A ctl.* request (not stored, as with init).
static void Control(const std::string& name, const std::string& value) {
  std::string action = name.substr(4);
  std::string target = value;
  if (StartsWith(action, "interface_")) {
    action = action.substr(10);
    std::string iface = StartsWith(value, "aidl/") ? value.substr(5) : value;
    auto it = g_interfaces.find(iface);
    if (it == g_interfaces.end()) {
      msg("%s: no service declares %s", name.c_str(), value.c_str());
      return;
    }
    target = it->second;
  }
  if (action == "start") {
    StartService(target);
  } else if (action == "stop") {
    StopService(target);
  } else if (action == "restart") {
    StopService(target);
    StartService(target);
  }
}

// Settings of the image native mode overrides (they name hardware an app
// can't reach).
static const std::pair<const char*, const char*> kNativeOverrides[] = {
    // Factory reset protection's partition: no block devices here; with no
    // partition named, system_server doesn't start PersistentDataBlockService.
    {"ro.frp.pst", ""},
    // No HIDL HALs: as hwservicemanager says when it finds none (libhidl
    // otherwise waits for it in every process, since system_ext ships it).
    {"hwservicemanager.disabled", "true"},
    // system_ext enables an audio HAL vendor extension the image's HALs
    // don't provide; audioserver would wait for it forever.
    {"ro.audio.ihaladaptervendorextension_enabled", "false"},
};

// sys.powerctl ("shutdown,...", "reboot,..."): init would power off or
// restart the device; here the host app does it for Android 17 (it watches
// this file).
static void PowerCtl(const std::string& value) {
  int fd = open("/data/local/tmp/npowerctl", O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
  if (fd < 0) return;
  (void)!write(fd, value.data(), value.size());
  close(fd);
}

static uint32_t SetProperty(const std::string& name, const std::string& value) {
  if (name.empty() || name.find_first_of(" \t\n=") != std::string::npos) {
    return PROP_ERROR_INVALID_NAME;
  }
  if (name == "sys.powerctl") PowerCtl(value);
  if (StartsWith(name, "ctl.")) {
    Control(name, value);
    return PROP_SUCCESS;
  }
  prop_info* pi = const_cast<prop_info*>(__system_property_find(name.c_str()));
  if (pi != nullptr) {
    if (StartsWith(name, "ro.")) return PROP_ERROR_READ_ONLY_PROPERTY;
    if (value.size() >= PROP_VALUE_MAX) return PROP_ERROR_INVALID_VALUE;
    __system_property_update(pi, value.c_str(), value.size());
    return PROP_SUCCESS;
  }
  if (!StartsWith(name, "ro.") && value.size() >= PROP_VALUE_MAX) return PROP_ERROR_INVALID_VALUE;
  if (__system_property_add(name.c_str(), name.size(), value.c_str(), value.size()) < 0) {
    return PROP_ERROR_SET_FAILED;
  }
  return PROP_SUCCESS;
}

static bool RecvAll(int fd, void* buf, size_t len) {
  char* p = static_cast<char*>(buf);
  while (len > 0) {
    ssize_t n = TEMP_FAILURE_RETRY(recv(fd, p, len, 0));
    if (n <= 0) return false;
    p += n;
    len -= static_cast<size_t>(n);
  }
  return true;
}

static bool RecvString(int fd, std::string* out) {
  uint32_t len;
  if (!RecvAll(fd, &len, sizeof(len)) || len > 0xffff) return false;
  out->resize(len);
  return len == 0 || RecvAll(fd, out->data(), len);
}

// One request per connection, as init's property service.
static void HandleClient(int listen_fd) {
  unique_fd fd(TEMP_FAILURE_RETRY(accept4(listen_fd, nullptr, nullptr, SOCK_CLOEXEC)));
  if (fd == -1) return;
  struct timeval tv = {2, 0};
  setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
  uint32_t cmd;
  if (!RecvAll(fd, &cmd, sizeof(cmd))) return;
  if (cmd == PROP_MSG_SETPROP2) {
    std::string name, value;
    uint32_t result = (RecvString(fd, &name) && RecvString(fd, &value))
                          ? SetProperty(name, value)
                          : PROP_ERROR_READ_DATA;
    TEMP_FAILURE_RETRY(send(fd, &result, sizeof(result), 0));
  } else if (cmd == PROP_MSG_SETPROP) {
    char name[PROP_NAME_MAX];
    char value[PROP_VALUE_MAX];
    if (!RecvAll(fd, name, sizeof(name)) || !RecvAll(fd, value, sizeof(value))) return;
    name[sizeof(name) - 1] = '\0';
    value[sizeof(value) - 1] = '\0';
    SetProperty(name, value);  // the old protocol has no reply
  } else {
    uint32_t result = PROP_ERROR_INVALID_CMD;
    TEMP_FAILURE_RETRY(send(fd, &result, sizeof(result), 0));
  }
}

static int Listen(const char* path) {
  unlink(path);
  int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (fd == -1) return -1;
  struct sockaddr_un addr = {};
  addr.sun_family = AF_UNIX;
  strlcpy(addr.sun_path, path, sizeof(addr.sun_path));
  if (bind(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) != 0 ||
      listen(fd, 64) != 0) {
    msg("cannot listen on %s: %s", path, strerror(errno));
    close(fd);
    return -1;
  }
  chmod(path, 0666);
  return fd;
}

// Start from an empty /dev/__properties__: the area files are created with O_EXCL.
static void ClearPropertyDir() {
  mkdir("/dev", 0755);
  mkdir(PROP_DIRNAME, 0711);
  DIR* d = opendir(PROP_DIRNAME);
  if (d == nullptr) return;
  while (struct dirent* e = readdir(d)) {
    if (e->d_name[0] == '.') continue;
    unlinkat(dirfd(d), e->d_name, 0);
  }
  closedir(d);
}

int main(int argc, char** argv) {
  bool foreground = false;
  std::map<std::string, std::string> overrides;
  for (int i = 1; i < argc; i++) {
    if (strcmp(argv[i], "-f") == 0) {
      foreground = true;
    } else if (const char* eq = strchr(argv[i], '=')) {
      overrides[std::string(argv[i], static_cast<size_t>(eq - argv[i]))] = eq + 1;
    } else {
      fprintf(stderr, "usage: nprops [-f] [NAME=VALUE...]\n");
      return 2;
    }
  }
  if (getenv("ANDROID_NATIVE_ROOT") == nullptr) {
    msg("ANDROID_NATIVE_ROOT is not set: refusing to touch the host's /dev");
    return 1;
  }

  ClearPropertyDir();
  if (!WritePropertyInfo()) return 1;
  // The area files can't get SELinux labels from an app: bionic logs one line
  // per file, still sets the areas up and returns -1. Keep stderr quiet
  // meanwhile and check that the areas work instead.
  int saved_stderr = dup(STDERR_FILENO);
  int null_fd = open("/dev/null", O_WRONLY | O_CLOEXEC);
  if (saved_stderr != -1 && null_fd != -1) dup2(null_fd, STDERR_FILENO);
  int init_result = __system_property_area_init();
  if (saved_stderr != -1) {
    dup2(saved_stderr, STDERR_FILENO);
    close(saved_stderr);
  }
  if (null_fd != -1) close(null_fd);
  if (SetProperty("ro.native.nprops", "1") != PROP_SUCCESS) {
    msg("cannot create the property areas in %s", PROP_DIRNAME);
    return 1;
  }
  if (init_result != 0) msg("property areas have no SELinux labels (expected in an app)");

  std::map<std::string, std::string> props;
  for (const char* f : {"/system/build.prop", "/system_ext/etc/build.prop", "/vendor/build.prop",
                        "/vendor/default.prop", "/odm/etc/build.prop", "/product/etc/build.prop",
                        "/data/local/native.prop"}) {  // the last: the user's own settings
    LoadPropFile(f, &props);
  }
  DeriveProperties(&props);
  for (const auto& [k, v] : kDeviceDefaults) props.emplace(k, v);
  // What native mode must change in the image's own settings.
  for (const auto& [k, v] : kNativeOverrides) props[k] = v;
  for (const auto& [k, v] : overrides) props[k] = v;
  props["ro.property_service.version"] = "2";  // as init: clients use PROP_MSG_SETPROP2
  size_t loaded = 0;
  for (const auto& [k, v] : props) {
    if (__system_property_add(k.c_str(), k.size(), v.c_str(), v.size()) == 0) loaded++;
  }
  fprintf(stderr, "nprops: %zu properties loaded\n", loaded);

  mkdir("/dev/socket", 0755);
  int fds[2] = {Listen("/dev/socket/" PROP_SERVICE_NAME),
                Listen("/dev/socket/" PROP_SERVICE_FOR_SYSTEM_NAME)};
  if (fds[0] == -1) return 1;
  LoadServices();
  // Everything is loaded and the service listens: tell whoever waits.
  SetProperty("native.nprops.ready", "1");

  if (!foreground && daemon(1, 1) != 0) {
    msg("daemon: %s", strerror(errno));
    return 1;
  }
  struct pollfd pfds[2] = {{fds[0], POLLIN, 0}, {fds[1], POLLIN, 0}};
  int nfds = fds[1] == -1 ? 1 : 2;
  while (true) {
    ReapServices();
    if (poll(pfds, nfds, 1000) < 0) {
      if (errno == EINTR) continue;
      msg("poll: %s", strerror(errno));
      return 1;
    }
    for (int i = 0; i < nfds; i++) {
      if (pfds[i].revents & POLLIN) HandleClient(pfds[i].fd);
    }
  }
}
