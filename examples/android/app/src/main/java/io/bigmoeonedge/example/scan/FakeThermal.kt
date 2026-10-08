package io.bigmoeonedge.example.scan

/**
 * A simulated phone for the emulator: it heats while the fake engine generates and cools while it
 * idles, so the scan's cooldown gate, throttled-sample logic and contamination re-run all have
 * something to react to. [timeScale] makes a "minute" of heating pass in a fraction of one.
 */
object FakeThermal {
    @Volatile var timeScale = 1.0

    /** Multiplies the fake engine's speed, so tests run a cell in a couple of seconds. */
    @Volatile var speedScale = 1.0
    @Volatile var active = false
    private var heat = 0.0
    private var last = System.currentTimeMillis()

    /** Seconds of generation to fully heat, and of idling to fully cool, at timeScale 1. */
    private const val HEAT_S = 90.0
    private const val COOL_S = 120.0

    @Synchronized fun advance(now: Long = System.currentTimeMillis()): Double {
        val dt = (now - last).coerceAtLeast(0) / 1000.0 * timeScale
        last = now
        heat = if (active) minOf(1.0, heat + dt / HEAT_S) else maxOf(0.0, heat - dt / COOL_S)
        return heat
    }

    @Synchronized fun reset() {
        heat = 0.0
        active = false
        last = System.currentTimeMillis()
    }

    /** How much slower the fake engine decodes when hot. */
    fun slowdown(): Double = 1.0 - 0.35 * advance()

    fun sample(tMs: Long): Sample {
        val h = advance()
        return Sample(
            tMs = tMs,
            thermalStatus = if (h > 0.8) 2 else if (h > 0.35) 1 else 0,
            headroom = 0.6 + 0.35 * h,
            cpu4MaxKHz = if (h > 0.5) 2_000_000 else HwMax.CPU4_KHZ,
            cpu6MaxKHz = if (h > 0.3) 2_400_000 else HwMax.CPU6_KHZ,
            cpu6CurKHz = 500_000,
            batteryC = 30.0 + 3.0 * h,
            memAvailMb = 2000,
            charging = false,
            screenOn = false,
        )
    }

    /** tok/s the fake engine would reach with these settings, from its argv: a made-up but fixed landscape. */
    fun tokPerSec(argv: List<String>): Double {
        fun arg(flag: String) = argv.indexOf(flag).let { if (it >= 0 && it + 1 < argv.size) argv[it + 1] else null }
        var v = 2.0
        val threads = arg("-t")?.toIntOrNull() ?: 4
        v *= when (threads) { 2 -> 0.7; 4 -> 1.0; 6 -> 1.15; else -> 1.1 }
        val cache = arg("--cache-mb")?.toIntOrNull() ?: 0
        v *= 1.0 + 0.00006 * cache.coerceAtMost(4000)
        if (arg("--dense-weights") == "warm") v *= 1.08
        if (argv.contains("--ngram")) v *= 1.15
        if (!argv.contains("--moe-stream")) v *= 0.9
        if (argv.contains("--drop-cold-experts")) v *= 1.3
        return v * speedScale
    }
}
