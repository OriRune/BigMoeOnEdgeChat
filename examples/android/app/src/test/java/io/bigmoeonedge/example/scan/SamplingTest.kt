package io.bigmoeonedge.example.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplingTest {
    private fun sample(
        t: Long = 0, status: Int = 0, headroom: Double = 0.6, c4: Int = HwMax.CPU4_KHZ, c6: Int = HwMax.CPU6_KHZ,
        bat: Double = 30.0, mem: Int = 2000,
    ) = Sample(t, status, headroom, c4, c6, 500_000, bat, mem, charging = false, screenOn = false)

    private val ref = Reference(headroom = 0.6, thermalStatus = 0, batteryC = 30.0, memAvailMb = 2000)

    @Test fun aCoolSampleOpensTheGate() {
        assertTrue(CooldownGate.ready(ref, sample()))
    }

    @Test fun eachConditionKeepsTheGateShut() {
        assertFalse(CooldownGate.ready(ref, sample(headroom = 0.7)))
        assertFalse(CooldownGate.ready(ref, sample(status = 1)))
        assertFalse(CooldownGate.ready(ref, sample(c6 = 2_048_000)))
        assertFalse(CooldownGate.ready(ref, sample(c4 = 1_836_000)))
        assertFalse(CooldownGate.ready(ref, sample(bat = 32.0)))
        assertFalse(CooldownGate.ready(ref, sample(mem = 1500)))
    }

    @Test fun smallDriftWithinTheSlackIsFine() {
        assertTrue(CooldownGate.ready(ref, sample(headroom = 0.64, bat = 31.4, mem = 1750)))
    }

    @Test fun unavailableHeadroomAndUnreadableCpufreqNeverBlock() {
        val noHeadroomRef = ref.copy(headroom = -1.0)
        assertTrue(CooldownGate.ready(noHeadroomRef, sample(headroom = 0.95)))
        assertTrue(CooldownGate.ready(ref, sample(headroom = -1.0)))
        assertTrue(CooldownGate.ready(ref, sample(c4 = -1, c6 = -1)))
    }

    @Test fun theReferenceIsTheMedianOfTheLastSamples() {
        val idle = (0 until 10).map { sample(t = it * 5000L, headroom = if (it < 4) 0.9 else 0.6) }
        val r = CooldownGate.reference(idle)
        assertEquals(0.6, r.headroom, 1e-9)
        assertEquals(0, r.thermalStatus)
        assertEquals(30.0, r.batteryC, 1e-9)
    }

    @Test fun aSampleIsThrottledByStatusOrACap() {
        assertFalse(sample().throttled)
        assertTrue(sample(status = 1).throttled)
        assertTrue(sample(c6 = 2_700_000).throttled)
        assertFalse(sample(c4 = -1, c6 = -1).throttled)
    }

    @Test fun samplesRoundTripThroughJson() {
        val l = listOf(sample(1000), sample(6000, status = 2, headroom = -1.0))
        assertEquals(l, Sample.listFromJson(Sample.listToJson(l)))
    }

    @Test fun statsIgnoreTheFirstStepsAndTakeTheMedian() {
        // 32 slow warm-up tokens then 100 at 250 ms (4 tok/s): the warm-up must not count.
        val wall = List(32) { 2000.0 } + List(100) { 250.0 }
        val at = wall.runningFold(0L) { a, w -> a + w.toLong() }.drop(1)
        val s = CellStats.compute(wall, at, emptyList(), sustained = false)
        assertEquals(4.0, s.decodeMedianTokS, 1e-9)
        // Never throttled and 132 tokens: all of it is cool.
        assertEquals(4.0, s.coolMedianTokS, 1e-9)
    }

    @Test fun coolSpeedUsesOnlyTokensBeforeTheFirstThrottledSample() {
        // 200 tokens at 100 ms (10 tok/s) then 200 at 400 ms (2.5 tok/s); throttling is first seen at 20 s.
        val wall = List(200) { 100.0 } + List(200) { 400.0 }
        val at = wall.runningFold(0L) { a, w -> a + w.toLong() }.drop(1)
        val samples = listOf(sample(5_000), sample(10_000), sample(20_000, status = 1), sample(25_000, status = 1))
        val s = CellStats.compute(wall, at, samples, sustained = false)
        assertEquals(10.0, s.coolMedianTokS, 1e-9)
        assertEquals(20.0, s.timeToThrottleS, 1e-9)
        assertEquals(0.5, s.throttledFrac, 1e-9)
        assertEquals(1, s.maxThermalStatus)
    }

    @Test fun tooFewCoolTokensGiveNoCoolFigure() {
        val wall = List(300) { 100.0 }
        val at = wall.runningFold(0L) { a, w -> a + w.toLong() }.drop(1)
        val samples = listOf(sample(2_000, status = 1)) // throttled after ~20 tokens
        assertEquals(-1.0, CellStats.compute(wall, at, samples, false).coolMedianTokS, 0.0)
    }

    @Test fun sustainedSpeedIsTheLastFiveMinutes() {
        // 10 minutes: the first 5 at 10 tok/s, the last 5 at 2 tok/s.
        val at = ArrayList<Long>()
        var t = 0L
        while (t < 300_000) { t += 100; at += t }
        while (t < 600_000) { t += 500; at += t }
        val wall = at.indices.map { if (at[it] <= 300_000) 100.0 else 500.0 }
        val s = CellStats.compute(wall, at, emptyList(), sustained = true)
        assertEquals(2.0, s.sustainedTokS, 0.05)
        assertTrue(s.firstMinuteTokS > 9.0)
    }

    @Test fun aBurstHasNoSustainedFigure() {
        val s = CellStats.compute(List(50) { 100.0 }, (1..50).map { it * 100L }, emptyList(), sustained = false)
        assertEquals(-1.0, s.sustainedTokS, 0.0)
    }
}
