package io.bigmoeonedge.example.chat.data

/**
 * The queue's decisions, free of Android and the database so they can be tested on the JVM.
 */
object QueueRules {
    /** The reply to run next: the oldest QUEUED assistant message across all conversations. */
    fun pickNext(messages: List<MessageEntity>): MessageEntity? =
        messages
            .filter { it.role == Role.ASSISTANT && it.status == MessageStatus.QUEUED }
            .minWithOrNull(compareBy({ it.queuedAt }, { it.id }))

    /**
     * "Queued, N ahead": queued replies older than [m], plus one if a job is running. 0 means it is
     * next, with nothing running.
     */
    fun aheadOf(m: MessageEntity, queuedOldestFirst: List<Long>, running: Boolean): Int {
        val before = queuedOldestFirst.indexOf(m.id).let { if (it < 0) queuedOldestFirst.size else it }
        return before + if (running) 1 else 0
    }

    /** What a message left PREFILLING or GENERATING by a dead engine process becomes. */
    data class Recovery(val status: String, val attempt: Int, val error: String?)

    /** One automatic retry, then FAILED with a Retry button in the UI. */
    fun recover(m: MessageEntity): Recovery =
        if (m.attempt < 1) Recovery(MessageStatus.QUEUED, m.attempt + 1, null)
        else Recovery(MessageStatus.FAILED, m.attempt, "Interrupted")

    /** Title for a new chat: the first [max] characters of its first message, on one line. */
    fun titleFrom(text: String, max: Int = 40): String {
        val one = text.trim().replace(Regex("\\s+"), " ")
        return if (one.length <= max) one else one.take(max).trimEnd() + "…"
    }
}
