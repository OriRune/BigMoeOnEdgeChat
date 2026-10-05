package io.bigmoeonedge.example.scan

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File

/**
 * Reads what the app may read of the phone (Phase 0): the thermal API, the world-readable cpufreq
 * nodes, the battery broadcast and /proc/meminfo. /sys/class/thermal is not readable here, so it is
 * not used. Anything unreadable comes back as -1 and never blocks the gate.
 */
class AndroidSampler(private val ctx: Context) : DeviceSampler {
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var lastHeadroomAt = 0L
    private var lastHeadroom = -1.0

    override fun sample(nowMs: Long): Sample {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        return Sample(
            tMs = nowMs,
            thermalStatus = runCatching { pm.currentThermalStatus }.getOrDefault(0),
            headroom = headroom(),
            cpu4MaxKHz = khz(4, "scaling_max_freq"),
            cpu6MaxKHz = khz(6, "scaling_max_freq"),
            cpu6CurKHz = khz(6, "scaling_cur_freq"),
            batteryC = if (temp != Int.MIN_VALUE) temp / 10.0 else -1.0,
            memAvailMb = memAvailMb(),
            charging = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            screenOn = pm.isInteractive,
        )
    }

    /** The headroom call is rate limited by the system; ask at most once a second. */
    private fun headroom(): Double {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return -1.0
        val now = System.currentTimeMillis()
        if (now - lastHeadroomAt < 1000) return lastHeadroom
        lastHeadroomAt = now
        val h = runCatching { pm.getThermalHeadroom(10).toDouble() }.getOrDefault(Double.NaN)
        lastHeadroom = if (h.isNaN() || h < 0) -1.0 else h
        return lastHeadroom
    }

    private fun khz(cpu: Int, node: String): Int =
        runCatching { File("/sys/devices/system/cpu/cpu$cpu/cpufreq/$node").readText().trim().toInt() }.getOrDefault(-1)

    private fun memAvailMb(): Int = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.first { it.startsWith("MemAvailable:") }.filter { it.isDigit() }.toLong() / 1024
        }.toInt()
    }.getOrDefault(-1)
}

/** The simulated phone behind a [DeviceSampler]. */
class FakeSampler : DeviceSampler {
    override fun sample(nowMs: Long): Sample = FakeThermal.sample(nowMs)
}
