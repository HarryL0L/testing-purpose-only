package io.github.harryl0l.llamachat.ui.models

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.harryl0l.llamachat.ChatViewModel
import io.github.harryl0l.llamachat.DownloadState
import io.github.harryl0l.llamachat.data.AppSettings
import io.github.harryl0l.llamachat.data.MODEL_PRESETS
import io.github.harryl0l.llamachat.data.ModelPreset
import io.github.harryl0l.llamachat.engine.EngineState
import java.io.File
import kotlin.math.roundToInt

@Composable
fun ModelsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val settings by vm.settings.collectAsStateWithLifecycle()
    val engineState by vm.engineState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::import)
    }
    LaunchedEffect(Unit) { vm.refreshLocalModels() }

    // leave the screen once a model finished loading from here
    var waitingForLoad by remember { mutableStateOf(false) }
    LaunchedEffect(engineState) {
        if (waitingForLoad && engineState is EngineState.Ready) onBack()
        if (engineState is EngineState.Failed) waitingForLoad = false
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Models") },
                subtitle = { Text("Everything runs offline with llama.cpp") },
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionTitle("Recommended vision models") }
            items(MODEL_PRESETS, key = { it.id }) { preset ->
                PresetCard(
                    preset = preset,
                    downloaded = vm.repo.isDownloaded(preset),
                    active = settings.modelPath == vm.repo.presetFiles(preset).first.absolutePath,
                    download = vm.downloads[preset.id],
                    onDownload = { vm.download(preset); waitingForLoad = true },
                    onUse = { vm.loadPreset(preset); waitingForLoad = true },
                )
            }

            item { SectionTitle("Your files") }
            item {
                LocalModels(
                    models = vm.localModels.filterNot { it.isProjector }.map { it.file },
                    projectors = vm.localModels.filter { it.isProjector }.map { it.file },
                    settings = settings,
                    importProgress = vm.importProgress,
                    onImport = { importer.launch(arrayOf("*/*")) },
                    onLoad = { m, p -> vm.load(m, p); waitingForLoad = true },
                    onDelete = vm::deleteModel,
                )
            }

            item { SectionTitle("Settings") }
            item { SettingsCard(settings, vm) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, start = 4.dp),
    )
}

@Composable
private fun PresetCard(
    preset: ModelPreset,
    downloaded: Boolean,
    active: Boolean,
    download: DownloadState?,
    onDownload: () -> Unit,
    onUse: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(preset.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        Formatter.formatShortFileSize(context, preset.sizeBytes) + " · image + video",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (active) Icon(Icons.Rounded.CheckCircle, "In use", tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.size(8.dp))
            Text(preset.summary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(16.dp))
            when {
                download is DownloadState.Running -> {
                    LinearWavyProgressIndicator(progress = { download.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.size(6.dp))
                    Text("Downloading… ${(download.progress * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                }
                downloaded -> Button(onClick = onUse, enabled = !active, shapes = ButtonDefaults.shapes()) {
                    Text(if (active) "In use" else "Use this model")
                }
                else -> {
                    if (download is DownloadState.Failed) {
                        Text(download.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.size(8.dp))
                    }
                    Button(onClick = onDownload, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(if (download is DownloadState.Failed) "Retry" else "Download")
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalModels(
    models: List<File>,
    projectors: List<File>,
    settings: AppSettings,
    importProgress: Float?,
    onImport: () -> Unit,
    onLoad: (File, File?) -> Unit,
    onDelete: (File) -> Unit,
) {
    val context = LocalContext.current
    var model by remember(models, settings.modelPath) {
        mutableStateOf(models.firstOrNull { it.absolutePath == settings.modelPath } ?: models.firstOrNull())
    }
    var projector by remember(projectors, settings.mmprojPath) {
        mutableStateOf(projectors.firstOrNull { it.absolutePath == settings.mmprojPath })
    }
    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                "Import any GGUF model. For images & video also import its matching mmproj-*.gguf projector.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (models.isNotEmpty()) {
                Label("Language model")
                models.forEach { f ->
                    FileRow(f, f == model, Formatter.formatShortFileSize(context, f.length()), { model = f }, { onDelete(f) })
                }
            }
            if (projectors.isNotEmpty()) {
                Label("Vision projector")
                FileRow(null, projector == null, "Text only", { projector = null }, null)
                projectors.forEach { f ->
                    FileRow(f, f == projector, Formatter.formatShortFileSize(context, f.length()), { projector = f }, { onDelete(f) })
                }
            }
            if (importProgress != null) {
                LinearWavyProgressIndicator(
                    progress = { importProgress },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onImport, enabled = importProgress == null, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.FileOpen, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Import .gguf")
                }
                val m = model
                if (m != null) {
                    Button(onClick = { onLoad(m, projector) }, shapes = ButtonDefaults.shapes()) { Text("Load") }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun FileRow(file: File?, selected: Boolean, supporting: String, onSelect: () -> Unit, onDelete: (() -> Unit)?) {
    ListItem(
        headlineContent = { Text(file?.name ?: "None") },
        supportingContent = { Text(supporting) },
        leadingContent = { RadioButton(selected = selected, onClick = onSelect) },
        trailingContent = onDelete?.let {
            { IconButton(onClick = it, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Delete, "Delete") } }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.clickable(onClick = onSelect),
    )
}

@Composable
private fun SettingsCard(settings: AppSettings, vm: ChatViewModel) {
    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingSlider("Context size", settings.contextSize.toFloat(), 1024f..16384f, 14, { "${it.roundToInt()} tokens" }) { v ->
                vm.updateSettings(reloadNeeded = true) { it.copy(contextSize = (v / 1024).roundToInt() * 1024) }
            }
            SettingSlider("CPU threads", settings.threads.toFloat(), 1f..8f, 6, { "${it.roundToInt()}" }) { v ->
                vm.updateSettings(reloadNeeded = true) { it.copy(threads = v.roundToInt()) }
            }
            SettingSlider("Max video frames", settings.maxVideoFrames.toFloat(), 4f..64f, 14, { "${it.roundToInt()}" }) { v ->
                vm.updateSettings(reloadNeeded = false) { it.copy(maxVideoFrames = (v / 4).roundToInt() * 4) }
            }
            SettingSlider("Temperature", settings.sampling.temperature, 0f..2f, 19, { "%.1f".format(it) }) { v ->
                vm.updateSettings(reloadNeeded = false) { it.copy(sampling = it.sampling.copy(temperature = v)) }
            }
            SettingSlider("Max reply length", settings.sampling.maxTokens.toFloat(), 128f..4096f, 30, { "${it.roundToInt()} tokens" }) { v ->
                vm.updateSettings(reloadNeeded = false) { it.copy(sampling = it.sampling.copy(maxTokens = (v / 128).roundToInt() * 128)) }
            }
            var system by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
            OutlinedTextField(
                value = system,
                onValueChange = { system = it },
                label = { Text("System prompt (applies to new chats)") },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            )
            if (system != settings.systemPrompt) {
                Button(
                    onClick = { vm.updateSettings(reloadNeeded = false) { it.copy(systemPrompt = system) } },
                    shapes = ButtonDefaults.shapes(),
                ) { Text("Save") }
            }
            Text(
                "Changing context size or threads reloads the model.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(format(current), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onCommit(current) },
            valueRange = range,
            steps = steps,
        )
    }
}
