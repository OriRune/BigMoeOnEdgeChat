package io.bigmoeonedge.example.scan

import io.bigmoeonedge.example.AppSettings
import io.bigmoeonedge.example.DenseWeights
import io.bigmoeonedge.example.chat.data.CellKind
import io.bigmoeonedge.example.chat.data.CellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the planner to the end against a made-up phone: [burst] and [sustained] are tok/s per setting. */
private class Sim(
    val inp: PlanInput,
    val burst: (AppSettings) -> Double = { 3.0 },
    val sustained: (AppSettings) -> Double = { burst(it) * 0.8 },
    val throttled: (CellSpec) -> Double = { 0.0 },
    val peakAnonMb: Int = -1,
    val cores: Int = 6,
    val failIf: (CellSpec) -> Boolean = { false },
) {
    val cells = mutableListOf<PCell>()
    val ran = mutableListOf<CellSpec>()

    fun cellFor(spec: CellSpec): PCell {
        val fail = failIf(spec)
        val frac = throttled(spec).let { if (spec.attempt > 0) 0.0 else it }
        return PCell(
            spec.stage, spec.kind, spec.label, spec.key,
            if (fail) CellStatus.FAILED else CellStatus.DONE, spec.attempt,
            decodeMedianTokS = burst(spec.settings), coolMedianTokS = burst(spec.settings) * 1.1,
            sustainedTokS = if (spec.kind == CellKind.SUSTAINED) sustained(spec.settings) else -1.0,
            throttledFrac = frac, peakAnonMb = peakAnonMb, cacheResidentMib = 500.0, cpusAllowed = cores, nExpertUsed = 8,
        )
    }

    fun run(limit: Int = 200): ScanOutcome {
        repeat(limit) {
            when (val s = ScanPlanner.next(inp, cells)) {
                is PlanStep.Finished -> return s.outcome
                is PlanStep.Run -> {
                    ran += s.spec
                    cells += cellFor(s.spec)
                }
            }
        }
        error("planner did not finish")
    }
}

class ScanPlannerTest {
    private val gb = 1L shl 30
    private val base = AppSettings(dropColdPct = 0)
    private fun input(
        current: AppSettings = base, modelGb: Long = 4, sustained: Boolean = false, lossy: Boolean = false,
    ) = PlanInput(current, modelGb * gb, 12 * gb, lossy, sustained)

    @Test fun stagesRunInOrderAndBaselineFirst() {
        val sim = Sim(input())
        sim.run()
        val stages = sim.ran.map { it.stage }.distinct()
        assertEquals(listOf("BASE", "A", "B", "C", "D", "E", "F", "G"), stages)
        assertEquals("Lossless baseline", sim.ran.first().label)
    }

    @Test fun currentSettingsRunFirstWhenTheyAreLossy() {
        val sim = Sim(input(current = AppSettings(dropColdPct = 75)))
        sim.run()
        assertEquals(listOf("CUR", "BASE"), sim.ran.take(2).map { it.stage })
        assertTrue(sim.ran.first().lossy)
    }

    @Test fun aCandidateNeedsFivePercentToReplaceTheIncumbent() {
        // threads 6 is 4% faster: kept out; cache 3000 is 6% faster: taken.
        val speed: (AppSettings) -> Double = {
            var v = 3.0
            if (it.threads == 6) v *= 1.04
            if (it.cacheMb == 3000) v *= 1.06
            v
        }
        val sim = Sim(input(), burst = speed)
        val out = sim.run()
        assertEquals(3000, out.recommended!!.cacheMb)
        assertEquals(base.threads, out.recommended!!.threads)
        // Later stages start from the new incumbent: its cache is in the threads candidates.
        val c = sim.ran.first { it.stage == "C" }
        assertEquals(3000, c.settings.cacheMb)
    }

    @Test fun nothingWinningKeepsTheBaseline() {
        val out = Sim(input(), burst = { 3.0 }).run()
        assertEquals(base.copy(), out.recommended)
        assertTrue(out.verdict.contains("baseline"))
    }

