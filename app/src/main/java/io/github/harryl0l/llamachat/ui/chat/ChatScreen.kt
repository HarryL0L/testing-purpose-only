package io.github.harryl0l.llamachat.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.harryl0l.llamachat.ChatMessage
import io.github.harryl0l.llamachat.ChatViewModel
import io.github.harryl0l.llamachat.Role
import io.github.harryl0l.llamachat.engine.EngineState

@Composable
fun ChatScreen(vm: ChatViewModel, onOpenModels: () -> Unit) {
    val engineState by vm.engineState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHost = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(vm.snackbar) {
        vm.snackbar?.let { snackbarHost.showSnackbar(it); vm.snackbar = null }
    }
    // keep the newest text in view while streaming
    val lastText = vm.messages.lastOrNull()?.text
    LaunchedEffect(vm.messages.size, lastText?.length) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.size - 1, Int.MAX_VALUE)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        vm.attach(uris)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    Text(
                        when (val s = engineState) {
                            is EngineState.Ready -> s.info.name
                            is EngineState.Loading -> "Loading…"
                            else -> "LlamaChat"
                        },
                        maxLines = 1,
                    )
                },
                subtitle = { Text(subtitle(engineState, vm.contextUsage), maxLines = 1) },
                actions = {
                    IconButton(onClick = vm::newChat, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.EditNote, "New chat")
                    }
                    IconButton(onClick = onOpenModels, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Memory, "Models & settings")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            Composer(
                vm = vm,
                enabled = engineState is EngineState.Ready,
                canAttach = (engineState as? EngineState.Ready)?.info?.vision == true,
                onAttach = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                engineState is EngineState.Loading -> LoadingModel()
                vm.messages.isEmpty() -> EmptyState(engineState, onOpenModels) { vm.input = it }
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(vm.messages, key = { it.id }) { msg -> MessageBubble(msg) }
                }
            }
        }
    }
}

private fun subtitle(state: EngineState, usage: Pair<Int, Int>): String = when (state) {
    is EngineState.Ready -> buildList {
        if (state.info.vision) add("Images")
        if (state.info.video) add("Video")
        if (!state.info.vision) add("Text only")
        add("${usage.first} / ${usage.second} tokens")
    }.joinToString(" · ")
    is EngineState.Loading -> state.name
    is EngineState.Failed -> "Failed to load model"
    EngineState.NoModel -> "No model loaded"
}

@Composable
private fun LoadingModel() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ContainedLoadingIndicator(Modifier.size(96.dp))
        Spacer(Modifier.size(16.dp))
        Text("Loading model into memory…", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun EmptyState(state: EngineState, onOpenModels: () -> Unit, onSuggestion: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = MaterialShapes.Cookie9Sided.toShape(),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(120.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.AutoAwesome, null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        Spacer(Modifier.size(24.dp))
        val ready = state is EngineState.Ready
        Text(
            if (ready) "Ask anything. Attach photos or videos." else "Private AI, fully on your phone",
            style = MaterialTheme.typography.headlineSmallEmphasized,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            if (ready) "Everything runs locally with llama.cpp — nothing leaves your device."
            else (state as? EngineState.Failed)?.message ?: "Download or import a GGUF model to get started.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(20.dp))
        if (ready) {
            listOf("What's in this photo?", "Summarize this video", "Read the text in this image").forEach {
                SuggestionChip(onClick = { onSuggestion(it) }, label = { Text(it) })
            }
        } else {
            FilledTonalIconButton(
                onClick = onOpenModels,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(IconButtonDefaults.mediumContainerSize()),
            ) { Icon(Icons.Rounded.Memory, "Choose a model") }
            TextButton(onClick = onOpenModels) { Text("Choose a model") }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == Role.User
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (msg.attachments.isNotEmpty()) {
            val shapes = listOf(
                MaterialShapes.Cookie4Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Pill,
                MaterialShapes.Sunny, MaterialShapes.Slanted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                msg.attachments.forEachIndexed { i, a ->
                    MediaThumbnail(a, shapes[i % shapes.size].toShape(), 96.dp)
                }
            }
        }
        if (!isUser || msg.text.isNotEmpty()) {
            val container = when {
                msg.error -> MaterialTheme.colorScheme.errorContainer
                isUser -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            }
            val content = when {
                msg.error -> MaterialTheme.colorScheme.onErrorContainer
                isUser -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            }
            Surface(
                color = container,
                contentColor = content,
                shape = if (isUser) RoundedCornerShape(28.dp, 28.dp, 8.dp, 28.dp)
                else RoundedCornerShape(28.dp, 28.dp, 28.dp, 8.dp),
                modifier = Modifier.widthIn(max = 340.dp).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
            ) {
                AnimatedContent(
                    targetState = msg.text.isEmpty() && msg.streaming,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "bubble",
                ) { thinking ->
                    if (thinking) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LoadingIndicator(Modifier.size(36.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(msg.status ?: "Thinking…", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        SelectionContainer {
                            Text(
                                msg.text.trimStart(),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
        if (!isUser && msg.text.isNotEmpty() && msg.status != null) {
            Text(
                msg.status!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun Composer(vm: ChatViewModel, enabled: Boolean, canAttach: Boolean, onAttach: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(12.dp)) {
            AnimatedVisibility(
                visible = vm.pendingAttachments.isNotEmpty(),
                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(),
                exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeOut(),
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 10.dp),
                ) {
                    items(vm.pendingAttachments.toList(), key = { it.uri.toString() }) { a ->
                        Box {
                            MediaThumbnail(a, RoundedCornerShape(20.dp), 72.dp, Modifier.animateItem())
                            FilledIconButton(
                                onClick = { vm.removeAttachment(a) },
                                modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                            ) { Icon(Icons.Rounded.Close, "Remove", Modifier.size(16.dp)) }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                FilledTonalIconButton(
                    onClick = onAttach,
                    enabled = enabled && canAttach && !vm.generating,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(56.dp),
                ) { Icon(Icons.Rounded.AddPhotoAlternate, "Attach image or video") }
                Spacer(Modifier.size(8.dp))
                TextField(
                    value = vm.input,
                    onValueChange = { vm.input = it },
                    enabled = enabled,
                    placeholder = { Text(if (enabled) "Message" else "Load a model first") },
                    maxLines = 6,
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                AnimatedContent(
                    targetState = vm.generating,
                    transitionSpec = { scaleIn() + fadeIn() togetherWith scaleOut() + fadeOut() },
                    label = "send",
                ) { generating ->
                    FilledIconButton(
                        onClick = { if (generating) vm.stop() else vm.send() },
                        enabled = enabled,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(56.dp),
                    ) {
                        if (generating) Icon(Icons.Rounded.Stop, "Stop")
                        else Icon(Icons.AutoMirrored.Rounded.Send, "Send")
                    }
                }
            }
        }
    }
}
