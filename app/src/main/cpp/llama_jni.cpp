// JNI bridge between the Kotlin app and llama.cpp (+ libmtmd for image / video input).
//
// All functions in this file must be called from ONE thread (the Kotlin side uses a
// single-threaded dispatcher). Nothing here is thread-safe on purpose: it keeps it simple.

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>
#include <unordered_map>
#include <unistd.h>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "sampling.h"

#define TAG "llamachat"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------------------------
// Video: frames are decoded on the Kotlin side (MediaMetadataRetriever) and handed over as RGB.
// They are fed to mtmd through a *lazy bitmap*, which mtmd expands during tokenization into the
// model-specific layout (timestamps + frames), mirroring mtmd_helper_video_seq::read_next().
// ---------------------------------------------------------------------------------------------

struct video_source {
    std::vector<uint8_t> frames;  // n * w * h * 3 bytes, RGB
    std::vector<float>   ts;      // timestamp (seconds) of every frame
    uint32_t w = 0, h = 0;
    int      n = 0;
    mtmd_video_template tmpl = MTMD_VIDEO_TEMPLATE_DEFAULT;
    std::string id;

    int           idx            = 0;
    bool          start_emitted  = false;
    std::string   pending_text;
    mtmd_bitmap * pending_frame  = nullptr;
    float         next_ts_ms     = 0.0f;

    static constexpr float TIMESTAMP_INTERVAL_MS = 5000.0f;

    ~video_source() { mtmd_bitmap_free(pending_frame); }

    // the whole conversation is re-tokenized every turn, so the sequence must be replayable
    void reset() {
        idx           = 0;
        start_emitted = false;
        pending_text.clear();
        mtmd_bitmap_free(pending_frame);
        pending_frame = nullptr;
        next_ts_ms    = 0.0f;
    }

    int group_size() const {
        return tmpl == MTMD_VIDEO_TEMPLATE_DEFAULT ? 0 : 2;
    }

    mtmd_bitmap * make_frame(int i) const {
        const size_t frame_bytes = (size_t) w * h * 3;
        mtmd_bitmap * bm = mtmd_bitmap_init(w, h, frames.data() + frame_bytes * i);
        mtmd_bitmap_set_mergeable(bm, true);
        mtmd_bitmap_set_is_video(bm, true);
        const std::string fid = id + "+" + std::to_string(i);
        mtmd_bitmap_set_id(bm, fid.c_str());
        return bm;
    }

    // same contract as mtmd_bitmap_lazy_callback
    int read_next(mtmd_bitmap ** out_bitmap, char ** out_text) {
        *out_bitmap = nullptr;
        *out_text   = nullptr;

        if (!pending_text.empty()) {
            *out_text = strdup(pending_text.c_str());
            pending_text.clear();
            return *out_text ? 0 : -2;
        }
        if (pending_frame) {
            *out_bitmap   = pending_frame;
            pending_frame = nullptr;
            return 0;
        }
        if (idx >= n) {
            return idx == 0 ? -2 : -1;
        }
        if (!start_emitted) {
            start_emitted = true;
            if (tmpl == MTMD_VIDEO_TEMPLATE_DEFAULT) {
                *out_text = strdup("Video:");
                return *out_text ? 0 : -2;
            }
        }

        const int cur = idx++;
        mtmd_bitmap * frame = make_frame(cur);

        const int g = group_size();
        if (g > 0) {
            if (cur % g == 0) {
                // timestamp of the group = midpoint of its first and last frame
                const int last = std::min(cur + g - 1, n - 1);
                const double t = (ts[cur] + ts[last]) / 2.0;
                char buf[64];
                snprintf(buf, sizeof(buf), "<%.1f seconds>", t);
                pending_frame = frame;
                *out_text = strdup(buf);
                return *out_text ? 0 : -2;
            }
            *out_bitmap = frame;
            return 0;
        }

        // default template: "[XmY.YYs]" after the frame every TIMESTAMP_INTERVAL_MS
        *out_bitmap = frame;
        const float elapsed_ms = ts[cur] * 1000.0f;
        if (elapsed_ms >= next_ts_ms) {
            const float elapsed_s = elapsed_ms / 1000.0f;
            const int   minutes   = (int) (elapsed_s / 60.0f);
            const float seconds   = elapsed_s - minutes * 60.0f;
            char buf[32];
            snprintf(buf, sizeof(buf), "[%dm%.2fs]", minutes, seconds);
            pending_text = buf;
            next_ts_ms += TIMESTAMP_INTERVAL_MS;
        }
        return 0;
    }
};

// ---------------------------------------------------------------------------------------------
// Global state
// ---------------------------------------------------------------------------------------------

struct pending_media {
    mtmd_bitmap *                 bitmap = nullptr;
    std::unique_ptr<video_source> video;  // must outlive mtmd_tokenize() for lazy bitmaps
    ~pending_media() { mtmd_bitmap_free(bitmap); }
};

static llama_model *              g_model   = nullptr;
static FILE *                     g_model_fp = nullptr; // set when the model was loaded from a file descriptor
static llama_context *            g_ctx     = nullptr;
static mtmd_context *             g_mtmd    = nullptr;
static common_sampler *           g_sampler = nullptr;
static common_chat_templates_ptr  g_tmpls;
static common_batch               g_batch;
static const llama_vocab *        g_vocab   = nullptr;

static std::vector<common_chat_msg>                 g_history;
static std::vector<std::unique_ptr<pending_media>>  g_media;       // attached for the next user turn
static std::vector<std::unique_ptr<pending_media>>  g_chat_media;  // all media of the conversation, in order

