package io.bigmoeonedge.example.chat.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import io.bigmoeonedge.example.chat.ChatFormat
import io.bigmoeonedge.example.chat.ChatSettings
import io.bigmoeonedge.example.chat.data.ChatDb
import io.bigmoeonedge.example.chat.data.MessageEntity
import io.bigmoeonedge.example.chat.data.MessageStatus
import io.bigmoeonedge.example.chat.data.Role
import io.bigmoeonedge.example.chat.engine.EngineService
import io.bigmoeonedge.example.chat.ui.ChatActivity

/**
 * One notification per conversation, updated in place, in a messaging style so the lock screen can
 * show the exchange and take an inline reply. Posted by the engine process when a reply finishes
 * and the thread is not on screen.
 */
class ReplyNotifier(private val ctx: Context, private val db: ChatDb) {
    private val nm = NotificationManagerCompat.from(ctx)

    fun ensureChannel() {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_REPLIES, "Replies", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A reply from the model, when its chat is not open"
            },
        )
    }

    /** [queuedNote] adds the "Queued" subtitle after an inline reply, so the spinner stops. */
    suspend fun post(conversationId: Long, queuedNote: Boolean = false) {
        if (!nm.areNotificationsEnabled()) return
        ensureChannel()
        val conv = db.conversations().get(conversationId) ?: return
        val msgs = db.messages().listFor(conversationId)
        val shown = msgs.filter { it.role == Role.USER || it.status == MessageStatus.DONE || it.status == MessageStatus.FAILED }
            .takeLast(MAX_SHOWN)
        val you = Person.Builder().setName("You").build()
        val model = Person.Builder().setName(ChatFormat.modelShortName(conv.modelPath)).build()
        val style = NotificationCompat.MessagingStyle(you).setConversationTitle(conv.title)
        for (m in shown) {
            val text = when {
                m.role == Role.USER -> m.text
                m.status == MessageStatus.FAILED -> "Failed: ${m.error ?: "error"}"
                else -> m.text.ifEmpty { "(no text)" }
            }
            style.addMessage(text, m.finishedAt ?: m.createdAt, if (m.role == Role.USER) you else model)
        }
        val failed = shown.lastOrNull()?.takeIf { it.role == Role.ASSISTANT && it.status == MessageStatus.FAILED }

        val b = NotificationCompat.Builder(ctx, CHANNEL_REPLIES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(queuedNote)
            .setContentIntent(open(conversationId))
            .addAction(replyAction(conversationId))
        if (ChatSettings.run { refreshFromDisk(ctx); load(ctx) }.lockScreenText) {
            b.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        } else {
            b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            b.setPublicVersion(
                NotificationCompat.Builder(ctx, CHANNEL_REPLIES).setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(conv.title).setContentText("New reply").build(),
            )
        }
        if (queuedNote) b.setSubText("Queued")
        if (failed != null) b.addAction(0, "Retry", service(EngineService.ACTION_RETRY, conversationId, failed.id))
        else b.addAction(0, "Mark read", service(EngineService.ACTION_MARK_READ, conversationId))
        runCatching { nm.notify(notificationId(conversationId), b.build()) }
    }

    /** One notification when a scan ends, on the reply channel so it is heard. */
    fun postScanFinished(run: io.bigmoeonedge.example.chat.data.ScanRunEntity) {
        if (!nm.areNotificationsEnabled()) return
        ensureChannel()
        val model = ChatFormat.modelShortName(run.modelPath)
        val (title, text) = when (run.status) {
            io.bigmoeonedge.example.chat.data.ScanRunStatus.DONE -> "Scan finished · $model" to run.verdict.ifEmpty { "Open the results." }
            io.bigmoeonedge.example.chat.data.ScanRunStatus.STOPPED -> "Scan stopped · $model" to "Finished cells are kept; resume any time."
            else -> "Scan failed · $model" to run.verdict
        }
        val open = PendingIntent.getActivity(
            ctx, SCAN_NOTIF_ID,
            Intent(ctx, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_OPEN_SCAN, run.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_REPLIES)
            .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify(SCAN_NOTIF_ID, n) }
    }

    fun cancel(conversationId: Long) = nm.cancel(notificationId(conversationId))

    private fun open(conversationId: Long): PendingIntent {
        val i = Intent(ctx, ChatActivity::class.java)
            .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            ctx, conversationId.toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun replyAction(conversationId: Long): NotificationCompat.Action {
        val input = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()
        val i = Intent(ctx, EngineService::class.java).setAction(EngineService.ACTION_REPLY)
            .putExtra(EngineService.EXTRA_CONVERSATION_ID, conversationId)
        // Mutable: the system fills the typed text into this intent.
        val pi = PendingIntent.getForegroundService(
            ctx, REPLY_CODE_BASE + conversationId.toInt(), i, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, "Reply", pi)
            .addRemoteInput(input)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(false)
            // Answering a chat is not sensitive: let it run from a secure lock screen without unlocking.
            .setAuthenticationRequired(false)
            .build()
    }

    private fun service(action: String, conversationId: Long, messageId: Long = -1): PendingIntent {
        val i = Intent(ctx, EngineService::class.java).setAction(action)
            .putExtra(EngineService.EXTRA_CONVERSATION_ID, conversationId)
            .putExtra(EngineService.EXTRA_MESSAGE_ID, messageId)
        return PendingIntent.getForegroundService(
            ctx, action.hashCode() xor conversationId.toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_REPLIES = "replies"
        const val KEY_REPLY = "reply"
        private const val MAX_SHOWN = 4
        const val SCAN_NOTIF_ID = 1002
        private const val REPLY_CODE_BASE = 1_000_000

        /** Clear of the engine's and the scan's fixed ids (1001..1003), which a small conversation id would hit. */
        fun notificationId(conversationId: Long): Int = 2_000_000 + conversationId.toInt()

        fun replyText(intent: Intent): CharSequence? = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)
    }
}

/** Whether the user is looking at [conversationId] right now. A dead UI process cannot be looking. */
fun isThreadVisible(ctx: Context, visibleId: Long?, conversationId: Long): Boolean {
    if (visibleId == null || visibleId != conversationId) return false
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    return am.runningAppProcesses?.any { it.processName == ctx.packageName } == true
}
