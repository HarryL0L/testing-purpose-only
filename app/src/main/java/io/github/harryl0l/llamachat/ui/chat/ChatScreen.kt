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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.harryl0l.llamachat.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Eject
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.text.format.DateUtils
import androidx.compose.ui.text.style.TextOverflow
import io.github.harryl0l.llamachat.data.ChatSummary
import kotlinx.coroutines.launch
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
    // The list is bottom-anchored (reverseLayout): a growing reply stays in view by itself and the
    // keyboard doesn't hide it. Jump to the newest message only when one is added (send / open chat),
    // so scrolling up to read while a reply streams isn't undone on every token.
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        vm.attach(uris)
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var viewing by remember { mutableStateOf<Attachment?>(null) }
    viewing?.let { a -> ImageViewer(a, onSave = { vm.saveToGallery(a.uri) }, onDismiss = { viewing = null }) }
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatHistoryDrawer(
                vm = vm,
                onNewChat = { vm.newChat(); scope.launch { drawerState.close() } },
                onOpen = { vm.openChat(it); scope.launch { drawerState.close() } },
            )
        },
    ) {
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
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Menu, "Chat history")
                    }
                },
                actions = {
                    if (engineState is EngineState.Ready) {
                        IconButton(onClick = vm::unload, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.Eject, "Unload model (free memory)")
                        }
                    }
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
                thinking = settings.thinking,
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
                vm.messages.isEmpty() -> EmptyState(engineState, vm.lastModelName, vm::loadLast, onOpenModels) { vm.input = it }
                else -> Column(Modifier.fillMaxSize()) {
                AnimatedVisibility(engineState !is EngineState.Ready) {
                    LoadBanner(engineState, vm.lastModelName, vm::loadLast, onOpenModels)
                }
                LazyColumn(
                    state = listState,
                    reverseLayout = true, // newest message at the bottom, anchored above the message bar
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(vm.messages.asReversed(), key = { it.id }) { msg -> MessageBubble(msg) { viewing = it } }
                }
                }
            }
        }
    }
    }
}

