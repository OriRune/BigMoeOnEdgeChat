package io.bigmoeonedge.example.chat.engine

import java.io.File

/**
 * One engine session process, however it is hosted: the real `bmoe-cli --session` child or the fake
 * used on the emulator. Lines arrive on the backend's own threads through [Listener]; the job loop
 * moves them onto its single dispatcher.
 */
interface EngineBackend {
    interface Listener {
        fun onLine(line: String)

        /** A stderr line; the real backend also sniffs the read mode from it. */
        fun onStderr(line: String) {}

        /** The process ended. Called once, after the last line. */
        fun onExit(code: Int)
    }

    val isAlive: Boolean

    /** The child's pid, or -1 when there is no OS process (the fake). */
    val pid: Int

    fun start(argv: List<String>, env: Map<String, String>, workDir: File?, listener: Listener)

    /** Writes one request line. False when the process is gone. */
    fun send(json: String): Boolean

    /** Ask the session to wind down on its own terms, then force it after a short deadline. */
    fun close()

    /** Kill now. */
    fun kill()
}