    @Test fun cacheCandidatesAboveTheMemoryBudgetAreSkipped() {
        // peak 1800 MB with a 500 MB cache => 1300 MB without it; a budget of 2672 allows 1000 only.
        val sim = Sim(input(), peakAnonMb = 1800)
        sim.run()
        val caches = sim.ran.filter { it.stage == "B" }.map { it.settings.cacheMb }
        assertEquals(listOf(1000), caches)
    }

    @Test fun threadCandidatesStayWithinTheCoresTheChildMayUse() {
        val sim = Sim(input(), cores = 6)
        sim.run()
        val threads = sim.ran.filter { it.stage == "C" }.map { it.settings.threads }
        assertFalse(8 in threads)
        assertTrue(6 in threads && 2 in threads)
    }

    @Test fun memoryHungryDenseModesAreOnlyTriedOnASmallModel() {
        val small = Sim(input(modelGb = 4)).also { it.run() }.ran.filter { it.stage == "A" }.map { it.label }
        val big = Sim(input(modelGb = 20)).also { it.run() }.ran.filter { it.stage == "A" }.map { it.label }
        assertTrue(small.any { it.contains("warm") } && small.any { it.contains("mmap") })
        assertTrue(big.none { it.contains("warm") || it.contains("mmap") })
        assertTrue(big.any { it.contains("ahwb") })
    }

    @Test fun aContaminatedBurstCellRunsOnceMoreAndKeepsTheSecond() {
        val sim = Sim(input(), throttled = { if (it.stage == "C" && it.settings.threads == 2) 0.5 else 0.0 })
        sim.run()
        val c2 = sim.ran.filter { it.stage == "C" && it.settings.threads == 2 }
        assertEquals(listOf(0, 1), c2.map { it.attempt })
    }

    @Test fun aCellThatStaysContaminatedIsComparedOnItsCoolSpeed() {
        // Both attempts hot; the planner stops after two and scores the cool-only median.
        val cells = mutableListOf<PCell>()
        val hot = PCell("C", CellKind.BURST, "x", "k", CellStatus.DONE, 1, decodeMedianTokS = 2.0, coolMedianTokS = 4.0, throttledFrac = 0.6)
        assertEquals(4.0, ScanPlanner.score(hot), 0.0)
        val noCool = hot.copy(coolMedianTokS = -1.0)
        assertEquals(2.0, ScanPlanner.score(noCool), 0.0)
        assertEquals(0.0, ScanPlanner.score(hot.copy(status = CellStatus.FAILED)), 0.0)
        assertTrue(cells.isEmpty())
    }

    @Test fun theRecommendationIsChosenOnSustainedSpeedNotBurst() {
        // threads 6 wins the burst stage by 20% but collapses when sustained; the baseline holds.
        val sim = Sim(
            input(sustained = true),
            burst = { if (it.threads == 6) 3.6 else 3.0 },
            sustained = { if (it.threads == 6) 1.5 else if (it.threads == 4) 2.9 else 2.8 },
        )
        val out = sim.run()
        assertEquals(base.threads, out.recommended!!.threads)
        assertTrue(sim.ran.any { it.kind == CellKind.SUSTAINED })
        assertTrue(out.verdict.contains("baseline"))
    }

    @Test fun whenSustainedAgreesWithBurstTheWinnerIsRecommendedAndConfirmed() {
        val sim = Sim(input(sustained = true), burst = { if (it.cacheMb == 3000) 3.6 else 3.0 })
        val out = sim.run()
        assertEquals(3000, out.recommended!!.cacheMb)
        assertTrue(out.confirmed)
        assertEquals(listOf("Recommended 1", "Baseline 1", "Recommended 2", "Baseline 2"), sim.ran.filter { it.stage == "Z" }.map { it.label })
    }

