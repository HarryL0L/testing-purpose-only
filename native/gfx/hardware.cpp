/*
 * libhardware.so for the Vulkan driver APEX (com.google.cf.vulkan). The
 * image's copy is the APEX build of libhardware: it looks for modules in
 * /apex/<name>/lib64/hw, with <name> from libapexsupport, which reads it off
 * the caller's path. Here that path is the host's (.../root/apex/...), the
 * name lookup fails and PanVK finds no gralloc module (no YUV buffers, see
 * gralloc.cpp). This one looks in the hw/ directory next to itself, with
 * libhardware's variant order: ro.hardware.<class>, ro.hardware,
 * ro.product.board, ro.board.platform, ro.arch, default.
 */
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <sys/system_properties.h>
#include <unistd.h>

struct hw_module_t {
  unsigned tag;
  unsigned short module_api_version, hal_api_version;
  const char *id, *name, *author;
  void* methods;
  void* dso;
};

static const char* const kVariantKeys[] = {"ro.hardware", "ro.product.board",
                                           "ro.board.platform", "ro.arch"};

// This library's directory.
static bool own_dir(char* dir, size_t size) {
  Dl_info info;
  if (!dladdr(reinterpret_cast<void*>(own_dir), &info) || !info.dli_fname) return false;
  const char* slash = strrchr(info.dli_fname, '/');
  if (!slash || (size_t)(slash - info.dli_fname) >= size) return false;
  memcpy(dir, info.dli_fname, slash - info.dli_fname);
  dir[slash - info.dli_fname] = '\0';
  return true;
}

static int load(const char* id, const char* path, const hw_module_t** module) {
  void* dso = dlopen(path, RTLD_NOW);
  if (!dso) {
    fprintf(stderr, "libhardware: %s: %s\n", path, dlerror());
    return -EINVAL;
  }
  hw_module_t* hmi = static_cast<hw_module_t*>(dlsym(dso, "HMI"));
  if (!hmi || strcmp(id, hmi->id) != 0) {
    dlclose(dso);
    return -EINVAL;
  }
  hmi->dso = dso;
  *module = hmi;
  return 0;
}

extern "C" __attribute__((visibility("default"))) int hw_get_module_by_class(
    const char* class_id, const char* inst, const hw_module_t** module) {
  char name[PATH_MAX], dir[PATH_MAX], path[PATH_MAX], prop[PROP_VALUE_MAX];
  if (inst)
    snprintf(name, sizeof(name), "%s.%s", class_id, inst);
  else
    snprintf(name, sizeof(name), "%s", class_id);
  if (!own_dir(dir, sizeof(dir))) return -ENOENT;
  char key[PROP_NAME_MAX];
  snprintf(key, sizeof(key), "ro.hardware.%s", name);
  for (int i = -1; i < (int)(sizeof(kVariantKeys) / sizeof(kVariantKeys[0])); i++) {
    if (__system_property_get(i < 0 ? key : kVariantKeys[i], prop) <= 0) continue;
    snprintf(path, sizeof(path), "%s/hw/%s.%s.so", dir, name, prop);
    if (access(path, R_OK) == 0) return load(class_id, path, module);
  }
  snprintf(path, sizeof(path), "%s/hw/%s.default.so", dir, name);
  if (access(path, R_OK) == 0) return load(class_id, path, module);
  return -ENOENT;
}

extern "C" __attribute__((visibility("default"))) int hw_get_module(const char* id,
                                                                     const hw_module_t** module) {
  return hw_get_module_by_class(id, nullptr, module);
}
