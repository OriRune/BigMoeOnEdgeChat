package io.bigmoeonedge.example.chat.engine

import io.bigmoeonedge.example.chat.data.ChatDb
import io.bigmoeonedge.example.chat.data.ConversationEntity
import io.bigmoeonedge.example.chat.data.EngineStateName
import io.bigmoeonedge.example.chat.data.EngineStatusEntity
import io.bigmoeonedge.example.chat.data.HistoryBuilder
import io.bigmoeonedge.example.chat.data.JobHistory
import io.bigmoeonedge.example.chat.data.MessageEntity
import io.bigmoeonedge.example.chat.data.MessageStatus
import io.bigmoeonedge.example.chat.data.QueueRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors

/** How to open the session for one conversation's model, resolved when the job starts. */
data class JobConfig(
    val argv: List<String>,
    val sig: String,
    val env: Map<String, String>,
    val workDir: File?,
    val nPredict: Int,
    val ctx: Int,
    val fake: Boolean,
    val modelName: String,
)

/** What the runner needs from Android. The service implements it; tests use a stub. */
interface EngineHost {
    suspend fun jobConfig(conv: ConversationEntity): JobConfig
    fun createBackend(cfg: JobConfig): EngineBackend

    /** Make sure no other engine child (the lab screen's, an orphan) holds the model's memory. */
    suspend fun clearOtherEngines(selfPid: Int)

    /** The foreground notification text. */
    fun foregroundText(text: String)

    /** Keep the CPU awake while the queue is being worked. */
    fun holdWake(on: Boolean)

    /** Why queued work must not start now ("Paused, low battery"), or null. */
    fun pauseReason(): String?

    fun thermalStatus(): Int
    fun keepLoadedMinutes(): Int

    /** A reply reached a final status. [status] is DONE, FAILED or CANCELLED. */
    suspend fun replyFinished(messageId: Long, conversationId: Long, status: String)

    /** The queue drained; [modelLoaded] says whether a session is still open. */
    fun idle(modelLoaded: Boolean)
}

/**
 * The job loop. One coroutine on a single thread: pick the oldest queued reply across all
 * conversations, make sure the right session is open, seed it with the stored conversation, stream
 * the answer into the database, repeat. It is the only writer of reply progress.
 */
