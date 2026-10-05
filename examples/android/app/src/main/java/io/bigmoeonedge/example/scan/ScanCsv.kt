package io.bigmoeonedge.example.scan

import io.bigmoeonedge.example.chat.data.ScanCellEntity
import io.bigmoeonedge.example.chat.data.ScanRunEntity
import java.util.Locale

/** A scan as CSV: one row per cell, and one row per device sample, for a spreadsheet. */
object ScanCsv {
    private fun q(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    private fun f(v: Double): String = if (v.isNaN() || v < 0) "" else String.format(Locale.US, "%.3f", v)

    val CELL_HEADER = listOf(
        "run", "cell", "stage", "kind", "label", "attempt", "status", "lossy", "decode_median_tok_s", "cool_median_tok_s",
        "sustained_tok_s", "first_minute_tok_s", "time_to_throttle_s", "throttled_frac", "max_thermal_status", "min_cpu6_max_khz",
        "load_s", "prefill_s", "prefill_tok_s", "n_prompt", "tokens", "tok_s", "eff_tok_s", "cache_hit_pct", "read_mib",
        "majflt_per_tok", "cpu_s_per_tok", "cache_resident_mib", "peak_anon_mb", "cpuset", "cpus_allowed", "io_mode",
        "charging", "screen_on", "gate_wait_s", "gate_gave_up", "gate_note", "headroom_start", "headroom_end", "error",
    ).joinToString(",")

    fun cells(run: ScanRunEntity, cells: List<ScanCellEntity>): String = buildString {
        append(CELL_HEADER).append('\n')
        for (c in cells) {
            append(
                listOf(
                    run.id.toString(), c.id.toString(), c.stage, c.kind, q(c.label), c.attempt.toString(), c.status,
                    c.lossy.toString(), f(c.decodeMedianTokS), f(c.coolMedianTokS), f(c.sustainedTokS), f(c.firstMinuteTokS),
                    f(c.timeToThrottleS), f(c.throttledFrac), c.maxThermalStatus.toString(), c.minCpu6MaxKHz.toString(),
                    f(c.loadS), f(c.prefillS), f(c.prefillTps), c.nPrompt.toString(), c.tokens.toString(), f(c.tokS),
                    f(c.effTokS), f(c.cacheHitPct), f(c.readMib), f(c.majfltPerTok), f(c.cpuSPerTok), f(c.cacheResidentMib),
                    c.peakAnonMb.toString(), q(c.cpuset), c.cpusAllowed.toString(), q(c.ioMode), c.charging.toString(),
                    c.screenOn.toString(), f(c.gateWaitS), c.gateGaveUp.toString(), q(c.gateNote), f(c.headroomStart),
                    f(c.headroomEnd), q(c.error ?: ""),
                ).joinToString(","),
            ).append('\n')
        }
    }

    fun samples(cells: List<ScanCellEntity>): String = buildString {
        append("cell,t_ms,thermal_status,headroom,cpu4_max_khz,cpu6_max_khz,cpu6_cur_khz,battery_c,mem_avail_mb,charging,screen_on\n")
        for (c in cells) for (s in Sample.listFromJson(c.samplesJson)) {
            append("${c.id},${s.tMs},${s.thermalStatus},${f(s.headroom)},${s.cpu4MaxKHz},${s.cpu6MaxKHz},${s.cpu6CurKHz},")
            append("${f(s.batteryC)},${s.memAvailMb},${s.charging},${s.screenOn}\n")
        }
    }
}