// What the KV cache currently holds, one unit per text token or per media chunk.
// Each turn the whole conversation is re-rendered and compared against this, so only the part
// that changed is evaluated again (media are matched by id and not re-encoded).
struct cache_unit {
    llama_token tok = LLAMA_TOKEN_NULL; // LLAMA_TOKEN_NULL for a media chunk
    std::string media_id;
    llama_pos   pos   = 0;
    llama_pos   n_pos = 1;
    const mtmd_input_chunk * chunk = nullptr; // only valid while the chunks of one prompt() live
};
static std::vector<cache_unit> g_cache;
// Vision-encoder output per media id, so re-evaluating a conversation (e.g. hybrid/recurrent
// models whose memory can't be rewound partially) doesn't run the image encoder again.
static std::unordered_map<std::string, std::vector<float>> g_embd_cache;
static int g_image_counter = 0;
static bool g_use_gpu = false;
// GPU mode for the current model: 0 = CPU, 1 = Vulkan, 2 = Mali (OpenCL, experimental backend)
static int g_gpu_mode = 0;
static ggml_backend_dev_t g_mali_dev = nullptr;
static ggml_backend_dev_t g_vk_dev   = nullptr; // first Vulkan device in Vulkan mode
// Context checkpoints (like llama-server): hybrid/recurrent (e.g. Qwen3.5) and SWA models can't
// drop the end of their memory, so without these every turn re-processes the whole chat (video
// included). A checkpoint is the small "partial" state (recurrent / SWA part) saved right before
// the generation prompt; restoring it lets the next turn continue from there.
struct checkpoint {
    size_t               n_units = 0; // prompt units covered
    llama_pos            pos_end = 0; // position of the first unit after it
    std::vector<uint8_t> data;
};
static std::vector<checkpoint> g_ckpts;
static bool g_use_ckpts = false;
static constexpr size_t MAX_CKPTS = 2;

// prompt progress, read from the UI thread while prompt() runs
static std::atomic<int64_t> g_prog_done{0};
static std::atomic<int64_t> g_prog_total{0};

static bool g_enable_thinking = true;
static bool g_opens_thinking  = false; // the generation prompt ends inside an open <think> block
static std::string                                  g_system_prompt;
static common_params_sampling                       g_sparams;

static llama_pos   g_n_past   = 0;
static int         g_n_batch  = 512;
static int         g_n_gen    = 0;
static int         g_max_gen  = 4096;
static std::string g_utf8_buf;        // bytes of an incomplete UTF-8 sequence
static std::string g_assistant_text;  // text generated in the current turn
static int         g_video_counter = 0;

static void android_log_cb(ggml_log_level level, const char * text, void *) {
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default: break;
    }
    __android_log_write(prio, TAG, text);
}

