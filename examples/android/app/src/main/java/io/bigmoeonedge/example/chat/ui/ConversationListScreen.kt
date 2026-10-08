package io.bigmoeonedge.example.chat.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bigmoeonedge.example.chat.ChatFormat
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationListScreen(
    vm: ConversationListViewModel,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onLab: () -> Unit,
    onScan: (() -> Unit)? = null,
    onUnload: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val rows by vm.rows.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ConversationRow?>(null) }
    var renaming by remember { mutableStateOf<ConversationRow?>(null) }

    // The row can say BUSY after the engine process died; ask the system, not the database.
    val alive by produceState(true) {
        while (true) {
            value = EngineLiveness.isAlive(ctx)
            delay(4_000)
        }
    }
    val pending = rows.any { it.chip == ReplyChip.QUEUED || it.chip == ReplyChip.WRITING }
    val banner = ConversationListViewModel.banner(engine, alive, pending)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = query, onValueChange = { vm.query.value = it },
                            placeholder = { Text("Search chats") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else Text("Chats")
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) vm.query.value = "" }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, contentDescription = "Search")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onSettings() })
                            if (onScan != null) DropdownMenuItem(text = { Text("Scan") }, onClick = { menu = false; onScan() })
                            if (onUnload != null) {
                                DropdownMenuItem(text = { Text("Unload model now") }, onClick = { menu = false; onUnload() })
                            }
                            DropdownMenuItem(text = { Text("Engine lab") }, onClick = { menu = false; onLab() })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, contentDescription = "New chat") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (banner != null) EngineBannerRow(banner, onRetry = vm::retryEngine)
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank()) "No chats yet. Tap + to start one." else "Nothing matches.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Relative times need a clock; one tick a minute is enough.
                val now by produceState(System.currentTimeMillis()) {
                    while (true) {
                        delay(60_000)
                        value = System.currentTimeMillis()
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }) { row ->
                        var rowMenu by remember { mutableStateOf(false) }
                        Box {
                            ConversationRowView(
                                row, now,
                                Modifier.combinedClickable(onClick = { onOpen(row.id) }, onLongClick = { rowMenu = true }),
                            )
                            DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { rowMenu = false; renaming = row })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { rowMenu = false; deleting = row })
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this chat?") },
            text = { Text("“${row.title}” and all its messages will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { vm.delete(row.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    renaming?.let { row ->
        RenameDialog(row.title, onDismiss = { renaming = null }) { vm.rename(row.id, it); renaming = null }
    }
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EngineBannerRow(b: EngineBanner, onRetry: () -> Unit) {
    Surface(
        color = if (b.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                b.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (b.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            if (b.canRetry) TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun ConversationRowView(row: ConversationRow, now: Long, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(10.dp).background(
                if (row.unread) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                CircleShape,
            ),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title, fontWeight = if (row.unread) FontWeight.Bold else FontWeight.Medium, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    ChatFormat.relativeTime(now, row.updatedAt), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                row.preview, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.model, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                when (row.chip) {
                    ReplyChip.QUEUED -> Chip("Queued", MaterialTheme.colorScheme.tertiary)
                    ReplyChip.WRITING -> Chip("Writing…", MaterialTheme.colorScheme.primary)
                    ReplyChip.FAILED -> Chip("Failed", MaterialTheme.colorScheme.error)
                    ReplyChip.NONE -> {}
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, color: androidx.compose.ui.graphics.Color) {
    Text(label, fontSize = 11.sp, color = color, fontWeight = FontWeight.Bold)
}
