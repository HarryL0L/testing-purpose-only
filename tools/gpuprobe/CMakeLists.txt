# GPU probe for phones (run in Termux, see README.md):
#   vkprobe           Vulkan driver report + compute micro-benchmarks (own code)
#   test-backend-ops  ggml's op-by-op correctness / performance test, built from the ggml copy that
#                     LlamaChat's image creation uses (stable-diffusion.cpp), with Vulkan and the
#                     app's Vulkan 1.1 patch
# Android: see build.sh. Desktop (for testing): cmake -S . -B build -DLLAMACHAT_GLSLC=/path/to/glslc
cmake_minimum_required(VERSION 3.22)
project(gpuprobe C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(REPO ${CMAKE_CURRENT_LIST_DIR}/../..)
set(THIRD_PARTY ${REPO}/third_party)
set(LLAMACHAT_VK ${REPO}/app/src/main/cpp/vulkan)
set(SD_GGML ${THIRD_PARTY}/stable-diffusion.cpp/ggml)

# Vulkan: our headers (the NDK's are too old for ggml), the system loader
set(Vulkan_INCLUDE_DIR ${THIRD_PARTY}/Vulkan-Headers/include CACHE PATH "" FORCE)
include(${LLAMACHAT_VK}/vk11_glslc.cmake) # LLAMACHAT_GLSLC + Vulkan 1.1 wrapper for ggml's shaders
set(SPIRV-Headers_DIR ${LLAMACHAT_VK} CACHE PATH "" FORCE)

# ---- vkprobe
set(SHADERS empty fma32 fma16 mad32 mad16 mad16x8 copy subgroup f16storage)
set(SHADER_HEADERS "")
foreach(s ${SHADERS})
    set(spv ${CMAKE_CURRENT_BINARY_DIR}/shaders/${s}.spv)
    set(hdr ${CMAKE_CURRENT_BINARY_DIR}/shaders/${s}.h)
    add_custom_command(
        OUTPUT ${hdr}
        COMMAND ${CMAKE_COMMAND} -E make_directory ${CMAKE_CURRENT_BINARY_DIR}/shaders
        COMMAND ${LLAMACHAT_GLSLC} -fshader-stage=compute --target-env=vulkan1.1 -O ${CMAKE_CURRENT_LIST_DIR}/shaders/${s}.comp -o ${spv}
        COMMAND ${CMAKE_COMMAND} -DIN=${spv} -DOUT=${hdr} -DNAME=spv_${s} -P ${CMAKE_CURRENT_LIST_DIR}/bin2h.cmake
        DEPENDS ${CMAKE_CURRENT_LIST_DIR}/shaders/${s}.comp ${CMAKE_CURRENT_LIST_DIR}/bin2h.cmake
        COMMENT "vkprobe shader ${s}")
    list(APPEND SHADER_HEADERS ${hdr})
endforeach()
set(ALL_H ${CMAKE_CURRENT_BINARY_DIR}/shaders.h)
set(includes "")
foreach(s ${SHADERS})
    string(APPEND includes "#include \"shaders/${s}.h\"\n")
endforeach()
file(WRITE ${ALL_H}.tmp "#pragma once\n#include <cstddef>\n#include <cstdint>\n${includes}")
file(COPY_FILE ${ALL_H}.tmp ${ALL_H} ONLY_IF_DIFFERENT)

add_executable(vkprobe vkprobe.cpp ${SHADER_HEADERS})
target_include_directories(vkprobe PRIVATE ${Vulkan_INCLUDE_DIR} ${CMAKE_CURRENT_BINARY_DIR})
find_library(VULKAN_LIB vulkan REQUIRED)
target_link_libraries(vkprobe PRIVATE ${VULKAN_LIB})

# ---- test-backend-ops (ggml from stable-diffusion.cpp, CPU + Vulkan)
set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL       OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN           ON  CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()
add_subdirectory(${SD_GGML} ggml)
set(VK11_GGML_SRC ${SD_GGML})
include(${LLAMACHAT_VK}/ggml_vk11_patch.cmake)
target_include_directories(ggml-vulkan PRIVATE ${THIRD_PARTY}/SPIRV-Headers/include)

add_executable(test-backend-ops ${SD_GGML}/tests/test-backend-ops.cpp)
target_link_libraries(test-backend-ops PRIVATE ggml)
find_package(Threads REQUIRED)
target_link_libraries(test-backend-ops PRIVATE Threads::Threads)
