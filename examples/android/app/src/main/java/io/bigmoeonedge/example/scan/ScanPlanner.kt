package io.bigmoeonedge.example.scan

import io.bigmoeonedge.example.AppSettings
import io.bigmoeonedge.example.DenseWeights
import io.bigmoeonedge.example.chat.data.CellKind
import io.bigmoeonedge.example.chat.data.CellStatus

/** What the planner needs to know about a recorded cell. */
data class PCell(
    val stage: String,
    val kind: String,
    val label: String,
    val key: String,
    val status: String,
    val attempt: Int,
    val decodeMedianTokS: Double = -1.0,
    val coolMedianTokS: Double = -1.0,
    val sustainedTokS: Double = -1.0,
    val throttledFrac: Double = 0.0,
    val peakAnonMb: Int = -1,
    val cacheResidentMib: Double = -1.0,
    val cpusAllowed: Int = 0,
    val nExpertUsed: Int = 0,
    val gateGaveUp: Boolean = false,
)

/** A cell the planner wants run. [attempt] > 0 is a re-run (thermal contamination or an abort). */
data class CellSpec(
    val stage: String,
    val kind: String,
    val label: String,
    val settings: AppSettings,
    val lossy: Boolean,
    val attempt: Int,
) {
    val key: String get() = ScanPlanner.keyOf(settings)
}

data class PlanInput(
    val current: AppSettings,
    val modelBytes: Long,
    val ramBytes: Long,
    val includeLossy: Boolean,
    val includeSustained: Boolean,
    // Anonymous memory (RAM + swap) the engine process may hold: 3.1 GiB minus 500 MB in :engine.
    val budgetMb: Int = ScanPlanner.BUDGET_MB,
)

data class ScanOutcome(
    val recommended: AppSettings?,
    val recommendedLabel: String,
    val confirmed: Boolean,
    val verdict: String,
    val burstWinnerLabel: String = "",
    val sustainedWinnerLabel: String = "",
    val failed: Boolean = false,
)

sealed interface PlanStep {
    data class Run(val spec: CellSpec, val ordinal: Int = 1, val ofTotal: Int = 1) : PlanStep
    data class Finished(val outcome: ScanOutcome) : PlanStep
}

/**
 * Chooses the next cell of a scan from the cells recorded so far. It holds no state of its own, which
 * is what makes a scan resumable: after a restart the same cells give the same next step.
 *
 * Burst stages are a coordinate descent from the lossless baseline: each stage changes one knob of
 * the incumbent, and a candidate replaces it only by beating it by 5% (so defaults win over noise).
 * Texting replies are long and the phone throttles, so the recommendation is then chosen on a
 * sustained run, and finally confirmed against the baseline in alternating burst cells.
 */
object ScanPlanner {
    const val BUDGET_MB = 3172 - 500
    const val WIN_MARGIN = 1.05
    const val CONTAMINATED_FRAC = 0.25
    const val MAX_ATTEMPTS = 2
    private val BURST_STAGES = listOf("A", "B", "C", "D", "E", "F", "G")

    fun keyOf(s: AppSettings) = s.sessionSignature("m")

    /** Score a finished cell is compared on: cool-only speed when the run was thermally contaminated. */
    fun score(c: PCell): Double = when {
        c.status != CellStatus.DONE -> 0.0
        c.throttledFrac > CONTAMINATED_FRAC && c.coolMedianTokS > 0 -> c.coolMedianTokS
        else -> c.decodeMedianTokS.coerceAtLeast(0.0)
    }

    private sealed interface Res {
        data class Need(val spec: CellSpec) : Res
        data class Have(val cell: PCell) : Res
    }

