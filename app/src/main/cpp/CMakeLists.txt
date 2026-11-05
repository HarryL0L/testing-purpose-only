cmake_minimum_required(VERSION 3.22)
project("llamachat" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    set(GGML_OPENMP ON CACHE BOOL "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_SYSTEM_ARCH "x86")
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
endif()

set(THIRD_PARTY ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party)
set(LLAMA_SRC ${THIRD_PARTY}/llama.cpp)

# ------------------------------------------------------------------------------------------------
# Experimental GPU build: llama.cpp Vulkan backend + libadrenotools (custom Adreno drivers)
# ------------------------------------------------------------------------------------------------
option(LLAMACHAT_VULKAN "Build the Vulkan backend with libadrenotools driver loading" OFF)
if(LLAMACHAT_VULKAN)
    if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
        message(FATAL_ERROR "The experimental Vulkan/libadrenotools build only supports arm64-v8a")
    endif()
    foreach(dep Vulkan-Headers SPIRV-Headers libadrenotools)
        if(NOT EXISTS ${THIRD_PARTY}/${dep}/CMakeLists.txt AND NOT EXISTS ${THIRD_PARTY}/${dep}/include)
            message(FATAL_ERROR "${dep} not found. Run: git submodule update --init --recursive")
        endif()
    endforeach()

    set(CMAKE_ANDROID_ARCH_ABI ${ANDROID_ABI})
    add_subdirectory(${THIRD_PARTY}/libadrenotools build-adrenotools)

    # libvulkan_shim: exports the Vulkan entry points as trampolines into a table that is filled
    # at startup from either a custom driver (libadrenotools) or the system libvulkan.so
    file(STRINGS ${CMAKE_CURRENT_LIST_DIR}/vulkan/vulkan_exports.txt VK_EXPORTS)
    set(VK_INC "")
    set(VK_ASM ".text\n")
    set(VK_IDX 0)
    foreach(fn ${VK_EXPORTS})
        string(APPEND VK_INC "X(${fn})\n")
        math(EXPR VK_OFF "${VK_IDX} * 8")
        string(APPEND VK_ASM
            ".balign 16\n.globl ${fn}\n.type ${fn}, %function\n${fn}:\n"
            "    adrp x16, g_llamachat_vk_table\n"
            "    add  x16, x16, :lo12:g_llamachat_vk_table\n"
            "    ldr  x16, [x16, #${VK_OFF}]\n"
            "    br   x16\n"
            ".size ${fn}, .-${fn}\n")
        math(EXPR VK_IDX "${VK_IDX} + 1")
    endforeach()
    file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/vk_gen/vulkan_exports.inc "${VK_INC}")
    file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/vk_gen/vulkan_trampolines.S "${VK_ASM}")

    enable_language(ASM)
    add_library(vulkan_shim SHARED
        ${CMAKE_CURRENT_LIST_DIR}/vulkan/vulkan_shim.cpp
        ${CMAKE_CURRENT_BINARY_DIR}/vk_gen/vulkan_trampolines.S)
    set_target_properties(vulkan_shim PROPERTIES OUTPUT_NAME vulkan_shim)
    target_include_directories(vulkan_shim PRIVATE ${CMAKE_CURRENT_BINARY_DIR}/vk_gen)
    target_link_libraries(vulkan_shim PRIVATE adrenotools dl log)
    target_include_directories(vulkan_shim INTERFACE
        ${THIRD_PARTY}/Vulkan-Headers/include
        ${THIRD_PARTY}/SPIRV-Headers/include)

    # make llama.cpp's find_package(Vulkan) / Vulkan::Vulkan use the shim and our headers
    add_library(Vulkan::Vulkan ALIAS vulkan_shim)
    set(Vulkan_INCLUDE_DIR ${THIRD_PARTY}/Vulkan-Headers/include CACHE PATH "" FORCE)
    if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
        set(NDK_HOST_TAG darwin-x86_64)
    elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
        set(NDK_HOST_TAG windows-x86_64)
    else()
        set(NDK_HOST_TAG linux-x86_64)
    endif()
    find_program(Vulkan_GLSLC_EXECUTABLE glslc
        HINTS ${ANDROID_NDK}/shader-tools/${NDK_HOST_TAG} NO_CMAKE_FIND_ROOT_PATH REQUIRED)
    set(SPIRV-Headers_DIR ${CMAKE_CURRENT_LIST_DIR}/vulkan CACHE PATH "" FORCE)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
endif()
if(NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp sources not found. Run: git submodule update --init --recursive")
endif()
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(llamachat SHARED llama_jni.cpp)

target_include_directories(llamachat PRIVATE
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/common
    ${LLAMA_SRC}/ggml/include
    ${LLAMA_SRC}/tools/mtmd)

target_link_libraries(llamachat PRIVATE llama llama-common mtmd android log)
if(LLAMACHAT_VULKAN)
    target_compile_definitions(llamachat PRIVATE LLAMACHAT_VULKAN=1)
    target_link_libraries(llamachat PRIVATE vulkan_shim)
endif()
