# Vulkan 1.1 support for llama.cpp's Vulkan backend (experimental flavor only).
#
# Upstream ggml-vulkan requires Vulkan 1.2: it reads the device features through the 1.2-only
# VkPhysicalDeviceVulkan11Features / VkPhysicalDeviceVulkan12Features structures (left
# uninitialized, so a 1.1 driver that ignores them leaves garbage that is then passed back to
# vkCreateDevice) and compiles its shaders for Vulkan 1.2 / SPIR-V 1.5. Many Mali phones (e.g.
# Mali-G77 with driver r32) only expose Vulkan 1.1 plus the KHR extensions that 1.2 promoted.
#
# This script copies ggml-vulkan.cpp into the build tree and patches the copy (the submodule is
# left untouched). On Vulkan >= 1.2 devices the patched code behaves exactly like upstream.
# On 1.1 devices it:
#   - zero-initializes the feature structures,
#   - reads/enables 16-bit storage, 8-bit storage, float16/int8 and timeline semaphores through
#     their 1.1 extension structures and enables those extensions,
#   - leaves bufferDeviceAddress and vulkanMemoryModel off,
#   - fills the subgroup properties from VkPhysicalDeviceSubgroupProperties (core 1.1),
#   - points the timeline-semaphore entry points at their KHR versions.
# The shaders are compiled for Vulkan 1.1 by a glslc wrapper (vk11_glslc.cmake, glslc_vk11.sh.in).
#
# Every patch site is checked: if llama.cpp changes and a snippet is not found, configuration
# fails instead of silently building an unpatched backend.

# VK11_GGML_SRC: the ggml source tree whose ggml-vulkan target is patched
set(_vk_src ${VK11_GGML_SRC}/src/ggml-vulkan/ggml-vulkan.cpp)
set(_vk_dst ${CMAKE_CURRENT_BINARY_DIR}/vk11_patch/ggml-vulkan.cpp)
file(READ ${_vk_src} _vk)

function(_vk_patch old new)
    string(FIND "${_vk}" "${old}" _pos)
    if(_pos EQUAL -1)
        message(FATAL_ERROR "Vulkan 1.1 patch: snippet not found in ggml-vulkan.cpp:\n${old}")
    endif()
    string(REPLACE "${old}" "${new}" _out "${_vk}")
    set(_vk "${_out}" PARENT_SCOPE)
endfunction()

# 1. Device creation: feature structures
_vk_patch([[
        VkPhysicalDeviceVulkan11Features vk11_features;
        vk11_features.pNext = nullptr;
        vk11_features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
        device_features2.pNext = &vk11_features;

        VkPhysicalDeviceVulkan12Features vk12_features;
        vk12_features.pNext = nullptr;
        vk12_features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
        vk11_features.pNext = &vk12_features;

        last_struct = (VkBaseOutStructure *)&vk12_features;
]] [[
        VkPhysicalDeviceVulkan11Features vk11_features {};
        vk11_features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
        VkPhysicalDeviceVulkan12Features vk12_features {};
        vk12_features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;

        // LlamaChat: Vulkan 1.1 devices use the extension structures promoted to 1.2 instead
        // LLAMACHAT_VK_FORCE_11: take this path on any device (to test it on a desktop driver)
        const bool lc_vk11 = device->properties.apiVersion < VK_API_VERSION_1_2 || getenv("LLAMACHAT_VK_FORCE_11") != nullptr;
        auto lc_has_ext = [&](const char * name) {
            for (const auto & p : ext_props) {
                if (strcmp(name, p.extensionName) == 0) return true;
            }
            return false;
        };
        const bool lc_has_8bit     = lc_vk11 && lc_has_ext("VK_KHR_8bit_storage");
        const bool lc_has_f16i8    = lc_vk11 && lc_has_ext("VK_KHR_shader_float16_int8");
        const bool lc_has_timeline = lc_vk11 && lc_has_ext("VK_KHR_timeline_semaphore");
        VkPhysicalDevice16BitStorageFeatures lc_16bit {};
        lc_16bit.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES;
        VkPhysicalDevice8BitStorageFeatures lc_8bit {};
        lc_8bit.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_8BIT_STORAGE_FEATURES;
        VkPhysicalDeviceShaderFloat16Int8Features lc_f16i8 {};
        lc_f16i8.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES;
        VkPhysicalDeviceTimelineSemaphoreFeatures lc_timeline {};
        lc_timeline.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES;

        if (lc_vk11) {
            device_features2.pNext = &lc_16bit;
            last_struct = (VkBaseOutStructure *)&lc_16bit;
            if (lc_has_8bit) {
                last_struct->pNext = (VkBaseOutStructure *)&lc_8bit;
                last_struct = (VkBaseOutStructure *)&lc_8bit;
            }
            if (lc_has_f16i8) {
                last_struct->pNext = (VkBaseOutStructure *)&lc_f16i8;
                last_struct = (VkBaseOutStructure *)&lc_f16i8;
            }
            if (lc_has_timeline) {
                last_struct->pNext = (VkBaseOutStructure *)&lc_timeline;
                last_struct = (VkBaseOutStructure *)&lc_timeline;
            }
        } else {
            device_features2.pNext = &vk11_features;
            vk11_features.pNext = &vk12_features;
            last_struct = (VkBaseOutStructure *)&vk12_features;
        }
]])

