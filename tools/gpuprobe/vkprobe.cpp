// vkprobe: a Vulkan driver report + compute micro-benchmarks, for checking how a phone GPU
// (e.g. Mali on Vulkan 1.1) will cope with ggml's Vulkan backend. Plain text output; run it in
// Termux and share the output. See README.md.
//
//   vkprobe            everything (the long-dispatch test last)
//   vkprobe --no-long  skip the long-dispatch test (it can freeze the screen for ~10 s)
//   vkprobe --flops    only the fp32 / fp16 throughput tests (a few seconds)

#include <vulkan/vulkan.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "shaders.h" // generated: spv_<name>[] / spv_<name>_size for shaders/*.comp

static const char * vkres(VkResult r) {
    switch (r) {
        case VK_SUCCESS: return "VK_SUCCESS";
        case VK_NOT_READY: return "VK_NOT_READY";
        case VK_TIMEOUT: return "VK_TIMEOUT";
        case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
        case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
        case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
        case VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
        case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
        case VK_ERROR_FEATURE_NOT_PRESENT: return "VK_ERROR_FEATURE_NOT_PRESENT";
        case VK_ERROR_TOO_MANY_OBJECTS: return "VK_ERROR_TOO_MANY_OBJECTS";
        case VK_ERROR_NOT_PERMITTED_KHR: return "VK_ERROR_NOT_PERMITTED";
        default: {
            static char buf[32];
            snprintf(buf, sizeof buf, "VkResult %d", (int) r);
            return buf;
        }
    }
}

static std::string ver(uint32_t v) {
    return std::to_string(VK_API_VERSION_MAJOR(v)) + "." + std::to_string(VK_API_VERSION_MINOR(v)) + "." +
           std::to_string(VK_API_VERSION_PATCH(v));
}

