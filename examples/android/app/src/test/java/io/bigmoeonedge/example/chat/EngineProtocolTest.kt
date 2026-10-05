package io.bigmoeonedge.example.chat

import io.bigmoeonedge.example.chat.data.EngineMessage
import io.bigmoeonedge.example.chat.engine.EngineEvent
import io.bigmoeonedge.example.chat.engine.EngineProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineProtocolTest {
    private val tricky = "quote \" backslash \\ newline \n tab \t cr \r bell \u0007 emoji 😀 unicode é 日本"

    @Test fun jsonEscapeRoundTripsThroughARealParser() {
        val o = JSONObject("{\"s\":\"${EngineProtocol.jsonEscape(tricky)}\"}")
        assertEquals(tricky, o.getString("s"))
    }

    @Test fun generateRequestIsValidJsonWithParallelHistoryArrays() {
        val req = EngineProtocol.generate(
            id = 7, prompt = tricky, nPredict = 256, think = false, clearKv = false,
            history = listOf(EngineMessage("system", "be brief"), EngineMessage("user", tricky), EngineMessage("assistant", "ok")),
            fitCtx = true,
        )
        val o = JSONObject(req)
        assertEquals("generate", o.getString("cmd"))
        assertEquals(7, o.getInt("id"))
        assertEquals(256, o.getInt("n_predict"))
        assertFalse(o.getBoolean("think"))
        assertFalse(o.getBoolean("clear_kv"))
        assertTrue(o.getBoolean("fit_ctx"))
        assertEquals(tricky, o.getString("prompt"))
        val roles = o.getJSONArray("history_roles")
        val contents = o.getJSONArray("history_contents")
        assertEquals(3, roles.length())
        assertEquals(roles.length(), contents.length())
        assertEquals("user", roles.getString(1))
        assertEquals(tricky, contents.getString(1))
        // One line: the request reader is line-based.
        assertFalse(req.contains('\n'))
    }

    @Test fun noHistoryMeansTheKeysAreAbsent() {
        val o = JSONObject(EngineProtocol.generate(1, "hi", 8, true, true))
        assertFalse(o.has("history_roles"))
        assertFalse(o.has("fit_ctx"))
    }

    @Test fun anEmptyHistoryIsStillSeeded() {
        val o = JSONObject(EngineProtocol.generate(1, "hi", 8, true, false, history = emptyList()))
        assertEquals(0, o.getJSONArray("history_roles").length())
    }

    @Test fun readyBeginProgressDone() {
        val p = EngineProtocol()
        val ready = p.parse("""BMOE_READY {"load_s":12.5,"arch":"x","n_ctx":2048,"think_ctl":"template","n_expert_used":8}""")
        ready as EngineEvent.Ready
        assertEquals(12.5, ready.loadS, 0.0)
        assertEquals("template", ready.thinkControl)
        assertEquals(8, ready.nExpertUsed)
        assertEquals(2048, ready.nCtx)

        assertEquals(EngineEvent.Begin(3), p.parse("""BMOE_BEGIN {"id":3}"""))

        val a = p.parse("""BMOE_PROGRESS {"step":1,"steps":5,"wall_ms":500,"compute_ms":400,"delta_text":"Hel"}""")
        a as EngineEvent.Progress
        assertEquals("Hel", a.telemetry.text)
        val b = p.parse("""BMOE_PROGRESS {"step":2,"steps":5,"wall_ms":500,"compute_ms":400,"delta_text":"lo","delta_reasoning":"hm"}""")
        b as EngineEvent.Progress
        assertEquals("Hello", b.telemetry.text)
        assertEquals("hm", b.telemetry.reasoning)

        val done = p.parse(
            """BMOE_DONE {"id":3,"cancelled":false,"tokens":2,"tok_s":2.0,"prefill_s":1.5,"n_prompt":30,"n_past":40,""" +
                """"text":"Hello","reasoning":"","history_dropped":4}""",
        )
        done as EngineEvent.Done
        assertEquals("Hello", done.info.text)
        // The summary had no reasoning; the streamed span is kept.
        assertEquals("hm", done.info.reasoning)
        assertEquals(4, done.info.historyDropped)
        assertEquals(30, done.info.nPrompt)
        assertTrue(done.info.metricsLine(2048).contains("ctx 40/2048"))
    }

    @Test fun beginClearsTheStreamedTextOfThePreviousReply() {
        val p = EngineProtocol()
        p.parse("""BMOE_PROGRESS {"step":1,"steps":5,"wall_ms":500,"compute_ms":400,"delta_text":"old"}""")
        p.parse("""BMOE_BEGIN {"id":2}""")
        val a = p.parse("""BMOE_PROGRESS {"step":1,"steps":5,"wall_ms":500,"compute_ms":400,"delta_text":"new"}""")
        assertEquals("new", (a as EngineEvent.Progress).telemetry.text)
    }

    @Test fun resetReplacesTheAccumulatedText() {
        val p = EngineProtocol()
        p.parse("""BMOE_PROGRESS {"step":1,"steps":5,"wall_ms":500,"compute_ms":400,"delta_text":"answer"}""")
        val r = p.parse("""BMOE_PROGRESS {"step":2,"steps":5,"wall_ms":500,"compute_ms":400,"reset":1,"delta_reasoning":"thought"}""")
        r as EngineEvent.Progress
        assertEquals("", r.telemetry.text)
        assertEquals("thought", r.telemetry.reasoning)
    }

    @Test fun errorsCarryTheirFatalFlag() {
        val p = EngineProtocol()
        assertEquals(EngineEvent.Error("too long", false), p.parse("""BMOE_ERROR {"id":1,"fatal":false,"msg":"too long"}"""))
        assertEquals(EngineEvent.Error("boom", true), p.parse("""BMOE_ERROR {"id":1,"fatal":true,"msg":"boom"}"""))
    }

    @Test fun aBrokenDoneIsAnErrorNotACrash() {
        val e = EngineProtocol().parse("BMOE_DONE {not json")
        assertTrue(e is EngineEvent.Error && !e.fatal)
    }

    @Test fun otherLinesAreIgnored() {
        assertEquals(EngineEvent.Other, EngineProtocol().parse("some log line"))
        assertEquals(EngineEvent.Other, EngineProtocol().parse("BMOE_LOAD {\"mb\":1}"))
    }
}
