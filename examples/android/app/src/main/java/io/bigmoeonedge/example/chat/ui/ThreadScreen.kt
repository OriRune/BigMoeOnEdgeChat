package io.bigmoeonedge.example.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bigmoeonedge.example.MarkdownText
import io.bigmoeonedge.example.chat.ChatFormat
import io.bigmoeonedge.example.chat.data.MessageEntity
import io.bigmoeonedge.example.chat.data.MessageStatus
import io.bigmoeonedge.example.chat.data.Role
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ThreadScreen(vm: ThreadViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val conv = ui.conversation
    var draft by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MessageEntity?>(null) }
    val scope = rememberCoroutineScope()

    // The engine process notifies when a reply finishes unless this thread is on screen, so the
    // presence row has to follow the lifecycle exactly.
    LifecycleResumeEffect(vm.conversationId) {
        vm.onShown()
        onPauseOrDispose { vm.onHidden() }
    }
    // A reply that finishes while the thread is open is read as it arrives.
    val lastDone = ui.messages.lastOrNull { it.role == Role.ASSISTANT && it.status == MessageStatus.DONE }?.finishedAt
    LaunchedEffect(lastDone) { if (lastDone != null) vm.markRead() }

    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            vm.readAttachment(uri)?.let { draft = if (draft.isEmpty()) it else draft.trimEnd() + "\n\n" + it }
        }
    }

    // The conversation vanished (deleted elsewhere): leave rather than show an empty shell.
    LaunchedEffect(ui.loaded, conv) { if (ui.loaded && conv == null) onBack() }

    val running = ui.messages.firstOrNull { it.role == Role.ASSISTANT && it.status in MessageStatus.ACTIVE }
    val newestOutOfContext = ui.messages.lastOrNull { it.outOfContext }?.id
    val lastAssistantId = ui.messages.lastOrNull { it.role == Role.ASSISTANT }?.id
    val lastUserId = ui.messages.lastOrNull { it.role == Role.USER }?.id

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(conv?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 18.sp)
                        if (conv != null) {
                            Text(
                                ChatFormat.modelShortName(conv.modelPath), fontSize = 12.sp, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem(
                                text = { Text("Change model") },
                                onClick = { menu = false; vm.loadModels(); switching = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Export as Markdown") },
                                onClick = {
                                    menu = false
                                    scope.launch {
                                        vm.exportMarkdown()?.let { (uri, title) -> shareMarkdown(ctx, uri, title) }
                                    }
                                },
                            )
                            DropdownMenuItem(text = { Text("Delete chat") }, onClick = { menu = false; deleting = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            val state = rememberLazyListState()
            // reverseLayout puts item 0 at the bottom, so the newest message first.
            val shown = ui.messages.asReversed()
            LazyColumn(
                state = state, reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { it.id }) { m ->
                    Column {
                        MessageBubble(
                            m = m, ui = ui,
                            canRegenerate = m.id == lastAssistantId && m.status !in MessageStatus.ACTIVE &&
                                m.status != MessageStatus.QUEUED,
                            canEdit = m.id == lastUserId,
                            onCopy = { copy(ctx, m.text) },
                            onDelete = { vm.delete(m.id) },
                            onRegenerate = { vm.regenerate(m.id) },
                            onEdit = { editing = m },
                            onRetry = { vm.retry(m.id) },
                            onStop = { vm.stop(m.id) },
                            onCancelQueued = { vm.cancelQueued(m.id) },
                        )
                        if (m.id == newestOutOfContext) {
                            // Below the newest dropped message: everything above it is no longer read.
                            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                HorizontalDivider(Modifier.weight(1f))
                                Text(
                                    "  Messages above are outside the model's memory  ", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                HorizontalDivider(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            Composer(
                draft = draft, onDraft = { draft = it },
                running = running != null,
                onSend = {
                    vm.send(draft)
                    draft = ""
                },
                onAttach = { attach.launch(arrayOf("text/*")) },
                onStop = { running?.let { vm.stop(it.id) } },
            )
        }
    }

    if (renaming && conv != null) {
        RenameDialog(conv.title, onDismiss = { renaming = false }) { vm.rename(it); renaming = false }
    }
    if (switching && conv != null) {
        val models by vm.models.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { switching = false },
            title = { Text("Change model") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (models.isEmpty()) Text("Looking for models…", fontSize = 13.sp)
                    for (f in models) {
                        TextButton(onClick = { vm.setModel(f.absolutePath); switching = false }) {
                            Text(
                                (if (f.absolutePath == conv.modelPath) "✓ " else "") + ChatFormat.modelShortName(f.absolutePath),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        "Applies from the next reply. If the model differs from the loaded one it reloads first, " +
                            "which can take a while.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { switching = false }) { Text("Close") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this chat?") },
            text = { Text("All its messages will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { deleting = false; vm.deleteConversation(onBack) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
    editing?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit and resend") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { text = it }, minLines = 2, maxLines = 8, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Replies after this message are deleted and the model answers again.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.editAndResend(m.id, text); editing = null }) { Text("Resend") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

private fun shareMarkdown(ctx: Context, uri: android.net.Uri, title: String) {
    val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, title)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        ctx.startActivity(android.content.Intent.createChooser(i, "Share chat").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("message", text))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    m: MessageEntity,
    ui: ThreadUi,
    canRegenerate: Boolean,
    canEdit: Boolean,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    onCancelQueued: () -> Unit,
) {
    val mine = m.role == Role.USER
    var menu by remember { mutableStateOf(false) }
    val bg = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box {
            Surface(
                color = bg, contentColor = fg, shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (mine) UserBody(m) else AssistantBody(m, ui, onRetry, onStop, onCancelQueued)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (m.text.isNotEmpty()) DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; onCopy() })
                if (canRegenerate) DropdownMenuItem(text = { Text("Regenerate") }, onClick = { menu = false; onRegenerate() })
                if (canEdit && mine) DropdownMenuItem(text = { Text("Edit & resend") }, onClick = { menu = false; onEdit() })
                if (m.status !in MessageStatus.ACTIVE) DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun UserBody(m: MessageEntity) {
    Text(m.text, fontSize = 15.sp)
}

@Composable
private fun AssistantBody(
    m: MessageEntity, ui: ThreadUi, onRetry: () -> Unit, onStop: () -> Unit, onCancelQueued: () -> Unit,
) {
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    when (m.status) {
        MessageStatus.QUEUED -> {
            Text(ChatFormat.queueLabel(ThreadViewModel.ahead(m, ui)), fontSize = 13.sp, color = sub)
            ui.engine?.pausedReason?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onCancelQueued) { Text("Cancel") }
        }
        MessageStatus.PREFILLING -> {
            Text(
                if (ui.engine?.state == "LOADING") ui.engine.detail.ifEmpty { "Loading the model…" }
                else "Reading the conversation…",
                fontSize = 13.sp, color = sub,
            )
            TextButton(onClick = onStop) { Text("Stop") }
        }
        MessageStatus.GENERATING -> {
            if (m.reasoning.isNotEmpty()) ReasoningBlock(m.reasoning, initiallyExpanded = m.text.isEmpty())
            // Plain text while streaming: re-parsing Markdown on every update is what froze the lab screen.
            if (m.text.isNotEmpty()) Text(m.text, fontSize = 15.sp)
            else if (m.reasoning.isEmpty()) Text("…", fontSize = 15.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    String.format(Locale.US, "Writing · %.1f tok/s", m.tokPerSec), fontSize = 12.sp, color = sub,
                )
                TextButton(onClick = onStop) { Text("Stop") }
            }
        }
        MessageStatus.FAILED, MessageStatus.INTERRUPTED -> {
            if (m.text.isNotEmpty()) Text(m.text, fontSize = 15.sp)
            Text(m.error ?: "Failed", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        else -> { // DONE, CANCELLED
            if (m.reasoning.isNotEmpty()) ReasoningBlock(m.reasoning, initiallyExpanded = false)
            if (m.text.isNotEmpty()) MarkdownText(m.text)
            if (m.status == MessageStatus.CANCELLED) {
                Text("Stopped", fontSize = 12.sp, color = sub)
                TextButton(onClick = onRetry) { Text("Try again") }
            } else if (m.metrics.isNotEmpty()) {
                Text(m.metrics, fontSize = 11.sp, color = sub)
            }
        }
    }
}

@Composable
private fun ReasoningBlock(reasoning: String, initiallyExpanded: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            ) {
                Text("Thinking", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (expanded) "▾" else "▸", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                Text(
                    reasoning, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String, onDraft: (String) -> Unit, running: Boolean, onSend: () -> Unit, onAttach: () -> Unit, onStop: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        if (draft.length > 2_000) {
            Text(
                "${draft.length} characters (about ${ChatFormat.approxTokens(draft.length)} tokens). " +
                    "Long text takes a while to read.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onAttach) { Text("Attach") }
            OutlinedTextField(
                value = draft, onValueChange = onDraft, placeholder = { Text("Message") },
                minLines = 1, maxLines = 6, modifier = Modifier.weight(1f),
            )
            if (running && draft.isBlank()) {
                Button(onClick = onStop) { Text("Stop") }
            } else {
                // Always enabled apart from an empty box: sending while a reply is writing just queues.
                IconButton(onClick = onSend, enabled = draft.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}