static double now() {
    return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

static void section(const char * title) { printf("\n==== %s ====\n", title); }

static bool has_ext(const std::vector<VkExtensionProperties> & exts, const char * name) {
    for (const auto & e : exts) if (strcmp(e.extensionName, name) == 0) return true;
    return false;
}

static uint16_t f32_to_f16(float f) {
    uint32_t x; memcpy(&x, &f, 4);
    const uint32_t sign = (x >> 16) & 0x8000;
    int32_t exp = (int32_t) ((x >> 23) & 0xff) - 127 + 15;
    uint32_t mant = x & 0x7fffff;
    if (exp <= 0) return (uint16_t) sign; // tiny values -> 0 (not needed here)
    if (exp >= 31) return (uint16_t) (sign | 0x7c00);
    return (uint16_t) (sign | (exp << 10) | (mant >> 13));
}

// ------------------------------------------------------------------------------------------------
// driver report

static void report_device(VkPhysicalDevice pd, const std::vector<VkExtensionProperties> & exts) {
    VkPhysicalDeviceProperties p;
    vkGetPhysicalDeviceProperties(pd, &p);
    section("Device");
    static const char * types[] = {"other", "integrated GPU", "discrete GPU", "virtual GPU", "CPU"};
    printf("name            %s\n", p.deviceName);
    printf("type            %s\n", p.deviceType <= 4 ? types[p.deviceType] : "?");
    printf("API version     %s\n", ver(p.apiVersion).c_str());
    printf("driver version  0x%08x\n", p.driverVersion);
    printf("vendor/device   0x%04x / 0x%08x\n", p.vendorID, p.deviceID);

    // properties chain (only structs whose extension/core version exists)
    VkPhysicalDeviceProperties2 p2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
    VkPhysicalDeviceSubgroupProperties sg{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES};
    VkPhysicalDeviceMaintenance3Properties m3{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_3_PROPERTIES};
    VkPhysicalDeviceDriverPropertiesKHR drv{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES_KHR};
    VkPhysicalDeviceSubgroupSizeControlPropertiesEXT sgc{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_SIZE_CONTROL_PROPERTIES_EXT};
    VkPhysicalDeviceFloatControlsPropertiesKHR fc{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FLOAT_CONTROLS_PROPERTIES_KHR};
    p2.pNext = &sg; sg.pNext = &m3;
    void ** tail = &m3.pNext;
    const bool has_drv = has_ext(exts, "VK_KHR_driver_properties");
    const bool has_sgc = has_ext(exts, "VK_EXT_subgroup_size_control");
    const bool has_fc  = has_ext(exts, "VK_KHR_shader_float_controls");
    if (has_drv) { *tail = &drv; tail = &drv.pNext; }
    if (has_sgc) { *tail = &sgc; tail = &sgc.pNext; }
    if (has_fc)  { *tail = &fc;  tail = &fc.pNext; }
    vkGetPhysicalDeviceProperties2(pd, &p2);
    if (has_drv) {
        printf("driver          %s / %s (conformance %u.%u.%u.%u)\n", drv.driverName, drv.driverInfo,
               drv.conformanceVersion.major, drv.conformanceVersion.minor, drv.conformanceVersion.subminor,
               drv.conformanceVersion.patch);
    }

    section("Compute limits");
    const auto & l = p.limits;
    printf("max shared memory / workgroup  %u bytes\n", l.maxComputeSharedMemorySize);
    printf("max invocations / workgroup    %u\n", l.maxComputeWorkGroupInvocations);
    printf("max workgroup size             %u x %u x %u\n", l.maxComputeWorkGroupSize[0], l.maxComputeWorkGroupSize[1], l.maxComputeWorkGroupSize[2]);
    printf("max workgroup count            %u x %u x %u\n", l.maxComputeWorkGroupCount[0], l.maxComputeWorkGroupCount[1], l.maxComputeWorkGroupCount[2]);
    printf("max storage buffer range       %u bytes (%.0f MB)\n", l.maxStorageBufferRange, l.maxStorageBufferRange / 1048576.0);
    printf("max memory allocation          %.0f MB\n", m3.maxMemoryAllocationSize / 1048576.0);
    printf("max push constants             %u bytes\n", l.maxPushConstantsSize);
    printf("min storage buffer alignment   %llu\n", (unsigned long long) l.minStorageBufferOffsetAlignment);
    printf("timestamp period               %.2f ns (compute+graphics timestamps: %s)\n", l.timestampPeriod,
           l.timestampComputeAndGraphics ? "yes" : "no");

    section("Subgroups");
    printf("subgroup size                  %u\n", sg.subgroupSize);
    printf("in compute shaders             %s\n", (sg.supportedStages & VK_SHADER_STAGE_COMPUTE_BIT) ? "yes" : "NO");
    static const std::pair<uint32_t, const char *> ops[] = {
        {VK_SUBGROUP_FEATURE_BASIC_BIT, "basic"}, {VK_SUBGROUP_FEATURE_VOTE_BIT, "vote"},
        {VK_SUBGROUP_FEATURE_ARITHMETIC_BIT, "arithmetic"}, {VK_SUBGROUP_FEATURE_BALLOT_BIT, "ballot"},
        {VK_SUBGROUP_FEATURE_SHUFFLE_BIT, "shuffle"}, {VK_SUBGROUP_FEATURE_SHUFFLE_RELATIVE_BIT, "shuffle_relative"},
        {VK_SUBGROUP_FEATURE_CLUSTERED_BIT, "clustered"}, {VK_SUBGROUP_FEATURE_QUAD_BIT, "quad"}};
    printf("operations                     ");
    for (auto & o : ops) if (sg.supportedOperations & o.first) printf("%s ", o.second);
    printf("\n");
    if (has_sgc) printf("size control                   min %u, max %u\n", sgc.minSubgroupSize, sgc.maxSubgroupSize);
    if (has_fc) {
        printf("fp16 round-to-nearest-even     %s, fp16 denorm preserve %s\n",
               fc.shaderRoundingModeRTEFloat16 ? "yes" : "no", fc.shaderDenormPreserveFloat16 ? "yes" : "no");
    }

    section("Features (what ggml-vulkan uses)");
    VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    VkPhysicalDevice16BitStorageFeatures s16{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES};
    VkPhysicalDevice8BitStorageFeaturesKHR s8{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_8BIT_STORAGE_FEATURES_KHR};
    VkPhysicalDeviceShaderFloat16Int8FeaturesKHR f16{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES_KHR};
    VkPhysicalDeviceTimelineSemaphoreFeaturesKHR tl{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES_KHR};
    VkPhysicalDeviceSubgroupSizeControlFeaturesEXT sgcf{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_SIZE_CONTROL_FEATURES_EXT};
    VkPhysicalDeviceBufferDeviceAddressFeaturesKHR bda{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES_KHR};
    VkPhysicalDeviceVulkanMemoryModelFeaturesKHR vmm{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_MEMORY_MODEL_FEATURES_KHR};
    VkPhysicalDeviceShaderSubgroupExtendedTypesFeaturesKHR sgx{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_SUBGROUP_EXTENDED_TYPES_FEATURES_KHR};
    f2.pNext = &s16;
    tail = &s16.pNext;
    auto chain = [&](bool present, void * s, void ** next) { if (present) { *tail = s; tail = next; } };
    chain(has_ext(exts, "VK_KHR_8bit_storage"), &s8, &s8.pNext);
    chain(has_ext(exts, "VK_KHR_shader_float16_int8"), &f16, &f16.pNext);
    chain(has_ext(exts, "VK_KHR_timeline_semaphore"), &tl, &tl.pNext);
    chain(has_sgc, &sgcf, &sgcf.pNext);
    chain(has_ext(exts, "VK_KHR_buffer_device_address"), &bda, &bda.pNext);
    chain(has_ext(exts, "VK_KHR_vulkan_memory_model"), &vmm, &vmm.pNext);
    chain(has_ext(exts, "VK_KHR_shader_subgroup_extended_types"), &sgx, &sgx.pNext);
    vkGetPhysicalDeviceFeatures2(pd, &f2);
    auto yn = [](VkBool32 b) { return b ? "yes" : "no"; };
    printf("16-bit storage buffers         %s (uniform+storage %s, push constants %s)\n", yn(s16.storageBuffer16BitAccess),
           yn(s16.uniformAndStorageBuffer16BitAccess), yn(s16.storagePushConstant16));
    printf("8-bit storage buffers          %s\n", yn(s8.storageBuffer8BitAccess));
    printf("shader float16                 %s\n", yn(f16.shaderFloat16));
    printf("shader int8                    %s\n", yn(f16.shaderInt8));
    printf("shader int16 / int64 / fp64    %s / %s / %s\n", yn(f2.features.shaderInt16), yn(f2.features.shaderInt64), yn(f2.features.shaderFloat64));
    printf("timeline semaphores            %s\n", yn(tl.timelineSemaphore));
    printf("subgroup size control          %s (compute full subgroups %s)\n", yn(sgcf.subgroupSizeControl), yn(sgcf.computeFullSubgroups));
    printf("subgroup extended types        %s\n", yn(sgx.shaderSubgroupExtendedTypes));
    printf("buffer device address          %s\n", yn(bda.bufferDeviceAddress));
    printf("vulkan memory model            %s\n", yn(vmm.vulkanMemoryModel));

    section("Extensions ggml-vulkan looks for");
    static const char * wanted[] = {
        "VK_KHR_16bit_storage", "VK_KHR_8bit_storage", "VK_KHR_shader_float16_int8", "VK_KHR_timeline_semaphore",
        "VK_KHR_spirv_1_4", "VK_KHR_shader_float_controls", "VK_EXT_subgroup_size_control", "VK_KHR_maintenance4",
        "VK_KHR_shader_integer_dot_product", "VK_KHR_cooperative_matrix", "VK_KHR_shader_bfloat16",
        "VK_EXT_pipeline_robustness", "VK_EXT_external_memory_host", "VK_KHR_buffer_device_address",
        "VK_KHR_vulkan_memory_model", "VK_EXT_global_priority", "VK_EXT_global_priority_query",
        "VK_KHR_pipeline_executable_properties", "VK_EXT_device_fault", "VK_EXT_memory_budget"};
    for (const char * w : wanted) printf("%-40s %s\n", w, has_ext(exts, w) ? "yes" : "-");
    printf("(%zu extensions in total)\n", exts.size());

    section("Memory");
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(pd, &mp);
    for (uint32_t i = 0; i < mp.memoryHeapCount; i++) {
        printf("heap %u: %.0f MB%s\n", i, mp.memoryHeaps[i].size / 1048576.0,
               (mp.memoryHeaps[i].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) ? " device-local" : "");
    }
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++) {
        const auto f = mp.memoryTypes[i].propertyFlags;
        printf("type %u (heap %u):%s%s%s%s%s\n", i, mp.memoryTypes[i].heapIndex,
               (f & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) ? " device-local" : "",
               (f & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) ? " host-visible" : "",
               (f & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) ? " coherent" : "",
               (f & VK_MEMORY_PROPERTY_HOST_CACHED_BIT) ? " cached" : "",
               (f & VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT) ? " lazy" : "");
    }

    section("Queue families");
    uint32_t nq = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, nullptr);
    std::vector<VkQueueFamilyProperties> qf(nq);
    vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, qf.data());
    for (uint32_t i = 0; i < nq; i++) {
        printf("family %u: %u queue(s)%s%s%s, timestamp bits %u\n", i, qf[i].queueCount,
               (qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) ? " graphics" : "",
               (qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT) ? " compute" : "",
               (qf[i].queueFlags & VK_QUEUE_TRANSFER_BIT) ? " transfer" : "", qf[i].timestampValidBits);
    }
}

