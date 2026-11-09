# LlamaChat — on-device multimodal AI for Android

A native Android chat app built on the **latest [llama.cpp](https://github.com/ggml-org/llama.cpp) (tag `b12111`)**, with a
**Material 3 Expressive / Material You** UI, that understands **images and videos entirely on the phone**.
After the model download, nothing leaves the device.

## Features

- **llama.cpp b12111** compiled from source with the NDK (arm64 with KleidiAI and runtime-selected CPU variants: dotprod, i8mm, SVE…).
- **Vision via `libmtmd`**: attach photos (any format Android can decode, HEIC included) and ask about them.
- **Video understanding** without ffmpeg:
  - frames are decoded with **hardware `MediaCodec`** (`MediaExtractor` → YUV `Image` → RGB) in one forward pass. Far-apart frames are reached by seeking to the nearest keyframe, and `MediaMetadataRetriever` is a fallback.
  - frame count, frame rate, frame size and timestamp layout follow the model's own recommendation (`mtmd_get_video_config`). That covers Qwen-VL `<t seconds>` frame pairs, LFM, and the default `[XmY.YYs]` timestamps.
  - frames are handed to mtmd as a *lazy bitmap*, so the model sees one video with proper begin/end tokens.
- **Material 3 Expressive** (`material3` 1.5.0-rc01, the first line where these APIs are public): `MaterialExpressiveTheme` with wallpaper-based dynamic color and the expressive spring motion scheme, flexible top app bars, the morphing `LoadingIndicator`, wavy progress indicators, shape-morphing buttons, `MaterialShapes` cut-outs for attachments, and a themed (monochrome) launcher icon.
- **One-tap model downloads** from Hugging Face (resumable), **import a copy** of any `.gguf`, or **link a folder** (e.g. `Download/models`):
  - the language model is read in place from the folder through its file descriptor (`llama_model_load_from_file_ptr`), so it is never copied.
  - the mmproj is opened in place through `/proc/self/fd` when Android allows it. Otherwise only the mmproj is copied to app storage, once.
- **Chat history**: conversations are saved on the phone and listed in a side drawer. Images and sampled video frames are stored with each chat as JPEGs, so a reopened chat gives the model its media back.
- **Thinking on/off** (passed to the template's `enable_thinking`, e.g. Qwen3 / Qwen3.5). Reasoning appears in a collapsible "Thinking" section, and earlier turns pass it back to the template as `reasoning_content`.
- Each turn the whole conversation is rendered and compared with the KV cache token by token (like llama-server), so only the changed part is evaluated. This works with any template, including ones that rewrite earlier turns. Vision-encoder output is cached by media ID, so hybrid/recurrent models (Qwen3.5), whose memory has to be rebuilt each turn, don't run the image encoder again.
- **Fast follow-ups with video on hybrid models**: context checkpoints, like llama-server. Qwen3.5 (hybrid/recurrent) and sliding-window models can't drop the end of their memory, so the app snapshots the small recurrent/SWA state right before the generation prompt and restores it on the next turn, instead of re-processing the whole chat. In testing, a follow-up after a 16-frame video went from 3.5 s to 0.2 s of prompt time.
- **Image quality** (Low / Medium / High, default Low) caps the pixels of photos. Dynamic-resolution encoders turn more pixels into more tokens: 1536×1152 was 494 tokens / 4.0 s versus 192 tokens / 1.4 s at 512×384 on the test host.
- **Video quality** (Low / Medium / Model max) caps the pixels per frame. Models like Qwen-VL ask for 672×1216 frames for a short portrait clip, which is very slow on a phone CPU. The default is Low with 8 frames, and a progress percentage shows while media is processed.
- **Repack weights on/off**: CPU weight repacking (`use_extra_bufts`) is faster, but it copies the weights into RAM instead of reading them from the memory-mapped file. Turn it off for large models that run out of memory or load slowly.
- **You decide when a model is in memory**: nothing loads automatically (unless "Load last model on startup" is switched on). The chat screen offers "Load *last model*", and the eject button in the top bar unloads it. Downloads finish without loading anything.
- **Benchmark** (Models → Benchmark): measures the loaded chat model's prompt and reply speed (tok/s, like
  llama-bench, with threads switched on the live context) and the selected image model (one 256×256 image), at several
  thread counts. One tap applies the fastest. It also shows the CPU layout (fast/slow cores from cpufreq). The **Auto**
  thread setting, now the default, uses only the fast cores, since ggml threads on slow cores hold the others back.
- Streaming replies with tok/s, a stop button, a context usage readout, and settings for context size, threads, temperature, reply length, max video frames and the system prompt.

| Preset | Size | Notes |
| --- | --- | --- |
| SmolVLM2 500M Video | 0.5 GB | runs on almost any phone, trained on video |
| Qwen3-VL 2B | 2.3 GB | strong image + video, timestamps |
| Qwen2.5-VL 3B (Q4_K_M) | 2.8 GB | documents / OCR |
| Gemma 3 4B (Q4_K_M) | 3.3 GB | best general chat |

## Build

Requirements: JDK 17+, Android SDK 37 (Material3 1.5 requires it), NDK `29.0.14206865`, CMake `3.31.6` (Android Studio installs them on first sync).

```bash
git clone --recursive <this repo>
# or, if already cloned:
git submodule update --init --recursive

./gradlew :app:assembleRelease      # app/build/outputs/apk/release/app-release.apk
# build for the emulator too:
./gradlew :app:assembleDebug -PllamaAbis=arm64-v8a,x86_64
```

The release build is signed with the debug key so it installs as-is. Set up your own `signingConfig` before you publish it.

## Experimental GPU build (Vulkan + libadrenotools)

A second flavor, `experimental`, adds llama.cpp's **Vulkan** backend and
**[libadrenotools](https://github.com/bylaws/libadrenotools)**, so Adreno (Snapdragon) GPUs can run with a custom
Vulkan driver such as Mesa Turnip. It installs next to the normal app as *LlamaChat GPU (experimental)*
(`…llamachat.experimental`). It is arm64 only.

```bash
./gradlew :app:assembleExperimentalRelease   # app/build/outputs/apk/experimental/release/
./gradlew :app:assembleStandardRelease       # the normal CPU-only app
```

In the app, Models → **GPU · experimental**:
- **Use GPU (Vulkan)** offloads the model and the vision encoder to the GPU (off = CPU only, same as the standard build).
- **Vulkan driver**: the system driver, or an imported AdrenoTools driver package (`.zip` with a `meta.json` naming the driver library). A driver can only be loaded once per process, so changing it needs **Restart app**.
- The card shows which driver loaded and which GPU llama.cpp found.

How it works: llama.cpp's Vulkan backend normally links to the system `libvulkan.so`. libadrenotools, however,
returns a *separate* Vulkan loader instance backed by the custom driver. So in this flavor `libggml-vulkan.so` is
linked against `libvulkan_shim.so` instead (`app/src/main/cpp/vulkan/`). The shim exports every Vulkan entry point as
an assembly trampoline into a function table, which is filled at startup from the custom driver or the system driver
before any backend is loaded.

Caveats: custom drivers only work on Adreno GPUs. On other GPUs (e.g. Mali) only the system driver is available, and
llama.cpp's Vulkan backend may be slower than the CPU or give wrong output depending on the driver. This build has not
been tested on real GPUs by the author of this change.

**Vulkan 1.1 devices.** Upstream llama.cpp needs Vulkan 1.2, but many Mali phones (e.g. Mali-G77, driver r32) only
offer 1.1 plus the extensions that 1.2 later made standard. This flavor builds a patched copy of `ggml-vulkan.cpp`
(`app/src/main/cpp/vulkan/ggml_vk11_patch.cmake`; the submodule itself is untouched). On 1.1 devices the patched copy:

- reads and enables 16-bit and 8-bit storage, fp16/int8 and timeline semaphores through their `VK_KHR_*` extensions;
- clears the feature structures before querying them;
- leaves buffer device address and the Vulkan memory model off.

A glslc wrapper (`glslc_vk11.sh.in`) compiles the shaders for Vulkan 1.1 (SPIR-V 1.3). Devices with Vulkan 1.2 or
newer behave exactly as upstream.

### Experimental Mali backend (OpenCL)

The experimental flavor also has its own small ggml backend for **Mali GPUs**
(`app/src/main/cpp/mali/`). It's needed because llama.cpp's OpenCL backend only accepts Adreno and Intel GPUs, and its
kernels assume their sub-group sizes. The Mali backend:
- implements only matrix multiplication (`MUL_MAT`) for **Q8_0** and **F16** weights. ggml's scheduler runs everything
  else on the CPU.
- is used for large batches only: prompt, image and video processing (≥ 32 tokens). Token-by-token generation stays on
  the CPU, where a phone GPU doesn't win.
- follows the approach of Google's ML Drift (Apache-2.0), which makes LiteRT fast on Mali: activations are quantized to
  int8 and multiplied with the Arm int8 dot-product instruction (`cl_arm_integer_dot_product_accumulate_int8`), using
  register tiles that don't depend on a sub-group size.
- uses host memory wrapped with `CL_MEM_USE_HOST_PTR`. Mali shares RAM with the CPU, so tensors aren't copied. In Mali
  mode the model is read into RAM (no mmap) and CPU repacking is off.

Pick it in Models → GPU · experimental → Run on: **Mali (OpenCL)**. Each reply then shows the prompt time and how many
matrix multiplications ran on the GPU, for comparing against CPU mode. Correctness was checked against ggml's CPU
backend with PoCL (`mali/test/mali_matmul_test.cpp`); greedy outputs of Qwen3-VL 2B (text and image) were identical. It
has **not been benchmarked on a real Mali GPU**.

## Image creation (stable-diffusion.cpp)

Turn on **Create image** in the message bar and describe a picture. It's generated on the phone with
[stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) and shown in the chat. Images are saved with the
chat; tap one to view it full screen and save it to the gallery (`Pictures/LlamaChat`).

| Model | Download | Steps | Desktop CPU, 4 threads, 512×512 |
| --- | --- | --- | --- |
| SDXS-512 (tiny one-step model, tiny decoder built in) | 683 MB | 1 | 6.5 s |
| SD-Turbo + TAESD decoder | 2.0 GB + 10 MB | 1–4 (default 2) | 14.9 s (2 steps) |

**Your own models:** Models → Image creation → *Import a model file* accepts any stable-diffusion.cpp model
(`.gguf`, `.safetensors`, `.ckpt`), e.g. DreamShaper 8 LCM. Defaults come from the file name: LCM models use the LCM
sampler with 6 steps and CFG 1.0, Turbo/Lightning models use 4 steps and CFG 1.0, anything else 20 steps and CFG 7. You
can change the sampler (Euler A / LCM / DPM++ 2M), steps and CFG. SD 1.x/2.x imports use the TAESD fast decoder when it's
present. On the test host, DreamShaper 8 LCM Q8_0 (512×512, LCM, 6 steps) took 40 s at CFG 1.0 versus 76 s at CFG 1.5.

Phones are several times slower. With SD-Turbo, the TAESD decoder cut decoding from 26 s to 2.8 s with practically
identical images. Models download in Models → Image creation, where you also set the size (256/384/512), the number of
steps and a negative prompt. A file you downloaded yourself can be imported there too: it needs the same file name
(`sdxs-512-tinySDdistilled_Q8_0.gguf`, `sd_turbo-f16-q8_0.gguf`, `diffusion_pytorch_model.safetensors` for TAESD).

stable-diffusion.cpp bundles a patched ggml that isn't compatible with llama.cpp's. It's therefore built in its own
Gradle module (`imagegen/`) and CMake project, statically into `libimagegen.so` with all internal symbols hidden
(`-Wl,--exclude-libs,ALL`; only the JNI functions are exported). Both engines run in one process. This was tested on the
host: chat → image → chat in one JVM. The library is built for ARMv8.2 + dot product + fp16, and the app checks
`/proc/cpuinfo` before loading it.

Only the `ggml` submodule of stable-diffusion.cpp is needed:
`git submodule update --init third_party/stable-diffusion.cpp && git -C third_party/stable-diffusion.cpp submodule update --init ggml`.

## Layout

```
third_party/llama.cpp/                 llama.cpp submodule pinned to b12111
third_party/libadrenotools, Vulkan-Headers, SPIRV-Headers   experimental GPU flavor only
app/src/main/cpp/vulkan/               libvulkan shim (trampolines) for custom Vulkan drivers
imagegen/                              text-to-image module: stable-diffusion.cpp (+ its own ggml) in libimagegen.so
third_party/stable-diffusion.cpp/      submodule
app/src/main/cpp/llama_jni.cpp         JNI: model/context/mtmd, chat template, sampling, lazy video bitmaps
app/src/main/java/.../engine/          LlamaNative (JNI decls) + LlamaEngine (single-thread engine, Flow streaming)
app/src/main/java/.../media/           Image decoding, MediaCodec video frame grabber
app/src/main/java/.../data/            Settings (DataStore), chat history store, model presets / download / import / linked folders
app/src/main/java/.../ui/              Compose UI (chat + models screens, expressive theme)
```

## Updating llama.cpp

```bash
cd third_party/llama.cpp && git fetch --depth 1 origin tag bNNNNN && git checkout bNNNNN
```

The JNI code uses `llama.h`, `mtmd.h`, `mtmd-helper.h` and the `common` library. Those APIs move quickly, so rebuild after you bump the tag.

## Notes

- Inference runs on the CPU. The GPU backends (OpenCL for Adreno, Vulkan) can be switched on with the matching `GGML_*` CMake flags, but they aren't tuned here.
- A video costs many tokens, roughly frames × tokens per frame. Raise *Context size* or lower *Max video frames* if you hit "context is full".
