package io.github.harryl0l.llamachat.ui.models

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.TextButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import io.github.harryl0l.llamachat.BuildConfig
import io.github.harryl0l.llamachat.ChatViewModel
import io.github.harryl0l.llamachat.DownloadState
import io.github.harryl0l.llamachat.data.AppSettings
import io.github.harryl0l.llamachat.data.CpuInfo
import io.github.harryl0l.llamachat.data.effectiveThreads
import io.github.harryl0l.llamachat.data.IMAGE_PRESETS
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
    // download / import / link / load messages are raised while this screen is showing
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(vm.snackbar) {
        vm.snackbar?.let { snackbarHost.showSnackbar(it); vm.snackbar = null }
    }

    // leave the screen once a model finished loading from here
    var waitingForLoad by remember { mutableStateOf(false) }
    LaunchedEffect(engineState) {
        if (waitingForLoad && engineState is EngineState.Ready) onBack()
        if (engineState is EngineState.Failed) waitingForLoad = false
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHost) },
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
            // shrink above the keyboard so a focused text field can be scrolled into view
            modifier = Modifier.consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionTitle("Recommended vision models") }
            items(MODEL_PRESETS, key = { it.id }) { preset ->
                PresetCard(
                    preset = preset,
                    downloaded = vm.repo.isDownloaded(preset),
                    // the last used model stays in settings after unloading: only "in use" when loaded
                    active = engineState is EngineState.Ready &&
                        settings.modelPath == vm.repo.presetFiles(preset).first.absolutePath,
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

            item { SectionTitle("Image creation") }
            item { ImageModelsCard(vm, settings) }
            if (BuildConfig.GPU_EXPERIMENTAL) {
                item { SectionTitle("GPU · experimental") }
                item { GpuCard(vm, settings) }
            }
            item { SectionTitle("Benchmark") }
            item { BenchmarkCard(vm, settings) }
            item { SectionTitle("Settings") }
            item { SettingsCard(settings, vm) }
        }
    }
}