// ------------------------------------------------------------------------------------------------
// compute tests

struct Gpu {
    VkPhysicalDevice pd = VK_NULL_HANDLE;
    VkDevice dev = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t family = 0;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkQueryPool qpool = VK_NULL_HANDLE;
    float ts_period = 1;
    bool timestamps = false;
    bool fp16 = false, storage16 = false, subgroup_arith = false;
    VkPhysicalDeviceMemoryProperties mem{};
    bool lost = false;
};

struct Buf {
    VkBuffer buf = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    void * map = nullptr;
    VkDeviceSize size = 0;
};

struct Pipe {
    VkShaderModule mod = VK_NULL_HANDLE;
    VkDescriptorSetLayout dsl = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkPipeline pipe = VK_NULL_HANDLE;
    VkDescriptorPool dpool = VK_NULL_HANDLE;
    VkDescriptorSet set = VK_NULL_HANDLE;
};

static int find_mem(const Gpu & g, uint32_t bits, VkMemoryPropertyFlags want, VkMemoryPropertyFlags avoid = 0) {
    for (uint32_t i = 0; i < g.mem.memoryTypeCount; i++) {
        const auto f = g.mem.memoryTypes[i].propertyFlags;
        if ((bits & (1u << i)) && (f & want) == want && !(f & avoid)) return (int) i;
    }
    return -1;
}

