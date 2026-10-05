package io.bigmoeonedge.example.chat.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.bigmoeonedge.example.chat.ChatFormat
import io.bigmoeonedge.example.chat.ChatServices
import io.bigmoeonedge.example.chat.data.ConversationEntity
import io.bigmoeonedge.example.chat.data.EngineStatusEntity
import io.bigmoeonedge.example.chat.data.MessageEntity
import io.bigmoeonedge.example.chat.data.MessageStatus
import io.bigmoeonedge.example.chat.data.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
