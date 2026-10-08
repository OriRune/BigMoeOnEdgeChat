package io.bigmoeonedge.example.scan

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Hardware ceilings of the target phone's cores, in kHz: a lower scaling_max_freq means a thermal cap. */
object HwMax {
    const val CPU4_KHZ = 2_253_000
    const val CPU6_KHZ = 2_802_000
}

/**
 * One reading of the device, every 5 s while a cell runs and every 15 s while waiting to start one.
 * Only what the app is allowed to read (Phase 0): the thermal API, the world-readable cpufreq nodes,
 * the battery broadcast and /proc/meminfo. -1 marks a value that could not be read.
 */
data class Sample(
    val tMs: Long,
    val thermalStatus: Int,
    val headroom: Double, // -1 when unavailable; 1.0 is the severe threshold
    val cpu4MaxKHz: Int,
    val cpu6MaxKHz: Int,
    val cpu6CurKHz: Int,
    val batteryC: Double,
    val memAvailMb: Int,
    val charging: Boolean,
    val screenOn: Boolean,
) {
    /** The phone is holding its clocks down for heat: a thermal status above NONE, or a capped core. */
    val throttled: Boolean
        get() = thermalStatus >= THERMAL_LIGHT ||
            (cpu6MaxKHz in 1 until HwMax.CPU6_KHZ) || (cpu4MaxKHz in 1 until HwMax.CPU4_KHZ)

    fun toJson(): JSONObject = JSONObject().apply {
        put("t", tMs); put("th", thermalStatus); put("hr", headroom); put("c4", cpu4MaxKHz); put("c6", cpu6MaxKHz)
        put("c6c", cpu6CurKHz); put("bt", batteryC); put("mem", memAvailMb); put("chg", charging); put("scr", screenOn)
    }

    companion object {
        const val THERMAL_LIGHT = 1
        const val THERMAL_SEVERE = 3

        fun fromJson(o: JSONObject) = Sample(
            o.optLong("t"), o.optInt("th"), o.optDouble("hr", -1.0), o.optInt("c4", -1), o.optInt("c6", -1),
            o.optInt("c6c", -1), o.optDouble("bt", -1.0), o.optInt("mem", -1), o.optBoolean("chg"), o.optBoolean("scr"),
        )

        fun listToJson(l: List<Sample>): String = JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }.toString()
        fun listFromJson(s: String): List<Sample> =
            JSONArray(s).let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } }
    }
}

/** The cool state a cell has to return to before it starts: measured once at the start of a run. */
data class Reference(val headroom: Double, val thermalStatus: Int, val batteryC: Double, val memAvailMb: Int)

/** Source of [Sample]s: the real device, or a simulated one on the emulator. */
interface DeviceSampler {
    fun sample(nowMs: Long): Sample
}

/**
 * The cooldown gate: every cell starts from the same cool state or the matrix measures run order
 * (docs/benchmark-method.md). A pure predicate, so the rules are tested against sample sequences.
 */
object CooldownGate {
    const val HEADROOM_SLACK = 0.05
    const val BATTERY_SLACK_C = 1.5
    const val MEM_SLACK_MB = 300

    /** What still keeps the gate shut. Empty means the cell may start. */
    fun blockers(ref: Reference, s: Sample): List<String> = buildList {
        // Headroom is the continuous signal; it is skipped when either side could not read it.
        if (ref.headroom >= 0 && s.headroom >= 0 && s.headroom > ref.headroom + HEADROOM_SLACK) {
            add("headroom %.2f > %.2f".format(s.headroom, ref.headroom + HEADROOM_SLACK))
        }
        if (s.thermalStatus > ref.thermalStatus) add("thermal status ${s.thermalStatus} > ${ref.thermalStatus}")
        // An unreadable cpufreq node (-1) cannot say anything, so it never blocks.
        if (s.cpu6MaxKHz in 1 until HwMax.CPU6_KHZ) add("cpu6 capped at ${s.cpu6MaxKHz} kHz")
        if (s.cpu4MaxKHz in 1 until HwMax.CPU4_KHZ) add("cpu4 capped at ${s.cpu4MaxKHz} kHz")
        if (ref.batteryC >= 0 && s.batteryC >= 0 && s.batteryC > ref.batteryC + BATTERY_SLACK_C) {
            add("battery %.1f C > %.1f C".format(s.batteryC, ref.batteryC + BATTERY_SLACK_C))
        }
        if (ref.memAvailMb > 0 && s.memAvailMb >= 0 && s.memAvailMb < ref.memAvailMb - MEM_SLACK_MB) {
            add("MemAvailable ${s.memAvailMb} MB < ${ref.memAvailMb - MEM_SLACK_MB} MB")
        }
    }

