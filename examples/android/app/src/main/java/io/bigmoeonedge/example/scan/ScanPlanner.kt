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

/** A cell of the plan: [have] is its measurement, null while it is still to run (or running). */
data class PlannedCell(val spec: CellSpec, val have: PCell?, val ordinal: Int = 1, val ofTotal: Int = 1)

/** One stage of the plan, with what it decided once its cells are measured. */
data class StagePlan(
    val stage: String,
    val title: String,
    val cells: List<PlannedCell>,
    /** What the stage tried to improve on, and how fast it was (tok/s on the stage's own measure). */
    val incumbentLabel: String = "",
    val incumbentScore: Double = 0.0,
    /** The candidate that replaced it; null when the incumbent stayed. */
    val winnerLabel: String? = null,
    /** The fastest measured candidate, whether or not it won. */
    val bestLabel: String? = null,
    val bestScore: Double = 0.0,
) {
    val measured: Int get() = cells.count { it.have != null }
    val isDone: Boolean get() = measured == cells.size
    /** The best candidate against the incumbent, in percent; 0 when either is unknown. */
    val bestGainPct: Double get() = if (bestLabel != null && incumbentScore > 0) (bestScore / incumbentScore - 1) * 100 else 0.0
}

data class Plan(val stages: List<StagePlan>, val outcome: ScanOutcome) {
    val steps: List<PlannedCell> get() = stages.flatMap { it.cells }
    /** True while some cell is unmeasured: the outcome and the later stages are then a projection. */
    val projected: Boolean get() = steps.any { it.have == null }
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

    /** The next thing to run, or the verdict when everything is measured. */
    fun next(inp: PlanInput, cells: List<PCell>): PlanStep {
        val p = plan(inp, cells)
        p.steps.firstOrNull { it.have == null }?.let { return PlanStep.Run(it.spec, it.ordinal, it.ofTotal) }
        return PlanStep.Finished(p.outcome)
    }

    fun stageTitle(stage: String): String = when (stage) {
        "CUR" -> "Your current settings"
        "BASE" -> "Lossless baseline"
        "A" -> "Dense weights"
        "B" -> "Expert cache"
        "C" -> "Threads"
        "D" -> "I/O lanes"
        "E" -> "Release mmap"
        "F" -> "Row streaming"
        "G" -> "N-gram drafting"
        "S" -> "Sustained test"
        "Z" -> "Confirmation"
        "H" -> "Lossy settings"
        else -> stage
    }

    private fun placeholder(spec: CellSpec, like: PCell? = null) = PCell(
        spec.stage, spec.kind, spec.label, spec.key, CellStatus.DONE, spec.attempt,
        decodeMedianTokS = 0.0, coolMedianTokS = 0.0, sustainedTokS = 0.0,
        peakAnonMb = like?.peakAnonMb ?: -1, cacheResidentMib = like?.cacheResidentMib ?: -1.0,
        cpusAllowed = like?.cpusAllowed ?: 0, nExpertUsed = like?.nExpertUsed ?: 0,
    )

