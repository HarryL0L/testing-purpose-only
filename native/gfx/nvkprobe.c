// nvkprobe: load a Vulkan driver (ICD) directly and list its GPUs.
//
//   nvkprobe /path/to/vulkan.<name>.so
//
// Run inside native mode's root (./run.sh /data/local/tmp/nvkprobe ...): it
// checks that the driver loads with Android 17's libraries and reaches the
// GPU from there, before the driver becomes the system's (ro.hardware.vulkan).
// Plain C with its own minimal Vulkan declarations; builds with any Android
// clang (Termux's: clang -O2 nvkprobe.c -o nvkprobe -ldl).
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

typedef void* VkInstance;
typedef void* VkPhysicalDevice;
typedef void (*PFN_vkVoidFunction)(void);
typedef PFN_vkVoidFunction (*PFN_GetInstanceProcAddr)(VkInstance, const char*);
typedef int32_t (*PFN_Negotiate)(uint32_t*);

typedef struct {
  int32_t sType;  // VK_STRUCTURE_TYPE_APPLICATION_INFO = 0
  const void* pNext;
  const char* pApplicationName;
  uint32_t applicationVersion;
  const char* pEngineName;
  uint32_t engineVersion;
  uint32_t apiVersion;
} VkApplicationInfo;

typedef struct {
  int32_t sType;  // VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO = 1
  const void* pNext;
  uint32_t flags;
  const VkApplicationInfo* pApplicationInfo;
  uint32_t enabledLayerCount;
  const char* const* ppEnabledLayerNames;
  uint32_t enabledExtensionCount;
  const char* const* ppEnabledExtensionNames;
} VkInstanceCreateInfo;

// The start of VkPhysicalDeviceProperties (the rest isn't read).
typedef struct {
  uint32_t apiVersion;
  uint32_t driverVersion;
  uint32_t vendorID;
  uint32_t deviceID;
  int32_t deviceType;
  char deviceName[256];
} DeviceProps;

typedef int32_t (*PFN_CreateInstance)(const VkInstanceCreateInfo*, const void*, VkInstance*);
typedef int32_t (*PFN_EnumerateDevices)(VkInstance, uint32_t*, VkPhysicalDevice*);
typedef void (*PFN_GetProperties)(VkPhysicalDevice, void*);
typedef void (*PFN_DestroyInstance)(VkInstance, const void*);

int main(int argc, char** argv) {
  if (argc != 2) {
    fprintf(stderr, "usage: %s /path/to/vulkan.<name>.so\n", argv[0]);
    return 2;
  }
  void* lib = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
  if (lib == NULL) {
    printf("FAIL dlopen: %s\n", dlerror());
    return 1;
  }
  printf("ok   loaded %s\n", argv[1]);
  PFN_Negotiate negotiate = (PFN_Negotiate)dlsym(lib, "vk_icdNegotiateLoaderICDInterfaceVersion");
  PFN_GetInstanceProcAddr gipa = (PFN_GetInstanceProcAddr)dlsym(lib, "vk_icdGetInstanceProcAddr");
  if (gipa == NULL) {
    printf("FAIL no vk_icdGetInstanceProcAddr\n");
    return 1;
  }
  if (negotiate != NULL) {
    uint32_t version = 5;
    int32_t r = negotiate(&version);
    printf("%s ICD interface version %u (%d)\n", r == 0 ? "ok  " : "FAIL", version, r);
  }
  PFN_CreateInstance create = (PFN_CreateInstance)gipa(NULL, "vkCreateInstance");
  if (create == NULL) {
    printf("FAIL no vkCreateInstance\n");
    return 1;
  }
  VkApplicationInfo app = {0, NULL, "nvkprobe", 1, "nvkprobe", 1, (1u << 22) | (1u << 12)};
  VkInstanceCreateInfo ci = {1, NULL, 0, &app, 0, NULL, 0, NULL};
  VkInstance instance = NULL;
  int32_t r = create(&ci, NULL, &instance);
  if (r != 0) {
    printf("FAIL vkCreateInstance: %d\n", r);
    return 1;
  }
  printf("ok   instance created\n");
  PFN_EnumerateDevices enumerate =
      (PFN_EnumerateDevices)gipa(instance, "vkEnumeratePhysicalDevices");
  PFN_GetProperties props = (PFN_GetProperties)gipa(instance, "vkGetPhysicalDeviceProperties");
  uint32_t count = 0;
  r = enumerate(instance, &count, NULL);
  if (r != 0 || count == 0) {
    printf("FAIL no GPU found (%d, %u devices)\n", r, count);
    return 1;
  }
  VkPhysicalDevice devices[8];
  if (count > 8) count = 8;
  enumerate(instance, &count, devices);
  for (uint32_t i = 0; i < count; i++) {
    static unsigned char buf[8192];  // the whole VkPhysicalDeviceProperties fits
    memset(buf, 0, sizeof(buf));
    props(devices[i], buf);
    DeviceProps* p = (DeviceProps*)buf;
    printf("ok   GPU %u: %s, Vulkan %u.%u.%u, vendor 0x%x device 0x%x\n", i, p->deviceName,
           p->apiVersion >> 22, (p->apiVersion >> 12) & 0x3ff, p->apiVersion & 0xfff,
           p->vendorID, p->deviceID);
  }
  PFN_DestroyInstance destroy = (PFN_DestroyInstance)gipa(instance, "vkDestroyInstance");
  if (destroy) destroy(instance, NULL);
  return 0;
}
