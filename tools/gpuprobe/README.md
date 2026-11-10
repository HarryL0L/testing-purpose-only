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

## Build (developers)

`./build.sh` (needs the Android NDK) → `out/gpuprobe.tar.gz`.