@Composable
private fun GpuCard(vm: ChatViewModel, settings: AppSettings) {
    val status by vm.engine.gpuStatus.collectAsStateWithLifecycle()
    val driverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importGpuDriver)
    }
    LaunchedEffect(Unit) { vm.refreshGpuDrivers(); vm.probeGpu() }
    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                "Runs the model with llama.cpp's Vulkan backend. On Adreno (Snapdragon) GPUs you can load " +
                    "a custom Vulkan driver such as Mesa Turnip through libadrenotools. Results vary by " +
                    "device and driver: it can be faster, slower, or produce wrong output.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (status.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            }
            vm.gpuCrashReport?.let { report ->
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Switched back to CPU after a crash", style = MaterialTheme.typography.titleSmall)
                        Text(report, style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis)
                        Row {
                            TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(report)) }) { Text("Copy details") }
                            TextButton(onClick = { vm.gpuCrashReport = null }) { Text("Dismiss") }
                        }
                    }
                }
            }
            Label("Run on")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                listOf("CPU", "Vulkan", "Mali (OpenCL)").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = settings.gpuMode == i,
                        onClick = { vm.updateSettings(reloadNeeded = true) { it.copy(gpuMode = i) } },
                        shape = SegmentedButtonDefaults.itemShape(i, 3),
                    ) { Text(label, maxLines = 1) }
                }
            }
            Text(
                when (settings.gpuMode) {
                    1 -> "Vulkan: the whole model runs on the GPU (needs a Vulkan driver llama.cpp accepts)."
                    2 -> "Mali: prompt, image and video processing (Q8_0 / F16 weights) run on the GPU; " +
                        "writing the reply stays on the CPU. The model is read into RAM and repacking is off."
                    else -> "CPU only (same as the normal app)."
                } + " Changing it reloads the model. Each reply shows prompt time and GPU work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            ListItem(
                headlineContent = { Text("Vulkan compatibility mode") },
                supportingContent = {
                    Text(
                        "Turns off Vulkan features older drivers (e.g. Mali) often crash on: fp16, integer dot " +
                            "products, cooperative matrices, fusion, big allocations. Slower but safer. Applies after a restart.",
                    )
                },
                trailingContent = {
                    Switch(
                        checked = settings.vulkanCompat,
                        onCheckedChange = { on -> vm.updateSettings(reloadNeeded = false) { it.copy(vulkanCompat = on) } },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
            Label("Vulkan driver")
            FileRow("System driver", settings.gpuDriver == null, "The phone's own driver", { vm.selectGpuDriver(null) }, null)
            vm.gpuDrivers.forEach { d ->
                FileRow(
                    d.name, settings.gpuDriver == d.id,
                    listOf(d.description, d.library).filter { it.isNotBlank() }.joinToString(" · "),
                    { vm.selectGpuDriver(d.id) }, { vm.deleteGpuDriver(d.id) },
                )
            }
            if (vm.gpuRestartNeeded) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Driver changes and compatibility mode apply at startup.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = vm::restartApp, shapes = ButtonDefaults.shapes()) { Text("Restart app") }
                }
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                OutlinedButton(
                    onClick = { driverPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.Rounded.FileOpen, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Import driver (.zip)")
                }
            }
            Text(
                "Custom drivers only work on Adreno GPUs (AdrenoTools .zip packages with a meta.json). " +
                    "Other GPUs, like Mali, can only use the system driver.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

@Composable
private fun ImageModelsCard(vm: ChatViewModel, settings: AppSettings) {
    val context = LocalContext.current
    val imageImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importImageModel)
    }
    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            if (!vm.imageCreationSupported) {
                Text(
                    "Image creation needs a CPU with ARMv8.2 dot-product and fp16 support, which this phone doesn't report.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                return@Column
            }
            Text(
                "Create images from text with stable-diffusion.cpp, fully on this phone. Turn on \"Create image\" " +
                    "in the chat. It's heavy work: unloading the chat model (eject button) frees memory.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            @Suppress("UNUSED_VARIABLE") val filesVersion = vm.imageFilesVersion // re-check after download / import / delete
            val downloaded = IMAGE_PRESETS.filter { vm.repo.isDownloaded(it) }
            val choices = vm.imageModelChoices()
            val current = vm.currentImageModel(settings)
            IMAGE_PRESETS.forEach { preset ->
                val isDownloaded = preset in downloaded
                val dl = vm.downloads["img:" + preset.id]
                ListItem(
                    headlineContent = { Text(preset.title) },
                    supportingContent = {
                        Column {
                            Text(
                                Formatter.formatShortFileSize(
                                    context, preset.sizeBytes + (preset.taesd?.sizeBytes ?: 0) + (preset.llm?.sizeBytes ?: 0),
                                ) + " · " + preset.summary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            when (dl) {
                                is DownloadState.Running -> Column {
                                    LinearWavyProgressIndicator(
                                        progress = { dl.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                    )
                                    Text(
                                        when {
                                            dl.converting -> "Converting for this phone (a minute or two)…"
                                            dl.verifying -> "Checking file… ${(dl.progress * 100).roundToInt()}%"
                                            else -> "Downloading… ${(dl.progress * 100).roundToInt()}%"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                is DownloadState.Failed -> Text(dl.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                null -> {}
                            }
                        }
                    },
                    leadingContent = {
                        RadioButton(selected = current?.id == preset.id, enabled = isDownloaded, onClick = { vm.selectImageModel(preset) })
                    },
                    trailingContent = {
                        if (isDownloaded) {
                            IconButton(onClick = { vm.deleteImageModel(preset) }, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Rounded.Delete, "Delete ${preset.title}")
                            }
                        } else if (dl !is DownloadState.Running) {
                            IconButton(onClick = { vm.downloadImageModel(preset) }, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Rounded.Download, "Download ${preset.title}")
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.clickable(enabled = isDownloaded) { vm.selectImageModel(preset) },
                )
            }
            // models you imported yourself (e.g. DreamShaper 8 LCM)
            choices.filter { it.custom }.forEach { c ->
                ListItem(
                    headlineContent = { Text(c.title) },
                    supportingContent = {
                        Text(
                            Formatter.formatShortFileSize(context, c.model.length()) + " · imported · " +
                                (if (c.defaultSampler == io.github.harryl0l.llamachat.data.SAMPLER_LCM) "LCM, " else "") +
                                "${c.defaultSteps} steps by default" + (if (c.taesd != null) " · fast decoder" else ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingContent = { RadioButton(selected = current?.id == c.id, onClick = { vm.selectImageModel(c.id) }) },
                    trailingContent = {
                        IconButton(onClick = { vm.deleteCustomImageModel(c) }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.Delete, "Delete ${c.title}")
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.clickable { vm.selectImageModel(c.id) },
                )
            }
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Size", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                val sizes = current?.sizes ?: listOf(256, 384, 512)
                val size = current?.sizeFor(settings.imageSize) ?: settings.imageSize
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    sizes.forEachIndexed { i, px ->
                        SegmentedButton(
                            selected = size == px,
                            onClick = { vm.updateSettings(reloadNeeded = false) { it.copy(imageSize = px) } },
                            shape = SegmentedButtonDefaults.itemShape(i, sizes.size),
                        ) { Text("$px") }
                    }
                }
                Text(
                    if (current != null && current.defaultSize > 512)
                        "This model is trained for about 1024×1024: 768 is a good balance; larger is sharper but much slower."
                    else "These models are trained for 512×512; smaller is much faster but less detailed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (current != null && current.maxSteps > 1) {
                    val steps = if (settings.imageSteps > 0) settings.imageSteps else current.defaultSteps
                    SettingSlider(
                        "Steps (${current.title})", steps.coerceIn(1, current.maxSteps).toFloat(),
                        1f..current.maxSteps.toFloat(), current.maxSteps - 2, { "${it.roundToInt()}" },
                    ) { v -> vm.updateSettings(reloadNeeded = false) { it.copy(imageSteps = v.roundToInt()) } }
                }
                if (current != null && (current.custom || current.defaultSampler == io.github.harryl0l.llamachat.data.SAMPLER_LCM)) {
                    // imported and LCM models: sampler + CFG matter (LCM models need the LCM sampler)
                    Text("Sampler", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    val sampler = if (settings.imageSampler >= 0) settings.imageSampler else current.defaultSampler
                    val samplers = io.github.harryl0l.llamachat.data.SAMPLERS
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        samplers.forEachIndexed { i, (id, label) ->
                            SegmentedButton(
                                selected = sampler == id,
                                onClick = { vm.updateSettings(reloadNeeded = false) { it.copy(imageSampler = id) } },
                                shape = SegmentedButtonDefaults.itemShape(i, samplers.size),
                            ) { Text(label, maxLines = 1) }
                        }
                    }
                    val cfg = if (settings.imageCfg > 0f) settings.imageCfg else current.defaultCfg
                    SettingSlider("Guidance (CFG)", cfg, 1f..10f, 17, { "%.1f".format(it) }) { v ->
                        vm.updateSettings(reloadNeeded = false) { it.copy(imageCfg = (v * 2).roundToInt() / 2f) }
                    }
                    Text(
                        "LCM / Turbo models: LCM sampler (for LCM), 4–8 steps, CFG 1.0. CFG above 1 uses the " +
                            "negative prompt but takes twice as long." +
                            if (current.taesd == null) " Download SD-Turbo / SDXL-Turbo (or import taesd.safetensors / taesdxl.safetensors " +
                                "for SDXL models) for ~10× faster final decoding." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { imageImporter.launch(arrayOf("*/*")) },
                    enabled = vm.importProgress == null,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(Icons.Rounded.FileOpen, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Import a model file")
                }
                var negative by remember(settings.imageNegative) { mutableStateOf(settings.imageNegative) }
                OutlinedTextField(
                    value = negative,
                    onValueChange = { negative = it },
                    label = { Text("Avoid (negative prompt)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                )
                if (negative != settings.imageNegative) {
                    Button(
                        onClick = { vm.updateSettings(reloadNeeded = false) { it.copy(imageNegative = negative) } },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun BenchmarkCard(vm: ChatViewModel, settings: AppSettings) {
    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(CpuInfo.describe(), style = MaterialTheme.typography.titleSmall)
            Text(
                "Measures this phone with the loaded chat model (prompt = reading text / images, reply = writing) " +
                    "and the selected image model, at several thread counts. Phones mix fast and slow cores, so " +
                    "more threads isn't always faster. Currently using ${settings.effectiveThreads} threads" +
                    (if (settings.threads == 0) " (Auto)." else ".") +
                    (if (BuildConfig.GPU_EXPERIMENTAL && settings.gpuMode != 0) " GPU mode is on: prompt speed includes it." else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::runChatBenchmark, enabled = !vm.benchRunning, shapes = ButtonDefaults.shapes()) { Text("Chat model") }
                OutlinedButton(onClick = vm::runImageBenchmark, enabled = !vm.benchRunning, shapes = ButtonDefaults.shapes()) { Text("Image model") }
                if (vm.benchRunning) TextButton(onClick = vm::stopBenchmark) { Text("Stop") }
            }
            vm.benchStatus?.let { status ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (vm.benchRunning) {
                        LoadingIndicator(Modifier.size(28.dp))
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }
            val rows = vm.benchResults.sortedBy { it.threads }
            if (rows.isNotEmpty()) {
                val bestGen = rows.filter { it.genTps != null }.maxByOrNull { it.genTps!! }?.threads
                val bestPrompt = rows.filter { it.promptTps != null }.maxByOrNull { it.promptTps!! }?.threads
                val bestImage = rows.filter { it.imageSec != null }.minByOrNull { it.imageSec!! }?.threads
                @Composable fun cell(text: String, best: Boolean, weight: Float) = Text(
                    text,
                    style = if (best) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
                    color = if (best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(weight),
                )
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row {
                            Text("Threads", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.7f))
                            Text("Prompt tok/s", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            Text("Reply tok/s", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            Text("Image", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.8f))
                        }
                        rows.forEach { r ->
                            Row {
                                Text("${r.threads}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(0.7f))
                                Box(Modifier.weight(1f)) { cell(r.promptTps?.let { "%.1f".format(it) } ?: "–", r.threads == bestPrompt, 1f) }
                                Box(Modifier.weight(1f)) { cell(r.genTps?.let { "%.1f".format(it) } ?: "–", r.threads == bestGen, 1f) }
                                Box(Modifier.weight(0.8f)) { cell(r.imageSec?.let { "%.1f s".format(it) } ?: "–", r.threads == bestImage, 1f) }
                            }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (bestGen != null && bestGen != settings.effectiveThreads) {
                        Button(onClick = { vm.applyThreads(bestGen) }, enabled = !vm.benchRunning, shapes = ButtonDefaults.shapes()) {
                            Text("Use $bestGen (fastest replies)")
                        }
                    }
                    if (bestImage != null && bestImage != settings.effectiveThreads && bestImage != bestGen) {
                        OutlinedButton(onClick = { vm.applyThreads(bestImage) }, enabled = !vm.benchRunning, shapes = ButtonDefaults.shapes()) {
                            Text("Use $bestImage (fastest images)")
                        }
                    }
                }
            }
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
                    Text(
                        (if (download.verifying) "Checking file… " else "Downloading… ") + "${(download.progress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                    )
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SettingsCard(settings: AppSettings, vm: ChatViewModel) {
    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingSlider("Context size", settings.contextSize.toFloat(), 1024f..16384f, 14, { "${it.roundToInt()} tokens" }) { v ->
                vm.updateSettings(reloadNeeded = true) { it.copy(contextSize = (v / 1024).roundToInt() * 1024) }
            }
            SettingSlider(
                "CPU threads", settings.threads.toFloat(), 0f..8f, 7,
                { if (it.roundToInt() == 0) "Auto (${AppSettings.defaultThreads()} fast cores)" else "${it.roundToInt()}" },
            ) { v ->
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
            Text("Image quality", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                listOf("Low", "Medium", "High").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = settings.imageQuality == i,
                        onClick = { vm.updateSettings(reloadNeeded = false) { it.copy(imageQuality = i) } },
                        shape = SegmentedButtonDefaults.itemShape(i, 3),
                    ) { Text(label) }
                }
            }
            Text(
                "Low is fastest and coolest. Use High for documents, screenshots or small text.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            // keep the field visible above the keyboard: scroll to it once the keyboard has opened
            val bringIntoView = remember { BringIntoViewRequester() }
            var systemFocused by remember { mutableStateOf(false) }
            val imeVisible = WindowInsets.isImeVisible
            LaunchedEffect(systemFocused, imeVisible) {
                if (systemFocused && imeVisible) {
                    kotlinx.coroutines.delay(150) // let the list finish shrinking
                    bringIntoView.bringIntoView()
                }
            }
            OutlinedTextField(
                value = system,
                onValueChange = { system = it },
                label = { Text("System prompt (applies to new chats)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(bringIntoView)
                    .onFocusChanged { systemFocused = it.isFocused },
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
