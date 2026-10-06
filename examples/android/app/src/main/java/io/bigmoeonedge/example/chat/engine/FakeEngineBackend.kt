package io.bigmoeonedge.example.chat.engine

import io.bigmoeonedge.example.scan.FakeThermal
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * A stand-in for the engine that speaks the same line protocol, so the emulator (which cannot run
 * the real engine) exercises the database, the queue, the service and the notifications.
 *
 * Markers in the prompt steer it, which keeps tests deterministic without a control channel into
 * the :engine process: a prompt ending in `[fail]` answers with a non-fatal error and one ending in
 * `[crash]` exits with a fatal error (they are read at the end because the app merges a failed
 * turn's message into the next prompt); `[slow]` runs at a tenth of the rate, `[long]` writes 600
 * tokens and `[drop2]` reports two dropped history messages, wherever they appear. The think flag
 * adds a reasoning span.
 *
 * [tokPerSec] may be derived from the session's argv, which is how the scan's fake varies speed by
 * setting.
 */
class FakeEngineBackend(
    private val tokPerSec: (List<String>) -> Double = FakeThermal::tokPerSec,
    private val loadMs: Long = 1500,
) : EngineBackend {
    private val inbox = LinkedBlockingQueue<String>()
    @Volatile private var alive = false
    @Volatile private var cancelled = false
    @Volatile private var frozen = false
    @Volatile private var argv: List<String> = emptyList()

    override val isAlive: Boolean get() = alive
    override val pid: Int get() = -1

    override fun start(argv: List<String>, env: Map<String, String>, workDir: File?, listener: EngineBackend.Listener) {
        this.argv = argv
        alive = true
        thread(name = "bmoe-fake-engine") {
            var code = 0
            try {
                Thread.sleep(loadMs)
                listener.onStderr("expert streaming ON o_direct=1 (fake)")
                listener.onLine(
                    """BMOE_READY {"load_s":${loadMs / 1000.0},"arch":"fake","n_ctx":4096,"think_ctl":"template","n_expert_used":8}""",
                )
                while (alive) {
                    val req = inbox.take()
                    if (req == POISON) break
                    val o = runCatching { JSONObject(req) }.getOrNull() ?: continue
                    when (o.optString("cmd")) {
                        "generate" -> if (!generate(o, listener)) {
                            code = 1
                            break
                        }
                        "close" -> break
                    }
                }
            } catch (_: InterruptedException) {
                code = 143
            }
            alive = false
            listener.onExit(code)
        }
    }

    /** False on a fatal error: the process ends. */
    private fun generate(o: JSONObject, out: EngineBackend.Listener): Boolean {
        cancelled = false
        val id = o.optInt("id")
        val prompt = o.optString("prompt")
        val roles = o.optJSONArray("history_roles")
        val histN = roles?.length() ?: 0
        if (prompt.trimEnd().endsWith("[crash]")) {
            out.onLine("""BMOE_ERROR {"id":$id,"fatal":true,"msg":"fake engine crashed"}""")
            return false
        }
        if (prompt.trimEnd().endsWith("[fail]")) {
            out.onLine("""BMOE_ERROR {"id":$id,"fatal":false,"msg":"fake engine refused the request"}""")
            return true
        }
        val baseRate = tokPerSec(argv) * if (prompt.contains("[slow]")) 0.1 else 1.0
        val nPredict = o.optInt("n_predict", 128)
        // The scan's prompts ask for a full-length reply, so the fake phone has time to heat.
        val want = if (prompt.contains("[long]")) 600 else if (prompt.contains("Continue")) nPredict else 40
        val total = minOf(want, nPredict)
        val think = o.optBoolean("think", false)
        out.onLine("""BMOE_BEGIN {"id":$id}""")

        var userTurns = 0
        for (i in 0 until histN) if (roles.optString(i) == "user") userTurns++
        val words = ArrayList<String>()
        words += "Fake reply. I was given ${userTurns + 1} user turns and $histN earlier messages; the newest says:"
        words += "“${prompt.take(60).replace('\n', ' ')}”."
        while (words.joinToString(" ").split(' ').size < total) words += LOREM
        val tokens = words.joinToString(" ").split(' ').take(total)

        val reasoning = StringBuilder()
        val text = StringBuilder()
        val t0 = System.nanoTime()
        FakeThermal.active = true
        if (think) {
            val r = "Let me think about that. "
            reasoning.append(r)
            out.onLine(progress(0, total, 1000.0 / baseRate, "", r))
        }
        for ((i, w) in tokens.withIndex()) {
            if (cancelled) break
            // A frozen process makes no progress; a cancel or a kill still ends the wait.
            while (frozen && !cancelled && alive) Thread.sleep(20)
            // A hot phone decodes slower: the speed is re-read per token.
            val gapMs = (1000.0 / (baseRate * FakeThermal.slowdown())).toLong().coerceAtLeast(1)
            Thread.sleep(gapMs)
            val delta = if (i == 0) w else " $w"
            text.append(delta)
            out.onLine(progress(i + 1, total, gapMs.toDouble(), delta, ""))
        }
        FakeThermal.active = false
        val elapsed = (System.nanoTime() - t0) / 1e9
        val n = if (text.isEmpty()) 0 else text.split(' ').size
        val dropped = if (prompt.contains("[drop2]")) 2 else 0
        out.onLine(
            String.format(
                Locale.US,
                """BMOE_DONE {"id":%d,"cancelled":%s,"tokens":%d,"tok_s":%.3f,"prefill_s":0.4,"prefill_tps":50.0,""" +
                    """"load_s":%.2f,"cache_hit_pct":80.0,"n_prompt":%d,"n_past":%d,"read_mib":10.0,""" +
                    """"majflt_tok":0.0,"cpu_s_tok":0.2,"cache_resident_mib":500.0,"loop_overhead_s_tok":0.0,""" +
                    """"reasoning":"%s","text":"%s","history_dropped":%d}""",
                id, cancelled, n, if (elapsed > 0) n / elapsed else 0.0, loadMs / 1000.0, prompt.length / 4, n + histN * 10,
                EngineProtocol.jsonEscape(reasoning.toString()), EngineProtocol.jsonEscape(text.toString()), dropped,
            ),
        )
        return true
    }

    private fun progress(step: Int, steps: Int, wallMs: Double, delta: String, reasoning: String): String =
        String.format(
            Locale.US,
            """BMOE_PROGRESS {"step":%d,"steps":%d,"wall_ms":%.1f,"compute_ms":%.1f,"io_ms":0.0,"cache_hit_pct":80.0,""" +
                """"delta_text":"%s","delta_reasoning":"%s"}""",
            step, steps, wallMs, wallMs * 0.8, EngineProtocol.jsonEscape(delta), EngineProtocol.jsonEscape(reasoning),
        )

    override fun send(json: String): Boolean {
        if (!alive) return false
        if (json.contains("\"cmd\":\"cancel\"")) {
            cancelled = true
            return true
        }
        inbox.put(json)
        return true
    }

    override fun freeze(on: Boolean) {
        frozen = on
    }

    override fun close() {
        cancelled = true
        inbox.put(POISON)
    }

    override fun kill() {
        alive = false
        cancelled = true
        inbox.put(POISON)
    }

    private companion object {
        const val POISON = "__poison__"
        val LOREM = "lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt".split(' ')
    }
}
