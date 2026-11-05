package io.github.harryl0l.llamachat.ui.models

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
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
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import io.github.harryl0l.llamachat.data.LinkedFolder
import io.github.harryl0l.llamachat.data.LocalModel
import io.github.harryl0l.llamachat.data.MODEL_PRESETS
import io.github.harryl0l.llamachat.data.ModelPreset
import io.github.harryl0l.llamachat.engine.EngineState
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
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::linkFolder)
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
                    onDownload = { vm.download(preset) },
                    onUse = { vm.loadPreset(preset); waitingForLoad = true },
                )
            }

            item { SectionTitle("Your files") }
            item {
                LocalModels(
                    models = vm.localModels.filterNot { it.isProjector },
                    projectors = vm.localModels.filter { it.isProjector },
                    folders = vm.linkedFolders,
                    settings = settings,
                    importProgress = vm.importProgress,
                    onImport = { importer.launch(arrayOf("*/*")) },
                    onLinkFolder = { folderPicker.launch(null) },
                    onUnlinkFolder = vm::unlinkFolder,
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
    models: List<LocalModel>,
    projectors: List<LocalModel>,
    folders: List<LinkedFolder>,
    settings: AppSettings,
    importProgress: Float?,
    onImport: () -> Unit,
    onLinkFolder: () -> Unit,
    onUnlinkFolder: (String) -> Unit,
    onLoad: (String, String?) -> Unit,
    onDelete: (LocalModel) -> Unit,
) {
    val context = LocalContext.current
    var model by remember(models, settings.modelPath) {
        mutableStateOf(models.firstOrNull { it.ref == settings.modelPath } ?: models.firstOrNull())
    }
    var projector by remember(projectors, settings.mmprojPath) {
        mutableStateOf(projectors.firstOrNull { it.ref == settings.mmprojPath })
    }
    fun supporting(m: LocalModel) = Formatter.formatShortFileSize(context, m.size) +
        (m.folder?.let { " · in $it (used in place)" } ?: " · app storage")
    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                "Link a folder to use the GGUFs in it directly (no copy, saves storage), or import a file " +
                    "into the app. For images & video also pick the matching mmproj-*.gguf.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (folders.isNotEmpty()) {
                Label("Linked folders")
                Row(
                    Modifier.padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    folders.forEach { f ->
                        InputChip(
                            selected = false,
                            onClick = {},
                            label = { Text(f.name) },
                            leadingIcon = { Icon(Icons.Rounded.Folder, null, Modifier.size(InputChipDefaults.IconSize)) },
                            trailingIcon = {
                                IconButton(onClick = { onUnlinkFolder(f.uri) }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Rounded.Close, "Unlink ${f.name}", Modifier.size(16.dp))
                                }
                            },
                        )
                    }
                }
            }
            if (models.isNotEmpty()) {
                Label("Language model")
                models.forEach { m ->
                    FileRow(m.name, model == m, supporting(m), { model = m }, if (m.inLinkedFolder) null else ({ onDelete(m) }))
                }
            }
            if (projectors.isNotEmpty()) {
                Label("Vision projector")
                FileRow("None", projector == null, "Text only", { projector = null }, null)
                projectors.forEach { p ->
                    FileRow(p.name, projector == p, supporting(p), { projector = p }, if (p.inLinkedFolder) null else ({ onDelete(p) }))
                }
            }
            if (importProgress != null) {
                LinearWavyProgressIndicator(
                    progress = { importProgress },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            FlowRow(
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onLinkFolder, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Link folder")
                }
                OutlinedButton(onClick = onImport, enabled = importProgress == null, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.FileOpen, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Import copy")
                }
                val m = model
                if (m != null) {
                    Button(onClick = { onLoad(m.ref, projector?.ref) }, shapes = ButtonDefaults.shapes()) { Text("Load") }
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
private fun FileRow(name: String, selected: Boolean, supporting: String, onSelect: () -> Unit, onDelete: (() -> Unit)?) {
    ListItem(
        headlineContent = { Text(name) },
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
            ListItem(
                headlineContent = { Text("Load last model on startup") },
                supportingContent = { Text("Off: the app only loads a model when you tap Load.") },
                trailingContent = {
                    Switch(
                        checked = settings.autoLoad,
                        onCheckedChange = { on -> vm.updateSettings(reloadNeeded = false) { it.copy(autoLoad = on) } },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
            ListItem(
                headlineContent = { Text("Repack weights for speed") },
                supportingContent = {
                    Text(
                        "Faster on ARM, but the weights are copied into RAM instead of read from the file. " +
                            "Turn off for large models that run out of memory or load slowly."
                    )
                },
                trailingContent = {
                    Switch(
                        checked = settings.repack,
                        onCheckedChange = { on -> vm.updateSettings(reloadNeeded = true) { it.copy(repack = on) } },
                        thumbContent = {
                            Icon(
                                if (settings.repack) Icons.Rounded.Check else Icons.Rounded.Close, null,
                                Modifier.size(SwitchDefaults.IconSize),
                            )
                        },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.padding(horizontal = 0.dp),
            )
            SettingSlider("Max video frames", settings.maxVideoFrames.toFloat(), 4f..32f, 6, { "${it.roundToInt()}" }) { v ->
                vm.updateSettings(reloadNeeded = false) { it.copy(maxVideoFrames = (v / 4).roundToInt() * 4) }
            }
            Text("Video quality", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                listOf("Low", "Medium", "Model max").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = settings.videoQuality == i,
                        onClick = { vm.updateSettings(reloadNeeded = false) { it.copy(videoQuality = i) } },
                        shape = SegmentedButtonDefaults.itemShape(i, 3),
                    ) { Text(label) }
                }
            }
            Text(
                "Every frame goes through the vision encoder: higher quality and more frames are much " +
                    "slower and heat up the phone, especially with large models.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                "Changing context size, threads or repacking reloads the model.",
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
