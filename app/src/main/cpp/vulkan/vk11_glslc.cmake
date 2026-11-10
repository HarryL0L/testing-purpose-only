# Finds the NDK's glslc and points Vulkan_GLSLC_EXECUTABLE at a wrapper that compiles ggml's
# shaders for Vulkan 1.1 (see ggml_vk11_patch.cmake and glslc_vk11.sh.in). Used by the app's
# llama.cpp build and by :imagegen's stable-diffusion.cpp build.
if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
    set(NDK_HOST_TAG darwin-x86_64)
elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(NDK_HOST_TAG windows-x86_64)
else()
    set(NDK_HOST_TAG linux-x86_64)
endif()
find_program(LLAMACHAT_GLSLC glslc
    HINTS ${ANDROID_NDK}/shader-tools/${NDK_HOST_TAG} NO_CMAKE_FIND_ROOT_PATH REQUIRED)
if(CMAKE_HOST_WIN32)
    set(Vulkan_GLSLC_EXECUTABLE ${LLAMACHAT_GLSLC} CACHE FILEPATH "" FORCE)
else()
    set(GLSLC_REAL ${LLAMACHAT_GLSLC})
    set(VK11_LOG ${CMAKE_CURRENT_BINARY_DIR}/vk11_patch/shader-fallbacks.txt)
    configure_file(${CMAKE_CURRENT_LIST_DIR}/glslc_vk11.sh.in
        ${CMAKE_CURRENT_BINARY_DIR}/vk11_patch/glslc_vk11.sh @ONLY
        FILE_PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE GROUP_READ GROUP_EXECUTE WORLD_READ WORLD_EXECUTE)
    set(Vulkan_GLSLC_EXECUTABLE ${CMAKE_CURRENT_BINARY_DIR}/vk11_patch/glslc_vk11.sh CACHE FILEPATH "" FORCE)
endif()