static std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Java's NewStringUTF expects *modified* UTF-8 and aborts on 4-byte sequences (emoji).
// Go through a byte[] + String(bytes, UTF_8) instead.
static jstring to_jstring(JNIEnv * env, const std::string & s) {
    jbyteArray bytes = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    jclass    str_cls = env->FindClass("java/lang/String");
    jmethodID ctor    = env->GetMethodID(str_cls, "<init>", "([BLjava/lang/String;)V");
    jstring   charset = env->NewStringUTF("UTF-8");
    auto *    result  = (jstring) env->NewObject(str_cls, ctor, bytes, charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(str_cls);
    return result;
}

// length of the longest prefix of s made of complete UTF-8 sequences
static size_t utf8_complete_prefix(const std::string & s) {
    size_t i = 0;
    while (i < s.size()) {
        const auto c = (unsigned char) s[i];
        size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        if (i + len > s.size()) break;
        i += len;
    }
    return i;
}

static void free_sampler() {
    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
}

static void unload_all() {
    g_media.clear();
    g_chat_media.clear();
    g_cache.clear();
    g_embd_cache.clear();
    g_ckpts.clear();
    g_history.clear();
    g_tmpls.reset();
    g_batch = common_batch();
    free_sampler();
    if (g_mtmd)  { mtmd_free(g_mtmd);        g_mtmd  = nullptr; }
    if (g_ctx)   { llama_free(g_ctx);        g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    if (g_model_fp) { fclose(g_model_fp); g_model_fp = nullptr; }
    g_vocab  = nullptr;
    g_n_past = 0;
}

#define JNI_FN(name) Java_io_github_harryl0l_llamachat_engine_LlamaNative_##name

extern "C" {

#ifdef LLAMACHAT_VULKAN
extern "C" int llamachat_vk_init(const char * hook_lib_dir, const char * driver_dir, const char * driver_name,
                                 char * msg, size_t msg_len);
#endif

// Returns a status line about the GPU driver ("" in the CPU-only build).
// Experimental build: the Vulkan driver (system, or a custom one via libadrenotools when
// driver_dir/driver_name are set) must be chosen before the backends are loaded, and can't be
// changed afterwards without restarting the process.
JNIEXPORT jstring JNICALL JNI_FN(init)(JNIEnv * env, jobject, jstring native_lib_dir,
                                        jstring jdriver_dir, jstring jdriver_name) {
    llama_log_set(android_log_cb, nullptr);
    mtmd_helper_log_set(android_log_cb, nullptr);
    const std::string dir = jstr(env, native_lib_dir);
    std::string status;
#ifdef LLAMACHAT_VULKAN
    {
        const std::string driver_dir = jstr(env, jdriver_dir), driver_name = jstr(env, jdriver_name);
        char msg[512] = {0};
        const int r = llamachat_vk_init(dir.c_str(), driver_dir.c_str(), driver_name.c_str(), msg, sizeof(msg));
        LOGI("Vulkan driver: %s (%d)", msg, r);
        status = msg;
    }
#else
    (void) jdriver_dir;
    (void) jdriver_name;
#endif
    LOGI("Loading ggml backends from %s", dir.c_str());
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
    return to_jstring(env, status);
}

#ifdef LLAMACHAT_MALI
#include "mali/ggml-mali.h"
#endif

// Registers the experimental Mali (OpenCL) backend. Returns a status line ("" when not built in).
JNIEXPORT jstring JNICALL JNI_FN(enableMali)(JNIEnv * env, jobject, jboolean any_device) {
#ifdef LLAMACHAT_MALI
    if (!g_mali_dev) {
        ggml_backend_reg_t reg = ggml_backend_mali_reg(any_device);
        if (reg) {
            ggml_backend_register(reg);
            g_mali_dev = ggml_backend_reg_dev_get(reg, 0);
        }
    }
    return to_jstring(env, std::string(g_mali_dev ? "Mali backend: " : "Mali backend unavailable: ") + ggml_backend_mali_status());
#else
    (void) any_device;
    return to_jstring(env, "");
#endif
}

// "n ops, ms" of GPU matrix multiplications since the last call (Mali backend), "" otherwise
JNIEXPORT jstring JNICALL JNI_FN(maliStats)(JNIEnv * env, jobject) {
#ifdef LLAMACHAT_MALI
    int64_t n = 0;
    double ms = 0;
    ggml_backend_mali_stats(&n, &ms);
    ggml_backend_mali_reset_stats();
    char buf[96];
    snprintf(buf, sizeof(buf), "%lld GPU matmuls, %.0f ms", (long long) n, ms);
    return to_jstring(env, n > 0 ? buf : "");
#else
    return to_jstring(env, "");
#endif
}

// One line per GPU device ggml found ("" when none, e.g. the CPU-only build).
JNIEXPORT jstring JNICALL JNI_FN(gpuInfo)(JNIEnv * env, jobject) {
    std::string out;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const auto type = ggml_backend_dev_type(dev);
        if (type != GGML_BACKEND_DEVICE_TYPE_GPU && type != GGML_BACKEND_DEVICE_TYPE_IGPU) continue;
        size_t free = 0, total = 0;
        ggml_backend_dev_memory(dev, &free, &total);
        char line[384];
        snprintf(line, sizeof(line), "%s: %s (%.1f GiB)\n", ggml_backend_dev_name(dev),
                 ggml_backend_dev_description(dev), total / 1073741824.0);
        out += line;
    }
    if (!out.empty()) out.pop_back();
    return to_jstring(env, out);
}

JNIEXPORT jstring JNICALL JNI_FN(systemInfo)(JNIEnv * env, jobject) {
    return to_jstring(env, llama_print_system_info());
}

// Loads the language model either from a path or, when fd >= 0, from an open file descriptor
// (e.g. a file the user picked with the Storage Access Framework; the file is used in place).
// Returns null on success, an error message otherwise.
// repack: let the CPU backend repack weights into its faster interleaved layouts (extra buffer
// types). Faster, but repacked tensors live in anonymous memory instead of the mmapped file, so
// large models need more RAM and load slower. Off = weights stay memory-mapped.
// gpu_mode: 0 = CPU only, 1 = Vulkan (whole model on the GPU), 2 = Mali backend (experimental:
// weights stay in RAM, the large matrix multiplications of prompt / image processing run on the
// GPU, generation stays on the CPU).
JNIEXPORT jstring JNICALL JNI_FN(load)(JNIEnv * env, jobject, jstring jmodel, jint fd, jint n_ctx, jint n_threads,
                                        jboolean repack, jint gpu_mode) {
    unload_all();

    llama_model_params mparams = llama_model_default_params();
    mparams.use_extra_bufts = repack;
    // llama.cpp offloads to every GPU it finds by default; only do that when asked
    static ggml_backend_dev_t no_devices[] = { nullptr };
    static ggml_backend_dev_t vk_devices[8];
    static ggml_backend_dev_t mali_devices[2];
    if (gpu_mode == 2 && !g_mali_dev) gpu_mode = 0;
    g_gpu_mode = gpu_mode;
    g_use_gpu  = gpu_mode != 0;
    if (gpu_mode == 0) {
        mparams.devices      = no_devices;
        mparams.n_gpu_layers = 0;
    } else if (gpu_mode == 1) {
        // every GPU except the Mali backend
        size_t n = 0;
        for (size_t i = 0; i < ggml_backend_dev_count() && n + 1 < 8; i++) {
            ggml_backend_dev_t d = ggml_backend_dev_get(i);
            const auto t = ggml_backend_dev_type(d);
            if ((t == GGML_BACKEND_DEVICE_TYPE_GPU || t == GGML_BACKEND_DEVICE_TYPE_IGPU) && d != g_mali_dev) vk_devices[n++] = d;
        }
        vk_devices[n] = nullptr;
        mparams.devices = vk_devices;
        g_vk_dev = vk_devices[0];
    } else {
        // weights stay on the CPU side (read into RAM: the GPU wraps them without copying, which
        // isn't safe for a read-only mmap), in plain ggml layout (no repack); big batches offload
        mali_devices[0] = g_mali_dev;
        mali_devices[1] = nullptr;
        mparams.devices         = mali_devices;
        mparams.n_gpu_layers    = 0;
        mparams.use_extra_bufts = false;
        mparams.load_mode       = LLAMA_LOAD_MODE_NONE;
    }
    if (fd >= 0) {
        const int own_fd = dup(fd);
        g_model_fp = own_fd >= 0 ? fdopen(own_fd, "rb") : nullptr;
        if (!g_model_fp) {
            if (own_fd >= 0) close(own_fd);
            return to_jstring(env, "Could not open the model file");
        }
        g_model = llama_model_load_from_file_ptr(g_model_fp, mparams);
    } else {
        const std::string model_path = jstr(env, jmodel);
        g_model = llama_model_load_from_file(model_path.c_str(), mparams);
    }
    if (!g_model) {
        unload_all();
        return to_jstring(env, "Failed to load model (is it a valid GGUF?)");
    }
    g_vocab = llama_model_get_vocab(g_model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t) n_ctx;
    cparams.n_batch         = (uint32_t) g_n_batch;
    cparams.n_ubatch        = (uint32_t) g_n_batch;
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.op_offload      = gpu_mode != 0;
    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        unload_all();
        return to_jstring(env, "Failed to create context (out of memory? try a smaller context size)");
    }

    g_use_ckpts = llama_model_is_recurrent(g_model) || llama_model_is_hybrid(g_model) || llama_model_n_swa(g_model) > 0;
    g_tmpls = common_chat_templates_init(g_model, "");
    g_batch = common_batch(g_ctx);
    g_sampler = common_sampler_init(g_model, g_sparams);
    g_n_past = 0;
    g_history.clear();
    LOGI("Model loaded. n_ctx=%d threads=%d repack=%d", n_ctx, n_threads, (int) repack);
    return nullptr;
}

// Loads the vision projector (mmproj) for the already loaded model.
// Returns null on success, an error message otherwise (the language model stays loaded).
JNIEXPORT jstring JNICALL JNI_FN(loadProjector)(JNIEnv * env, jobject, jstring jpath, jint n_threads) {
    if (!g_model) return to_jstring(env, "No model loaded");
    if (g_mtmd) { mtmd_free(g_mtmd); g_mtmd = nullptr; }
    const std::string path = jstr(env, jpath);
    mtmd_context_params mp = mtmd_context_params_default();
    mp.use_gpu       = g_use_gpu;
    if (g_gpu_mode == 2) mp.device = g_mali_dev; // not another GPU (e.g. Vulkan) the encoder might pick
    if (g_gpu_mode == 1) mp.device = g_vk_dev;   // and not the Mali backend in Vulkan mode
    mp.n_threads     = n_threads;
    mp.print_timings = true;
    mp.warmup        = false;
    // images must fit in one ubatch for non-causal models
    mp.image_max_tokens = g_n_batch;
    mp.image_min_tokens = std::min(mp.image_min_tokens, g_n_batch);
    g_mtmd = mtmd_init_from_file(path.c_str(), g_model, mp);
    if (!g_mtmd) {
        return to_jstring(env, "Failed to load the mmproj (vision projector) file. "
                               "Make sure it belongs to the same model.");
    }
    return nullptr;
}

JNIEXPORT void JNICALL JNI_FN(unload)(JNIEnv *, jobject) {
    unload_all();
}

// bit 0: vision, bit 1: audio, bit 2: video (mtmd suggests a video layout)
JNIEXPORT jint JNICALL JNI_FN(capabilities)(JNIEnv *, jobject) {
    if (!g_mtmd) return 0;
    jint caps = 0;
    if (mtmd_support_vision(g_mtmd)) caps |= 1 | 4; // any vision model can take sampled frames
    if (mtmd_support_audio(g_mtmd))  caps |= 2;
    return caps;
}

JNIEXPORT jstring JNICALL JNI_FN(modelDescription)(JNIEnv * env, jobject) {
    if (!g_model) return to_jstring(env, "");
    char buf[256];
    llama_model_desc(g_model, buf, sizeof(buf));
    return to_jstring(env, buf);
}

JNIEXPORT jstring JNICALL JNI_FN(mediaMarker)(JNIEnv * env, jobject) {
    return to_jstring(env, mtmd_default_marker());
}

JNIEXPORT void JNICALL JNI_FN(setSampling)(JNIEnv *, jobject, jfloat temp, jfloat top_p, jint top_k,
                                           jfloat min_p, jint max_tokens) {
    g_sparams.temp  = temp;
    g_sparams.top_p = top_p;
    g_sparams.top_k = top_k;
    g_sparams.min_p = min_p;
    g_max_gen       = max_tokens;
    if (g_model) {
        free_sampler();
        g_sampler = common_sampler_init(g_model, g_sparams);
    }
}

JNIEXPORT void JNICALL JNI_FN(setSystemPrompt)(JNIEnv * env, jobject, jstring s) {
    g_system_prompt = jstr(env, s);
}

// [template, fps, n_frames, width, height]; zeros mean "no preference"
JNIEXPORT jfloatArray JNICALL JNI_FN(videoConfig)(JNIEnv * env, jobject, jint w, jint h, jint n_frames, jfloat fps) {
    float out[5] = {0, 0, 0, 0, 0};
    if (g_mtmd) {
        mtmd_video_config cfg = mtmd_get_video_config(g_mtmd, w, h, n_frames, fps);
        out[0] = (float) cfg.tmpl;
        out[1] = cfg.fps;
        out[2] = (float) cfg.n_frames;
        out[3] = (float) cfg.frame_width;
        out[4] = (float) cfg.frame_height;
    }
    jfloatArray arr = env->NewFloatArray(5);
    env->SetFloatArrayRegion(arr, 0, 5, out);
    return arr;
}

JNIEXPORT jboolean JNICALL JNI_FN(addImage)(JNIEnv * env, jobject, jbyteArray rgb, jint w, jint h) {
    if (!g_mtmd) return JNI_FALSE;
    const jsize len = env->GetArrayLength(rgb);
    if (len != w * h * 3) return JNI_FALSE;
    auto * data = (unsigned char *) env->GetByteArrayElements(rgb, nullptr);
    auto m = std::make_unique<pending_media>();
    m->bitmap = mtmd_bitmap_init((uint32_t) w, (uint32_t) h, data);
    env->ReleaseByteArrayElements(rgb, (jbyte *) data, JNI_ABORT);
    mtmd_bitmap_set_id(m->bitmap, ("img" + std::to_string(++g_image_counter)).c_str());
    g_media.push_back(std::move(m));
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL JNI_FN(addVideo)(JNIEnv * env, jobject, jbyteArray frames, jint w, jint h,
                                            jfloatArray timestamps, jint tmpl) {
    if (!g_mtmd) return JNI_FALSE;
    const jsize n = env->GetArrayLength(timestamps);
    if (n <= 0 || env->GetArrayLength(frames) != (jsize) ((size_t) w * h * 3 * n)) return JNI_FALSE;

    auto v = std::make_unique<video_source>();
    v->w = (uint32_t) w;
    v->h = (uint32_t) h;
    v->n = n;
    v->tmpl = (mtmd_video_template) tmpl;
    v->id = "video" + std::to_string(++g_video_counter);
    v->frames.resize((size_t) w * h * 3 * n);
    env->GetByteArrayRegion(frames, 0, (jsize) v->frames.size(), reinterpret_cast<jbyte *>(v->frames.data()));
    v->ts.resize(n);
    env->GetFloatArrayRegion(timestamps, 0, n, v->ts.data());

    auto m = std::make_unique<pending_media>();
    m->bitmap = mtmd_bitmap_init_lazy(g_mtmd, v->id.c_str(), v.get(),
        [](size_t, void * ud, mtmd_bitmap ** out_bitmap, char ** out_text) -> int {
            return static_cast<video_source *>(ud)->read_next(out_bitmap, out_text);
        });
    mtmd_bitmap_set_is_video(m->bitmap, true);
    m->video = std::move(v);
    g_media.push_back(std::move(m));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL JNI_FN(clearMedia)(JNIEnv *, jobject) {
    g_media.clear();
}

// "<think>reasoning</think>answer" -> reasoning_content + content, so templates can render
// (or drop) the reasoning of earlier turns the way the model was trained.
static common_chat_msg make_assistant_msg(const std::string & raw) {
    common_chat_msg msg;
    msg.role = "assistant";
    const size_t close = raw.find("</think>");
    if (close == std::string::npos) {
        msg.content = raw;
        return msg;
    }
    std::string reasoning = raw.substr(0, close);
    const size_t open = reasoning.find("<think>");
    if (open != std::string::npos) reasoning.erase(0, open + 7);
    std::string content = raw.substr(close + 8);
    auto trim = [](std::string & x) {
        x.erase(0, x.find_first_not_of(" \n\r\t") == std::string::npos ? x.size() : x.find_first_not_of(" \n\r\t"));
        const size_t e = x.find_last_not_of(" \n\r\t");
        x.erase(e == std::string::npos ? 0 : e + 1);
    };
    trim(reasoning);
    trim(content);
    msg.reasoning_content = reasoning;
    msg.content           = content;
    return msg;
}

static bool same_unit(const cache_unit & a, const cache_unit & b) {
    if (a.tok != LLAMA_TOKEN_NULL || b.tok != LLAMA_TOKEN_NULL) return a.tok == b.tok;
    return !a.media_id.empty() && a.media_id == b.media_id && a.n_pos == b.n_pos;
}

// Adds a user turn and evaluates it.
// The text must contain one media marker per media added with addImage/addVideo.
// The whole conversation is rendered with the chat template (robust to templates that render
// earlier turns differently depending on what follows, e.g. Qwen3.5 stripping reasoning), then
// only the units after the longest prefix already in the KV cache are evaluated.
// Returns null on success, an error message otherwise.
JNIEXPORT jstring JNICALL JNI_FN(prompt)(JNIEnv * env, jobject, jstring jtext) {
    if (!g_ctx) return to_jstring(env, "No model loaded");

    const size_t media_before = g_chat_media.size();
    for (auto & m : g_media) g_chat_media.push_back(std::move(m));
    g_media.clear();
    auto rollback_media = [&]() { g_chat_media.resize(media_before); };

    // --- render the whole conversation
    std::vector<common_chat_msg> msgs = g_history;
    if (msgs.empty() && !g_system_prompt.empty()) {
        common_chat_msg sys;
        sys.role    = "system";
        sys.content = g_system_prompt;
        msgs.push_back(std::move(sys));
    }
    {
        common_chat_msg msg;
        msg.role    = "user";
        msg.content = jstr(env, jtext);
        msgs.push_back(std::move(msg));
    }
    std::string formatted;
    std::string formatted_nogen;
    try {
        common_chat_templates_inputs inputs;
        inputs.use_jinja             = true;
        inputs.add_generation_prompt = true;
        inputs.enable_thinking       = g_enable_thinking;
        inputs.messages              = msgs;
        formatted = common_chat_templates_apply(g_tmpls.get(), inputs).prompt.str();
        if (g_use_ckpts) {
            inputs.add_generation_prompt = false;
            formatted_nogen = common_chat_templates_apply(g_tmpls.get(), inputs).prompt.str();
        }
    } catch (const std::exception & e) {
        rollback_media();
        return to_jstring(env, std::string("Chat template error: ") + e.what());
    }

    // --- tokenize into units
    std::vector<cache_unit> units;
    mtmd_input_chunks * chunks = nullptr;
    auto free_chunks = [&]() { if (chunks) { mtmd_input_chunks_free(chunks); chunks = nullptr; } };
    llama_pos pos = 0;
    if (g_mtmd) {
        std::vector<const mtmd_bitmap *> bitmaps;
        for (auto & m : g_chat_media) {
            if (m->video) m->video->reset();
            bitmaps.push_back(m->bitmap);
        }
        mtmd_input_text txt{formatted.c_str(), formatted.size(), /*add_special*/ true, /*parse_special*/ true};
        chunks = mtmd_input_chunks_init();
        const int32_t res = mtmd_tokenize(g_mtmd, chunks, &txt, bitmaps.data(), bitmaps.size());
        if (res != 0) {
            free_chunks();
            rollback_media();
            return to_jstring(env, res == 1 ? "Media / marker count mismatch" : "Failed to preprocess media");
        }
        for (size_t i = 0; i < mtmd_input_chunks_size(chunks); i++) {
            const mtmd_input_chunk * c = mtmd_input_chunks_get(chunks, i);
            if (mtmd_input_chunk_get_type(c) == MTMD_INPUT_CHUNK_TYPE_TEXT) {
                size_t n = 0;
                const llama_token * toks = mtmd_input_chunk_get_tokens_text(c, &n);
                for (size_t j = 0; j < n; j++) units.push_back({toks[j], {}, pos++, 1, nullptr});
            } else {
                const char * id = mtmd_input_chunk_get_id(c);
                cache_unit u;
                u.media_id = id ? id : "";
                u.pos      = pos;
                u.n_pos    = mtmd_input_chunk_get_n_pos(c);
                u.chunk    = c;
                pos += u.n_pos;
                units.push_back(std::move(u));
            }
        }
    } else {
        for (llama_token t : common_tokenize(g_ctx, formatted, true, true)) units.push_back({t, {}, pos++, 1, nullptr});
    }
    if (units.empty()) {
        free_chunks();
        rollback_media();
        return to_jstring(env, "Empty prompt");
    }
    if (pos + 8 > (llama_pos) llama_n_ctx(g_ctx)) {
        free_chunks();
        rollback_media();
        return to_jstring(env, "Context is full (" + std::to_string(pos) + " tokens needed). "
                               "Start a new chat or raise the context size.");
    }

    // --- reuse the longest common prefix of the KV cache
    size_t k = 0;
    while (k < g_cache.size() && k < units.size() && same_unit(g_cache[k], units[k])) k++;
    if (k == units.size()) k--;                           // always re-evaluate the last unit for logits
    const size_t k_match = k;
    llama_memory_t mem = llama_get_memory(g_ctx);
    if (!llama_memory_seq_rm(mem, 0, units[k].pos, -1)) {
        // the memory can't drop a partial range (recurrent / hybrid / SWA): go back to the latest
        // checkpoint inside the shared prefix, or start over
        bool restored = false;
        for (auto it = g_ckpts.rbegin(); it != g_ckpts.rend() && !restored; ++it) {
            if (it->n_units == 0 || it->n_units > k) continue;
            if (llama_state_seq_set_data_ext(g_ctx, it->data.data(), it->data.size(), 0,
                                             LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) == it->data.size() &&
                llama_memory_seq_rm(mem, 0, it->pos_end, -1)) {
                k = it->n_units;
                restored = true;
            }
        }
        if (!restored) {
            // media embeddings are cached, so at least the vision encoder doesn't run again
            llama_memory_clear(mem, true);
            k = 0;
        }
    }
    g_ckpts.erase(std::remove_if(g_ckpts.begin(), g_ckpts.end(),
                                 [&](const checkpoint & c) { return c.n_units > k; }),
                  g_ckpts.end());
    LOGI("prompt: %zu units, prefix match %zu, reusing %zu, evaluating %zu",
         units.size(), k_match, k, units.size() - k);

    // where to save the next checkpoint: just before the generation prompt, which the next turn
    // usually renders differently (e.g. thinking tags)
    size_t n_ckpt = 0;
    if (g_use_ckpts) {
        size_t tail = 16;
        if (!formatted_nogen.empty() && formatted.compare(0, formatted_nogen.size(), formatted_nogen) == 0) {
            tail = common_tokenize(g_vocab, formatted.substr(formatted_nogen.size()), false, true).size() + 2;
        }
        if (units.size() > tail && units.size() - tail > k) n_ckpt = units.size() - tail;
    }

    // progress: media weigh more than text (vision encoder + their tokens)
    {
        int64_t total = 0;
        for (size_t j = k; j < units.size(); j++) {
            total += units[j].tok == LLAMA_TOKEN_NULL ? 4 * (int64_t) mtmd_input_chunk_get_n_tokens(units[j].chunk) : 1;
        }
        g_prog_done  = 0;
        g_prog_total = std::max<int64_t>(total, 1);
    }

    // --- evaluate the rest
    int32_t res = 0;
    size_t i = k;
    while (i < units.size() && res == 0) {
        if (n_ckpt > 0 && i == n_ckpt) {
            checkpoint c;
            c.n_units = n_ckpt;
            c.pos_end = units[n_ckpt].pos;
            c.data.resize(llama_state_seq_get_size_ext(g_ctx, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY));
            if (!c.data.empty() &&
                llama_state_seq_get_data_ext(g_ctx, c.data.data(), c.data.size(), 0,
                                             LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) == c.data.size()) {
                LOGI("checkpoint at unit %zu (pos %d), %.1f MiB", c.n_units, c.pos_end, c.data.size() / 1048576.0);
                g_ckpts.push_back(std::move(c));
                if (g_ckpts.size() > MAX_CKPTS) g_ckpts.erase(g_ckpts.begin());
            }
            n_ckpt = 0;
        }
        if (units[i].tok == LLAMA_TOKEN_NULL) {
            const cache_unit & u = units[i];
            float * embd = nullptr;
            auto it = u.media_id.empty() ? g_embd_cache.end() : g_embd_cache.find(u.media_id);
            if (it != g_embd_cache.end()) {
                embd = it->second.data();
            } else {
                res = mtmd_encode_chunk(g_mtmd, u.chunk);
                if (res != 0) break;
                embd = mtmd_get_output_embd(g_mtmd);
                if (!u.media_id.empty()) {
                    const size_t n = (size_t) llama_model_n_embd_inp(g_model) * mtmd_input_chunk_get_n_tokens(u.chunk);
                    auto & v = g_embd_cache[u.media_id];
                    v.assign(embd, embd + n);
                    embd = v.data();
                }
            }
            llama_pos new_n_past = u.pos;
            res = mtmd_helper_decode_image_chunk(g_mtmd, g_ctx, u.chunk, embd, u.pos, 0, g_n_batch,
                                                 &new_n_past, nullptr, nullptr);
            g_prog_done += 4 * (int64_t) mtmd_input_chunk_get_n_tokens(u.chunk);
            i++;
            continue;
        }
        g_batch.clear();
        int n = 0;
        while (i < units.size() && units[i].tok != LLAMA_TOKEN_NULL && n < g_n_batch && !(n > 0 && i == n_ckpt)) {
            g_batch.add(units[i].tok, units[i].pos, 0, i == units.size() - 1);
            i++;
            n++;
        }
        res = llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch.get());
        g_prog_done += n;
    }
    g_prog_total = 0;
    free_chunks();
    for (auto & u : units) u.chunk = nullptr;

    if (res != 0) {
        // the KV cache is now in an unknown state: start from scratch next time
        llama_memory_clear(mem, true);
        g_cache.clear();
        g_ckpts.clear();
        g_n_past = 0;
        rollback_media();
        return to_jstring(env, "Failed to evaluate the prompt (" + std::to_string(res) + ")");
    }

    {
        const size_t tail = formatted.size() > 200 ? formatted.size() - 200 : 0;
        const size_t o = formatted.rfind("<think>");
        const size_t c = formatted.rfind("</think>");
        g_opens_thinking = o != std::string::npos && o >= tail && (c == std::string::npos || c < o);
    }
    g_cache   = std::move(units);
    g_n_past  = pos;
    g_history = std::move(msgs);
    g_n_gen   = 0;
    g_utf8_buf.clear();
    g_assistant_text.clear();
    return nullptr;
}

// Returns the next piece of text, "" if the token only produced part of a UTF-8 character,
// or null when generation is over (end-of-generation token, length limit or full context).
JNIEXPORT jstring JNICALL JNI_FN(nextToken)(JNIEnv * env, jobject) {
    if (!g_ctx || g_n_gen >= g_max_gen || g_n_past + 1 >= (llama_pos) llama_n_ctx(g_ctx)) {
        return nullptr;
    }
    const llama_token tok = common_sampler_sample(g_sampler, g_ctx, -1);
    common_sampler_accept(g_sampler, tok, true);
    if (llama_vocab_is_eog(g_vocab, tok)) {
        return nullptr;
    }
    g_n_gen++;

    g_cache.push_back({tok, {}, g_n_past, 1, nullptr});
    g_batch.clear();
    g_batch.add(tok, g_n_past++, 0, true);
    if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch.get()) != 0) {
        LOGE("decode failed");
        return nullptr;
    }

    g_utf8_buf += common_token_to_piece(g_ctx, tok);
    const size_t n = utf8_complete_prefix(g_utf8_buf);
    std::string out = g_utf8_buf.substr(0, n);
    g_utf8_buf.erase(0, n);
    g_assistant_text += out;
    return to_jstring(env, out);
}