static VkResult make_buf(const Gpu & g, VkDeviceSize size, VkMemoryPropertyFlags want, Buf & b, VkMemoryPropertyFlags avoid = 0) {
    b.size = size;
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bi.size = size;
    bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    VkResult r = vkCreateBuffer(g.dev, &bi, nullptr, &b.buf);
    if (r != VK_SUCCESS) return r;
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(g.dev, b.buf, &req);
    int type = find_mem(g, req.memoryTypeBits, want, avoid);
    if (type < 0 && avoid) type = find_mem(g, req.memoryTypeBits, want);
    if (type < 0) return VK_ERROR_FEATURE_NOT_PRESENT;
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = (uint32_t) type;
    if ((r = vkAllocateMemory(g.dev, &ai, nullptr, &b.mem)) != VK_SUCCESS) return r;
    if ((r = vkBindBufferMemory(g.dev, b.buf, b.mem, 0)) != VK_SUCCESS) return r;
    if (g.mem.memoryTypes[type].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) {
        if ((r = vkMapMemory(g.dev, b.mem, 0, VK_WHOLE_SIZE, 0, &b.map)) != VK_SUCCESS) return r;
    }
    return VK_SUCCESS;
}

static void free_buf(const Gpu & g, Buf & b) {
    if (b.map) vkUnmapMemory(g.dev, b.mem);
    if (b.buf) vkDestroyBuffer(g.dev, b.buf, nullptr);
    if (b.mem) vkFreeMemory(g.dev, b.mem, nullptr);
    b = Buf{};
}

static VkResult make_pipe(const Gpu & g, const uint32_t * code, size_t size, Pipe & p, const Buf & b0, const Buf & b1) {
    VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    mi.codeSize = size;
    mi.pCode = code;
    VkResult r = vkCreateShaderModule(g.dev, &mi, nullptr, &p.mod);
    if (r != VK_SUCCESS) return r;
    VkDescriptorSetLayoutBinding bind[2] = {
        {0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
        {1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr}};
    VkDescriptorSetLayoutCreateInfo li{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    li.bindingCount = 2;
    li.pBindings = bind;
    if ((r = vkCreateDescriptorSetLayout(g.dev, &li, nullptr, &p.dsl)) != VK_SUCCESS) return r;
    VkPushConstantRange pc{VK_SHADER_STAGE_COMPUTE_BIT, 0, 4};
    VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pli.setLayoutCount = 1;
    pli.pSetLayouts = &p.dsl;
    pli.pushConstantRangeCount = 1;
    pli.pPushConstantRanges = &pc;
    if ((r = vkCreatePipelineLayout(g.dev, &pli, nullptr, &p.layout)) != VK_SUCCESS) return r;
    VkComputePipelineCreateInfo ci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    ci.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    ci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    ci.stage.module = p.mod;
    ci.stage.pName = "main";
    ci.layout = p.layout;
    if ((r = vkCreateComputePipelines(g.dev, VK_NULL_HANDLE, 1, &ci, nullptr, &p.pipe)) != VK_SUCCESS) return r;
    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 2};
    VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dpi.maxSets = 1;
    dpi.poolSizeCount = 1;
    dpi.pPoolSizes = &ps;
    if ((r = vkCreateDescriptorPool(g.dev, &dpi, nullptr, &p.dpool)) != VK_SUCCESS) return r;
    VkDescriptorSetAllocateInfo dai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    dai.descriptorPool = p.dpool;
    dai.descriptorSetCount = 1;
    dai.pSetLayouts = &p.dsl;
    if ((r = vkAllocateDescriptorSets(g.dev, &dai, &p.set)) != VK_SUCCESS) return r;
    VkDescriptorBufferInfo bis[2] = {{b0.buf, 0, VK_WHOLE_SIZE}, {b1.buf, 0, VK_WHOLE_SIZE}};
    VkWriteDescriptorSet w[2] = {};
    for (int i = 0; i < 2; i++) {
        w[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w[i].dstSet = p.set;
        w[i].dstBinding = (uint32_t) i;
        w[i].descriptorCount = 1;
        w[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        w[i].pBufferInfo = &bis[i];
    }
    vkUpdateDescriptorSets(g.dev, 2, w, 0, nullptr);
    return VK_SUCCESS;
}

static void free_pipe(const Gpu & g, Pipe & p) {
    if (p.dpool) vkDestroyDescriptorPool(g.dev, p.dpool, nullptr);
    if (p.pipe) vkDestroyPipeline(g.dev, p.pipe, nullptr);
    if (p.layout) vkDestroyPipelineLayout(g.dev, p.layout, nullptr);
    if (p.dsl) vkDestroyDescriptorSetLayout(g.dev, p.dsl, nullptr);
    if (p.mod) vkDestroyShaderModule(g.dev, p.mod, nullptr);
    p = Pipe{};
}

struct Timing { VkResult r = VK_SUCCESS; double cpu = 0, gpu = -1; };

// Runs `dispatches` dispatches of `groups` workgroups in one command buffer and waits for them.
static Timing run(Gpu & g, const Pipe & p, uint32_t groups, uint32_t iters, int dispatches = 1, uint64_t timeout_ns = 120ull * 1000000000ull) {
    Timing t;
    if (g.lost) { t.r = VK_ERROR_DEVICE_LOST; return t; }
    VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    ai.commandPool = g.pool;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ai.commandBufferCount = 1;
    VkCommandBuffer cb;
    if ((t.r = vkAllocateCommandBuffers(g.dev, &ai, &cb)) != VK_SUCCESS) return t;
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cb, &bi);
    if (g.timestamps) {
        vkCmdResetQueryPool(cb, g.qpool, 0, 2);
        vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, g.qpool, 0);
    }
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p.pipe);
    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p.layout, 0, 1, &p.set, 0, nullptr);
    vkCmdPushConstants(cb, p.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, 4, &iters);
    for (int i = 0; i < dispatches; i++) vkCmdDispatch(cb, groups, 1, 1);
    if (g.timestamps) vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, g.qpool, 1);
    vkEndCommandBuffer(cb);
    VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkFence fence;
    vkCreateFence(g.dev, &fi, nullptr, &fence);
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cb;
    const double t0 = now();
    t.r = vkQueueSubmit(g.queue, 1, &si, fence);
    if (t.r == VK_SUCCESS) t.r = vkWaitForFences(g.dev, 1, &fence, VK_TRUE, timeout_ns);
    t.cpu = now() - t0;
    if (t.r == VK_SUCCESS && g.timestamps) {
        uint64_t ts[2];
        if (vkGetQueryPoolResults(g.dev, g.qpool, 0, 2, sizeof ts, ts, 8, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT) == VK_SUCCESS) {
            t.gpu = (double) (ts[1] - ts[0]) * g.ts_period * 1e-9;
        }
    }
    if (t.r == VK_ERROR_DEVICE_LOST) g.lost = true;
    if (t.r != VK_TIMEOUT) { // a timed-out command buffer may still be running
        vkDestroyFence(g.dev, fence, nullptr);
        vkFreeCommandBuffers(g.dev, g.pool, 1, &cb);
    }
    return t;
}