    private fun resolve(spec: CellSpec, cells: List<PCell>): Res {
        val mine = cells.filter { it.stage == spec.stage && it.label == spec.label && it.key == spec.key }
            .sortedBy { it.attempt }
        val last = mine.lastOrNull() ?: return Res.Need(spec.copy(attempt = 0))
        return when (last.status) {
            CellStatus.PENDING, CellStatus.RUNNING -> Res.Need(spec.copy(attempt = last.attempt))
            CellStatus.THERMAL_ABORT ->
                if (mine.size < MAX_ATTEMPTS) Res.Need(spec.copy(attempt = mine.size)) else Res.Have(last)
            CellStatus.DONE ->
                // A re-run only helps when the phone was cool at the start: if the gate gave up, the second
                // attempt would start from the same warm state.
                if (spec.kind != CellKind.SUSTAINED && last.throttledFrac > CONTAMINATED_FRAC && !last.gateGaveUp &&
                    mine.size < MAX_ATTEMPTS
                ) {
                    Res.Need(spec.copy(attempt = mine.size))
                } else Res.Have(last)
            else -> Res.Have(last) // FAILED is data
        }
    }

    private class Cand(val label: String, val settings: AppSettings)

    private fun candidates(stage: String, inc: AppSettings, base: PCell, inp: PlanInput): List<Cand> {
        val small = inp.ramBytes > 0 && inp.modelBytes < inp.ramBytes / 2
        val streaming = !inc.mmap
        val out = mutableListOf<Cand>()
        when (stage) {
            "A" -> if (streaming) {
                val kinds = mutableListOf(DenseWeights.ANON, DenseWeights.AHWB)
                if (small) kinds += DenseWeights.WARM
                for (d in kinds) if (d != inc.denseWeights) out += Cand("dense ${d.flag}", inc.copy(denseWeights = d))
                if (small) out += Cand("mmap (no streaming)", inc.copy(mmap = true))
            }
            "B" -> if (streaming) {
                for (mb in listOf(1000, 1500, 2000, 3000, 4000)) {
                    if (mb == inc.cacheMb) continue
                    // Predicted footprint: what the baseline peaked at, with its cache swapped for this one.
                    val predicted = base.peakAnonMb - maxOf(base.cacheResidentMib, 0.0).toInt() + mb
                    if (base.peakAnonMb > 0 && predicted > inp.budgetMb) continue
                    out += Cand("cache $mb MiB", inc.copy(cacheMb = mb))
                }
            }
            "C" -> {
                // The child is confined to the cores its cpuset allows (0-5 in the background): more
                // threads than that only fight each other.
                val cores = if (base.cpusAllowed > 0) base.cpusAllowed else 8
                for (t in listOf(2, 4, 6, 8)) if (t != inc.threads && t <= cores) out += Cand("threads $t", inc.copy(threads = t))
            }
            "D" -> if (streaming) {
                for (t in listOf(2, 4, 6)) if (t != inc.ioThreads) out += Cand("io lanes $t", inc.copy(ioThreads = t))
            }
            "E" -> if (streaming && (inc.denseWeights == DenseWeights.ANON || inc.denseWeights == DenseWeights.AHWB)) {
                out += Cand("release mmap ${if (inc.releaseMmap) "off" else "on"}", inc.copy(releaseMmap = !inc.releaseMmap))
            }
            "F" -> if (streaming) {
                out += Cand("row stream ${if (inc.rowStream) "off" else "on"}", inc.copy(rowStream = !inc.rowStream))
            }
            "G" -> if (inc.spec == AppSettings.SPEC_OFF) {
                out += Cand("n-gram drafting (greedy only)", inc.copy(spec = AppSettings.SPEC_NGRAM))
            }
        }
        return out
    }