// Commits the generated text to the chat history so the next turn is formatted correctly.
JNIEXPORT void JNICALL JNI_FN(finishTurn)(JNIEnv *, jobject) {
    if (!g_ctx) return;
    g_assistant_text += g_utf8_buf;
    g_utf8_buf.clear();
    g_history.push_back(make_assistant_msg((g_opens_thinking ? "<think>" : "") + g_assistant_text));
    g_assistant_text.clear();
    // the closing tokens (e.g. <|im_end|>) are added when the next turn re-renders the conversation
    common_sampler_reset(g_sampler);
}

JNIEXPORT void JNICALL JNI_FN(resetChat)(JNIEnv *, jobject) {
    g_history.clear();
    g_media.clear();
    g_chat_media.clear();
    g_cache.clear();
    g_embd_cache.clear();
    g_ckpts.clear();
    g_n_past = 0;
    g_utf8_buf.clear();
    g_assistant_text.clear();
    if (g_ctx) llama_memory_clear(llama_get_memory(g_ctx), true);
    if (g_sampler) common_sampler_reset(g_sampler);
}

// 0..1 while a prompt is being processed, -1 otherwise. Safe to call from any thread.
JNIEXPORT jfloat JNICALL JNI_FN(promptProgress)(JNIEnv *, jobject) {
    const int64_t total = g_prog_total.load();
    return total <= 0 ? -1.0f : (float) std::min<int64_t>(g_prog_done.load(), total) / (float) total;
}

