package io.bigmoeonedge.example.scan

import io.bigmoeonedge.example.AppSettings
import io.bigmoeonedge.example.chat.data.CellKind
import io.bigmoeonedge.example.chat.data.CellLite
import io.bigmoeonedge.example.chat.data.CellStatus
import io.bigmoeonedge.example.chat.data.ScanRunEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanProgressTest {
    private val gb = 1L shl 30
    private val settings = AppSettings(dropColdPct = 0)
    private val min = 60_000L

    private fun run(sustained: Boolean = false, currentJson: String = SettingsJson.toJson(settings)) = ScanRunEntity(
        id = 1, modelPath = "/no/such/model.gguf", startedAt = 0, includeSustained = sustained, includeLossy = false,
        currentJson = currentJson,
    )

    private fun input(sustained: Boolean = false) = PlanInput(settings, 4 * gb, 12 * gb, false, sustained)

    private fun lite(spec: CellSpec, start: Long = 0, end: Long? = null, speed: (CellSpec) -> Double = { 3.0 }) = CellLite(
        runId = 1, stage = spec.stage, kind = spec.kind, label = spec.label, argvSig = spec.key, status = CellStatus.DONE,
        attempt = spec.attempt, startedAt = start, finishedAt = end, decodeMedianTokS = speed(spec), coolMedianTokS = speed(spec),
        sustainedTokS = if (spec.kind == CellKind.SUSTAINED) speed(spec) else -1.0, throttledFrac = 0.0, peakAnonMb = -1,
        cacheResidentMib = 500.0, cpusAllowed = 6, nExpertUsed = 8, gateGaveUp = false,
        settingsJson = SettingsJson.toJson(spec.settings),
    )

    /** Measures the next [n] cells (all of them when null), each taking [cellMs]. */
    private fun measure(
        inp: PlanInput, n: Int? = null, cellMs: Long = 4 * min, sustained: Boolean = false,
        speed: (CellSpec) -> Double = { 3.0 },
    ): List<CellLite> {
        val cells = mutableListOf<CellLite>()
        var t = 0L
        repeat(n ?: 500) {
            val s = ScanPlanner.next(inp, cells.map { it.toPCell() })
            if (s !is PlanStep.Run) return cells
            cells += lite(s.spec, t, t + cellMs, speed)
            t += cellMs
        }
        return cells
    }

    @Test fun anEmptyScanPlansItsWholeSearchAndStartsAtTheBaseline() {
        val p = ScanPlanner.plan(input(), emptyList())
        assertTrue(p.projected)
        assertEquals("BASE", p.steps.first { it.have == null }.spec.stage)
        assertEquals(listOf("BASE", "A", "B", "C", "D", "E", "F", "G"), p.stages.map { it.stage }.filter { p.stages.first { s -> s.stage == it }.cells.isNotEmpty() })
        // The plan and next() agree on the first step.
        val next = ScanPlanner.next(input(), emptyList()) as PlanStep.Run
        assertEquals(p.steps.first { it.have == null }.spec, next.spec)
    }

    @Test fun aFinishedScanHasNothingLeftAndSaysWhatEachStageDecided() {
        val inp = input()
        // cache 3000 is 6% faster, so stage B hands the incumbent over.
        val cells = measure(inp, speed = { if (it.settings.cacheMb == 3000) 3.18 else 3.0 })
        val p = ScanPlanner.plan(inp, cells.map { it.toPCell() })
        assertFalse(p.projected)
        val b = p.stages.first { it.stage == "B" }
        assertEquals("cache 3000 MiB", b.winnerLabel)
        assertEquals(6.0, b.bestGainPct, 0.01)
        val c = p.stages.first { it.stage == "C" }
        assertNull(c.winnerLabel)
        assertNotNull(c.bestLabel)
        assertTrue(ScanProgress.stageLine(b, null).contains("cache 3000 MiB won (+6%)"))
        assertTrue(ScanProgress.stageLine(c, null).startsWith("kept "))
    }

    @Test fun anAlternativeUnderTheMarginIsReportedAsKept() {
        val inp = input()
        val cells = measure(inp, speed = { if (it.settings.threads == 6) 3.12 else 3.0 }) // +4%
        val c = ScanPlanner.plan(inp, cells.map { it.toPCell() }).stages.first { it.stage == "C" }
        assertNull(c.winnerLabel)
        assertEquals("threads 6", c.bestLabel)
        assertTrue(ScanProgress.stageLine(c, null), ScanProgress.stageLine(c, null).contains("+4%") && ScanProgress.stageLine(c, null).contains("needs +5%"))
    }

    @Test fun anEndedScanDoesNotCallUnreachedStagesWaiting() {
        val inp = input()
        val p = ScanPlanner.plan(inp, measure(inp, n = 1).map { it.toPCell() })
        val open = p.stages.first { !it.isDone }
        val later = p.stages.last { !it.isDone && it.stage != open.stage }
        assertEquals("measuring…", ScanProgress.stageLine(open, open.stage))
        assertEquals("waiting", ScanProgress.stageLine(later, open.stage))
        assertTrue(ScanProgress.stageLine(open, open.stage, ended = true).contains("stopped"))
        assertEquals("not run", ScanProgress.stageLine(later, open.stage, ended = true))
    }

    @Test fun spansReadAsElapsedTimeNotAnEstimate() {
        assertEquals("under a minute", ScanProgress.span(30_000))
        assertEquals("42 min", ScanProgress.span(42 * 60_000L))
        assertEquals("28 h 25 min", ScanProgress.span((28 * 60 + 25) * 60_000L))
    }

    @Test fun anUnreachedProjectionNeverLetsAnUnmeasuredCellWin() {
        // Only the baseline is measured: every later stage is a projection and the incumbent stays.
        val inp = input()
        val cells = measure(inp, n = 1)
        val p = ScanPlanner.plan(inp, cells.map { it.toPCell() })
        assertTrue(p.projected)
        assertTrue(p.stages.none { it.winnerLabel != null })
    }

    @Test fun theOutlineCountsMeasuredAndTotalCells() {
        val inp = input()
        val cells = measure(inp, n = 5)
        val o = ScanProgress.outline(run(), cells, 12 * gb, now = 100 * min)
        assertEquals(5, o.measuredCells)
        assertEquals(6, o.currentOrdinal)
        assertTrue(o.totalCells > 5)
        assertEquals(5f / o.totalCells, o.fraction, 0.0001f)
        assertTrue(o.currentStage!!.cells.any { it.have == null })
        assertFalse(o.finished)
    }

    @Test fun noConfirmationIsPlannedUntilSomethingWins_andTheScreenSaysSo() {
        val o = ScanProgress.outline(run(), measure(input(), n = 3), 12 * gb, 0)
        assertEquals(ScanProgress.CONFIRM_CELLS, o.extraIfWin)
        val done = ScanProgress.outline(run(), measure(input()), 12 * gb, 0)
        assertTrue(done.finished)
        assertEquals(0, done.extraIfWin)
    }

    @Test fun theTimeLeftUsesTheMeasuredPaceOfEachKindOfCell() {
        val inp = input()
        val cells = measure(inp, n = 4, cellMs = 4 * min)
        val o = ScanProgress.outline(run(), cells, 12 * gb, now = 16 * min)
        val left = o.totalCells - o.measuredCells
        assertEquals(left * 4 * min, o.remainingMs)
    }

    @Test fun aRunningCellHasAlreadyUsedPartOfItsTime() {
        val inp = input()
        val cells = measure(inp, n = 4, cellMs = 4 * min)
        val next = ScanPlanner.next(inp, cells.map { it.toPCell() }) as PlanStep.Run
        val running = lite(next.spec, start = 16 * min, end = null).copy(status = CellStatus.RUNNING)
        val o = ScanProgress.outline(run(), cells + running, 12 * gb, now = 18 * min)
        val left = o.totalCells - o.measuredCells
        // Two of the running cell's four minutes are gone.
        assertEquals((left - 1) * 4 * min + 2 * min, o.remainingMs)
    }

    @Test fun withNoFinishedCellsTheEstimateFallsBackToTheSustainedLength() {
        val o = ScanProgress.outline(run(sustained = true).copy(sustainedMinutes = 12), emptyList(), 12 * gb, 0)
        assertNotNull(o.remainingMs)
        assertTrue(o.remainingMs!! > 12 * min)
    }

    @Test fun anOldRunWithoutStoredSettingsIsReadFromItsBaselineCell() {
        val inp = input()
        val cells = measure(inp, n = 3)
        val from = ScanProgress.inputFor(run(currentJson = ""), cells, 12 * gb)
        assertEquals(ScanPlanner.keyOf(settings), ScanPlanner.keyOf(from.current))
        // With no cells and no settings the plan still starts, from the defaults.
        assertNotNull(ScanProgress.inputFor(run(currentJson = ""), emptyList(), 12 * gb).current)
    }

    @Test fun aSustainedScanPlansItsSustainedCells() {
        val inp = input(sustained = true)
        val p = ScanPlanner.plan(inp, measure(inp, n = 2).map { it.toPCell() })
        assertTrue(p.stages.any { it.stage == "S" && it.cells.isNotEmpty() })
    }

    @Test fun durationsReadNaturally() {
        assertEquals("2:05", ScanProgress.clock(125_000))
        assertEquals("1:02:03", ScanProgress.clock((3600 + 123) * 1000L))
        assertEquals("under a minute", ScanProgress.duration(30_000))
        assertEquals("about 12 min", ScanProgress.duration(12 * min))
        assertEquals("about 1 h 40 min", ScanProgress.duration(100 * min))
    }

    @Test fun theGainOverTheBaselineIsReadFromTheCells() {
        val inp = input()
        val cells = measure(inp, speed = { if (it.settings.cacheMb == 3000) 3.6 else 3.0 })
        val key = ScanPlanner.keyOf(settings.copy(cacheMb = 3000))
        assertEquals(20.0, ScanProgress.gainOverBaselinePct(cells, key)!!, 0.01)
        assertNull(ScanProgress.gainOverBaselinePct(emptyList(), key))
    }
}
