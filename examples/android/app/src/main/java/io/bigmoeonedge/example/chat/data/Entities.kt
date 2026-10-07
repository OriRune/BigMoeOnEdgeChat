package io.bigmoeonedge.example.chat.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Message states. Stored as strings so a schema reader can tell them apart without this file. */
object MessageStatus {
    const val QUEUED = "QUEUED"
    const val PREFILLING = "PREFILLING"
    const val GENERATING = "GENERATING"
    const val DONE = "DONE"
    const val CANCELLED = "CANCELLED"
    const val FAILED = "FAILED"
    const val INTERRUPTED = "INTERRUPTED"

    /** A reply the engine is working on right now. */
    val ACTIVE = listOf(PREFILLING, GENERATING)
}

object Role {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

/** What the engine process is doing, written by it and read by the UI. */
enum class EngineStateName { IDLE, LOADING, READY, BUSY, ERROR, SCANNING }

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val modelPath: String,
    val systemPrompt: String = "",
    val thinking: Boolean = false,
    // How long the model may think when [thinking] is on: a [io.bigmoeonedge.example.chat.ThinkLevel] name.
    val thinkLevel: String = "LOW",
    val createdAt: Long,
    val updatedAt: Long,
    // The newest reply the user has seen. A DONE assistant message finished after it is "unread".
    val lastReadAt: Long = createdAt,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index("status")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val text: String = "",
    val reasoning: String = "",
    val status: String = MessageStatus.DONE,
    val error: String? = null,
    val tokens: Int = 0,
    val tokPerSec: Double = 0.0,
    val prefillS: Double = 0.0,
    val metrics: String = "",
    // True when the engine had to drop this message to fit the context window.
    val outOfContext: Boolean = false,
    // How often this reply was started again after the engine process died under it.
    val attempt: Int = 0,
    // Display order. A regenerated reply takes the place of the one it replaces.
    val createdAt: Long,
    val finishedAt: Long? = null,
    // FIFO key across conversations: when this reply joined the queue (not where it sits on screen).
    val queuedAt: Long = createdAt,
    // The engine ended the reasoning (the chat's thinking budget, or Answer now) instead of the model,
    // and how many tokens the reasoning held.
    val thinkingCut: Boolean = false,
    val thinkingTokens: Int = 0,
)

/** Single row (id 0): the engine process's state, for the UI to show. */
@Entity(tableName = "engine_status")
data class EngineStatusEntity(
    @PrimaryKey val id: Int = 0,
    val state: String = EngineStateName.IDLE.name,
    val modelPath: String = "",
    val sessionSig: String = "",
    val activeMessageId: Long? = null,
    val step: Int = 0,
    val tokPerSec: Double = 0.0,
    // From PowerManager; the sysfs thermal zones are unreadable on the target phone.
    val thermalStatus: Int = 0,
    val ioMode: String = "",
    val lastError: String? = null,
    // Why the queue is not moving although work is waiting ("Paused, low battery", "Scan running").
    val pausedReason: String? = null,
    // Free text shown in the banner and the notification ("Loading Ling-tiny...", "Cooling 2:10").
    val detail: String = "",
    val updatedAt: Long = 0,
)

/** Single row (id 0): which thread the user is looking at, so the engine knows whether to notify. */
@Entity(tableName = "ui_presence")
data class UiPresenceEntity(
    @PrimaryKey val id: Int = 0,
    val visibleConversationId: Long? = null,
    val updatedAt: Long = 0,
)
