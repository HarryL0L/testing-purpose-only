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
- **One-tap model downloads** from Hugging Face (resumable), or **import any `.gguf`** plus its `mmproj-*.gguf`.
- Streaming replies with tok/s, a stop button, multi-turn chat that reuses the KV cache, a context usage readout, and settings for context size, threads, temperature, reply length, max video frames and the system prompt.

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
app/src/main/java/.../data/            Settings (DataStore), model presets / download / import
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