    /**
     * The whole scan, as far as it can be seen from the cells recorded so far: every stage in order with
     * the cells it has (measured, or still to run) and what it decided. [next] is the first unmeasured
     * cell of this, so the plan and the executor cannot disagree. Beyond the first unmeasured cell the
     * plan is a projection that assumes nothing measured later wins by the margin (the incumbent stays),
     * which is also why it shows no confirmation cells until something has won.
     */
    fun plan(inp: PlanInput, cells: List<PCell>): Plan {
        val base = SettingsJson.lossless(inp.current)
        val stages = mutableListOf<StagePlan>()
        fun burst(stage: String, label: String, s: AppSettings, lossy: Boolean = false, kind: String = CellKind.BURST) =
            CellSpec(stage, kind, label, s, lossy, 0)
        fun planned(spec: CellSpec, ordinal: Int = 1, ofTotal: Int = 1): PlannedCell = when (val r = resolve(spec, cells)) {
            is Res.Need -> PlannedCell(r.spec, null, ordinal, ofTotal)
            is Res.Have -> PlannedCell(spec, r.cell, ordinal, ofTotal)
        }

        // 1-2: where the user is now, and the lossless baseline the search starts from.
        if (keyOf(inp.current) != keyOf(base)) {
            stages += StagePlan("CUR", stageTitle("CUR"), listOf(planned(burst("CUR", "Your current settings", inp.current, lossy = true))))
        }
        val basePc = planned(burst("BASE", "Lossless baseline", base))
        stages += StagePlan("BASE", stageTitle("BASE"), listOf(basePc))
        val baseHave = basePc.have
        if (baseHave != null && baseHave.status != CellStatus.DONE) {
            return Plan(
                stages,
                ScanOutcome(null, "", false, "The baseline did not complete, so there is nothing to compare against.", failed = true),
            )
        }
        val baseCell = baseHave ?: placeholder(basePc.spec)

        // 3: burst stages. Only measured cells can win: an unmeasured one is not evidence.
        var inc = base
        var incCell = baseCell
        var runnerUpThreads: Int? = null
        for (stage in BURST_STAGES) {
            val cands = candidates(stage, inc, baseCell, inp)
            val before = if (keyOf(inc) == keyOf(base)) "Lossless baseline" else describe(inc, base)
            val pcs = cands.mapIndexed { i, c -> planned(burst(stage, c.label, c.settings), i + 1, cands.size) }
            val done = cands.zip(pcs).filter { it.second.have?.status == CellStatus.DONE }
            val best = done.maxByOrNull { score(it.second.have!!) }
            val incScore = score(incCell)
            var winner: String? = null
            if (best != null && score(best.second.have!!) >= WIN_MARGIN * incScore) {
                winner = best.first.label
                inc = best.first.settings
                incCell = best.second.have!!
            }
            stages += StagePlan(
                stage, stageTitle(stage), pcs, incumbentLabel = before, incumbentScore = incScore,
                winnerLabel = winner, bestLabel = best?.first?.label, bestScore = best?.let { score(it.second.have!!) } ?: 0.0,
            )
            if (stage == "C") {
                runnerUpThreads = done.filter { keyOf(it.first.settings) != keyOf(inc) }
                    .maxByOrNull { score(it.second.have!!) }?.first?.settings?.threads
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
            val pcs = configs.values.mapIndexed { i, v ->
                planned(burst("S", v.first, v.second, kind = CellKind.SUSTAINED), i + 1, configs.size)
            }
            val finished = configs.values.zip(pcs).filter { it.second.have != null }
                .map { Triple(it.first.first, it.first.second, it.second.have!!) }
            val best = finished.filter { it.third.status == CellStatus.DONE && it.third.sustainedTokS > 0 }
                .maxByOrNull { it.third.sustainedTokS }
            if (best != null) {
                recommended = best.second
                sustainedLabel = best.first
                recommendedLabel = if (keyOf(best.second) == keyOf(base)) "Lossless baseline" else describe(best.second, base)
            }
            val first = finished.firstOrNull { it.first == "Fastest burst" }
            stages += StagePlan(
                "S", stageTitle("S"), pcs, incumbentLabel = burstWinnerLabel, incumbentScore = first?.third?.sustainedTokS ?: 0.0,
                winnerLabel = best?.takeIf { it.first != "Fastest burst" }?.first,
                bestLabel = best?.first, bestScore = best?.third?.sustainedTokS ?: 0.0,
            )
        }

        // 5: confirmation, alternating W-B-W-B so slow drift hits both equally.
        var confirmed = false
        var verdict: String
        if (keyOf(recommended) == keyOf(base)) {
            verdict = "No setting beat the lossless baseline by a clear margin. Chats keep the baseline."
        } else {
            val seq = listOf("Recommended 1" to recommended, "Baseline 1" to base, "Recommended 2" to recommended, "Baseline 2" to base)
            val pcs = seq.mapIndexed { i, ls -> planned(burst("Z", ls.first, ls.second, kind = CellKind.CONFIRM), i + 1, seq.size) }
            val allMeasured = pcs.all { it.have != null }
            val got = seq.zip(pcs).filter { it.second.have != null }.map { it.first.first to it.second.have!! }
            val w = got.filter { it.first.startsWith("Recommended") }.map { score(it.second) }.average()
            val b = got.filter { it.first.startsWith("Baseline") }.map { score(it.second) }.average()
            confirmed = allMeasured && b > 0 && w >= WIN_MARGIN * b
            verdict = when {
                !allMeasured -> ""
                confirmed -> "Confirmed: %.1f tok/s against %.1f for the baseline.".format(w, b)
                else -> "The gain is within noise (%.1f against %.1f tok/s), so treat it as a tie.".format(w, b)
            }
            if (allMeasured && inp.includeSustained && sustainedLabel.isNotEmpty() && keyOf(inc) != keyOf(recommended)) {
                verdict = "Fastest from cold: $burstWinnerLabel. Fastest once the phone is hot: $recommendedLabel. " +
                    "Chats use the second. $verdict"
            }
            stages += StagePlan(
                "Z", stageTitle("Z"), pcs, incumbentLabel = "Lossless baseline", incumbentScore = if (allMeasured) b else 0.0,
                winnerLabel = if (confirmed) recommendedLabel else null, bestLabel = recommendedLabel, bestScore = if (allMeasured) w else 0.0,
            )
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
            val pcs = lossy.mapIndexed { i, c -> planned(burst("H", c.label, c.settings, lossy = true), i + 1, lossy.size) }
            stages += StagePlan("H", stageTitle("H"), pcs)
        }
        return Plan(stages, ScanOutcome(recommended, recommendedLabel, confirmed, verdict, burstWinnerLabel, sustainedLabel))
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
