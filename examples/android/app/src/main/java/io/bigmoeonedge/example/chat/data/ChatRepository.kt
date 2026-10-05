package io.bigmoeonedge.example.chat.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/** Wakes the engine process after the queue changed. */
fun interface EngineKicker {
    fun kick()
}

/**
 * Every write the chat screens and the engine make to the conversation tables. The queue rules live
 * here; the pure parts are in [QueueRules] and [HistoryBuilder].
 */
class ChatRepository(
    private val db: ChatDb,
    private val kicker: EngineKicker = EngineKicker {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val conversations get() = db.conversations()
    private val messages get() = db.messages()

    fun observeConversations(): Flow<List<ConversationEntity>> = conversations.observeAll()
    fun observeConversation(id: Long): Flow<ConversationEntity?> = conversations.observe(id)
    fun observeMessages(id: Long): Flow<List<MessageEntity>> = messages.observeFor(id)
    fun observeQueuedIds(): Flow<List<Long>> = messages.observeQueuedIds()
    fun observeActive(): Flow<MessageEntity?> = messages.observeActive()
    fun observeLastMessages(): Flow<List<MessageEntity>> = messages.observeLastMessages()
    fun observeLastReplyTimes(): Flow<List<ReplyTime>> = messages.observeLastReplyTimes()
    fun observeLatestReplyStatus(): Flow<List<ReplyStatus>> = messages.observeLatestReplyStatus()
    fun observeEngineStatus(): Flow<EngineStatusEntity?> = db.engineStatus().observe()

    fun search(query: String): Flow<List<ConversationEntity>> {
        val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return conversations.search("%$escaped%")
    }

    suspend fun lastUsedModel(): String? = conversations.lastUsedModel()
    suspend fun conversation(id: Long): ConversationEntity? = conversations.get(id)

    suspend fun createConversation(
        modelPath: String, systemPrompt: String, thinking: Boolean, title: String = "New chat",
    ): Long {
        val now = clock()
        return conversations.insert(
            ConversationEntity(
                title = title, modelPath = modelPath, systemPrompt = systemPrompt, thinking = thinking,
                createdAt = now, updatedAt = now, lastReadAt = now,
            ),
        )
    }

    /**
     * Send a message. Always accepted, whatever the engine is doing: a reply already waiting in the
     * queue simply answers this message too, so a burst of messages gets one reply that sees all of
     * them. Returns the id of the user message.
     */
    suspend fun send(conversationId: Long, text: String): Long {
        val id = db.withTransaction {
            val conv = conversations.get(conversationId) ?: return@withTransaction -1L
            val now = clock()
            val userId = messages.insert(
                MessageEntity(conversationId = conversationId, role = Role.USER, text = text, createdAt = now),
            )
            val pending = messages.queuedFor(conversationId)
            if (pending == null) {
                messages.insert(
                    MessageEntity(
                        conversationId = conversationId, role = Role.ASSISTANT, status = MessageStatus.QUEUED,
                        createdAt = now + 1, queuedAt = now + 1,
                    ),
                )
            } else {
                // Keep the waiting reply below the newest message on screen and in the history cut.
                // Its place in the queue (queuedAt) stays.
                messages.setCreatedAt(pending.id, now + 1)
            }
            if (conv.title == "New chat") conversations.rename(conversationId, QueueRules.titleFrom(text))
            conversations.touch(conversationId, now)
            userId
        }
        if (id >= 0) kicker.kick()
        return id
    }

    /** Stop a reply that has not started. A running one is stopped through the engine. */
    suspend fun cancelQueued(messageId: Long) {
        val m = messages.get(messageId) ?: return
        if (m.status == MessageStatus.QUEUED) {
            messages.setStatus(messageId, MessageStatus.CANCELLED)
        }
    }

    /** Put a failed, interrupted or cancelled reply back in the queue. */
    suspend fun retry(messageId: Long) {
        val m = messages.get(messageId) ?: return
        if (m.role != Role.ASSISTANT) return
        if (m.status !in listOf(MessageStatus.FAILED, MessageStatus.INTERRUPTED, MessageStatus.CANCELLED)) return
        db.withTransaction {
            messages.setText(messageId, "")
            messages.requeue(messageId, 0, clock())
        }
        kicker.kick()
    }

    /**
     * Replace the conversation's last reply with a fresh one at the same place. The old text is gone;
     * the new placeholder joins the back of the queue. Older replies are not regenerated: the
     * messages after them were written in answer to them.
     */
    suspend fun regenerate(messageId: Long) {
        val m = messages.get(messageId) ?: return
        if (m.role != Role.ASSISTANT || m.status in MessageStatus.ACTIVE) return
        val ok = db.withTransaction {
            val last = messages.listFor(m.conversationId).lastOrNull { it.role == Role.ASSISTANT }
            if (last?.id != messageId) return@withTransaction false
            messages.delete(messageId)
            val now = clock()
            messages.insert(
                MessageEntity(
                    conversationId = m.conversationId, role = Role.ASSISTANT, status = MessageStatus.QUEUED,
                    createdAt = m.createdAt, queuedAt = now,
                ),
            )
            conversations.touch(m.conversationId, now)
            true
        }
        if (ok) kicker.kick()
    }

    /**
     * Rewrite the last user message and answer again: everything after it is deleted and a new reply
     * is queued.
     */
    suspend fun editAndResend(messageId: Long, newText: String) {
        val m = messages.get(messageId) ?: return
        if (m.role != Role.USER) return
        db.withTransaction {
            messages.deleteAfter(m.conversationId, m.createdAt, m.id)
            messages.setText(messageId, newText)
            val now = clock()
            messages.insert(
                MessageEntity(
                    conversationId = m.conversationId, role = Role.ASSISTANT, status = MessageStatus.QUEUED,
                    createdAt = now, queuedAt = now,
                ),
            )
            conversations.touch(m.conversationId, now)
        }
        kicker.kick()
    }

    suspend fun deleteMessage(messageId: Long) {
        val m = messages.get(messageId) ?: return
        if (m.status in MessageStatus.ACTIVE) return
        messages.delete(messageId)
    }

    suspend fun deleteConversation(id: Long) = conversations.delete(id)
    suspend fun rename(id: Long, title: String) = conversations.rename(id, title.trim().ifEmpty { "Chat" })
    suspend fun setModel(id: Long, modelPath: String) = conversations.setModel(id, modelPath)
    suspend fun markRead(id: Long) = conversations.markRead(id, clock())

    /** Written by the UI in onResume / onPause so the engine process knows whether to notify. */
    suspend fun setVisible(conversationId: Long?) {
        db.presence().put(UiPresenceEntity(visibleConversationId = conversationId, updatedAt = clock()))
    }
}