@Composable
private fun ChatHistoryDrawer(vm: ChatViewModel, onNewChat: () -> Unit, onOpen: (String) -> Unit) {
    var confirmDelete by remember { mutableStateOf<ChatSummary?>(null) }
    ModalDrawerSheet(drawerShape = RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp)) {
        Text(
            "Chats",
            style = MaterialTheme.typography.headlineSmallEmphasized,
            modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 12.dp),
        )
        NavigationDrawerItem(
            label = { Text("New chat") },
            icon = { Icon(Icons.Rounded.Add, null) },
            selected = vm.currentChatId == null,
            onClick = onNewChat,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
        if (vm.chats.isEmpty()) {
            Text(
                "Your conversations are saved on this phone and show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            )
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            items(vm.chats, key = { it.id }) { chat ->
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                DateUtils.getRelativeTimeSpanString(chat.updated).toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    selected = chat.id == vm.currentChatId,
                    onClick = { onOpen(chat.id) },
                    badge = {
                        IconButton(onClick = { confirmDelete = chat }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.DeleteOutline, "Delete chat")
                        }
                    },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
    confirmDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete chat?") },
            text = { Text("\"${chat.title}\" and its attachments will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteChat(chat.id); confirmDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/** Splits a raw reply into (reasoning, answer, reasoningStillOpen). */
private fun splitThinking(raw: String): Triple<String?, String, Boolean> {
    val t = raw.trimStart()
    if (t.startsWith("<think>")) {
        val rest = t.removePrefix("<think>")
        val close = rest.indexOf("</think>")
        return if (close < 0) Triple(rest.trim(), "", true)
        else Triple(rest.substring(0, close).trim(), rest.substring(close + 8).trimStart(), false)
    }
    val close = raw.indexOf("</think>")
    if (close >= 0) return Triple(raw.substring(0, close).trim(), raw.substring(close + 8).trimStart(), false)
    return Triple(null, raw, false)
}

@Composable
private fun ThinkingSection(text: String, active: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(20.dp),
        onClick = { expanded = !expanded },
        modifier = Modifier
            .widthIn(max = 340.dp)
            .padding(bottom = 6.dp)
            // animate expand/collapse, but not while reasoning streams in (it would clip the newest line)
            .then(if (active) Modifier else Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) LoadingIndicator(Modifier.size(24.dp))
                else Icon(Icons.Rounded.Psychology, null, Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    if (active) "Thinking…" else "Thought process",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.size(4.dp))
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Hide" else "Show")
            }
            if (expanded && text.isNotEmpty()) {
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
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
private fun LoadBanner(state: EngineState, lastModel: String?, onLoad: () -> Unit, onOpenModels: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (state as? EngineState.Failed)?.message ?: "No model loaded. Load one to continue this chat.",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (lastModel != null) {
                Button(onClick = onLoad, shapes = ButtonDefaults.shapes()) { Text("Load") }
            } else {
                TextButton(onClick = onOpenModels) { Text("Choose") }
            }
        }
    }
}

@Composable
private fun EmptyState(
    state: EngineState,
    lastModel: String?,
    onLoad: () -> Unit,
    onOpenModels: () -> Unit,
    onSuggestion: (String) -> Unit,
) {
    // centred when it fits, scrollable when it doesn't (small screens, keyboard open)
    BoxWithConstraints(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .fillMaxWidth()
            .heightIn(min = maxHeight)
            .padding(24.dp),
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
            else (state as? EngineState.Failed)?.message
                ?: if (lastModel != null) "No model is loaded. Nothing uses memory until you load one."
                else "Download or import a GGUF model to get started.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(20.dp))
        if (ready) {
            listOf("What's in this photo?", "Summarize this video", "Read the text in this image").forEach {
                SuggestionChip(onClick = { onSuggestion(it) }, label = { Text(it) })
            }
        } else if (lastModel != null) {
            Button(
                onClick = onLoad,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
            ) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MediumContainerHeight)))
                Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(ButtonDefaults.MediumContainerHeight)))
                Text("Load $lastModel", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onOpenModels) { Text("Choose another model") }
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
}

@Composable
private fun MessageBubble(msg: ChatMessage, onOpenImage: (Attachment) -> Unit) {
    val isUser = msg.role == Role.User
    if (!isUser && msg.imageRequest && !msg.error) {
        CreatedImage(msg, onOpenImage)
        return
    }
    if (!isUser && msg.imageRequest && msg.log.isNotEmpty()) {
        // failed image creation: the error below, then the log (open by default: it explains the error)
        Column(Modifier.fillMaxWidth()) {
            Text(msg.text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp))
            ImageLog(msg, startOpen = true)
        }
        return
    }
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
        val (thinking, answer, thinkingOpen) =
            if (isUser || msg.error) Triple(null, msg.text, false) else splitThinking(msg.text)
        if (thinking != null) ThinkingSection(thinking, active = thinkingOpen && msg.streaming)
        if (isUser && msg.text.isNotEmpty() || !isUser && (answer.isNotEmpty() || (thinking == null && msg.streaming) || msg.error)) {
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
                modifier = Modifier.widthIn(max = 340.dp),
            ) {
                AnimatedContent(
                    targetState = answer.isEmpty() && msg.streaming,
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
                                answer.trimStart(),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
        // tok/s under a reply, or "Stopped" when it was stopped before writing anything
        if (!isUser && msg.status != null && (answer.isNotEmpty() || !msg.streaming)) {
            Text(
                msg.status!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
        }
    }
}

/** An image being created (progress) or the finished image, large and tappable. */
@Composable
private fun CreatedImage(msg: ChatMessage, onOpen: (Attachment) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        val image = msg.attachments.firstOrNull()
        if (image != null) {
            MediaThumbnail(
                image, RoundedCornerShape(28.dp), 300.dp,
                Modifier.clickable { onOpen(image) },
            )
        } else if (msg.streaming) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.size(300.dp),
            ) {
                Column(
                    Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LoadingIndicator(Modifier.size(64.dp))
                    Spacer(Modifier.size(16.dp))
                    Text(msg.status ?: "Creating…", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.size(12.dp))
                    val p = msg.progress
                    if (p != null) LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (!msg.streaming && msg.status != null) {
            Text(
                msg.status!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
        }
        if (msg.log.isNotEmpty()) ImageLog(msg, startOpen = false)
    }
}

/** The image engine's log for one image: what it's doing, with elapsed times. Live while creating. */
@Composable
private fun ImageLog(msg: ChatMessage, startOpen: Boolean) {
    var open by rememberSaveable(msg.id) { mutableStateOf(startOpen) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { open = !open }) {
                Text(if (open) "Hide log" else "Show log (${msg.log.size} lines)")
            }
            if (open) {
                TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg.log.joinToString("\n"))) }) {
                    Text("Copy")
                }
            }
        }
        if (open) {
            val scroll = rememberScrollState()
            // follow new lines while the image is being created
            LaunchedEffect(msg.log.size) { if (msg.streaming) scroll.animateScrollTo(scroll.maxValue) }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
            ) {
                Text(
                    msg.log.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.verticalScroll(scroll).padding(12.dp),
                )
            }
        }
    }
}

