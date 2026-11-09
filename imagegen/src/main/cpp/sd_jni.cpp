// JNI bridge to stable-diffusion.cpp (text-to-image). Built into libimagegen.so together with
// stable-diffusion.cpp's own ggml, statically and with hidden symbols (see CMakeLists.txt).
//
// load / generate / unload must be called from one thread; progress() and cancel() may be called
// from any thread while generate() runs.

#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <string>

#include "stable-diffusion.h"

#define TAG "imagegen"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static sd_ctx_t *           g_ctx = nullptr;
static std::string          g_error;
static std::mutex           g_stage_mutex;
static std::string          g_stage;
static std::atomic<int64_t> g_done{0};
static std::atomic<int64_t> g_total{0};

static void log_cb(enum sd_log_level_t level, const char * text, void *) {
    if (level >= SD_LOG_WARN) {
        __android_log_write(level >= SD_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, TAG, text);
        if (level >= SD_LOG_ERROR) {
            std::lock_guard<std::mutex> lock(g_stage_mutex);
            g_error = text;
            while (!g_error.empty() && (g_error.back() == '\n' || g_error.back() == '\r')) g_error.pop_back();
        }
    } else if (level == SD_LOG_INFO) {
        __android_log_write(ANDROID_LOG_INFO, TAG, text);
    }
}

static void progress_cb(const sd_generation_progress_t * p, void *) {
    {
        std::lock_guard<std::mutex> lock(g_stage_mutex);
        g_stage = p->stage ? p->stage : "";
    }
    g_done  = p->completed;
    g_total = p->total;
}

static std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

#define JNI_FN(name) Java_io_github_harryl0l_imagegen_ImageGenNative_##name

extern "C" {

// Returns null on success, an error message otherwise.
// jmodel: a full checkpoint, or (with jllm) only the diffusion model of a model whose text encoder
// is a separate LLM (e.g. Anima + Qwen3-0.6B); its VAE is then replaced by the tiny decoder (jtaesd).
JNIEXPORT jstring JNICALL JNI_FN(load)(JNIEnv * env, jobject, jstring jmodel, jstring jllm, jstring jtaesd, jint n_threads) {
    if (g_ctx) { free_sd_ctx(g_ctx); g_ctx = nullptr; }
    sd_set_log_callback(log_cb, nullptr);
    g_error.clear();

    const std::string model = jstr(env, jmodel), llm = jstr(env, jllm), taesd = jstr(env, jtaesd);
    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    if (llm.empty()) {
        p.model_path = model.c_str();
    } else {
        p.diffusion_model_path = model.c_str();
        p.llm_path             = llm.c_str();
    }
    p.taesd_path  = taesd.empty() ? nullptr : taesd.c_str(); // tiny decoder: ~10x faster decoding
    p.n_threads   = n_threads;
    p.enable_mmap = true;
    g_ctx = new_sd_ctx(&p);
    if (!g_ctx) {
        return env->NewStringUTF(g_error.empty() ? "Failed to load the image model" : g_error.c_str());
    }
    if (!sd_ctx_supports_image_generation(g_ctx)) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        return env->NewStringUTF("This model can't generate images");
    }
    sd_set_generation_progress_callback(g_ctx, progress_cb, nullptr);
    LOGI("image model loaded: %s", model.c_str());
    return nullptr;
}

JNIEXPORT void JNICALL JNI_FN(unload)(JNIEnv *, jobject) {
    if (g_ctx) { free_sd_ctx(g_ctx); g_ctx = nullptr; }
}

