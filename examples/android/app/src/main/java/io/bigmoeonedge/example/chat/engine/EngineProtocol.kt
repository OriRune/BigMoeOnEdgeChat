package io.bigmoeonedge.example.chat.engine

import io.bigmoeonedge.example.Telemetry
import io.bigmoeonedge.example.TelemetryParser
import io.bigmoeonedge.example.chat.data.EngineMessage
import org.json.JSONObject
import java.util.Locale

/** The numbers of one finished generation (BMOE_DONE), as the chat and the scan use them. */
data class DoneInfo(
    val id: Int,
    val cancelled: Boolean,
    val tokens: Int,
    val tokS: Double,
    val prefillS: Double,
    val prefillTps: Double,
    val loadS: Double,
    val cacheHitPct: Double,
    val nPrompt: Int,
    val nPast: Int,
    val readMib: Double,
    val majfltPerTok: Double,
    val cpuSPerTok: Double,
    val cacheResidentMib: Double,
    val loopOverheadSPerTok: Double,
    val text: String,
    val reasoning: String,
    val historyDropped: Int,
) {
    /** The rate the user waits for: decode time plus the gap between decodes. */
    val effectiveTokS: Double
        get() = if (tokS > 0) 1.0 / (1.0 / tokS + loopOverheadSPerTok) else -1.0

    /** The compact line under an answer: "1.7 tok/s · 120 tok · prefill 3.2s (84 tok)". */
    fun metricsLine(ctx: Int = 0): String = buildString {
        val loc = Locale.US
        append(String.format(loc, "%.1f tok/s · %d tok", tokS, tokens))
        if (prefillS > 0) {
            append(String.format(loc, " · prefill %.1fs", prefillS))
            if (nPrompt >= 0) append(String.format(loc, " (%d tok)", nPrompt))
        }
        if (nPast >= 0 && ctx > 0) append(String.format(loc, " · ctx %d/%d", nPast, ctx))
        if (cancelled) append(" · stopped")
    }
}

/** One parsed line of the engine's stdout. */
sealed interface EngineEvent {
    data class Ready(val loadS: Double, val thinkControl: String?, val nExpertUsed: Int?, val nCtx: Int?) : EngineEvent
    data class Begin(val id: Int) : EngineEvent
    data class Progress(val telemetry: Telemetry) : EngineEvent
    data class Done(val info: DoneInfo) : EngineEvent
    data class Error(val msg: String, val fatal: Boolean) : EngineEvent
    data object Other : EngineEvent
}

/**
 * The line protocol of `bmoe-cli --session` (docs/telemetry.md, docs/session.md): request JSON
 * builders and a stateful line parser. Pure Kotlin, so the escaping and the parsing are tested on
 * the JVM.
 */
class EngineProtocol {
    private val telemetry = TelemetryParser()

    /** Per-token state of the generation in flight (live text, reasoning, rates). */
    val current: Telemetry get() = telemetry.current

    fun parse(line: String): EngineEvent {
        val t = line.trim()
        return when {
            t.startsWith("BMOE_READY ") -> {
                val o = obj(t, "BMOE_READY ")
                EngineEvent.Ready(
                    loadS = o?.optDouble("load_s", -1.0) ?: -1.0,
                    thinkControl = o?.optString("think_ctl")?.ifEmpty { null },
                    nExpertUsed = o?.takeIf { it.has("n_expert_used") }?.optInt("n_expert_used"),
                    nCtx = o?.takeIf { it.has("n_ctx") }?.optInt("n_ctx"),
                )
            }
            t.startsWith("BMOE_BEGIN ") -> {
                telemetry.reset()
                EngineEvent.Begin(obj(t, "BMOE_BEGIN ")?.optInt("id") ?: 0)
            }
            t.startsWith("BMOE_PROGRESS ") ->
                if (telemetry.onLine(t)) EngineEvent.Progress(telemetry.current) else EngineEvent.Other
            t.startsWith("BMOE_DONE ") -> {
                val o = obj(t, "BMOE_DONE ")
                    ?: return EngineEvent.Error("The engine's end-of-turn summary could not be read.", false)
                EngineEvent.Done(doneOf(o, telemetry.current))
            }
            t.startsWith("BMOE_ERROR ") -> {
                val o = obj(t, "BMOE_ERROR ")
                EngineEvent.Error(o?.optString("msg") ?: "engine error", o?.optBoolean("fatal", true) ?: true)
            }
            else -> EngineEvent.Other
        }
    }

    private fun obj(line: String, prefix: String): JSONObject? =
        runCatching { JSONObject(line.removePrefix(prefix)) }.getOrNull()

    private fun doneOf(o: JSONObject, live: Telemetry): DoneInfo {
        val text = o.optString("text")
        val reasoning = o.optString("reasoning")
        return DoneInfo(
            id = o.optInt("id"),
            cancelled = o.optBoolean("cancelled"),
            tokens = o.optInt("tokens"),
            tokS = o.optDouble("tok_s"),
            prefillS = o.optDouble("prefill_s", 0.0),
            prefillTps = o.optDouble("prefill_tps", -1.0),
            loadS = o.optDouble("load_s", -1.0),
            cacheHitPct = o.optDouble("cache_hit_pct", -1.0),
            nPrompt = o.optInt("n_prompt", -1),
            nPast = o.optInt("n_past", -1),
            readMib = o.optDouble("read_mib", -1.0),
            majfltPerTok = o.optDouble("majflt_tok", -1.0),
            cpuSPerTok = o.optDouble("cpu_s_tok", -1.0),
            cacheResidentMib = o.optDouble("cache_resident_mib", -1.0),
            loopOverheadSPerTok = o.optDouble("loop_overhead_s_tok", 0.0),
            // The summary's text is authoritative; fall back to the stream when a model drops it.
            text = text.ifEmpty { live.text },
            reasoning = reasoning.ifEmpty { live.reasoning },
            historyDropped = o.optInt("history_dropped", 0),
        )
    }

    companion object {
        const val CANCEL = """{"cmd":"cancel"}"""
        const val CLOSE = """{"cmd":"close"}"""

        /** [history] non-null seeds the engine's conversation (replace_history) before [prompt]. */
        fun generate(
            id: Int,
            prompt: String,
            nPredict: Int,
            think: Boolean,
            clearKv: Boolean,
            history: List<EngineMessage>? = null,
            fitCtx: Boolean = false,
        ): String = buildString {
            append("""{"cmd":"generate","id":""").append(id)
            append(""","n_predict":""").append(nPredict)
            append(""","think":""").append(think)
            append(""","clear_kv":""").append(clearKv)
            if (history != null) {
                append(""","history_roles":[""")
                append(history.joinToString(",") { "\"${jsonEscape(it.role)}\"" })
                append("""],"history_contents":[""")
                append(history.joinToString(",") { "\"${jsonEscape(it.content)}\"" })
                append("]")
            }
            if (fitCtx) append(""","fit_ctx":true""")
            append(""","prompt":"""").append(jsonEscape(prompt)).append("\"}")
        }

        fun jsonEscape(s: String): String {
            val o = StringBuilder(s.length + 8)
            for (c in s) when (c) {
                '"' -> o.append("\\\"")
                '\\' -> o.append("\\\\")
                '\n' -> o.append("\\n")
                '\r' -> o.append("\\r")
                '\t' -> o.append("\\t")
                else -> if (c < ' ') o.append(String.format("\\u%04x", c.code)) else o.append(c)
            }
            return o.toString()
        }
    }
}
