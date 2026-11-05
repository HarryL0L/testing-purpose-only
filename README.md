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

## Layout

```
third_party/llama.cpp/                 llama.cpp submodule pinned to b12111
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