JNIEXPORT void JNICALL JNI_FN(setThinking)(JNIEnv *, jobject, jboolean enabled) {
    g_enable_thinking = enabled;
}

// true when the last prompt left a <think> block open, i.e. the reply starts with reasoning
JNIEXPORT jboolean JNICALL JNI_FN(promptOpensThinking)(JNIEnv *, jobject) {
    return g_opens_thinking;
}

// Restores a saved conversation without evaluating it; the next prompt() renders and evaluates
// everything. Media added with addImage/addVideo before this call belong to the restored user
// messages, in order. Assistant contents are raw replies ("<think>..</think>answer" allowed).
JNIEXPORT void JNICALL JNI_FN(restoreChat)(JNIEnv * env, jobject, jobjectArray roles, jobjectArray contents) {
    g_history.clear();
    g_chat_media.clear();
    g_cache.clear();
    g_ckpts.clear();
    g_n_past = 0;
    if (g_ctx) llama_memory_clear(llama_get_memory(g_ctx), true);
    if (g_sampler) common_sampler_reset(g_sampler);
    if (!g_system_prompt.empty()) {
        common_chat_msg sys;
        sys.role    = "system";
        sys.content = g_system_prompt;
        g_history.push_back(std::move(sys));
    }
    const jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; i++) {
        auto jr = (jstring) env->GetObjectArrayElement(roles, i);
        auto jc = (jstring) env->GetObjectArrayElement(contents, i);
        const std::string role = jstr(env, jr), content = jstr(env, jc);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
        if (role == "assistant") {
            g_history.push_back(make_assistant_msg(content));
        } else {
            common_chat_msg m;
            m.role    = role;
            m.content = content;
            g_history.push_back(std::move(m));
        }
    }
    for (auto & m : g_media) g_chat_media.push_back(std::move(m));
    g_media.clear();
}

