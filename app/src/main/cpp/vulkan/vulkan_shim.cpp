// libvulkan_shim.so — stands in for libvulkan.so for llama.cpp's Vulkan backend (experimental build).
//
// libggml-vulkan is linked against this library instead of the system libvulkan.so. Every Vulkan
// entry point it uses is an assembly trampoline (generated from vulkan_exports.txt) that jumps
// through g_llamachat_vk_table. llamachat_vk_init() fills that table from either:
//   - a custom driver (e.g. Mesa Turnip for Adreno) loaded with libadrenotools, or
//   - the system libvulkan.so.
// This is needed because libadrenotools returns a *separate* libvulkan instance (dlopen handle),
// which a library linked normally against libvulkan.so would never use.

#include <android/log.h>
#include <dlfcn.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#include <adrenotools/driver.h>

#define TAG "llamachat-vk"

static const char * const k_vk_names[] = {
#define X(name) #name,
#include "vulkan_exports.inc"
#undef X
};
static constexpr size_t k_vk_count = sizeof(k_vk_names) / sizeof(k_vk_names[0]);

// definition (the braced form: `extern "C" T x[N];` alone would only declare it); the trampolines
// in vulkan_trampolines.S jump through it
extern "C" {
__attribute__((visibility("hidden"))) void * g_llamachat_vk_table[k_vk_count] = {};
}

static void * g_vk_handle = nullptr;

// called when the driver doesn't provide a function that is used anyway
extern "C" __attribute__((visibility("hidden"))) void llamachat_vk_missing() {
    __android_log_print(ANDROID_LOG_FATAL, TAG, "called a Vulkan function the loaded driver does not export");
    abort();
}

static void fill_table(void * handle) {
    size_t missing = 0;
    for (size_t i = 0; i < k_vk_count; i++) {
        void * p = dlsym(handle, k_vk_names[i]);
        if (!p) {
            p = (void *) &llamachat_vk_missing;
            missing++;
        }
        g_llamachat_vk_table[i] = p;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "resolved %zu/%zu Vulkan entry points", k_vk_count - missing, k_vk_count);
}

// Returns: 1 = custom driver loaded, 0 = system driver, -1 = custom driver failed (system driver
// used instead), -2 = no Vulkan at all. A human-readable status is written to msg.
// Must be called before libggml-vulkan makes its first Vulkan call (i.e. before loading backends).
extern "C" __attribute__((visibility("default")))
int llamachat_vk_init(const char * hook_lib_dir, const char * driver_dir, const char * driver_name,
                      char * msg, size_t msg_len) {
    if (g_vk_handle) {
        snprintf(msg, msg_len, "already initialized");
        return 0;
    }
    int result = 0;
    std::string why;
    if (driver_name && *driver_name && driver_dir && *driver_dir) {
        std::string dir = driver_dir;
        if (dir.back() != '/') dir += '/';
        g_vk_handle = adrenotools_open_libvulkan(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM, nullptr,
                                                 hook_lib_dir, dir.c_str(), driver_name, nullptr, nullptr);
        if (g_vk_handle) {
            fill_table(g_vk_handle);
            snprintf(msg, msg_len, "Custom driver loaded: %s", driver_name);
            return 1;
        }
        const char * e = dlerror();
        why = e ? e : "adrenotools_open_libvulkan failed";
        result = -1;
    }
    g_vk_handle = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!g_vk_handle) {
        for (size_t i = 0; i < k_vk_count; i++) g_llamachat_vk_table[i] = (void *) &llamachat_vk_missing;
        snprintf(msg, msg_len, "No Vulkan driver available");
        return -2;
    }
    fill_table(g_vk_handle);
    if (result == -1) {
        snprintf(msg, msg_len, "Custom driver failed (%s), using the system driver", why.c_str());
    } else {
        snprintf(msg, msg_len, "System Vulkan driver");
    }
    return result;
}
