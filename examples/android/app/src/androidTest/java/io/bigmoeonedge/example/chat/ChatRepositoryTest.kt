package io.bigmoeonedge.example.chat

import androidx.test.platform.app.InstrumentationRegistry
import io.bigmoeonedge.example.chat.data.ChatDb
import io.bigmoeonedge.example.chat.data.ChatRepository
import io.bigmoeonedge.example.chat.data.MessageStatus
import io.bigmoeonedge.example.chat.data.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatRepositoryTest {
    private lateinit var db: ChatDb
    private lateinit var repo: ChatRepository
    private var now = 1_000L
    private var kicks = 0

    @Before fun open() {
        db = ChatDb.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        repo = ChatRepository(db, { kicks++ }, { now += 10; now })
    }

    @After fun close() = db.close()

    private fun conv() = runBlocking { repo.createConversation("/m.gguf", "", false) }

    @Test fun sendAddsTheMessageAndOneQueuedReply() = runBlocking {
        val c = conv()
        repo.send(c, "hello")
        val m = db.messages().listFor(c)
        assertEquals(listOf(Role.USER, Role.ASSISTANT), m.map { it.role })
        assertEquals(MessageStatus.DONE, m[0].status)
        assertEquals(MessageStatus.QUEUED, m[1].status)
        assertEquals(1, kicks)
        assertEquals("hello", db.conversations().get(c)!!.title)
    }

    @Test fun aBurstGetsOneReplyThatStaysBelowTheNewestMessage() = runBlocking {
        val c = conv()
        repo.send(c, "one")
        val placeholder = db.messages().queuedFor(c)!!
        repo.send(c, "two")
        repo.send(c, "three")
        val m = db.messages().listFor(c)
        assertEquals(1, m.count { it.status == MessageStatus.QUEUED })
        assertEquals(Role.ASSISTANT, m.last().role)
        val after = db.messages().queuedFor(c)!!
        assertEquals(placeholder.id, after.id)
        // It keeps its place in the queue, not its place on screen.
        assertEquals(placeholder.queuedAt, after.queuedAt)
    }

    @Test fun aMessageSentWhileAReplyRunsGetsItsOwnReply() = runBlocking {
        val c = conv()
        repo.send(c, "one")
        val running = db.messages().queuedFor(c)!!
        db.messages().setStatus(running.id, MessageStatus.GENERATING)
        repo.send(c, "two")
        assertEquals(1, db.messages().queuedCount(c))
    }

    @Test fun queueIsFifoAcrossConversations() = runBlocking {
        val a = conv()
        val b = conv()
        repo.send(a, "a1")
        repo.send(b, "b1")
        repo.send(a, "a2") // joins a's waiting reply, does not jump the queue or go to the back
        assertEquals(listOf(a, b), db.messages().queued().map { it.conversationId })
    }

    @Test fun regenerateTakesTheLastRepliesPlace() = runBlocking {
        val c = conv()
        repo.send(c, "q")
        val reply = db.messages().queuedFor(c)!!
        db.messages().finish(reply.id, "answer", "", MessageStatus.DONE, null, 3, 1.0, 0.1, "", now)
        repo.regenerate(reply.id)
        val m = db.messages().listFor(c)
        assertEquals(listOf(Role.USER, Role.ASSISTANT), m.map { it.role })
        assertNull(m.find { it.id == reply.id })
        assertEquals(MessageStatus.QUEUED, m[1].status)
        assertEquals(reply.createdAt, m[1].createdAt)
    }

    @Test fun anOlderReplyIsNotRegenerated() = runBlocking {
        val c = conv()
        repo.send(c, "q1")
        val r1 = db.messages().queuedFor(c)!!
        db.messages().finish(r1.id, "a1", "", MessageStatus.DONE, null, 1, 1.0, 0.1, "", now)
        repo.send(c, "q2")
        repo.regenerate(r1.id)
        assertEquals(4, db.messages().listFor(c).size)
        assertTrue(db.messages().get(r1.id) != null)
    }

    @Test fun editAndResendDropsEverythingAfterTheMessage() = runBlocking {
        val c = conv()
        repo.send(c, "first")
        val r1 = db.messages().queuedFor(c)!!
        db.messages().finish(r1.id, "ans", "", MessageStatus.DONE, null, 1, 1.0, 0.1, "", now)
        repo.send(c, "second")
        val second = db.messages().listFor(c).last { it.role == Role.USER }
        repo.editAndResend(second.id, "second, edited")
        val m = db.messages().listFor(c)
        assertEquals("second, edited", m.first { it.id == second.id }.text)
        assertEquals(Role.ASSISTANT, m.last().role)
        assertEquals(MessageStatus.QUEUED, m.last().status)
        assertEquals(4, m.size)
    }

    @Test fun retryRequeuesAFailedReplyAndClearsItsText() = runBlocking {
        val c = conv()
        repo.send(c, "q")
        val r = db.messages().queuedFor(c)!!
        db.messages().finish(r.id, "half", "", MessageStatus.FAILED, "boom", 1, 1.0, 0.1, "", now)
        repo.retry(r.id)
        val again = db.messages().get(r.id)!!
        assertEquals(MessageStatus.QUEUED, again.status)
        assertEquals("", again.text)
        assertNull(again.error)
    }

    @Test fun deletingAConversationDeletesItsMessages() = runBlocking {
        val c = conv()
        repo.send(c, "q")
        repo.deleteConversation(c)
        assertTrue(db.messages().listFor(c).isEmpty())
    }

    @Test fun searchFindsTitlesAndMessageText() = runBlocking {
        val a = conv()
        val b = conv()
        repo.send(a, "about pelicans")
        repo.send(b, "something else")
        repo.rename(b, "Gardening")
        assertEquals(listOf(a), repo.search("pelican").first().map { it.id })
        assertEquals(listOf(b), repo.search("garden").first().map { it.id })
        assertTrue(repo.search("100%").first().isEmpty())
    }

    @Test fun outOfContextFlagsAreReplacedNotAccumulated() = runBlocking {
        val c = conv()
        repo.send(c, "a")
        repo.send(c, "b")
        val ids = db.messages().listFor(c).map { it.id }
        db.messages().replaceOutOfContext(c, listOf(ids[0]))
        db.messages().replaceOutOfContext(c, listOf(ids[1]))
        assertEquals(listOf(ids[1]), db.messages().listFor(c).filter { it.outOfContext }.map { it.id })
    }
}