// Returns RGB pixels (width * height * 3), or null on failure / cancellation (see lastError()).
// sampler: a sample_method_t (e.g. 1 = Euler A, 9 = LCM for LCM-distilled models)
// scheduler: a scheduler_t, or -1 for the model's default
JNIEXPORT jbyteArray JNICALL JNI_FN(generate)(JNIEnv * env, jobject, jstring jprompt, jstring jnegative,
                                               jint width, jint height, jint steps, jfloat cfg, jlong seed,
                                               jint sampler, jint scheduler) {
    if (!g_ctx) { g_error = "No image model loaded"; return nullptr; }
    const std::string prompt = jstr(env, jprompt), negative = jstr(env, jnegative);

    sd_cancel_generation(g_ctx, SD_CANCEL_RESET);
    g_error.clear();
    g_done = 0;
    g_total = steps;
    { std::lock_guard<std::mutex> lock(g_stage_mutex); g_stage = "preparing"; }

    sd_img_gen_params_t gp;
    sd_img_gen_params_init(&gp);
    gp.prompt          = prompt.c_str();
    gp.negative_prompt = negative.c_str();
    gp.width           = width;
    gp.height          = height;
    gp.seed            = seed;
    gp.batch_count     = 1;
    const auto method = (sampler >= 0 && sampler < SAMPLE_METHOD_COUNT) ? (sample_method_t) sampler : EULER_A_SAMPLE_METHOD;
    gp.sample_params.sample_method        = method;
    gp.sample_params.scheduler            = (scheduler >= 0 && scheduler < SCHEDULER_COUNT)
                                                ? (scheduler_t) scheduler : sd_get_default_scheduler(g_ctx, method);
    gp.sample_params.sample_steps         = steps;
    gp.sample_params.guidance.txt_cfg     = cfg;

    sd_image_generation_result_t * result = nullptr;
    const bool ok = generate_image(g_ctx, &gp, &result);
    { std::lock_guard<std::mutex> lock(g_stage_mutex); g_stage.clear(); }
    g_total = 0;
    if (!ok || !result || result->count < 1 || !result->items[0].image.data) {
        if (result) free_sd_image_generation_result(result);
        if (g_error.empty()) g_error = "Image generation failed or was cancelled";
        return nullptr;
    }

    const sd_image_t & img = result->items[0].image;
    const size_t n = (size_t) img.width * img.height;
    jbyteArray out = env->NewByteArray((jsize) (n * 3));
    if (img.channel == 3) {
        env->SetByteArrayRegion(out, 0, (jsize) (n * 3), (const jbyte *) img.data);
    } else {
        std::string rgb(n * 3, '\0');
        for (size_t i = 0; i < n; i++) memcpy(&rgb[i * 3], img.data + i * img.channel, 3);
        env->SetByteArrayRegion(out, 0, (jsize) rgb.size(), (const jbyte *) rgb.data());
    }
    free_sd_image_generation_result(result);
    return out;
}

// "stage|completed|total" while generating, "" otherwise
JNIEXPORT jstring JNICALL JNI_FN(progress)(JNIEnv * env, jobject) {
    std::string s;
    {
        std::lock_guard<std::mutex> lock(g_stage_mutex);
        if (g_stage.empty()) return env->NewStringUTF("");
        s = g_stage;
    }
    s += "|" + std::to_string(g_done.load()) + "|" + std::to_string(g_total.load());
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT void JNICALL JNI_FN(cancel)(JNIEnv *, jobject) {
    if (g_ctx) sd_cancel_generation(g_ctx, SD_CANCEL_ALL);
}

// Converts (quantizes) a model file to GGUF, streaming, tensor by tensor. type: an sd_type_t
// (e.g. 8 = Q8_0, 13 = Q5_K). Returns null on success, an error message otherwise.
JNIEXPORT jstring JNICALL JNI_FN(convert)(JNIEnv * env, jobject, jstring jin, jstring jout, jint type, jint n_threads) {
    sd_set_log_callback(log_cb, nullptr);
    { std::lock_guard<std::mutex> lock(g_stage_mutex); g_error.clear(); }
    const std::string in = jstr(env, jin), out = jstr(env, jout);
    const bool ok = convert_with_components(in.c_str(), "", "", "", "", "", out.c_str(), (sd_type_t) type, "", false,
                                            n_threads, nullptr, 0);
    if (ok) return nullptr;
    std::lock_guard<std::mutex> lock(g_stage_mutex);
    return env->NewStringUTF(g_error.empty() ? "Model conversion failed" : g_error.c_str());
}

JNIEXPORT jstring JNICALL JNI_FN(lastError)(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_stage_mutex);
    return env->NewStringUTF(g_error.c_str());
}

} // extern "C"
