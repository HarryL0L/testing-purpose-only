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

add_subdirectory(${SD_SRC} build-sd)

add_library(imagegen SHARED sd_jni.cpp)
target_link_libraries(imagegen PRIVATE stable-diffusion)
if(ANDROID)
    target_link_libraries(imagegen PRIVATE android log)
endif()
target_link_options(imagegen PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,--gc-sections")
