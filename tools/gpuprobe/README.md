# GPU probe

Checks how a phone's Vulkan driver copes with the GPU code LlamaChat uses.

* `vkprobe` — driver report (version, features, limits, memory, queues) and compute tests:
  submission overhead, fp32 / fp16 throughput, memory bandwidth, CPU access to GPU memory,
  16-bit storage and subgroup correctness, allocation sizes, low / high queue priority, and
  long single dispatches (1 / 3 / 8 s).
* `test-backend-ops` — ggml's own test: every operation on the Vulkan GPU against the CPU, and
  performance of matrix multiplication and attention (Vulkan and CPU). Built from the ggml copy
  that LlamaChat's image creation uses, with the app's Vulkan 1.1 patch.

## Run it in Termux

```sh
termux-setup-storage            # once: lets Termux read Download/
cd ~
cp /sdcard/Download/gpuprobe.tar.gz .
tar xzf gpuprobe.tar.gz
cd gpuprobe
sh run.sh quick                 # a few minutes
sh run.sh                       # everything: 15-40 min, keep the screen on
```

Then share `report.txt` (e.g. `cp report.txt /sdcard/Download/`). The long-dispatch test may
freeze the screen for a few seconds; that is expected.

## Build

* On the phone, in Termux (vkprobe only):
  `pkg install clang shaderc vulkan-headers vulkan-loader-android`, then `sh build-termux.sh`.
* On a PC with the Android NDK (vkprobe + test-backend-ops), from a full checkout of this repo
  with submodules: `./build.sh` → `out/gpuprobe.tar.gz`.

## Files

* `vkprobe.cpp` — the probe (plain Vulkan C API, no other dependencies)
* `shaders/*.comp` — its compute shaders (GLSL), compiled to SPIR-V at build time
* `run.sh` — runs everything, writes `report.txt`
* `CMakeLists.txt`, `build.sh` — Android build incl. test-backend-ops (uses
  `third_party/stable-diffusion.cpp/ggml` and the app's Vulkan 1.1 patch in `app/src/main/cpp/vulkan`)
* `build-termux.sh` — on-phone build of vkprobe
