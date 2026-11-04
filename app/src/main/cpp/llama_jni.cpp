// JNI bridge between the Kotlin app and llama.cpp (+ libmtmd for image / video input).
//
// All functions in this file must be called from ONE thread (the Kotlin side uses a
// single-threaded dispatcher). Nothing here is thread-safe on purpose: it keeps it simple.

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

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
static llama_context *            g_ctx     = nullptr;
static mtmd_context *             g_mtmd    = nullptr;
static common_sampler *           g_sampler = nullptr;
static common_chat_templates_ptr  g_tmpls;
static common_batch               g_batch;
static const llama_vocab *        g_vocab   = nullptr;

static std::vector<common_chat_msg>                 g_history;
static std::vector<std::unique_ptr<pending_media>>  g_media;
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
    g_history.clear();
    g_tmpls.reset();
    g_batch = common_batch();
    free_sampler();
    if (g_mtmd)  { mtmd_free(g_mtmd);        g_mtmd  = nullptr; }
    if (g_ctx)   { llama_free(g_ctx);        g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab  = nullptr;
    g_n_past = 0;
}

#define JNI_FN(name) Java_io_github_harryl0l_llamachat_engine_LlamaNative_##name

extern "C" {

JNIEXPORT void JNICALL JNI_FN(init)(JNIEnv * env, jobject, jstring native_lib_dir) {
    llama_log_set(android_log_cb, nullptr);
    mtmd_helper_log_set(android_log_cb, nullptr);
    const std::string dir = jstr(env, native_lib_dir);
    LOGI("Loading ggml backends from %s", dir.c_str());
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
}

JNIEXPORT jstring JNICALL JNI_FN(systemInfo)(JNIEnv * env, jobject) {
    return to_jstring(env, llama_print_system_info());
}

// returns null on success, an error message otherwise
JNIEXPORT jstring JNICALL JNI_FN(load)(JNIEnv * env, jobject, jstring jmodel, jstring jmmproj,
                                        jint n_ctx, jint n_threads) {
    unload_all();

    const std::string model_path  = jstr(env, jmodel);
    const std::string mmproj_path = jstr(env, jmmproj);

    llama_model_params mparams = llama_model_default_params();
    g_model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!g_model) {
        return to_jstring(env, "Failed to load model (is it a valid GGUF?)");
    }
    g_vocab = llama_model_get_vocab(g_model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t) n_ctx;
    cparams.n_batch         = (uint32_t) g_n_batch;
    cparams.n_ubatch        = (uint32_t) g_n_batch;
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads;
    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        unload_all();
        return to_jstring(env, "Failed to create context (out of memory? try a smaller context size)");
    }

    if (!mmproj_path.empty()) {
        mtmd_context_params mp = mtmd_context_params_default();
        mp.use_gpu       = false;
        mp.n_threads     = n_threads;
        mp.print_timings = true;
        mp.warmup        = false;
        // images must fit in one ubatch for non-causal models
        mp.image_max_tokens = g_n_batch;
        mp.image_min_tokens = std::min(mp.image_min_tokens, g_n_batch);
        g_mtmd = mtmd_init_from_file(mmproj_path.c_str(), g_model, mp);
        if (!g_mtmd) {
            unload_all();
            return to_jstring(env, "Failed to load the mmproj (vision projector) file. "
                                   "Make sure it belongs to the same model.");
        }
    }

    g_tmpls = common_chat_templates_init(g_model, "");
    g_batch = common_batch(g_ctx);
    g_sampler = common_sampler_init(g_model, g_sparams);
    g_n_past = 0;
    g_history.clear();
    LOGI("Model loaded. n_ctx=%d threads=%d mtmd=%s", n_ctx, n_threads, g_mtmd ? "yes" : "no");
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

