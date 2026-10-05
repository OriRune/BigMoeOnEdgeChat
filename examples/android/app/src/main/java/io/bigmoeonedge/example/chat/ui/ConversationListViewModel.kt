package io.bigmoeonedge.example.chat.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.bigmoeonedge.example.chat.ChatFormat
import io.bigmoeonedge.example.chat.ChatServices
import io.bigmoeonedge.example.chat.data.EngineStateName
import io.bigmoeonedge.example.chat.data.EngineStatusEntity
import io.bigmoeonedge.example.chat.data.MessageStatus
import io.bigmoeonedge.example.chat.data.Role
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ReplyChip { NONE, QUEUED, WRITING, FAILED }

data class ConversationRow(
    val id: Long,
    val title: String,
    val preview: String,
    val updatedAt: Long,
    val model: String,
    val chip: ReplyChip,
    val unread: Boolean,
)

/** What the banner above the list says about the engine process. */
data class EngineBanner(val text: String, val isError: Boolean, val canRetry: Boolean)

class ConversationListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ChatServices.repository(app)
    private val client = ChatServices.client(app)

    val query = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: StateFlow<List<ConversationRow>> = query
        .flatMapLatest { q -> if (q.isBlank()) repo.observeConversations() else repo.search(q) }
        .combine(repo.observeLastMessages()) { convs, last -> convs to last.associateBy { it.conversationId } }
        .combine(repo.observeLastReplyTimes()) { (convs, last), times ->
            Triple(convs, last, times.associate { it.conversationId to (it.finishedAt ?: 0L) })
        }
        .combine(repo.observeLatestReplyStatus()) { (convs, last, times), status ->
            val st = status.associate { it.conversationId to it.status }
            convs.map { c ->
                val m = last[c.id]
                ConversationRow(
                    id = c.id,
                    title = c.title,
                    preview = when {
                        m == null -> ""
                        m.role == Role.ASSISTANT && m.text.isEmpty() && m.error != null -> "Failed: ${m.error}"
                        else -> (if (m.role == Role.USER) "You: " else "") + ChatFormat.preview(m.text)
                    },
                    updatedAt = c.updatedAt,
                    model = ChatFormat.modelShortName(c.modelPath),
                    chip = when (st[c.id]) {
                        MessageStatus.QUEUED -> ReplyChip.QUEUED
                        MessageStatus.PREFILLING, MessageStatus.GENERATING -> ReplyChip.WRITING
                        MessageStatus.FAILED, MessageStatus.INTERRUPTED -> ReplyChip.FAILED
                        else -> ReplyChip.NONE
                    },
                    unread = (times[c.id] ?: 0L) > c.lastReadAt,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val engine: StateFlow<EngineStatusEntity?> = repo.observeEngineStatus()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(id: Long) {
        viewModelScope.launch { repo.deleteConversation(id) }
    }

    fun rename(id: Long, title: String) {
        viewModelScope.launch { repo.rename(id, title) }
    }

    fun retryEngine() = client.kick()

    companion object {
        fun banner(s: EngineStatusEntity?, alive: Boolean, pending: Boolean): EngineBanner? {
            if (s == null) return null
            if (!alive) {
                // The row says BUSY or LOADING but the process is gone: the job loop recovers it.
                return if (pending) EngineBanner("The engine stopped. Tap Retry to continue.", true, true) else null
            }
            s.pausedReason?.let { return EngineBanner(it, false, false) }
            return when (s.state) {
                EngineStateName.LOADING.name -> EngineBanner(s.detail.ifEmpty { "Loading the model…" }, false, false)
                EngineStateName.BUSY.name -> EngineBanner(s.detail.ifEmpty { "Writing a reply…" }, false, false)
                EngineStateName.SCANNING.name -> EngineBanner(s.detail.ifEmpty { "Scan running" }, false, false)
                EngineStateName.ERROR.name -> EngineBanner(s.lastError ?: "The engine reported an error.", true, true)
                EngineStateName.READY.name ->
                    EngineBanner(
                        "Model loaded" + (if (s.modelPath.isNotEmpty()) " · " + ChatFormat.modelShortName(s.modelPath) else ""),
                        false, false,
                    )
                else -> null
            }
        }
    }
}
