package io.bigmoeonedge.example.chat.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import io.bigmoeonedge.example.ModelManager
import io.bigmoeonedge.example.chat.ChatFormat
import io.bigmoeonedge.example.chat.MarkdownExport
import io.bigmoeonedge.example.chat.ChatServices
import io.bigmoeonedge.example.chat.ChatSettings
import io.bigmoeonedge.example.chat.data.ConversationEntity
import io.bigmoeonedge.example.chat.data.EngineStatusEntity
import io.bigmoeonedge.example.chat.data.MessageEntity
import io.bigmoeonedge.example.chat.data.MessageStatus
import io.bigmoeonedge.example.chat.data.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ThreadUi(
    val conversation: ConversationEntity? = null,
    val messages: List<MessageEntity> = emptyList(),
    val queuedIds: List<Long> = emptyList(),
    /** The reply being written right now, in any conversation. */
    val active: MessageEntity? = null,
    val engine: EngineStatusEntity? = null,
    val loaded: Boolean = false,
)

class ThreadViewModel(private val app: Application, val conversationId: Long) : AndroidViewModel(app) {
    private val repo = ChatServices.repository(app)
    private val client = ChatServices.client(app)

    val ui: StateFlow<ThreadUi> = combine(
        repo.observeConversation(conversationId),
        repo.observeMessages(conversationId),
        repo.observeQueuedIds(),
        repo.observeActive(),
        repo.observeEngineStatus(),
    ) { conv, msgs, queued, active, engine ->
        ThreadUi(conv, msgs, queued, active, engine, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUi())

    private val _models = MutableStateFlow<List<java.io.File>>(emptyList())

    /** MoE models on the device, for the model switch. Header probing reads files, so off the main thread. */
    val models: StateFlow<List<java.io.File>> = _models

    fun loadModels() {
        viewModelScope.launch { _models.value = withContext(Dispatchers.IO) { ModelManager.listMoeModels(app) } }
    }

    /** Takes effect on the next reply, which reloads the model if it differs from the loaded one. */
    fun setModel(path: String) {
        viewModelScope.launch { repo.setModel(conversationId, path) }
    }

    /** Applies from the next reply; the reply being written keeps the budget it started with. */
    fun setThinking(choice: Int) {
        viewModelScope.launch { repo.setThinking(conversationId, choice > 0, ThinkingChoices.level(choice)) }
    }

    /** End the model's thinking now and make it answer. */
    fun answerNow(messageId: Long) = client.endThinking(messageId)

    /** Writes the conversation as Markdown and returns a content:// URI for the share sheet. */
    suspend fun exportMarkdown(): Pair<Uri, String>? = withContext(Dispatchers.IO) {
        val conv = repo.conversation(conversationId) ?: return@withContext null
        val msgs = ChatServices.db(app).messages().listFor(conversationId)
        val dir = java.io.File(app.cacheDir, "exports").apply { mkdirs() }
        val f = java.io.File(dir, MarkdownExport.fileName(conv.title))
        f.writeText(MarkdownExport.render(conv, msgs))
        FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", f) to conv.title
    }

    /** True while chats with this conversation's model use a saved scan profile. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val hasProfile: StateFlow<Boolean> = repo.observeConversation(conversationId)
        .flatMapLatest { c ->
            if (c == null) kotlinx.coroutines.flow.flowOf(false)
            else ChatServices.scan(app).observeProfile(c.modelPath).map { it != null }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _foreground = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** True while this conversation's model is set to foreground mode (runs only while the app is open). */
    val foreground: StateFlow<Boolean> = _foreground

    init {
        viewModelScope.launch {
            repo.observeConversation(conversationId).collect { c ->
                _foreground.value = c != null && withContext(Dispatchers.IO) { ChatSettings.isForeground(app, c.modelPath) }
            }
        }
    }

    /** The model reloads in the other process, so the old copy is unloaded first. */
    fun setForeground(on: Boolean) {
        val c = ui.value.conversation ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ChatSettings.setForeground(app, c.modelPath, on) }
            _foreground.value = on
            client.unloadAll()
            client.kick()
        }
    }

    fun resetProfile() {
        val c = ui.value.conversation ?: return
        viewModelScope.launch { ChatServices.scan(app).resetProfile(c.modelPath) }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { repo.send(conversationId, text.trim()) }
    }

    fun stop(messageId: Long) = client.cancel(messageId)

    fun cancelQueued(messageId: Long) {
        viewModelScope.launch { repo.cancelQueued(messageId) }
    }

    fun retry(messageId: Long) {
        viewModelScope.launch { repo.retry(messageId) }
    }

    fun regenerate(messageId: Long) {
        viewModelScope.launch { repo.regenerate(messageId) }
    }

    fun delete(messageId: Long) {
        viewModelScope.launch { repo.deleteMessage(messageId) }
    }

    fun editAndResend(messageId: Long, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { repo.editAndResend(messageId, text.trim()) }
    }

    fun rename(title: String) {
        viewModelScope.launch { repo.rename(conversationId, title) }
    }

    fun deleteConversation(done: () -> Unit) {
        viewModelScope.launch {
            repo.deleteConversation(conversationId)
            done()
        }
    }

    /** Presence for the engine process's "is the user looking at this thread" check, and read state. */
    fun onShown() {
        viewModelScope.launch {
            repo.setVisible(conversationId)
            repo.markRead(conversationId)
        }
    }

    fun onHidden() {
        // Not viewModelScope: it is cancelled with the screen, and this write must land.
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { repo.setVisible(null) }
    }

    fun markRead() {
        viewModelScope.launch { repo.markRead(conversationId) }
    }

    /** Reads a picked text file for the composer. Capped: the whole file would be read as prompt. */
    suspend fun readAttachment(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = app.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "file"
            resolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(MAX_ATTACH_BYTES + 1)
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                val truncated = n > MAX_ATTACH_BYTES
                val body = String(buf, 0, minOf(n, MAX_ATTACH_BYTES), Charsets.UTF_8)
                ChatFormat.attachment(name, body, truncated)
            }
        }.getOrNull()
    }

    companion object {
        const val MAX_ATTACH_BYTES = 64 * 1024

        /** Replies that count against "N ahead": those queued before [m], plus the one running. */
        fun ahead(m: MessageEntity, ui: ThreadUi): Int =
            io.bigmoeonedge.example.chat.data.QueueRules.aheadOf(m, ui.queuedIds, ui.active != null)

        fun isLive(m: MessageEntity) = m.role == Role.ASSISTANT && m.status in MessageStatus.ACTIVE
    }
}
