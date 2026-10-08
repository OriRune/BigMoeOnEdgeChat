package io.bigmoeonedge.example.chat.data

/** One entry of the conversation as the engine sees it. */
data class EngineMessage(val role: String, val content: String)

/**
 * What one reply job sends to the engine: the seeded [history], the [prompt] (the last user turn)
 * and, for each history entry, the database rows it was built from, so `history_dropped` can be
 * mapped back to rows.
 */
data class JobHistory(
    val history: List<EngineMessage>,
    val prompt: String,
    /** Parallel to [history]; empty for the system entry. */
    val rowIds: List<List<Long>>,
    /** Rows folded into [prompt]. */
    val promptRowIds: List<Long>,
)

/**
 * Turns the stored conversation into the engine's alternating-role history. Pure, so the rules that
 * decide what the model reads are unit-tested.
 */
object HistoryBuilder {
    const val MERGE_SEPARATOR = "\n\n"

    /**
     * @param messages the conversation in display order (createdAt, id).
     * @param placeholderId the QUEUED reply this job answers; everything before it is context.
     */
    fun build(systemPrompt: String, messages: List<MessageEntity>, placeholderId: Long): JobHistory? {
        val at = messages.indexOfFirst { it.id == placeholderId }
        if (at < 0) return null
        val entries = ArrayList<EngineMessage>()
        val rows = ArrayList<MutableList<Long>>()
        if (systemPrompt.isNotBlank()) {
            entries += EngineMessage("system", systemPrompt)
            rows.add(mutableListOf())
        }
        for (m in messages.subList(0, at)) {
            // A reply that never completed is not something the model said; a partial one would
            // teach it to stop mid-sentence.
            if (m.role == Role.ASSISTANT && m.status != MessageStatus.DONE) continue
            // Chat templates expect alternating roles, so consecutive turns of one role are one turn.
            val last = entries.lastOrNull()
            if (last != null && last.role == m.role) {
                entries[entries.lastIndex] = last.copy(content = last.content + MERGE_SEPARATOR + m.text)
                rows.last() += m.id
            } else {
                entries += EngineMessage(m.role, m.text)
                rows.add(mutableListOf(m.id))
            }
        }
        // The prompt is the trailing user turn. With none (an assistant first, or a failed reply
        // with nothing after it) there is nothing to answer.
        val tail = entries.lastOrNull()
        if (tail == null || tail.role != Role.USER) return null
        entries.removeAt(entries.lastIndex)
        val promptRows = rows.removeAt(rows.lastIndex)
        return JobHistory(entries, tail.content, rows, promptRows)
    }

    /**
     * Rows to mark `outOfContext` when the engine reports [dropped] messages gone. The engine drops
     * from the front of the non-system history, so these are the first [dropped] non-system entries.
     */
    fun droppedRows(job: JobHistory, dropped: Int): List<Long> {
        if (dropped <= 0) return emptyList()
        val out = ArrayList<Long>()
        var left = dropped
        for ((i, e) in job.history.withIndex()) {
            if (e.role == "system") continue
            if (left == 0) break
            out += job.rowIds[i]
            left--
        }
        return out
    }
}