    fun ready(ref: Reference, s: Sample) = blockers(ref, s).isEmpty()

    /** The reference is the median of the last [n] samples of the idle period. */
    fun reference(idle: List<Sample>, n: Int = 6): Reference {
        val last = idle.takeLast(n)
        fun med(v: List<Double>): Double = v.sorted().let { if (it.isEmpty()) -1.0 else it[it.size / 2] }
        return Reference(
            headroom = med(last.map { it.headroom }.filter { it >= 0 }),
            thermalStatus = last.maxOfOrNull { it.thermalStatus } ?: 0,
            batteryC = med(last.map { it.batteryC }.filter { it >= 0 }),
            memAvailMb = med(last.map { it.memAvailMb.toDouble() }.filter { it >= 0 }).toInt(),
        )
    }

    /** The start state is cool enough to call a cool start: thermal status at most LIGHT. */
    fun coolEnoughToStart(ref: Reference) = ref.thermalStatus <= Sample.THERMAL_LIGHT
}

/** Numbers derived from a cell's per-token times and its samples. */
data class CellStats(
    val decodeMedianTokS: Double,
    val coolMedianTokS: Double, // -1 when fewer than 128 tokens came before the first throttled sample
    val sustainedTokS: Double, // tok/s over the last 5 minutes; -1 for a burst
    val firstMinuteTokS: Double,
    val timeToThrottleS: Double, // -1 when the run never throttled
    val throttledFrac: Double,
    val maxThermalStatus: Int,
    val minCpu6MaxKHz: Int,
) {
    companion object {
        const val SKIP_STEPS = 32
        const val MIN_COOL_TOKENS = 128
        const val SUSTAIN_WINDOW_MS = 5 * 60_000L

        private fun medianTokS(wallMs: List<Double>): Double {
            val v = wallMs.filter { it > 0 }.sorted()
            if (v.isEmpty()) return -1.0
            val m = if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
            return 1000.0 / m
        }

        /**
         * @param wallMs per-token decode time from BMOE_PROGRESS, in order.
         * @param atMs when each token arrived, in ms since the generation started (same length).
         * @param samples taken while it ran, with [Sample.tMs] on the same clock.
         */
        fun compute(wallMs: List<Double>, atMs: List<Long>, samples: List<Sample>, sustained: Boolean): CellStats {
            val steady = if (wallMs.size > SKIP_STEPS) wallMs.drop(SKIP_STEPS) else wallMs
            val median = medianTokS(steady)

            val firstThrottle = samples.firstOrNull { it.throttled }?.tMs
            val cool = if (firstThrottle == null) {
                // Never throttled: all of it is cool. Same rule as the others, the 128-token floor.
                if (wallMs.size >= MIN_COOL_TOKENS) median else -1.0
            } else {
                val n = atMs.indexOfFirst { it >= firstThrottle }.let { if (it < 0) atMs.size else it }
                if (n >= MIN_COOL_TOKENS) medianTokS(wallMs.take(n).drop(SKIP_STEPS)) else -1.0
            }

            val end = atMs.lastOrNull() ?: 0L
            val windowStart = maxOf(0L, end - SUSTAIN_WINDOW_MS)
            val inWindow = atMs.indices.filter { atMs[it] >= windowStart }
            val sustainedTokS =
                if (!sustained || inWindow.size < 2) -1.0
                else (inWindow.size - 1) * 1000.0 / (atMs[inWindow.last()] - atMs[inWindow.first()]).coerceAtLeast(1)

            val firstMinute = atMs.count { it <= 60_000L }
            val firstMinuteTokS = if (end >= 30_000L) firstMinute / minOf(60.0, end / 1000.0) else -1.0

            return CellStats(
                decodeMedianTokS = median,
                coolMedianTokS = cool,
                sustainedTokS = sustainedTokS,
                firstMinuteTokS = firstMinuteTokS,
                timeToThrottleS = firstThrottle?.let { it / 1000.0 } ?: -1.0,
                throttledFrac = if (samples.isEmpty()) 0.0 else samples.count { it.throttled }.toDouble() / samples.size,
                maxThermalStatus = samples.maxOfOrNull { it.thermalStatus } ?: 0,
                minCpu6MaxKHz = samples.map { it.cpu6MaxKHz }.filter { it > 0 }.minOrNull() ?: -1,
            )
        }
    }
}

