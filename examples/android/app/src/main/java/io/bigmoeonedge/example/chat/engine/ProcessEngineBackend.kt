package io.bigmoeonedge.example.chat.engine

import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.concurrent.thread

/**
 * The real engine: `libbmoe-cli.so --session` as a child process, exec'd the way the lab screen's
 * RunService does it. Writing the child's pid to [pidFile] lets the next engine process find and
 * kill an orphan this one left behind, so no stray child keeps holding the model's memory.
 */
class ProcessEngineBackend(private val pidFile: File) : EngineBackend {
    @Volatile private var proc: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    private val writeLock = Any()
    private val main = Handler(Looper.getMainLooper())

    override val isAlive: Boolean get() = proc?.isAlive == true
    override val pid: Int get() = proc?.let(::pidOf) ?: -1

    override fun start(argv: List<String>, env: Map<String, String>, workDir: File?, listener: EngineBackend.Listener) {
        killOrphan(pidFile)
        val pb = ProcessBuilder(argv)
        pb.redirectErrorStream(false)
        pb.environment().putAll(env)
        if (workDir != null) pb.directory(workDir)
        val p = pb.start()
        proc = p
        writer = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))
        runCatching { pidFile.writeText(pidOf(p).toString()) }

        thread(name = "bmoe-engine-err") {
            try {
                BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)).forEachLine { listener.onStderr(it) }
            } catch (_: Throwable) {
                // The stream closes when the process is killed.
            }
        }
        thread(name = "bmoe-engine-out") {
            try {
                BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).forEachLine { listener.onLine(it) }
            } catch (_: Throwable) {
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            runCatching { pidFile.delete() }
            listener.onExit(code)
        }
    }

    override fun send(json: String): Boolean = synchronized(writeLock) {
        val w = writer ?: return false
        try {
            w.write(json)
            w.write("\n")
            w.flush()
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun close() {
        // cancel first: close is queued behind an in-flight generate, and the engine applies a
        // cancel off its reader thread.
        send(EngineProtocol.CANCEL)
        send(EngineProtocol.CLOSE)
        main.postDelayed({ kill() }, FORCE_KILL_MS)
    }

    override fun freeze(on: Boolean) {
        val p = pid
        if (p > 0) runCatching { android.os.Process.sendSignal(p, if (on) SIGSTOP else SIGCONT) }
    }

    override fun kill() {
        synchronized(writeLock) {
            runCatching { writer?.close() }
            writer = null
        }
        runCatching { proc?.destroyForcibly() }
    }

    companion object {
        private const val FORCE_KILL_MS = 2000L
        private const val SIGSTOP = 19
        private const val SIGCONT = 18

        /** The stubs have no Process.pid(); the implementation class has had a pid field since the first release. */
        fun pidOf(p: Process): Int = runCatching {
            val f = p.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            f.getInt(p)
        }.getOrDefault(-1)

        /** Kill a child left over from an engine process that died, if the pid still is one. */
        fun killOrphan(pidFile: File) {
            val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull() ?: return
            if (isCli(pid)) android.os.Process.killProcess(pid)
            runCatching { pidFile.delete() }
        }

        fun isCli(pid: Int): Boolean =
            runCatching { File("/proc/$pid/cmdline").readText().contains("libbmoe-cli.so") }.getOrDefault(false)

        /**
         * Pids of every engine child this app uid can see, except [self]. The lab screen's child and
         * a leftover from a dead engine process both show up here.
         */
        fun otherCliPids(self: Int): List<Int> =
            File("/proc").listFiles()?.mapNotNull { f ->
                val pid = f.name.toIntOrNull() ?: return@mapNotNull null
                if (pid != self && isCli(pid)) pid else null
            } ?: emptyList()
    }
}