static double best_time(const Timing & t) { return t.gpu > 0 ? t.gpu : t.cpu; }

static VkResult make_gpu(VkPhysicalDevice pd, const std::vector<VkExtensionProperties> & exts, Gpu & g,
                         VkQueueGlobalPriorityEXT priority = (VkQueueGlobalPriorityEXT) 0) {
    g = Gpu{};
    g.pd = pd;
    uint32_t nq = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, nullptr);
    std::vector<VkQueueFamilyProperties> qf(nq);
    vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, qf.data());
    g.family = UINT32_MAX;
    for (uint32_t i = 0; i < nq; i++) if (qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT) { g.family = i; break; }
    if (g.family == UINT32_MAX) return VK_ERROR_FEATURE_NOT_PRESENT;

    VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    VkPhysicalDevice16BitStorageFeatures s16{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES};
    VkPhysicalDeviceShaderFloat16Int8FeaturesKHR f16{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES_KHR};
    std::vector<const char *> dev_exts;
    f2.pNext = &s16;
    if (has_ext(exts, "VK_KHR_shader_float16_int8")) { s16.pNext = &f16; dev_exts.push_back("VK_KHR_shader_float16_int8"); }
    if (has_ext(exts, "VK_KHR_16bit_storage")) dev_exts.push_back("VK_KHR_16bit_storage");
    vkGetPhysicalDeviceFeatures2(pd, &f2);
    // enable only what the tests need
    VkPhysicalDeviceFeatures2 en{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    VkPhysicalDevice16BitStorageFeatures en16{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES};
    VkPhysicalDeviceShaderFloat16Int8FeaturesKHR enf16{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES_KHR};
    en16.storageBuffer16BitAccess = s16.storageBuffer16BitAccess;
    enf16.shaderFloat16 = f16.shaderFloat16;
    en.features.shaderInt16 = f2.features.shaderInt16;
    en.pNext = &en16;
    if (has_ext(exts, "VK_KHR_shader_float16_int8")) en16.pNext = &enf16;
    g.fp16 = f16.shaderFloat16;
    g.storage16 = s16.storageBuffer16BitAccess;

    const float prio = 1.0f;
    VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qi.queueFamilyIndex = g.family;
    qi.queueCount = 1;
    qi.pQueuePriorities = &prio;
    VkDeviceQueueGlobalPriorityCreateInfoEXT gp{VK_STRUCTURE_TYPE_DEVICE_QUEUE_GLOBAL_PRIORITY_CREATE_INFO_EXT};
    if (priority) {
        gp.globalPriority = priority;
        qi.pNext = &gp;
        dev_exts.push_back("VK_EXT_global_priority");
    }
    VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    di.pNext = &en;
    di.queueCreateInfoCount = 1;
    di.pQueueCreateInfos = &qi;
    di.enabledExtensionCount = (uint32_t) dev_exts.size();
    di.ppEnabledExtensionNames = dev_exts.data();
    VkResult r = vkCreateDevice(pd, &di, nullptr, &g.dev);
    if (r != VK_SUCCESS) return r;
    vkGetDeviceQueue(g.dev, g.family, 0, &g.queue);
    if (!g.queue) return VK_ERROR_INITIALIZATION_FAILED;
    VkCommandPoolCreateInfo pi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pi.queueFamilyIndex = g.family;
    pi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    if ((r = vkCreateCommandPool(g.dev, &pi, nullptr, &g.pool)) != VK_SUCCESS) return r;
    VkPhysicalDeviceProperties p;
    vkGetPhysicalDeviceProperties(pd, &p);
    g.ts_period = p.limits.timestampPeriod;
    if (qf[g.family].timestampValidBits > 0 && p.limits.timestampComputeAndGraphics) {
        VkQueryPoolCreateInfo qpi{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
        qpi.queryType = VK_QUERY_TYPE_TIMESTAMP;
        qpi.queryCount = 2;
        g.timestamps = vkCreateQueryPool(g.dev, &qpi, nullptr, &g.qpool) == VK_SUCCESS;
    }
    VkPhysicalDeviceSubgroupProperties sg{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES};
    VkPhysicalDeviceProperties2 p2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
    p2.pNext = &sg;
    vkGetPhysicalDeviceProperties2(pd, &p2);
    g.subgroup_arith = (sg.supportedStages & VK_SHADER_STAGE_COMPUTE_BIT) && (sg.supportedOperations & VK_SUBGROUP_FEATURE_ARITHMETIC_BIT);
    vkGetPhysicalDeviceMemoryProperties(pd, &g.mem);
    return VK_SUCCESS;
}

static void free_gpu(Gpu & g) {
    if (!g.dev) return;
    vkDeviceWaitIdle(g.dev);
    if (g.qpool) vkDestroyQueryPool(g.dev, g.qpool, nullptr);
    if (g.pool) vkDestroyCommandPool(g.dev, g.pool, nullptr);
    vkDestroyDevice(g.dev, nullptr);
    g = Gpu{};
}

#define SPV(name) spv_##name, spv_##name##_size

// FMA throughput: grows the iteration count until one dispatch takes >= 0.25 s. Returns GFLOPS
// (0 on failure) and the measured time per iteration (for sizing the long-dispatch test).
static double flops_test(Gpu & g, const uint32_t * code, size_t size, const char * label, double * sec_per_iter = nullptr) {
    const uint32_t groups = 256, invocations = groups * 256;
    Buf out, dummy;
    Pipe p;
    VkResult r;
    if ((r = make_buf(g, invocations * 16, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, out)) != VK_SUCCESS ||
        (r = make_buf(g, 16, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, dummy)) != VK_SUCCESS ||
        (r = make_pipe(g, code, size, p, out, dummy)) != VK_SUCCESS) {
        printf("%-28s setup failed: %s\n", label, vkres(r));
        free_pipe(g, p); free_buf(g, out); free_buf(g, dummy);
        return 0;
    }
    run(g, p, groups, 16); // warm-up (shader compile, clocks)
    double gflops = 0;
    for (uint32_t iters = 64; iters <= (1u << 20); iters *= 2) {
        Timing t = run(g, p, groups, iters);
        if (t.r != VK_SUCCESS) { printf("%-28s FAILED at %u iterations: %s\n", label, iters, vkres(t.r)); break; }
        const double s = best_time(t);
        if (s >= 0.25 || iters == (1u << 20)) {
            gflops = (double) invocations * iters * 4 * 4 * 2 / s * 1e-9;
            if (sec_per_iter) *sec_per_iter = s / iters;
            printf("%-28s %8.1f GFLOPS  (%.3f s for %u iterations)\n", label, gflops, s, iters);
            break;
        }
    }
    // sanity: results must be finite
    if (gflops > 0) {
        const float * o = (const float *) out.map;
        bool ok = true;
        for (uint32_t i = 0; i < invocations * 4; i += 997) if (!std::isfinite(o[i])) { ok = false; break; }
        if (!ok) printf("%-28s WARNING: non-finite results\n", "");
    }
    free_pipe(g, p); free_buf(g, out); free_buf(g, dummy);
    return gflops;
}

static void compute_tests(VkPhysicalDevice pd, const std::vector<VkExtensionProperties> & exts, bool long_test, bool flops_only) {
    section("Compute tests");
    Gpu g;
    VkResult r = make_gpu(pd, exts, g);
    if (r != VK_SUCCESS) { printf("creating the device failed: %s\n", vkres(r)); return; }
    printf("GPU timestamps: %s\n", g.timestamps ? "yes (times below are GPU time)" : "no (times below include CPU overhead)");

    // 1. submission overhead
    if (!flops_only) {
        Buf a, b;
        Pipe p;
        if (make_buf(g, 256, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, a) == VK_SUCCESS &&
            make_buf(g, 256, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, b) == VK_SUCCESS &&
            make_pipe(g, SPV(empty), p, a, b) == VK_SUCCESS) {
            run(g, p, 1, 0);
            double total = 0;
            const int n = 100;
            for (int i = 0; i < n; i++) total += run(g, p, 1, 0).cpu;
            printf("%-28s %8.3f ms per submit + wait (empty dispatch)\n", "submission round trip", total / n * 1e3);
            Timing t = run(g, p, 1, 0, 1000);
            printf("%-28s %8.2f us per dispatch (1000 empty dispatches in one submit)\n", "dispatch cost", best_time(t) / 1000 * 1e6);
        } else {
            printf("submission test setup failed\n");
        }
        free_pipe(g, p); free_buf(g, a); free_buf(g, b);
    }

    // 2. arithmetic throughput
    double sec_per_iter = 0;
    const double f32 = flops_test(g, SPV(fma32), "fp32 fma()", &sec_per_iter);
    const double m32 = flops_test(g, SPV(mad32), "fp32 a*b+c");
    if (g.fp16) {
        const double f16 = flops_test(g, SPV(fma16), "fp16 fma() vec4");
        const double m16 = flops_test(g, SPV(mad16), "fp16 a*b+c vec4");
        const double p16 = flops_test(g, SPV(mad16x8), "fp16 a*b+c vec2 x8");
        const double best16 = std::max(f16, std::max(m16, p16)), best32 = std::max(f32, m32);
        if (best32 > 0 && best16 > 0) printf("%-28s %8.2fx (best fp16 / best fp32)\n", "fp16 / fp32 speed", best16 / best32);
    } else {
        printf("%-28s skipped (shaderFloat16 not supported)\n", "fp16 throughput");
    }
    if (flops_only) { free_gpu(g); return; }

    // 3. memory bandwidth (GPU copy) and CPU access to mapped memory
    {
        const VkDeviceSize size = 128ull << 20;
        Buf src, dst;
        Pipe p;
        if ((r = make_buf(g, size, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, src)) == VK_SUCCESS &&
            (r = make_buf(g, size, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, dst)) == VK_SUCCESS &&
            (r = make_pipe(g, SPV(copy), p, dst, src)) == VK_SUCCESS) {
            const uint32_t groups = (uint32_t) (size / 16 / 256);
            run(g, p, groups, 0);
            double best = 1e9;
            for (int i = 0; i < 5; i++) {
                Timing t = run(g, p, groups, 0);
                if (t.r != VK_SUCCESS) { printf("copy failed: %s\n", vkres(t.r)); break; }
                best = std::min(best, best_time(t));
            }
            printf("%-28s %8.1f GB/s (128 MB read + 128 MB written)\n", "GPU memory bandwidth", 2.0 * size / best * 1e-9);
        } else {
            printf("%-28s setup failed: %s\n", "GPU memory bandwidth", vkres(r));
        }
        free_pipe(g, p); free_buf(g, src); free_buf(g, dst);

        // CPU <-> mapped memory: uncached (coherent) vs cached types
        std::vector<uint8_t> host(64ull << 20, 1);
        const struct { const char * name; VkMemoryPropertyFlags want, avoid; } kinds[] = {
            {"uncached (coherent)", VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, VK_MEMORY_PROPERTY_HOST_CACHED_BIT},
            {"cached", VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT, 0}};
        for (const auto & k : kinds) {
            Buf b;
            if (find_mem(g, ~0u, k.want, k.avoid) < 0) {
                printf("CPU <-> %-20s no such memory type\n", k.name);
                continue;
            }
            if (make_buf(g, host.size(), k.want, b, k.avoid) != VK_SUCCESS || !b.map) {
                printf("CPU <-> %-20s not available\n", k.name);
                free_buf(g, b);
                continue;
            }
            double t0 = now();
            memcpy(b.map, host.data(), host.size());
            const double w = now() - t0;
            t0 = now();
            memcpy(host.data(), b.map, host.size());
            const double rd = now() - t0;
            printf("CPU <-> %-20s write %6.2f GB/s, read %6.2f GB/s\n", k.name, host.size() / w * 1e-9, host.size() / rd * 1e-9);
            free_buf(g, b);
        }
    }

    // 4. correctness: 16-bit storage, subgroups
    if (g.storage16) {
        const uint32_t n = 4096;
        Buf out, in;
        Pipe p;
        if (make_buf(g, n * 4, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, out) == VK_SUCCESS &&
            make_buf(g, n * 2, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, in) == VK_SUCCESS &&
            make_pipe(g, SPV(f16storage), p, out, in) == VK_SUCCESS) {
            uint16_t * h = (uint16_t *) in.map;
            for (uint32_t i = 0; i < n; i++) h[i] = f32_to_f16((float) (i % 512) * 0.25f);
            Timing t = run(g, p, n / 64, 0);
            const float * o = (const float *) out.map;
            uint32_t bad = 0;
            for (uint32_t i = 0; i < n; i++) if (o[i] != (float) (i % 512) * 0.5f) bad++;
            printf("%-28s %s\n", "16-bit storage", t.r != VK_SUCCESS ? vkres(t.r) : bad ? "WRONG RESULTS" : "correct");
        } else {
            printf("%-28s setup failed\n", "16-bit storage");
        }
        free_pipe(g, p); free_buf(g, out); free_buf(g, in);
    }
    if (g.subgroup_arith) {
        const uint32_t n = 4096;
        Buf out, dummy;
        Pipe p;
        if (make_buf(g, n * 4, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, out) == VK_SUCCESS &&
            make_buf(g, 16, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, dummy) == VK_SUCCESS &&
            make_pipe(g, SPV(subgroup), p, out, dummy) == VK_SUCCESS) {
            Timing t = run(g, p, n / 64, 0);
            const uint32_t * o = (const uint32_t *) out.map;
            uint32_t bad = 0, size = o[0] % 1000;
            for (uint32_t i = 0; i < n; i++) if (o[i] / 1000 != o[i] % 1000) bad++;
            printf("%-28s %s (subgroup size in shaders: %u)\n", "subgroupAdd",
                   t.r != VK_SUCCESS ? vkres(t.r) : bad ? "WRONG RESULTS" : "correct", size);
        }
        free_pipe(g, p); free_buf(g, out); free_buf(g, dummy);
    } else {
        printf("%-28s skipped (no subgroup arithmetic in compute)\n", "subgroupAdd");
    }

    // 5. allocation sizes
    {
        printf("%-28s", "single allocations");
        for (VkDeviceSize mb : {256, 512, 1024, 1536, 2048, 3072}) {
            Buf b;
            VkResult ar = make_buf(g, mb << 20, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, b);
            printf(" %lluMB:%s", (unsigned long long) mb, ar == VK_SUCCESS ? "ok" : "FAIL");
            free_buf(g, b);
        }
        printf("\n");
    }

    // 6. global priority
    if (has_ext(exts, "VK_EXT_global_priority")) {
        for (auto pr : {VK_QUEUE_GLOBAL_PRIORITY_LOW_EXT, VK_QUEUE_GLOBAL_PRIORITY_HIGH_EXT}) {
            Gpu g2;
            const char * name = pr == VK_QUEUE_GLOBAL_PRIORITY_LOW_EXT ? "low" : "high";
            VkResult pr_r = make_gpu(pd, exts, g2, pr);
            if (pr_r != VK_SUCCESS) { printf("%-28s %s priority queue: %s\n", "global priority", name, vkres(pr_r)); free_gpu(g2); continue; }
            char label[64];
            snprintf(label, sizeof label, "fp32 at %s priority", name);
            flops_test(g2, SPV(fma32), label);
            free_gpu(g2);
        }
    } else {
        printf("%-28s VK_EXT_global_priority not supported\n", "global priority");
    }

    // 7. long single dispatches (what a big attention or matmul does): does the driver finish them?
    if (long_test && sec_per_iter > 0 && !g.lost) {
        Buf out, dummy;
        Pipe p;
        if (make_buf(g, 256 * 256 * 16, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, out) == VK_SUCCESS &&
            make_buf(g, 16, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, dummy) == VK_SUCCESS &&
            make_pipe(g, SPV(fma32), p, out, dummy) == VK_SUCCESS) {
            printf("long dispatches (the screen may freeze briefly):\n");
            for (double target : {1.0, 3.0, 8.0}) {
                const double it = std::min(target / sec_per_iter, 4.0e9);
                Timing t = run(g, p, 256, (uint32_t) it, 1, 60ull * 1000000000ull);
                printf("  %4.0f s target: %s, took %.2f s\n", target, vkres(t.r), t.cpu);
                if (t.r != VK_SUCCESS) break;
            }
        }
        free_pipe(g, p); free_buf(g, out); free_buf(g, dummy);
    }
    if (g.lost) printf("THE DEVICE WAS LOST during the tests (driver reset)\n");
    free_gpu(g);
}

int main(int argc, char ** argv) {
    bool long_test = true, flops_only = false;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--no-long") == 0) long_test = false;
        if (strcmp(argv[i], "--flops") == 0) flops_only = true;
    }
    printf("vkprobe 1.1\n");

    uint32_t inst_ver = VK_API_VERSION_1_0;
    vkEnumerateInstanceVersion(&inst_ver);
    printf("Vulkan loader (instance) version %s\n", ver(inst_ver).c_str());
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "vkprobe";
    app.apiVersion = std::min(inst_ver, (uint32_t) VK_API_VERSION_1_3);
    VkInstanceCreateInfo ii{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ii.pApplicationInfo = &app;
    VkInstance inst;
    VkResult r = vkCreateInstance(&ii, nullptr, &inst);
    if (r != VK_SUCCESS) { printf("vkCreateInstance failed: %s\n", vkres(r)); return 1; }
    uint32_t n = 0;
    vkEnumeratePhysicalDevices(inst, &n, nullptr);
    std::vector<VkPhysicalDevice> devs(n);
    vkEnumeratePhysicalDevices(inst, &n, devs.data());
    printf("%u Vulkan device(s)\n", n);
    for (VkPhysicalDevice pd : devs) {
        uint32_t ne = 0;
        vkEnumerateDeviceExtensionProperties(pd, nullptr, &ne, nullptr);
        std::vector<VkExtensionProperties> exts(ne);
        vkEnumerateDeviceExtensionProperties(pd, nullptr, &ne, exts.data());
        if (!flops_only) report_device(pd, exts);
        compute_tests(pd, exts, long_test, flops_only);
    }
    vkDestroyInstance(inst, nullptr);
    printf("\ndone\n");
    return 0;
}
