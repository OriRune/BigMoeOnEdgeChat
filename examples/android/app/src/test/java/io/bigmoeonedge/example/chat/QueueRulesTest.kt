package io.bigmoeonedge.example.chat

import io.bigmoeonedge.example.chat.data.MessageEntity
import io.bigmoeonedge.example.chat.data.MessageStatus
import io.bigmoeonedge.example.chat.data.QueueRules
import io.bigmoeonedge.example.chat.data.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueRulesTest {
    private fun reply(id: Long, queuedAt: Long, status: String = MessageStatus.QUEUED, attempt: Int = 0) =
        MessageEntity(
            id = id, conversationId = id, role = Role.ASSISTANT, status = status, createdAt = 0, queuedAt = queuedAt,
            attempt = attempt,
        )

    @Test fun oldestQueuedReplyRunsFirstAcrossConversations() {
        val all = listOf(reply(3, 300), reply(1, 100), reply(2, 200))
        assertEquals(1L, QueueRules.pickNext(all)!!.id)
    }

    @Test fun onlyQueuedAssistantMessagesAreCandidates() {
        val user = MessageEntity(id = 9, conversationId = 1, role = Role.USER, createdAt = 0, queuedAt = 0)
        val all = listOf(user, reply(1, 10, MessageStatus.DONE), reply(2, 20, MessageStatus.GENERATING), reply(3, 30))
        assertEquals(3L, QueueRules.pickNext(all)!!.id)
        assertNull(QueueRules.pickNext(listOf(user)))
    }

    @Test fun equalTimesBreakTiesById() {
        assertEquals(4L, QueueRules.pickNext(listOf(reply(5, 100), reply(4, 100)))!!.id)
    }

    @Test fun aRequeuedReplyKeepsItsPlaceInLine() {
        // Recovery re-queues with the original queuedAt, so it does not jump behind newer work.
        assertEquals(1L, QueueRules.pickNext(listOf(reply(2, 200), reply(1, 100, attempt = 1)))!!.id)
    }

    @Test fun aheadCountsOlderQueuedRepliesAndTheRunningOne() {
        val m = reply(3, 300)
        assertEquals(0, QueueRules.aheadOf(m, listOf(3), running = false))
        assertEquals(1, QueueRules.aheadOf(m, listOf(3), running = true))
        assertEquals(3, QueueRules.aheadOf(m, listOf(1, 2, 3), running = true))
    }

    @Test fun anInterruptedReplyGetsOneRetryThenFails() {
        val first = QueueRules.recover(reply(1, 1, MessageStatus.GENERATING, attempt = 0))
        assertEquals(MessageStatus.QUEUED, first.status)
        assertEquals(1, first.attempt)
        val second = QueueRules.recover(reply(1, 1, MessageStatus.GENERATING, attempt = 1))
        assertEquals(MessageStatus.FAILED, second.status)
        assertEquals("Interrupted", second.error)
    }

    @Test fun titleIsTheFirstFortyCharactersOnOneLine() {
        assertEquals("hello world", QueueRules.titleFrom("  hello\n\n world  "))
        val t = QueueRules.titleFrom("x".repeat(100))
        assertEquals(41, t.length)
        assertEquals('…', t.last())
    }
}