    fun next(inp: PlanInput, cells: List<PCell>): PlanStep {
        val base = SettingsJson.lossless(inp.current)
        fun burst(stage: String, label: String, s: AppSettings, lossy: Boolean = false, kind: String = CellKind.BURST) =
            CellSpec(stage, kind, label, s, lossy, 0)

        // 1-2: where the user is now, and the lossless baseline the search starts from.
        if (keyOf(inp.current) != keyOf(base)) {
            (resolve(burst("CUR", "Your current settings", inp.current, lossy = true), cells) as? Res.Need)
                ?.let { return PlanStep.Run(it.spec) }
        }
        val baseCell = when (val r = resolve(burst("BASE", "Lossless baseline", base), cells)) {
            is Res.Need -> return PlanStep.Run(r.spec)
            is Res.Have -> r.cell
        }
        if (baseCell.status != CellStatus.DONE) {
            return PlanStep.Finished(
                ScanOutcome(null, "", false, "The baseline did not complete, so there is nothing to compare against.", failed = true),
            )
        }

        // 3: burst stages.
        var inc = base
        var incCell = baseCell
        var runnerUpThreads: Int? = null
        for (stage in BURST_STAGES) {
            val results = mutableListOf<Pair<Cand, PCell>>()
            val cands = candidates(stage, inc, baseCell, inp)
            for ((i, c) in cands.withIndex()) {
                when (val r = resolve(burst(stage, c.label, c.settings), cells)) {
                    is Res.Need -> return PlanStep.Run(r.spec, i + 1, cands.size)
                    is Res.Have -> results += c to r.cell
                }
            }
            val done = results.filter { it.second.status == CellStatus.DONE }
            val best = done.maxByOrNull { score(it.second) }
            if (best != null && score(best.second) >= WIN_MARGIN * score(incCell)) {
                inc = best.first.settings
                incCell = best.second
            }
            if (stage == "C") {
                runnerUpThreads = done.filter { keyOf(it.first.settings) != keyOf(inc) }
                    .maxByOrNull { score(it.second) }?.first?.settings?.threads
            }
        }
        val burstWinnerLabel = if (keyOf(inc) == keyOf(base)) "Lossless baseline" else describe(inc, base)

        // 4: sustained runs decide the recommendation.
        var recommended = inc
        var recommendedLabel = burstWinnerLabel
        var sustainedLabel = ""
        if (inp.includeSustained) {
            val configs = LinkedHashMap<String, Pair<String, AppSettings>>()
            configs.putIfAbsent(keyOf(inc), "Fastest burst" to inc)
            if (runnerUpThreads != null && runnerUpThreads != inc.threads) {
                val s = inc.copy(threads = runnerUpThreads)
                configs.putIfAbsent(keyOf(s), "Threads ${runnerUpThreads} (runner-up)" to s)
            }
            configs.putIfAbsent(keyOf(base), "Lossless baseline" to base)
            val finished = mutableListOf<Triple<String, AppSettings, PCell>>()
            for ((i, v) in configs.values.withIndex()) {
                when (val r = resolve(burst("S", v.first, v.second, kind = CellKind.SUSTAINED), cells)) {
                    is Res.Need -> return PlanStep.Run(r.spec, i + 1, configs.size)
                    is Res.Have -> finished += Triple(v.first, v.second, r.cell)
                }
            }
            val best = finished.filter { it.third.status == CellStatus.DONE && it.third.sustainedTokS > 0 }
                .maxByOrNull { it.third.sustainedTokS }
            if (best != null) {
                recommended = best.second
                sustainedLabel = best.first
                recommendedLabel = if (keyOf(best.second) == keyOf(base)) "Lossless baseline" else describe(best.second, base)
            }
        }

        // 5: confirmation, alternating W-B-W-B so slow drift hits both equally.
        var confirmed = false
        var verdict: String
        if (keyOf(recommended) == keyOf(base)) {
            verdict = "No setting beat the lossless baseline by a clear margin. Chats keep the baseline."
        } else {
            val seq = listOf("Recommended 1" to recommended, "Baseline 1" to base, "Recommended 2" to recommended, "Baseline 2" to base)
            val got = mutableListOf<Pair<String, PCell>>()
            for ((i, ls) in seq.withIndex()) {
                val (label, s) = ls
                when (val r = resolve(burst("Z", label, s, kind = CellKind.CONFIRM), cells)) {
                    is Res.Need -> return PlanStep.Run(r.spec, i + 1, seq.size)
                    is Res.Have -> got += label to r.cell
                }
            }
            val w = got.filter { it.first.startsWith("Recommended") }.map { score(it.second) }.average()
            val b = got.filter { it.first.startsWith("Baseline") }.map { score(it.second) }.average()
            confirmed = b > 0 && w >= WIN_MARGIN * b
            verdict = if (confirmed) {
                "Confirmed: %.1f tok/s against %.1f for the baseline.".format(w, b)
            } else {
                "The gain is within noise (%.1f against %.1f tok/s), so treat it as a tie.".format(w, b)
            }
            if (inp.includeSustained && sustainedLabel.isNotEmpty() && keyOf(inc) != keyOf(recommended)) {
                verdict = "Fastest from cold: $burstWinnerLabel. Fastest once the phone is hot: $recommendedLabel. " +
                    "Chats use the second. $verdict"
            }
        }

        // 6: lossy candidates, measured against the final lossless recommendation and never applied.
        if (inp.includeLossy) {
            val k = baseCell.nExpertUsed
            val lossy = mutableListOf<Cand>()
            if (k > 2) lossy += Cand("top-k ${k - 1}", recommended.copy(nExpertUsed = k - 1))
            if (k > 3) lossy += Cand("top-k ${k - 2}", recommended.copy(nExpertUsed = k - 2))
            if (!recommended.mmap) {
                lossy += Cand("drop cold 50%", recommended.copy(dropColdPct = 50))
                lossy += Cand("drop cold 75%", recommended.copy(dropColdPct = 75))
                lossy += Cand("substitute 10%", recommended.copy(substitutePct = 10))
                lossy += Cand("substitute 15%", recommended.copy(substitutePct = 15))
            }
            for ((i, c) in lossy.withIndex()) {
                (resolve(burst("H", c.label, c.settings, lossy = true), cells) as? Res.Need)
                    ?.let { return PlanStep.Run(it.spec, i + 1, lossy.size) }
            }
        }
        return PlanStep.Finished(
            ScanOutcome(recommended, recommendedLabel, confirmed, verdict, burstWinnerLabel, sustainedLabel),
        )
    }

