package io.bigmoeonedge.example.chat

import androidx.test.platform.app.InstrumentationRegistry
import io.bigmoeonedge.example.chat.data.ChatDb
import io.bigmoeonedge.example.chat.data.ChatRepository
import io.bigmoeonedge.example.chat.data.ConversationEntity
import io.bigmoeonedge.example.chat.data.MessageEntity
import io.bigmoeonedge.example.chat.data.MessageStatus
import io.bigmoeonedge.example.chat.data.Role
import io.bigmoeonedge.example.chat.engine.EngineBackend
import io.bigmoeonedge.example.chat.engine.EngineHost
import io.bigmoeonedge.example.chat.engine.EngineRunner
import io.bigmoeonedge.example.chat.engine.FakeEngineBackend
import io.bigmoeonedge.example.chat.engine.JobConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The job loop against the fake engine, without any service around it. */
class EngineRunnerTest {
    private lateinit var db: ChatDb
    private lateinit var repo: ChatRepository
    private lateinit var runner: EngineRunner
    private val finished = CopyOnWriteArrayList<Long>()
    private var backends = 0
    @Volatile private var pause: String? = null

    private val host = object : EngineHost {
        override suspend fun jobConfig(conv: ConversationEntity) =
            JobConfig(listOf("fake"), "sig", emptyMap(), null, 128, 4096, true, "fake")

        override fun createBackend(cfg: JobConfig): EngineBackend {
            backends++
            return FakeEngineBackend(tokPerSec = { 200.0 }, loadMs = 50)
        }

        override suspend fun clearOtherEngines(selfPid: Int) {}
        override fun foregroundText(text: String) {}
        override fun holdWake(on: Boolean) {}
        override fun pauseReason(): String? = pause
        override fun thermalStatus() = 0
        override fun keepLoadedMinutes() = -1
        override suspend fun replyFinished(messageId: Long, conversationId: Long, status: String) {
            finished += messageId
        }

        override fun idle(modelLoaded: Boolean) {}
    }

    @Before fun open() {
        db = ChatDb.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        repo = ChatRepository(db, { if (::runner.isInitialized) runner.kick() })
        runner = EngineRunner(db, host)
    }

    @After fun close() {
        runner.shutdown()
        // The runner's last status writes land after a reply's final status; closing under them crashes the
        // whole instrumentation process, so let them finish.
        runBlocking { delay(400) }
        db.close()
    }

