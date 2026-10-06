package io.bigmoeonedge.example.chat

import androidx.test.platform.app.InstrumentationRegistry
import io.bigmoeonedge.example.chat.data.ChatDb
import io.bigmoeonedge.example.chat.data.ChatRepository
import io.bigmoeonedge.example.chat.data.ConversationEntity
import io.bigmoeonedge.example.chat.data.EngineStateName
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Two hosts (the :engine process and the main-process service) sharing one queue, one engine at a time. */
class ForegroundModeTest {
    private lateinit var db: ChatDb
    private lateinit var repo: ChatRepository
    private val slotTaken = AtomicBoolean(false)
    private val holders = AtomicInteger(0)
    private val maxHolders = AtomicInteger(0)
    private val runners = mutableListOf<EngineRunner>()
    private val backends = mutableMapOf<String, AtomicInteger>()

    private inner class TestHost(val name: String, val modelPath: String, override val primary: Boolean) : EngineHost {
        val answered = java.util.concurrent.CopyOnWriteArrayList<Long>()
        override fun handles(modelPath: String) = modelPath == this.modelPath || (primary && modelPath != FG_MODEL)

        override suspend fun jobConfig(conv: ConversationEntity) =
            JobConfig(listOf("fake"), "sig-$name", emptyMap(), null, 128, 4096, true, name)

        override fun createBackend(cfg: JobConfig): EngineBackend {
            backends.getOrPut(name) { AtomicInteger() }.incrementAndGet()
            return FakeEngineBackend(tokPerSec = { 100.0 }, loadMs = 50)
        }

        override fun tryEngineSlot(): AutoCloseable? {
            if (!slotTaken.compareAndSet(false, true)) return null
            maxHolders.accumulateAndGet(holders.incrementAndGet()) { a, b -> maxOf(a, b) }
            val released = AtomicBoolean(false)
            return AutoCloseable {
                if (released.compareAndSet(false, true)) {
                    holders.decrementAndGet()
                    slotTaken.set(false)
                }
            }
        }

        override suspend fun clearOtherEngines(selfPid: Int) {}
        override fun foregroundText(text: String) {}
        override fun holdWake(on: Boolean) {}
        override fun pauseReason(): String? = null
        override fun thermalStatus() = 0
        override fun keepLoadedMinutes() = -1
        override suspend fun replyFinished(messageId: Long, conversationId: Long, status: String) {
            answered += messageId
        }

        override fun idle(modelLoaded: Boolean) {}
    }

    private lateinit var engineHost: TestHost
    private lateinit var fgHost: TestHost
    private lateinit var engineRunner: EngineRunner
    private lateinit var fgRunner: EngineRunner

    @Before fun open() {
        db = ChatDb.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        engineHost = TestHost("engine", BG_MODEL, primary = true)
        fgHost = TestHost("fg", FG_MODEL, primary = false)
        repo = ChatRepository(db, { runners.forEach { it.kick() } })
        engineRunner = EngineRunner(db, engineHost)
        fgRunner = EngineRunner(db, fgHost)
        runners += engineRunner
        runners += fgRunner
    }

    @After fun close() {
        runners.forEach { it.shutdown() }
        db.close()
    }

    private suspend fun until(what: String, cond: suspend () -> Boolean) {
        try {
            withTimeout(30_000) { while (!cond()) delay(40) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private suspend fun reply(c: Long) = db.messages().listFor(c).first { it.role == Role.ASSISTANT }

    @Test fun eachHostAnswersOnlyItsOwnModelsAndNeverTogether() = runBlocking {
        val bg = repo.createConversation(BG_MODEL, "", false)
        val fg = repo.createConversation(FG_MODEL, "", false)
        repeat(3) {
            repo.send(bg, "bg $it")
            repo.send(fg, "fg $it")
        }
        engineRunner.start()
        fgRunner.start()
        until("both conversations answered") {
            val msgs = db.messages()
            listOf(bg, fg).all { c -> msgs.listFor(c).any { it.role == Role.ASSISTANT && it.status == MessageStatus.DONE } }
        }
        until("queue empty") { db.messages().queued().isEmpty() && db.messages().active().isEmpty() }
        // The foreground host never touched the :engine model, and the :engine host never the foreground one.
        val bgReply = reply(bg)
        val fgReply = reply(fg)
        assertEquals(listOf(bgReply.id), engineHost.answered.toList())
        assertEquals(listOf(fgReply.id), fgHost.answered.toList())
        assertEquals("one engine at a time across both hosts", 1, maxHolders.get())
        assertEquals(1, backends["engine"]?.get())
        assertEquals(1, backends["fg"]?.get())
    }

    @Test fun aReplyForTheOtherHostStaysQueuedWhileThisHostIsTheOnlyOneRunning() = runBlocking {
        val fg = repo.createConversation(FG_MODEL, "", false)
        repo.send(fg, "waiting for the app to be open")
        engineRunner.start() // only the :engine host is up, as when the app is closed
        delay(600)
        assertEquals(MessageStatus.QUEUED, db.messages().listFor(fg).last().status)
        assertEquals(0, backends["engine"]?.get() ?: 0)
        fgRunner.start()
        until("answered once the foreground host runs") { reply(fg).status == MessageStatus.DONE }
    }

    @Test fun aFrozenReplyMakesNoProgressAndFinishesWhenThawed() = runBlocking {
        val fg = repo.createConversation(FG_MODEL, "", false)
        repo.send(fg, "write something long [long]")
        fgRunner.start()
        until("some text") { db.messages().listFor(fg).any { it.role == Role.ASSISTANT && it.text.length > 40 } }

        fgRunner.freeze(true)
        until("paused status") { db.engineStatus().get()?.pausedReason?.contains("return to the app") == true }
        val a = reply(fg).text.length
        delay(700)
        val b = reply(fg).text.length
        assertTrue("no tokens while frozen ($a -> $b)", b - a <= 8)
        assertEquals(MessageStatus.GENERATING, reply(fg).status)

        fgRunner.freeze(false)
        until("finished after thaw") { reply(fg).status == MessageStatus.DONE }
        assertNull(db.engineStatus().get()?.pausedReason)
    }

    @Test fun leavingTheAppWithAnIdleLoadedModelUnloadsIt() = runBlocking {
        val fg = repo.createConversation(FG_MODEL, "", false)
        repo.send(fg, "hello")
        fgRunner.start()
        until("answered and loaded") {
            reply(fg).status == MessageStatus.DONE && db.engineStatus().get()?.state == EngineStateName.READY.name
        }
        assertTrue(db.engineStatus().get()!!.modelPath.isNotEmpty())
        fgRunner.freeze(true)
        until("model unloaded") { db.engineStatus().get()?.state == EngineStateName.IDLE.name }
        assertEquals("", db.engineStatus().get()?.modelPath)
    }

    @Test fun anIdleSecondaryHostDoesNotOverwriteTheOtherHostsStatus() = runBlocking {
        val bg = repo.createConversation(BG_MODEL, "", false)
        repo.send(bg, "keep the model loaded")
        engineRunner.start()
        until("loaded") { db.engineStatus().get()?.state == EngineStateName.READY.name }
        val before = db.engineStatus().get()!!
        fgRunner.start()
        fgRunner.kick()
        delay(500)
        val after = db.engineStatus().get()!!
        assertEquals(before.state, after.state)
        assertEquals(before.modelPath, after.modelPath)
    }

    private companion object {
        const val BG_MODEL = "/models/bg.gguf"
        const val FG_MODEL = "/models/fg.gguf"
    }
}
