package io.github.harryl0l.llamachat.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.harryl0l.llamachat.engine.SamplingConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    /** model / projector reference: an absolute path or a content:// URI from a linked folder */
    val modelPath: String? = null,
    val mmprojPath: String? = null,
    val thinking: Boolean = true,
    /** CPU weight repacking: faster, but large models need more RAM (weights leave the mmapped file) */
    val repack: Boolean = true,
    /** load the last used model when the app starts (off: only load when the user asks) */
    val autoLoad: Boolean = false,
    /** image creation: preset id, steps (0 = model default), size, negative prompt */
    val imageModel: String? = null,
    val imageSteps: Int = 0,
    val imageSize: Int = 512,
    val imageNegative: String = "blurry, low quality, deformed, watermark, text",
    /** 0 = model default */
    val imageCfg: Float = 0f,
    /** -1 = model default, else a stable-diffusion.cpp sample_method_t */
    val imageSampler: Int = -1,
    /** experimental build: 0 = CPU, 1 = Vulkan, 2 = Mali (OpenCL, prompt/image processing only) */
    val gpuMode: Int = 0,
    /** experimental build: id of a custom Vulkan driver (libadrenotools), null = system driver */
    val gpuDriver: String? = null,
    /** experimental build: Vulkan compatibility mode (risky driver features off) */
    val vulkanCompat: Boolean = true,
    val linkedFolders: Set<String> = emptySet(),
    val contextSize: Int = 4096,
    /** 0 = Auto: the fast cores (see CpuInfo) */
    val threads: Int = 0,
    val maxVideoFrames: Int = 8,
    /** 0 = low, 1 = medium, 2 = what the model recommends (can be very slow on a phone) */
    val videoQuality: Int = 0,
    /** 0 = low, 1 = medium, 2 = high (best for small text / documents, slowest) */
    val imageQuality: Int = 0,
    val sampling: SamplingConfig = SamplingConfig(),
    val systemPrompt: String = "You are a helpful assistant running fully offline on the user's phone.",
) {
    companion object {
        fun defaultThreads(): Int = CpuInfo.fastCores.coerceIn(1, 8)
    }
}

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object K {
        val model = stringPreferencesKey("model")
        val mmproj = stringPreferencesKey("mmproj")
        val ctx = intPreferencesKey("ctx")
        val threads = intPreferencesKey("threads")
        val frames = intPreferencesKey("video_frames")
        val videoQuality = intPreferencesKey("video_quality")
        val imageQuality = intPreferencesKey("image_quality")
        val temp = floatPreferencesKey("temp")
        val topP = floatPreferencesKey("top_p")
        val topK = intPreferencesKey("top_k")
        val minP = floatPreferencesKey("min_p")
        val maxTokens = intPreferencesKey("max_tokens")
        val system = stringPreferencesKey("system")
        val thinking = booleanPreferencesKey("thinking")
        val repack = booleanPreferencesKey("repack")
        val autoLoad = booleanPreferencesKey("auto_load")
        val imageModel = stringPreferencesKey("image_model")
        val imageSteps = intPreferencesKey("image_steps")
        val imageSize = intPreferencesKey("image_size")
        val imageNegative = stringPreferencesKey("image_negative")
        val imageCfg = floatPreferencesKey("image_cfg")
        val imageSampler = intPreferencesKey("image_sampler")
        val useGpu = booleanPreferencesKey("use_gpu") // old on/off switch (= Vulkan)
        val gpuMode = intPreferencesKey("gpu_mode")
        val gpuDriver = stringPreferencesKey("gpu_driver")
        val vulkanCompat = booleanPreferencesKey("vulkan_compat")
        val folders = stringSetPreferencesKey("folders")
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            modelPath = p[K.model],
            mmprojPath = p[K.mmproj],
            thinking = p[K.thinking] ?: d.thinking,
            repack = p[K.repack] ?: d.repack,
            autoLoad = p[K.autoLoad] ?: d.autoLoad,
            imageModel = p[K.imageModel],
            imageSteps = p[K.imageSteps] ?: d.imageSteps,
            imageSize = p[K.imageSize] ?: d.imageSize,
            imageNegative = p[K.imageNegative] ?: d.imageNegative,
            imageCfg = p[K.imageCfg] ?: d.imageCfg,
            imageSampler = p[K.imageSampler] ?: d.imageSampler,
            gpuMode = p[K.gpuMode] ?: if (p[K.useGpu] == true) 1 else d.gpuMode,
            gpuDriver = p[K.gpuDriver],
            vulkanCompat = p[K.vulkanCompat] ?: d.vulkanCompat,
            linkedFolders = p[K.folders] ?: d.linkedFolders,
            contextSize = p[K.ctx] ?: d.contextSize,
            threads = p[K.threads] ?: d.threads,
            maxVideoFrames = p[K.frames] ?: d.maxVideoFrames,
            videoQuality = p[K.videoQuality] ?: d.videoQuality,
            imageQuality = p[K.imageQuality] ?: d.imageQuality,
            sampling = SamplingConfig(
                temperature = p[K.temp] ?: d.sampling.temperature,
                topP = p[K.topP] ?: d.sampling.topP,
                topK = p[K.topK] ?: d.sampling.topK,
                minP = p[K.minP] ?: d.sampling.minP,
                maxTokens = p[K.maxTokens] ?: d.sampling.maxTokens,
            ),
            systemPrompt = p[K.system] ?: d.systemPrompt,
        )
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map(::read)

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val cur = read(p)
            val n = transform(cur)
            if (n.modelPath != null) p[K.model] = n.modelPath else p.remove(K.model)
            if (n.mmprojPath != null) p[K.mmproj] = n.mmprojPath else p.remove(K.mmproj)
            p[K.ctx] = n.contextSize
            p[K.threads] = n.threads
            p[K.frames] = n.maxVideoFrames
            p[K.videoQuality] = n.videoQuality
            p[K.imageQuality] = n.imageQuality
            p[K.temp] = n.sampling.temperature
            p[K.topP] = n.sampling.topP
            p[K.topK] = n.sampling.topK
            p[K.minP] = n.sampling.minP
            p[K.maxTokens] = n.sampling.maxTokens
            p[K.system] = n.systemPrompt
            p[K.thinking] = n.thinking
            p[K.repack] = n.repack
            p[K.autoLoad] = n.autoLoad
            if (n.imageModel != null) p[K.imageModel] = n.imageModel else p.remove(K.imageModel)
            p[K.imageSteps] = n.imageSteps
            p[K.imageSize] = n.imageSize
            p[K.imageNegative] = n.imageNegative
            p[K.imageCfg] = n.imageCfg
            p[K.imageSampler] = n.imageSampler
            p[K.gpuMode] = n.gpuMode
            p.remove(K.useGpu)
            if (n.gpuDriver != null) p[K.gpuDriver] = n.gpuDriver else p.remove(K.gpuDriver)
            p[K.vulkanCompat] = n.vulkanCompat
            p[K.folders] = n.linkedFolders
        }
    }
}

/** Threads actually used: the setting, or the fast cores for Auto (0). */
val AppSettings.effectiveThreads: Int get() = if (threads > 0) threads else AppSettings.defaultThreads()
