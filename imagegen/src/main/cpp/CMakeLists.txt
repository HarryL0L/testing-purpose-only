cmake_minimum_required(VERSION 3.22)
project(imagegen LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# stable-diffusion.cpp ships its own patched ggml (different struct sizes than llama.cpp's), so it
# is built here, in a separate CMake project, *statically* into libimagegen.so with all of its
# symbols hidden (--exclude-libs). It can then live in the same process as llama.cpp's libggml.so.
set(SD_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/stable-diffusion.cpp)
if(NOT EXISTS ${SD_SRC}/CMakeLists.txt OR NOT EXISTS ${SD_SRC}/ggml/CMakeLists.txt)
    message(FATAL_ERROR "stable-diffusion.cpp not found. Run: git submodule update --init --recursive")
endif()

set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS  OFF CACHE BOOL "" FORCE)
set(SD_BUILD_EXAMPLES     OFF CACHE BOOL "" FORCE)
set(SD_WEBP               OFF CACHE BOOL "" FORCE)
set(SD_WEBM               OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE) # ggml's own thread pool; no second libomp
set(GGML_BACKEND_DL       OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # dot product + fp16 arithmetic: every big core since ~2018 (Cortex-A55/A75+). The app checks
    # /proc/cpuinfo before loading this library, so older CPUs just don't get image creation.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

# Experimental flavor: stable-diffusion.cpp's Vulkan backend (system Vulkan driver), with the same
# Vulkan 1.1 patch as the app's llama.cpp build (e.g. for Mali r32 drivers).
option(IMAGEGEN_VULKAN "Build stable-diffusion.cpp's Vulkan backend" OFF)
if(IMAGEGEN_VULKAN)
    set(LLAMACHAT_VK ${CMAKE_CURRENT_LIST_DIR}/../../../../app/src/main/cpp/vulkan)
    set(THIRD_PARTY ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party)
    set(Vulkan_INCLUDE_DIR ${THIRD_PARTY}/Vulkan-Headers/include CACHE PATH "" FORCE)
    include(${LLAMACHAT_VK}/vk11_glslc.cmake)
    set(SPIRV-Headers_DIR ${LLAMACHAT_VK} CACHE PATH "" FORCE)
    set(SD_VULKAN ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(${SD_SRC} build-sd)
include(${CMAKE_CURRENT_LIST_DIR}/patches/sd_attention.cmake) # attention in head groups

if(IMAGEGEN_VULKAN)
    set(VK11_GGML_SRC ${SD_SRC}/ggml)
    set(VK11_EXTRA_PATCHES ${LLAMACHAT_VK}/ggml_vk_mali_gemm.cmake) # Mali f16 GEMM fast path
    include(${LLAMACHAT_VK}/ggml_vk11_patch.cmake)
    target_include_directories(ggml-vulkan PRIVATE ${THIRD_PARTY}/SPIRV-Headers/include)
endif()

add_library(imagegen SHARED sd_jni.cpp)
if(IMAGEGEN_VULKAN)
    target_compile_definitions(imagegen PRIVATE IMAGEGEN_VULKAN=1)
endif()
target_link_libraries(imagegen PRIVATE stable-diffusion)
if(ANDROID)
    target_link_libraries(imagegen PRIVATE android log)
endif()
target_link_options(imagegen PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,--gc-sections")
