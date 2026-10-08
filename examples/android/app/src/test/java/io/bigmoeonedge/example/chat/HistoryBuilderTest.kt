package io.bigmoeonedge.example.chat

import io.bigmoeonedge.example.chat.data.EngineMessage
import io.bigmoeonedge.example.chat.data.HistoryBuilder
import io.bigmoeonedge.example.chat.data.MessageEntity
import io.bigmoeonedge.example.chat.data.MessageStatus
import io.bigmoeonedge.example.chat.data.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryBuilderTest {
    private var next = 1L
    private fun msg(role: String, text: String, status: String = MessageStatus.DONE) =
        MessageEntity(id = next, conversationId = 1, role = role, text = text, status = status, createdAt = next++)

    private fun u(t: String) = msg(Role.USER, t)
    private fun a(t: String, st: String = MessageStatus.DONE) = msg(Role.ASSISTANT, t, st)
    private fun q() = msg(Role.ASSISTANT, "", MessageStatus.QUEUED)

    @Test fun lastUserTurnIsThePromptAndTheRestIsHistory() {
        val m = listOf(u("hi"), a("hello"), u("how are you"), q())
        val j = HistoryBuilder.build("", m, m.last().id)!!
        assertEquals("how are you", j.prompt)
        assertEquals(listOf(EngineMessage("user", "hi"), EngineMessage("assistant", "hello")), j.history)
    }

    @Test fun consecutiveUserMessagesAreMergedWithABlankLine() {
        val m = listOf(u("one"), u("two"), u("three"), q())
        val j = HistoryBuilder.build("", m, m.last().id)!!
        assertEquals("one\n\ntwo\n\nthree", j.prompt)
        assertEquals(emptyList<EngineMessage>(), j.history)
        assertEquals(listOf(1L, 2L, 3L), j.promptRowIds)
    }

    @Test fun mergedTurnsInTheMiddleKeepAllTheirRows() {
        val m = listOf(u("a"), u("b"), a("r"), u("c"), q())
        val j = HistoryBuilder.build("", m, m.last().id)!!
        assertEquals(listOf(EngineMessage("user", "a\n\nb"), EngineMessage("assistant", "r")), j.history)
        assertEquals(listOf(listOf(1L, 2L), listOf(3L)), j.rowIds)
    }

    @Test fun systemPromptLeadsAndIsNeverMappedToRows() {
        val m = listOf(u("hi"), a("yo"), u("next"), q())
        val j = HistoryBuilder.build("be brief", m, m.last().id)!!
        assertEquals(EngineMessage("system", "be brief"), j.history.first())
        assertEquals(emptyList<Long>(), j.rowIds.first())
        assertEquals(3, j.history.size)
    }

    @Test fun aBlankSystemPromptIsLeftOut() {
        val m = listOf(u("hi"), q())
        assertEquals(emptyList<EngineMessage>(), HistoryBuilder.build("  ", m, m.last().id)!!.history)
    }

    @Test fun unfinishedRepliesAreSkippedSoTheUserTurnsAroundThemMerge() {
        val m = listOf(u("one"), a("partial", MessageStatus.FAILED), u("two"), a("x", MessageStatus.CANCELLED), u("three"), q())
        val j = HistoryBuilder.build("", m, m.last().id)!!
        assertEquals("one\n\ntwo\n\nthree", j.prompt)
        assertEquals(emptyList<EngineMessage>(), j.history)
    }

    @Test fun messagesAfterThePlaceholderAreNotContext() {
        // Regenerating an old reply: only what came before it is read.
        val first = listOf(u("q1"))
        val ph = q()
        val later = listOf(u("q2"), a("a2"))
        val j = HistoryBuilder.build("", first + ph + later, ph.id)!!
        assertEquals("q1", j.prompt)
        assertEquals(emptyList<EngineMessage>(), j.history)
    }

    @Test fun outOfContextRowsAreStillSentAndLeftToTheEngine() {
        val old = u("old").copy(outOfContext = true)
        val m = listOf(old, a("r"), u("new"), q())
        val j = HistoryBuilder.build("", m, m.last().id)!!
        assertEquals(2, j.history.size)
    }

    @Test fun noTrailingUserTurnMeansNothingToAnswer() {
        val m = listOf(u("hi"), a("hello"), q())
        assertNull(HistoryBuilder.build("", m, m.last().id))
    }

    @Test fun unknownPlaceholderIsNull() {
        assertNull(HistoryBuilder.build("", listOf(u("hi")), 99))
    }

    @Test fun droppedCountMapsToTheFirstNonSystemEntries() {
        val m = listOf(u("a"), u("b"), a("r1"), u("c"), a("r2"), u("d"), q())
        val j = HistoryBuilder.build("sys", m, m.last().id)!!
        // history: system, user(a+b), assistant(r1), user(c), assistant(r2); prompt d
        assertEquals(listOf(1L, 2L, 3L), HistoryBuilder.droppedRows(j, 2))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), HistoryBuilder.droppedRows(j, 4))
        assertEquals(emptyList<Long>(), HistoryBuilder.droppedRows(j, 0))
    }
}