    private suspend fun until(what: String, cond: suspend () -> Boolean) {
        try {
            withTimeout(20_000) { while (!cond()) delay(40) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private suspend fun conv() = repo.createConversation("/m.gguf", "", false)
    private suspend fun replies(c: Long) = db.messages().listFor(c).filter { it.role == Role.ASSISTANT }

    @Test fun threeQueuedMessagesInTwoConversationsAreAnsweredInOrder() = runBlocking {
        val a = conv()
        val b = conv()
        repo.send(a, "A one")
        repo.send(b, "B one")
        repo.send(a, "A two")
        runner.start()
        until("both replies") { replies(a).single().status == MessageStatus.DONE && replies(b).single().status == MessageStatus.DONE }
        val ra = replies(a).single()
        val rb = replies(b).single()
        // a was queued first, and its reply saw both of its messages.
        assertTrue(ra.finishedAt!! <= rb.finishedAt!!)
        assertTrue(ra.text, ra.text.contains("A one") && ra.text.contains("A two"))
        // The callback follows the status write by a few database calls.
        until("both reply callbacks") { finished.size == 2 }
        assertEquals(listOf(ra.id, rb.id), finished.toList())
        assertEquals(1, backends)
    }

    @Test fun aThinkingChatCutsTheReasoningAtItsBudgetAndStillAnswers() = runBlocking {
        val c = repo.createConversation("/m.gguf", "", true, thinkLevel = ThinkLevel.LOW)
        runner.start()
        repo.send(c, "think hard [thinklong]")
        until("the reply") { replies(c).singleOrNull()?.status == MessageStatus.DONE }
        until("the callback") { finished.size == 1 }
        val r = replies(c).single()
        assertTrue(r.thinkingCut)
        assertEquals(ThinkLevel.LOW.tokens, r.thinkingTokens)
        assertTrue(r.reasoning, r.reasoning.contains("idea${ThinkLevel.LOW.tokens} ") && !r.reasoning.contains("idea${ThinkLevel.LOW.tokens + 1} "))
        assertTrue("the answer follows the cut", r.text.isNotEmpty())
    }

    @Test fun answerNowEndsTheThinkingAndTheModelAnswers() = runBlocking {
        val c = repo.createConversation("/m.gguf", "", true, thinkLevel = ThinkLevel.HIGH)
        runner.start()
        repo.send(c, "think hard [thinklong]")
        until("it is thinking") { replies(c).singleOrNull()?.reasoning?.isNotEmpty() == true }
        runner.endThinking(replies(c).single().id)
        until("the reply") { replies(c).single().status == MessageStatus.DONE }
        until("the callback") { finished.size == 1 }
        val r = replies(c).single()
        assertTrue(r.thinkingCut)
        assertTrue("cut well before the budget", r.thinkingTokens in 1 until 600)
        assertTrue(r.text.isNotEmpty())
    }

    @Test fun aThinkingChatWithoutACutIsNotMarked() = runBlocking {
        val c = repo.createConversation("/m.gguf", "", true)
        runner.start()
        repo.send(c, "quick")
        until("the reply") { replies(c).singleOrNull()?.status == MessageStatus.DONE }
        until("the callback") { finished.size == 1 }
        assertEquals(false, replies(c).single().thinkingCut)
    }

    @Test fun theSecondTurnIsSeededWithTheFirst() = runBlocking {
        val c = conv()
        runner.start()
        repo.send(c, "first")
        until("first reply") { replies(c).singleOrNull()?.status == MessageStatus.DONE }
        repo.send(c, "second")
        until("second reply") { replies(c).count { it.status == MessageStatus.DONE } == 2 }
        // history was user+assistant, so the fake saw 2 earlier messages.
        assertTrue(replies(c).last().text, replies(c).last().text.contains("2 earlier messages"))
    }

    @Test fun aRejectedRequestFailsOnlyThatReply() = runBlocking {
        val c = conv()
        runner.start()
        repo.send(c, "please [fail]")
        until("failure") { replies(c).single().status == MessageStatus.FAILED }
        assertTrue(replies(c).single().error!!.contains("refused"))
        repo.send(c, "now fine")
        until("recovery") { replies(c).any { it.status == MessageStatus.DONE } }
        assertEquals(1, backends)
    }

    @Test fun aCrashFailsTheReplyAndTheNextOneReloads() = runBlocking {
        val c = conv()
        runner.start()
        repo.send(c, "[crash]")
        until("crash") { replies(c).single().status == MessageStatus.FAILED }
        repo.send(c, "after")
        until("reload") { replies(c).any { it.status == MessageStatus.DONE } }
        assertEquals(2, backends)
    }

    @Test fun stopCancelsTheRunningReplyAndKeepsItsText() = runBlocking {
        val c = conv()
        runner.start()
        repo.send(c, "write a lot [long] [slow]")
        until("generating") { replies(c).single().status == MessageStatus.GENERATING }
        runner.cancel(replies(c).single().id)
        until("cancelled") { replies(c).single().status == MessageStatus.CANCELLED }
    }

    @Test fun anInterruptedReplyIsRetriedOnceThenFails() = runBlocking {
        val c = conv()
        val now = System.currentTimeMillis()
        db.messages().insert(MessageEntity(conversationId = c, role = Role.USER, text = "q", createdAt = now))
        val first = db.messages().insert(
            MessageEntity(
                conversationId = c, role = Role.ASSISTANT, status = MessageStatus.GENERATING, createdAt = now + 1,
                queuedAt = now + 1, text = "partial",
            ),
        )
        runner.recover()
        val requeued = db.messages().get(first)!!
        assertEquals(MessageStatus.QUEUED, requeued.status)
        assertEquals(1, requeued.attempt)
        assertEquals("", requeued.text)

        db.messages().setStatus(first, MessageStatus.GENERATING)
        runner.recover()
        assertEquals(MessageStatus.FAILED, db.messages().get(first)!!.status)
        assertEquals("Interrupted", db.messages().get(first)!!.error)
    }

    @Test fun reportedDropsMarkTheOldestMessagesOutOfContext() = runBlocking {
        val c = conv()
        runner.start()
        repo.send(c, "one")
        until("r1") { replies(c).singleOrNull()?.status == MessageStatus.DONE }
        repo.send(c, "[drop2] two")
        until("r2") { replies(c).count { it.status == MessageStatus.DONE } == 2 }
        val flagged = db.messages().listFor(c).filter { it.outOfContext }
        assertEquals(listOf(Role.USER, Role.ASSISTANT), flagged.map { it.role })
    }

    @Test fun lowBatteryHoldsTheQueueUntilItClears() = runBlocking {
        val c = conv()
        pause = "Paused — low battery"
        repo.send(c, "hello")
        runner.start()
        until("paused status") { db.engineStatus().get()?.pausedReason != null }
        assertEquals(MessageStatus.QUEUED, replies(c).single().status)
        pause = null
        runner.kick()
        until("answered") { replies(c).single().status == MessageStatus.DONE }
    }

    @Test fun suspendRequeuesTheRunningReplyAndHoldsTheQueue() = runBlocking {
        val c = conv()
        runner.start()
        repo.send(c, "go [long] [slow]")
        until("generating") { replies(c).single().status == MessageStatus.GENERATING }
        runner.suspend()
        until("requeued") { replies(c).single().status == MessageStatus.QUEUED }
        delay(500)
        assertEquals(MessageStatus.QUEUED, replies(c).single().status)
        runner.resume()
        until("done after resume") { replies(c).single().status == MessageStatus.DONE }
    }
}