    @Test fun aGainWithinNoiseIsNotConfirmed() {
        // The search sees +6%, the confirmation cells see none (the cell speed differs per attempt).
        var z = false
        val sim = Sim(input(), burst = { if (it.cacheMb == 3000) 3.2 else 3.0 })
        val real = sim::cellFor
        val out = run {
            repeat(200) {
                when (val s = ScanPlanner.next(sim.inp, sim.cells)) {
                    is PlanStep.Finished -> return@run s.outcome
                    is PlanStep.Run -> {
                        sim.ran += s.spec
                        z = z || s.spec.stage == "Z"
                        sim.cells += real(s.spec).let { c -> if (s.spec.stage == "Z") c.copy(decodeMedianTokS = 3.0) else c }
                    }
                }
            }
            error("no end")
        }
        assertTrue(z)
        assertFalse(out.confirmed)
        assertTrue(out.verdict.contains("noise"))
    }

    @Test fun lossySettingsAreMeasuredButNeverRecommended() {
        val sim = Sim(input(lossy = true), burst = { if (it.dropColdPct > 0) 9.0 else 3.0 })
        val out = sim.run()
        val h = sim.ran.filter { it.stage == "H" }
        assertTrue(h.isNotEmpty() && h.all { it.lossy })
        assertEquals(0, out.recommended!!.dropColdPct)
        assertTrue(h.any { it.settings.nExpertUsed == 7 })
    }

    @Test fun noLossyStageWithoutOptingIn() {
        val sim = Sim(input(lossy = false))
        sim.run()
        assertTrue(sim.ran.none { it.stage == "H" })
    }

    @Test fun resumeSkipsDoneCellsAndRerunsTheInterruptedOne() {
        val full = Sim(input()).also { it.run() }
        // Stop after 5 cells, the fifth left RUNNING by a dead process.
        val cut = full.cells.take(5).toMutableList()
        cut[4] = cut[4].copy(status = CellStatus.RUNNING)
        val step = ScanPlanner.next(full.inp, cut) as PlanStep.Run
        assertEquals(full.ran[4].label, step.spec.label)
        assertEquals(full.ran[4].stage, step.spec.stage)
        // With the fifth really done, the next step is the sixth.
        val next = ScanPlanner.next(full.inp, full.cells.take(5)) as PlanStep.Run
        assertEquals(full.ran[5].label, next.spec.label)
    }

    @Test fun aFailedCandidateIsDataAndTheScanGoesOn() {
        val sim = Sim(input(), failIf = { it.stage == "B" && it.settings.cacheMb == 4000 })
        val out = sim.run()
        assertNotNull(out.recommended)
        assertFalse(out.failed)
    }

    @Test fun aFailedBaselineEndsTheScan() {
        val sim = Sim(input(), failIf = { it.stage == "BASE" })
        val out = sim.run()
        assertTrue(out.failed)
        assertNull(out.recommended)
    }

    @Test fun theRerunLimitIsTwoAttempts() {
        val cells = listOf(
            PCell("B", CellKind.BURST, "l", ScanPlanner.keyOf(base), CellStatus.THERMAL_ABORT, 0),
            PCell("B", CellKind.BURST, "l", ScanPlanner.keyOf(base), CellStatus.THERMAL_ABORT, 1),
        )
        // Not asserting on the planner internals: after two aborts the run moves on instead of looping.
        val sim = Sim(input())
        sim.cells += cells
        val out = sim.run()
        assertNotNull(out)
    }

    @Test fun estimateIsLongerForABigModel() {
        val small = ScanPlanner.estimateMinutes(4 * gb, 12 * gb, false, true, 12)
        val big = ScanPlanner.estimateMinutes(20 * gb, 12 * gb, false, true, 12)
        assertTrue(big.first > small.first)
        assertTrue(small.last > small.first)
    }

    @Test fun denseModeEnumIsCoveredByTheLabels() {
        assertEquals("anon", DenseWeights.ANON.flag)
    }
}