// Formats the user message with the chat template, then evaluates it (text + media).
// The text must contain one media marker per media added with addImage/addVideo.
// Returns null on success, an error message otherwise.
JNIEXPORT jstring JNICALL JNI_FN(prompt)(JNIEnv * env, jobject, jstring jtext) {
    if (!g_ctx) return to_jstring(env, "No model loaded");

    const bool first_turn = g_history.empty();
    std::string formatted;
    try {
        if (first_turn && !g_system_prompt.empty()) {
            common_chat_msg sys;
            sys.role    = "system";
            sys.content = g_system_prompt;
            formatted += common_chat_format_single(g_tmpls.get(), g_history, sys, false, true);
            g_history.push_back(sys);
        }
        common_chat_msg msg;
        msg.role    = "user";
        msg.content = jstr(env, jtext);
        formatted += common_chat_format_single(g_tmpls.get(), g_history, msg, true, true);
        g_history.push_back(std::move(msg));
    } catch (const std::exception & e) {
        g_media.clear();
        return to_jstring(env, std::string("Chat template error: ") + e.what());
    }

    llama_pos new_n_past = g_n_past;
    int32_t   res;

    if (g_mtmd) {
        std::vector<const mtmd_bitmap *> bitmaps;
        for (auto & m : g_media) bitmaps.push_back(m->bitmap);

        mtmd_input_text txt{formatted.c_str(), formatted.size(), /*add_special*/ first_turn, /*parse_special*/ true};
        mtmd_input_chunks * chunks = mtmd_input_chunks_init();
        res = mtmd_tokenize(g_mtmd, chunks, &txt, bitmaps.data(), bitmaps.size());
        if (res != 0) {
            mtmd_input_chunks_free(chunks);
            g_media.clear();
            g_history.pop_back();
            return to_jstring(env, res == 1 ? "Media / marker count mismatch" : "Failed to preprocess media");
        }

        const llama_pos needed = mtmd_helper_get_n_pos(chunks);
        if (g_n_past + needed + 8 > (llama_pos) llama_n_ctx(g_ctx)) {
            mtmd_input_chunks_free(chunks);
            g_media.clear();
            g_history.pop_back();
            return to_jstring(env, "Context is full (" + std::to_string(needed) +
                                   " tokens needed). Start a new chat or raise the context size.");
        }

        res = mtmd_helper_eval_chunks(g_mtmd, g_ctx, chunks, g_n_past, 0, g_n_batch, true, &new_n_past);
        mtmd_input_chunks_free(chunks);
        g_media.clear();
    } else {
        llama_tokens toks = common_tokenize(g_ctx, formatted, first_turn, true);
        if (g_n_past + (llama_pos) toks.size() + 8 > (llama_pos) llama_n_ctx(g_ctx)) {
            g_history.pop_back();
            return to_jstring(env, "Context is full. Start a new chat or raise the context size.");
        }
        res = 0;
        for (size_t i = 0; i < toks.size() && res == 0; i += g_n_batch) {
            g_batch.clear();
            const size_t end = std::min(toks.size(), i + g_n_batch);
            for (size_t j = i; j < end; j++) {
                g_batch.add(toks[j], new_n_past++, 0, j == toks.size() - 1);
            }
            res = llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch.get());
        }
    }

    if (res != 0) {
        g_history.pop_back();
        return to_jstring(env, "Failed to evaluate the prompt (" + std::to_string(res) + ")");
    }

    g_n_past = new_n_past;
    g_n_gen  = 0;
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
    common_chat_msg msg;
    msg.role    = "assistant";
    msg.content = g_assistant_text;
    g_history.push_back(std::move(msg));
    g_assistant_text.clear();
    // the closing tokens (e.g. <|im_end|>) of the template are added by the next format call
    common_sampler_reset(g_sampler);
}

JNIEXPORT void JNICALL JNI_FN(resetChat)(JNIEnv *, jobject) {
    g_history.clear();
    g_media.clear();
    g_n_past = 0;
    g_utf8_buf.clear();
    g_assistant_text.clear();
    if (g_ctx) llama_memory_clear(llama_get_memory(g_ctx), true);
    if (g_sampler) common_sampler_reset(g_sampler);
}

// [used, total]
JNIEXPORT jintArray JNICALL JNI_FN(contextUsage)(JNIEnv * env, jobject) {
    jint out[2] = {(jint) g_n_past, g_ctx ? (jint) llama_n_ctx(g_ctx) : 0};
    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, out);
    return arr;
}

} // extern "C"