class EngineRunner(
    private val db: ChatDb,
    private val host: EngineHost,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "bmoe-engine-jobs") }
    private val scope = CoroutineScope(executor.asCoroutineDispatcher() + SupervisorJob())
    private val kicks = Channel<Unit>(Channel.CONFLATED)

    private sealed interface Ev {
        data class Line(val text: String) : Ev
        data class Exit(val code: Int) : Ev
    }

    private class Live(val backend: EngineBackend, val sig: String, val modelPath: String) {
        val events = Channel<Ev>(Channel.UNLIMITED)
        val protocol = EngineProtocol()
        val stderrTail = StringBuilder()
        @Volatile var ioMode: String = ""
        var nextId = 1
    }

    private class SessionFailed(msg: String) : Exception(msg)

    private var live: Live? = null
    private var idleJob: Job? = null
    private var retryJob: Job? = null

    @Volatile private var suspended = false
    @Volatile private var activeId = -1L
    @Volatile private var requeueActive = false
    @Volatile private var cancelRequested = false

    fun start() {
        scope.launch {
            recover()
            for (k in kicks) drain()
        }
        kick()
    }

    fun kick() {
        kicks.trySend(Unit)
    }

    fun shutdown() {
        kicks.close()
        scope.launch { teardown() }.invokeOnCompletion { scope.coroutineContext[Job]?.cancel() }
    }

    /** Stop the reply [messageId] if it is the one running. A queued one is stopped in the database. */
    fun cancel(messageId: Long) {
        if (activeId == messageId) {
            cancelRequested = true
            live?.backend?.send(EngineProtocol.CANCEL)
        } else scope.launch {
            val m = db.messages().get(messageId)
            if (m?.status == MessageStatus.QUEUED) db.messages().setStatus(messageId, MessageStatus.CANCELLED)
        }
    }

    /** Stop whatever reply is running (the notification's Stop button). */
    fun cancelActive() {
        val id = activeId
        if (id >= 0) cancel(id)
    }

    /** Unload the model now (and stop a running reply first). */
    fun unload() {
        scope.launch { if (activeId < 0) teardown().also { status(EngineStateName.IDLE, "") } }
    }

    /** The lab screen wants the engine: give the memory back and hold the queue until [resume]. */
    fun suspend() {
        suspended = true
        if (activeId >= 0) {
            requeueActive = true
            live?.backend?.send(EngineProtocol.CANCEL)
        }
        kick()
    }

    fun resume() {
        suspended = false
        kick()
    }

    // ── recovery ──

    /** Messages a dead engine process left PREFILLING or GENERATING get one more try, then fail. */
    internal suspend fun recover() {
        for (m in db.messages().active()) {
            val r = QueueRules.recover(m)
            if (r.status == MessageStatus.QUEUED) db.messages().requeue(m.id, r.attempt, m.queuedAt)
            else db.messages().setStatus(m.id, MessageStatus.FAILED, r.error)
        }
        status(EngineStateName.IDLE, "")
    }

    // ── queue ──

    private suspend fun drain() {
        idleJob?.cancel()
        retryJob?.cancel()
        var ran = false
        while (true) {
            if (suspended) {
                teardown()
                status(EngineStateName.IDLE, "", paused = "Engine lab is open")
                break
            }
            val next = QueueRules.pickNext(db.messages().queued()) ?: break
            val pause = host.pauseReason()
            if (pause != null) {
                status(EngineStateName.IDLE, pause, paused = pause)
                host.foregroundText(pause)
                if (ran) host.holdWake(false)
                // The battery receiver kicks on charging; this covers a missed broadcast.
                retryJob = scope.launch { delay(PAUSE_RETRY_MS); kick() }
                host.idle(live != null)
                return
            }
            if (!ran) {
                host.holdWake(true)
                ran = true
            }
            runJob(next)
        }
        if (ran) host.holdWake(false)
        afterQueue()
    }

    private suspend fun afterQueue() {
        if (suspended) {
            host.idle(false)
            return
        }
        val l = live
        if (l == null) {
            status(EngineStateName.IDLE, "")
            host.idle(false)
            return
        }
        status(EngineStateName.READY, "Model loaded · idle")
        host.foregroundText("Model loaded · idle")
        host.idle(true)
        when (val minutes = host.keepLoadedMinutes()) {
            -1 -> {}
            0 -> unloadNow()
            else -> idleJob = scope.launch {
                delay(minutes * 60_000L)
                unloadNow()
            }
        }
    }

    private suspend fun unloadNow() {
        teardown()
        status(EngineStateName.IDLE, "")
        host.idle(false)
    }

    // ── one reply ──

    private suspend fun runJob(m: MessageEntity) {
        val msgs = db.messages()
        val conv = db.conversations().get(m.conversationId)
        if (conv == null) {
            msgs.delete(m.id)
            return
        }
        val job = HistoryBuilder.build(conv.systemPrompt, msgs.listFor(conv.id), m.id)
        if (job == null) {
            msgs.finish(m.id, "", "", MessageStatus.FAILED, "There is no message to answer.", 0, 0.0, 0.0, "", clock())
            host.replyFinished(m.id, conv.id, MessageStatus.FAILED)
            return
        }
        activeId = m.id
        requeueActive = false
        cancelRequested = false
        var cfg: JobConfig? = null
        try {
            cfg = host.jobConfig(conv)
            msgs.setStatus(m.id, MessageStatus.PREFILLING)
            val l = ensureSession(cfg, conv.modelPath, m)
            if (cancelRequested || requeueActive) {
                // Stopped while the model was loading: nothing was generated yet.
                if (requeueActive) msgs.requeue(m.id, m.attempt, m.queuedAt)
                else {
                    msgs.finish(m.id, "", "", MessageStatus.CANCELLED, null, 0, 0.0, 0.0, "", clock())
                    host.replyFinished(m.id, conv.id, MessageStatus.CANCELLED)
                }
                return
            }
            status(EngineStateName.BUSY, "Reading the conversation…", activeId = m.id)
            host.foregroundText("Reading the conversation…")
            val id = l.nextId++
            val req = EngineProtocol.generate(
                id = id, prompt = job.prompt, nPredict = cfg.nPredict, think = conv.thinking, clearKv = false,
                history = job.history, fitCtx = true,
            )
            if (!l.backend.send(req)) throw SessionFailed("The engine is not running.")
            stream(l, m, conv, cfg, job)
        } catch (e: SessionFailed) {
            fail(m, conv, e.message ?: "The engine failed.")
            teardown()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(m, conv, e.message ?: e.toString())
            teardown()
        } finally {
            activeId = -1L
        }
    }

    private suspend fun fail(m: MessageEntity, conv: ConversationEntity, msg: String) {
        val cur = db.messages().get(m.id)
        db.messages().finish(
            m.id, cur?.text ?: "", cur?.reasoning ?: "", MessageStatus.FAILED, msg, cur?.tokens ?: 0,
            cur?.tokPerSec ?: 0.0, 0.0, "", clock(),
        )
        status(EngineStateName.ERROR, msg, error = msg)
        host.replyFinished(m.id, conv.id, MessageStatus.FAILED)
    }

    private suspend fun stream(l: Live, m: MessageEntity, conv: ConversationEntity, cfg: JobConfig, job: JobHistory) {
        val msgs = db.messages()
        var lastFlush = 0L
        var firstTokenAt = 0L
        var deleted = false
        while (true) {
            when (val ev = l.events.receive()) {
                is Ev.Exit -> {
                    live = null
                    val tail = synchronized(l.stderrTail) { l.stderrTail.takeLast(600).toString().trim() }
                    throw SessionFailed(
                        "The engine stopped unexpectedly (exit ${ev.code})." + if (tail.isEmpty()) "" else "\n$tail",
                    )
                }
                is Ev.Line -> when (val p = l.protocol.parse(ev.text)) {
                    is EngineEvent.Progress -> {
                        val now = clock()
                        if (firstTokenAt == 0L) {
                            firstTokenAt = now
                            msgs.setStatus(m.id, MessageStatus.GENERATING)
                            status(EngineStateName.BUSY, "Writing reply", activeId = m.id)
                        }
                        if (now - lastFlush >= FLUSH_MS) {
                            lastFlush = now
                            val t = p.telemetry
                            val secs = (now - firstTokenAt) / 1000.0
                            val rate = if (secs > 0.5) t.step / secs else t.tokensPerSecond
                            if (!deleted && msgs.updateProgress(m.id, t.text, t.reasoning, MessageStatus.GENERATING, t.step, rate) == 0) {
                                // The row is gone (an edit deleted it): stop writing for nothing.
                                deleted = true
                                l.backend.send(EngineProtocol.CANCEL)
                            }
                            status(EngineStateName.BUSY, "Writing reply", activeId = m.id, step = t.step, tokPerSec = rate)
                            host.foregroundText(
                                String.format(java.util.Locale.US, "Writing reply · %d tok · %.1f tok/s", t.step, rate),
                            )
                        }
                    }
                    is EngineEvent.Done -> {
                        val info = p.info
                        if (requeueActive && info.cancelled) {
                            msgs.requeue(m.id, m.attempt, m.queuedAt)
                            return
                        }
                        val st = if (info.cancelled) MessageStatus.CANCELLED else MessageStatus.DONE
                        msgs.finish(
                            m.id, info.text, info.reasoning, st, null, info.tokens, info.tokS, info.prefillS,
                            info.metricsLine(cfg.ctx), clock(),
                        )
                        if (st == MessageStatus.DONE) {
                            msgs.replaceOutOfContext(conv.id, HistoryBuilder.droppedRows(job, info.historyDropped))
                        }
                        status(EngineStateName.READY, "", tokPerSec = info.tokS)
                        if (!deleted) host.replyFinished(m.id, conv.id, st)
                        return
                    }
                    is EngineEvent.Error -> {
                        if (p.fatal) throw SessionFailed(p.msg)
                        // A rejected request (context overflow, bad history): the session stays usable.
                        val cur = msgs.get(m.id)
                        msgs.finish(
                            m.id, cur?.text ?: "", cur?.reasoning ?: "", MessageStatus.FAILED, p.msg,
                            cur?.tokens ?: 0, cur?.tokPerSec ?: 0.0, 0.0, "", clock(),
                        )
                        status(EngineStateName.READY, "", error = p.msg)
                        host.replyFinished(m.id, conv.id, MessageStatus.FAILED)
                        return
                    }
                    else -> {}
                }
            }
        }
    }

    // ── session lifecycle ──

    private suspend fun ensureSession(cfg: JobConfig, modelPath: String, m: MessageEntity): Live {
        val cur = live
        if (cur != null && cur.sig == cfg.sig && cur.backend.isAlive) return cur
        if (cur != null) teardown()
        status(EngineStateName.LOADING, "Loading ${cfg.modelName}…", activeId = m.id)
        host.foregroundText("Loading ${cfg.modelName}…")
        if (!cfg.fake) withContext(Dispatchers.IO) { host.clearOtherEngines(-1) }

        val backend = host.createBackend(cfg)
        val l = Live(backend, cfg.sig, modelPath)
        live = l
        backend.start(
            cfg.argv, cfg.env, cfg.workDir,
            object : EngineBackend.Listener {
                override fun onLine(line: String) {
                    l.events.trySend(Ev.Line(line))
                }

                override fun onStderr(line: String) {
                    synchronized(l.stderrTail) { if (l.stderrTail.length < 4000) l.stderrTail.append(line).append('\n') }
                    sniffIoMode(l, line)
                }

                override fun onExit(code: Int) {
                    l.events.trySend(Ev.Exit(code))
                }
            },
        )
        while (true) {
            when (val ev = l.events.receive()) {
                is Ev.Exit -> {
                    live = null
                    val tail = synchronized(l.stderrTail) { l.stderrTail.takeLast(600).toString().trim() }
                    throw SessionFailed(
                        "The model did not load (exit ${ev.code})." + if (tail.isEmpty()) "" else "\n$tail",
                    )
                }
                is Ev.Line -> when (val p = l.protocol.parse(ev.text)) {
                    is EngineEvent.Ready -> {
                        status(EngineStateName.READY, "", ioMode = l.ioMode)
                        return l
                    }
                    is EngineEvent.Error -> if (p.fatal) throw SessionFailed(p.msg)
                    else -> {}
                }
            }
        }
    }

    private fun sniffIoMode(l: Live, line: String) {
        when {
            "O_DIRECT returns wrong data" in line -> l.ioMode = "buffered (O_DIRECT unsupported on this storage)"
            "expert streaming ON" in line && l.ioMode.isEmpty() ->
                Regex("""o_direct=(\d)""").find(line)?.groupValues?.get(1)?.let {
                    l.ioMode = if (it == "1") "direct (O_DIRECT)" else "buffered"
                }
        }
    }

    /** Ask the session to close, wait for the process to be reaped, force it if it will not go. */
    private suspend fun teardown() {
        val l = live ?: return
        live = null
        l.backend.close()
        val gone = withTimeoutOrNull(EXIT_GRACE_MS) {
            while (true) if (l.events.receive() is Ev.Exit) break
            true
        }
        if (gone == null) {
            l.backend.kill()
            withTimeoutOrNull(EXIT_FORCE_MS) {
                while (true) if (l.events.receive() is Ev.Exit) break
            }
        }
    }

    // ── status row ──

    private var lastIo = ""

    private suspend fun status(
        state: EngineStateName, detail: String, activeId: Long? = null, step: Int = 0, tokPerSec: Double = 0.0,
        error: String? = null, paused: String? = null, ioMode: String? = null,
    ) {
        if (ioMode != null && ioMode.isNotEmpty()) lastIo = ioMode
        val l = live
        db.engineStatus().put(
            EngineStatusEntity(
                state = state.name,
                modelPath = l?.modelPath ?: "",
                sessionSig = l?.sig ?: "",
                activeMessageId = activeId,
                step = step,
                tokPerSec = tokPerSec,
                thermalStatus = host.thermalStatus(),
                ioMode = if (l != null) lastIo else "",
                lastError = error,
                pausedReason = paused,
                detail = detail,
                updatedAt = clock(),
            ),
        )
    }

    private companion object {
        const val FLUSH_MS = 500L
        const val EXIT_GRACE_MS = 3000L
        const val EXIT_FORCE_MS = 3000L
        const val PAUSE_RETRY_MS = 120_000L
    }
}