/** Full-screen view of a created image with "save to gallery". */
@Composable
private fun ImageViewer(attachment: Attachment, onSave: () -> Unit, onDismiss: () -> Unit) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, attachment.uri) {
        value = withContext(Dispatchers.IO) { runCatching { android.graphics.BitmapFactory.decodeFile(attachment.uri.path) }.getOrNull() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bmp.asImageBitmap(), "Created image",
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).clip(RoundedCornerShape(24.dp)),
                        contentScale = ContentScale.Fit,
                    )
                } else LoadingIndicator()
                Spacer(Modifier.size(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Close") }
                    Button(onClick = onSave, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Save to gallery")
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(vm: ChatViewModel, thinking: Boolean, enabled: Boolean, canAttach: Boolean, onAttach: () -> Unit) {
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
                        Box(Modifier.animateItem()) {
                            MediaThumbnail(a, RoundedCornerShape(20.dp), 72.dp)
                            FilledIconButton(
                                onClick = { vm.removeAttachment(a) },
                                modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                            ) { Icon(Icons.Rounded.Close, "Remove", Modifier.size(16.dp)) }
                        }
                    }
                }
            }
            // image mode works without a chat model
            val canType = enabled || vm.imageMode
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) {
                if (vm.imageCreationSupported) {
                    FilterChip(
                        selected = vm.imageMode,
                        onClick = { vm.imageMode = !vm.imageMode },
                        label = { Text("Create image") },
                        leadingIcon = {
                            Icon(
                                if (vm.imageMode) Icons.Rounded.Brush else Icons.Outlined.Brush, null,
                                Modifier.size(FilterChipDefaults.IconSize),
                            )
                        },
                        shape = RoundedCornerShape(16.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                }
                if (vm.imageMode) AssistChip(
                    onClick = { vm.improveImagePrompt() },
                    enabled = !vm.promptHelperBusy && !vm.generating && vm.input.isNotBlank(),
                    label = { Text(if (vm.promptHelperBusy) "Writing…" else "Improve prompt") },
                    leadingIcon = {
                        if (vm.promptHelperBusy) LoadingIndicator(Modifier.size(FilterChipDefaults.IconSize))
                        else Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(FilterChipDefaults.IconSize))
                    },
                    shape = RoundedCornerShape(16.dp),
                )
                if (!vm.imageMode) FilterChip(
                    selected = thinking,
                    onClick = { vm.setThinking(!thinking) },
                    label = { Text("Thinking") },
                    leadingIcon = {
                        Icon(
                            if (thinking) Icons.Rounded.Psychology else Icons.Outlined.Psychology, null,
                            Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                    shape = RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    when {
                        vm.imageMode && vm.promptHelperBusy -> "The chat model is rewriting your idea…"
                        vm.imageMode -> "Your message becomes an image (runs on this phone)"
                        thinking -> "Reasons before answering (if the model supports it)"
                        else -> "Answers directly"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                FilledTonalIconButton(
                    onClick = onAttach,
                    enabled = enabled && canAttach && !vm.generating && !vm.imageMode,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(56.dp),
                ) { Icon(Icons.Rounded.AddPhotoAlternate, "Attach image or video") }
                Spacer(Modifier.size(8.dp))
                TextField(
                    value = vm.input,
                    onValueChange = { vm.input = it },
                    enabled = canType,
                    placeholder = {
                        Text(
                            when {
                                vm.imageMode -> "Describe the image to create"
                                enabled -> "Message"
                                else -> "Load a model first"
                            }
                        )
                    },
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
                        enabled = canType || generating,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(56.dp),
                    ) {
                        if (generating) Icon(Icons.Rounded.Stop, "Stop")
                        else if (vm.imageMode) Icon(Icons.Rounded.Brush, "Create image")
                        else Icon(Icons.AutoMirrored.Rounded.Send, "Send")
                    }
                }
            }
        }
    }
}
