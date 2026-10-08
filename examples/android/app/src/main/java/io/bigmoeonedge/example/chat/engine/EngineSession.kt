package io.bigmoeonedge.example.chat.engine

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** The engine process failed to load or died; the message is for the user. */
class SessionFailed(msg: String) : Exception(msg)

/**
 * One running `bmoe-cli --session` (or its fake): the backend, the line protocol parser and the
 * channel its output arrives on. Chat jobs and scan cells both drive the engine through this.
 */
class EngineSession(val backend: EngineBackend, val sig: String, val modelPath: String) {
    sealed interface Ev {
        data class Line(val text: String) : Ev
        data class Exit(val code: Int) : Ev
    }

    val events = Channel<Ev>(Channel.UNLIMITED)
    val protocol = EngineProtocol()
    private val stderrTail = StringBuilder()
    @Volatile var ioMode: String = ""
    @Volatile var exited = false
    var nextId = 1
    var ready: EngineEvent.Ready? = null

    fun start(cfg: JobConfig) {
        backend.start(
            cfg.argv, cfg.env, cfg.workDir,
            object : EngineBackend.Listener {
                override fun onLine(line: String) {
                    events.trySend(Ev.Line(line))
                }

                override fun onStderr(line: String) {
                    synchronized(stderrTail) { if (stderrTail.length < 4000) stderrTail.append(line).append('\n') }
                    sniffIoMode(line)
                }

                override fun onExit(code: Int) {
                    events.trySend(Ev.Exit(code))
                }
            },
        )
    }

    fun tail(): String = synchronized(stderrTail) { stderrTail.takeLast(600).toString().trim() }

    /** Waits for BMOE_READY. A process that exits first, or a fatal error, is a [SessionFailed]. */
    suspend fun awaitReady(): EngineEvent.Ready {
        while (true) {
            when (val ev = events.receive()) {
                is Ev.Exit -> {
                    exited = true
                    val t = tail()
                    throw SessionFailed("The model did not load (exit ${ev.code})." + if (t.isEmpty()) "" else "\n$t")
                }
                is Ev.Line -> when (val p = protocol.parse(ev.text)) {
                    is EngineEvent.Ready -> return p.also { ready = it }
                    is EngineEvent.Error -> if (p.fatal) throw SessionFailed(p.msg)
                    else -> {}
                }
            }
        }
    }

    /** Ask the session to close, wait for the process to be reaped, force it if it will not go. */
    suspend fun close(graceMs: Long = 3000, forceMs: Long = 3000) {
        if (exited) return
        backend.close()
        val gone = withTimeoutOrNull(graceMs) { drainUntilExit() }
        if (gone == null) {
            backend.kill()
            withTimeoutOrNull(forceMs) { drainUntilExit() }
        }
        exited = true
    }

    private suspend fun drainUntilExit(): Boolean {
        while (true) if (events.receive() is Ev.Exit) return true
    }

    private fun sniffIoMode(line: String) {
        when {
            "O_DIRECT returns wrong data" in line -> ioMode = "buffered (O_DIRECT unsupported on this storage)"
            "expert streaming ON" in line && ioMode.isEmpty() ->
                Regex("""o_direct=(\d)""").find(line)?.groupValues?.get(1)?.let {
                    ioMode = if (it == "1") "direct (O_DIRECT)" else "buffered"
                }
        }
    }
}