    /** What differs from the baseline, in a few words. */
    fun describe(s: AppSettings, base: AppSettings): String {
        val parts = mutableListOf<String>()
        if (s.mmap != base.mmap) parts += if (s.mmap) "mmap" else "streaming"
        if (s.denseWeights != base.denseWeights) parts += "dense ${s.denseWeights.flag}"
        if (s.cacheMb != base.cacheMb) parts += "cache ${s.cacheMb}"
        if (s.threads != base.threads) parts += "threads ${s.threads}"
        if (s.ioThreads != base.ioThreads) parts += "io ${s.ioThreads}"
        if (s.releaseMmap != base.releaseMmap) parts += "release mmap"
        if (s.rowStream != base.rowStream) parts += "row stream"
        if (s.spec != base.spec) parts += "n-gram"
        if (s.nExpertUsed != base.nExpertUsed) parts += "top-k ${s.nExpertUsed}"
        if (s.dropColdPct != base.dropColdPct) parts += "drop ${s.dropColdPct}%"
        if (s.substitutePct != base.substitutePct) parts += "substitute ${s.substitutePct}%"
        return if (parts.isEmpty()) "Lossless baseline" else parts.joinToString(", ")
    }

    /** Rough minutes for the whole scan, shown before Start. A big model loads slower and decodes slower. */
    fun estimateMinutes(modelBytes: Long, ramBytes: Long, includeLossy: Boolean, includeSustained: Boolean, sustainedMin: Int): IntRange {
        val big = ramBytes > 0 && modelBytes >= ramBytes / 2
        val perBurst = if (big) 8.0 else 3.0 // load + prefill + 256 tokens + the cooldown gate
        val bursts = 14 + (if (includeLossy) 6 else 0) + 4
        val sustained = if (includeSustained) 3 * (sustainedMin + 5) else 0
        val mid = 3 + bursts * perBurst + sustained
        return (mid * 0.8).toInt()..(mid * 1.3).toInt()
    }
}