# 2. After the feature query: map the extension features onto the 1.1/1.2 structures
_vk_patch([[
        vkGetPhysicalDeviceFeatures2(device->physical_device, &device_features2);
]] [[
        vkGetPhysicalDeviceFeatures2(device->physical_device, &device_features2);

        if (lc_vk11) {
            vk11_features.storageBuffer16BitAccess           = lc_16bit.storageBuffer16BitAccess;
            vk11_features.uniformAndStorageBuffer16BitAccess = lc_16bit.uniformAndStorageBuffer16BitAccess;
            vk12_features.storageBuffer8BitAccess            = lc_8bit.storageBuffer8BitAccess;
            vk12_features.shaderFloat16                      = lc_f16i8.shaderFloat16;
            vk12_features.shaderInt8                         = lc_f16i8.shaderInt8;
            vk12_features.timelineSemaphore                  = lc_timeline.timelineSemaphore;
            if (!lc_timeline.timelineSemaphore) {
                std::cerr << "ggml_vulkan: Vulkan 1.1 device " << GGML_VK_NAME << idx << " lacks timeline semaphores." << std::endl;
                throw std::runtime_error("Unsupported device");
            }
            device_extensions.push_back("VK_KHR_timeline_semaphore");
            if (lc_has_8bit)  device_extensions.push_back("VK_KHR_8bit_storage");
            if (lc_has_f16i8) device_extensions.push_back("VK_KHR_shader_float16_int8");
            // shaders that only compile for SPIR-V 1.4 (see glslc_vk11.sh.in)
            if (lc_has_ext("VK_KHR_spirv_1_4") && lc_has_ext("VK_KHR_shader_float_controls")) {
                device_extensions.push_back("VK_KHR_shader_float_controls");
                device_extensions.push_back("VK_KHR_spirv_1_4");
            }
            GGML_LOG_INFO("ggml_vulkan: Vulkan 1.1 compatibility path for %s (driver fp16 %d, int8 %d, 8-bit storage %d)\n",
                          device->properties.deviceName.data(), (int) lc_f16i8.shaderFloat16, (int) lc_f16i8.shaderInt8,
                          (int) lc_8bit.storageBuffer8BitAccess);
        }
]])

# 2c. Matmul tile sizes for vendors ggml has no settings for (e.g. ARM Mali gets all of large / medium /
#     small; Apple, Qualcomm and Samsung skip large). LLAMACHAT_VK_MM_TILES=<letters from "lms">
#     restricts the choice (e.g. "m", "ms") so they can be measured with test-backend-ops (and then
#     become the default for ARM here).
_vk_patch([[
            device->mul_mat_l_int[i]    = device->mul_mat_l[i];
]] [[
            if (const char * lc_tiles = getenv("LLAMACHAT_VK_MM_TILES")) {
                const bool l = strchr(lc_tiles, 'l') != nullptr, m = strchr(lc_tiles, 'm') != nullptr, s = strchr(lc_tiles, 's') != nullptr;
                if (l || m || s) {
                    device->mul_mat_l[i] = device->mul_mat_l[i] && l; device->mul_mat_id_l[i] = device->mul_mat_id_l[i] && l;
                    device->mul_mat_m[i] = device->mul_mat_m[i] && m; device->mul_mat_id_m[i] = device->mul_mat_id_m[i] && m;
                    device->mul_mat_s[i] = device->mul_mat_s[i] && s; device->mul_mat_id_s[i] = device->mul_mat_id_s[i] && s;
                    if (i == 0) GGML_LOG_INFO("ggml_vulkan: matmul tiles limited to '%s'\n", lc_tiles);
                }
            }
            device->mul_mat_l_int[i]    = device->mul_mat_l[i];
]])

# 2b. Low global priority for the compute queues (when the driver has VK_EXT_global_priority):
#     a phone's GPU also draws the screen, and long compute work at normal priority makes the
#     whole UI stutter. LLAMACHAT_VK_NORMAL_PRIORITY turns this off.
_vk_patch([[
        } else {
            device_queue_create_infos.push_back({queue_flags, compute_queue_family_index, 1, priorities});
        }
]] [[
        } else {
            device_queue_create_infos.push_back({queue_flags, compute_queue_family_index, 1, priorities});
        }

        VkDeviceQueueGlobalPriorityCreateInfoEXT lc_prio {};
        lc_prio.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_GLOBAL_PRIORITY_CREATE_INFO_EXT;
        lc_prio.globalPriority = VK_QUEUE_GLOBAL_PRIORITY_LOW_EXT;
        if (lc_has_ext("VK_EXT_global_priority") && getenv("LLAMACHAT_VK_NORMAL_PRIORITY") == nullptr) {
            for (auto & qci : device_queue_create_infos) qci.setPNext(&lc_prio);
            device_extensions.push_back("VK_EXT_global_priority");
            GGML_LOG_INFO("ggml_vulkan: compute queues at low priority (keeps the screen smooth)\n");
        }
]])

