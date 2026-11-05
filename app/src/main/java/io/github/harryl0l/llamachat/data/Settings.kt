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
    val linkedFolders: Set<String> = emptySet(),
    val contextSize: Int = 4096,
    val threads: Int = defaultThreads(),
    val maxVideoFrames: Int = 16,
    val sampling: SamplingConfig = SamplingConfig(),
    val systemPrompt: String = "You are a helpful assistant running fully offline on the user's phone.",
) {
    companion object {
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
    }
}

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object K {
        val model = stringPreferencesKey("model")
        val mmproj = stringPreferencesKey("mmproj")
        val ctx = intPreferencesKey("ctx")
        val threads = intPreferencesKey("threads")
        val frames = intPreferencesKey("frames")
        val temp = floatPreferencesKey("temp")
        val topP = floatPreferencesKey("top_p")
        val topK = intPreferencesKey("top_k")
        val minP = floatPreferencesKey("min_p")
        val maxTokens = intPreferencesKey("max_tokens")
        val system = stringPreferencesKey("system")
        val thinking = booleanPreferencesKey("thinking")
        val repack = booleanPreferencesKey("repack")
        val folders = stringSetPreferencesKey("folders")
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            modelPath = p[K.model],
            mmprojPath = p[K.mmproj],
            thinking = p[K.thinking] ?: d.thinking,
            repack = p[K.repack] ?: d.repack,
            linkedFolders = p[K.folders] ?: d.linkedFolders,
            contextSize = p[K.ctx] ?: d.contextSize,
            threads = p[K.threads] ?: d.threads,
            maxVideoFrames = p[K.frames] ?: d.maxVideoFrames,
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
            p[K.temp] = n.sampling.temperature
            p[K.topP] = n.sampling.topP
            p[K.topK] = n.sampling.topK
            p[K.minP] = n.sampling.minP
            p[K.maxTokens] = n.sampling.maxTokens
            p[K.system] = n.systemPrompt
            p[K.thinking] = n.thinking
            p[K.repack] = n.repack
            p[K.folders] = n.linkedFolders
        }
    }
}
