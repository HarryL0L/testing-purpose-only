# Minimal package config: llama.cpp's Vulkan backend only needs the SPIR-V headers, which come
# from the third_party/SPIRV-Headers submodule (added to the include path by the shim target).
set(SPIRV-Headers_FOUND TRUE)