# 3. Don't enable VK_KHR_shader_float16_int8 twice
_vk_patch([[
        if (device->fp16) {
            device_extensions.push_back("VK_KHR_shader_float16_int8");
]] [[
        if (device->fp16 && !lc_vk11) {
            device_extensions.push_back("VK_KHR_shader_float16_int8");
]])

# 4. Timeline semaphore entry points: use the KHR versions on 1.1 devices
_vk_patch([[
        device->device = device->physical_device.createDevice(device_create_info);
]] [[
        device->device = device->physical_device.createDevice(device_create_info);

        if (lc_vk11) {
            auto & lc_d = VULKAN_HPP_DEFAULT_DISPATCHER;
            if (auto p = vkGetDeviceProcAddr(device->device, "vkWaitSemaphoresKHR"))
                lc_d.vkWaitSemaphores = (PFN_vkWaitSemaphores) p;
            if (auto p = vkGetDeviceProcAddr(device->device, "vkSignalSemaphoreKHR"))
                lc_d.vkSignalSemaphore = (PFN_vkSignalSemaphore) p;
            if (auto p = vkGetDeviceProcAddr(device->device, "vkGetSemaphoreCounterValueKHR"))
                lc_d.vkGetSemaphoreCounterValue = (PFN_vkGetSemaphoreCounterValue) p;
        }
]])

# 5. Subgroup properties: VkPhysicalDeviceVulkan11Properties is 1.2-only, use the core 1.1 struct
_vk_patch([[
        device->physical_device.getProperties2(&props2);
        device->properties = props2.properties;
]] [[
        device->physical_device.getProperties2(&props2);
        device->properties = props2.properties;
        if (props2.properties.apiVersion < VK_API_VERSION_1_2 || getenv("LLAMACHAT_VK_FORCE_11") != nullptr) {
            vk11_props.subgroupSize                = subgroup_props.subgroupSize;
            vk11_props.subgroupSupportedStages     = subgroup_props.supportedStages;
            vk11_props.subgroupSupportedOperations = subgroup_props.supportedOperations;
        }
]])

# 6. Device enumeration check: query 16-bit storage through the core 1.1 struct
_vk_patch([[
    VkPhysicalDeviceVulkan11Features vk11_features;
    vk11_features.pNext = nullptr;
    vk11_features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
    device_features2.pNext = &vk11_features;

    vkGetPhysicalDeviceFeatures2(vkdev, &device_features2);

    return vk11_features.storageBuffer16BitAccess;
]] [[
    VkPhysicalDevice16BitStorageFeatures storage16 {};
    storage16.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES;
    device_features2.pNext = &storage16;

    vkGetPhysicalDeviceFeatures2(vkdev, &device_features2);

    return storage16.storageBuffer16BitAccess;
]])

# 7. Queues: use vkGetDeviceQueue (1.0) unless the queue needs creation flags. Equivalent per spec,
#    but on the Android 13 loader + Mali r32 vkGetDeviceQueue2 handed back a queue that crashed in
#    vkQueueSubmit. Fail with an error instead of crashing if the driver returns no queue.
_vk_patch([[
    h->queue = device->device.getQueue2(queue_info2);
]] [[
    if (queue_info2.flags) {
        h->queue = device->device.getQueue2(queue_info2);
    } else {
        h->queue = device->device.getQueue(queue_family_index, queue_index);
    }
    if (!h->queue) {
        std::cerr << "ggml_vulkan: driver returned no queue (family " << queue_family_index << ", index " << queue_index << ")" << std::endl;
        throw std::runtime_error("ggml_vulkan: no device queue");
    }
]])

# 8. Remaining (query-only) uses: zero-initialize so a 1.1 driver leaves them false
_vk_patch("VkPhysicalDeviceVulkan11Features vk11_features;" "VkPhysicalDeviceVulkan11Features vk11_features {};")
_vk_patch("VkPhysicalDeviceVulkan12Features vk12_features;" "VkPhysicalDeviceVulkan12Features vk12_features {};")

file(WRITE ${_vk_dst}.tmp "${_vk}")
file(COPY_FILE ${_vk_dst}.tmp ${_vk_dst} ONLY_IF_DIFFERENT)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${_vk_src} ${CMAKE_CURRENT_LIST_FILE})

get_target_property(_vk_sources ggml-vulkan SOURCES)
list(FILTER _vk_sources EXCLUDE REGEX "(^|/)ggml-vulkan\\.cpp$")
list(APPEND _vk_sources ${_vk_dst})
set_property(TARGET ggml-vulkan PROPERTY SOURCES ${_vk_sources})
target_include_directories(ggml-vulkan PRIVATE ${VK11_GGML_SRC}/src/ggml-vulkan)
message(STATUS "LlamaChat: using Vulkan 1.1-patched ggml-vulkan.cpp")