// Benchmark of the loaded model, like llama-bench: prompt processing of pp tokens (one batch run,
// as for reading a message / image) and generation of tg tokens one by one, with n_threads.
// Returns [prompt tok/s, generation tok/s] or null. Clears the KV cache: the caller restores the
// conversation afterwards. The context keeps n_threads until the next call / reload.
JNIEXPORT jfloatArray JNICALL JNI_FN(bench)(JNIEnv * env, jobject, jint pp, jint tg, jint n_threads) {
    if (!g_ctx || pp < 1 || tg < 1) return nullptr;
    llama_set_n_threads(g_ctx, n_threads, n_threads);
    llama_memory_t mem = llama_get_memory(g_ctx);
    auto reset = [&]() {
        llama_memory_clear(mem, true);
        g_cache.clear();
        g_ckpts.clear();
        g_n_past = 0;
    };
    reset();
    const int32_t n_vocab = llama_vocab_n_tokens(g_vocab);
    uint32_t rng = 1234567u;
    auto next_token = [&]() { rng = rng * 1664525u + 1013904223u; return (llama_token) (rng % (uint32_t) n_vocab); };

    // prompt processing
    const int64_t t0 = ggml_time_us();
    for (int i = 0; i < pp; i += g_n_batch) {
        g_batch.clear();
        const int n = std::min(g_n_batch, pp - i);
        for (int j = 0; j < n; j++) g_batch.add(next_token(), i + j, 0, i + j == pp - 1);
        if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch.get()) != 0) { reset(); return nullptr; }
    }
    llama_synchronize(g_ctx);
    const int64_t t1 = ggml_time_us();

    // generation, one token at a time
    for (int i = 0; i < tg; i++) {
        g_batch.clear();
        g_batch.add(next_token(), pp + i, 0, true);
        if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch.get()) != 0) { reset(); return nullptr; }
    }
    llama_synchronize(g_ctx);
    const int64_t t2 = ggml_time_us();
    reset();

    const float out[2] = {
        (float) (pp * 1e6 / std::max<int64_t>(t1 - t0, 1)),
        (float) (tg * 1e6 / std::max<int64_t>(t2 - t1, 1)),
    };
    jfloatArray arr = env->NewFloatArray(2);
    env->SetFloatArrayRegion(arr, 0, 2, out);
    return arr;
}

JNIEXPORT void JNICALL JNI_FN(setThreads)(JNIEnv *, jobject, jint n_threads) {
    if (g_ctx) llama_set_n_threads(g_ctx, n_threads, n_threads);
}

// [used, total]
JNIEXPORT jintArray JNICALL JNI_FN(contextUsage)(JNIEnv * env, jobject) {
    jint out[2] = {(jint) g_n_past, g_ctx ? (jint) llama_n_ctx(g_ctx) : 0};
    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, out);
    return arr;
}

} // extern "C"
