package io.bigmoeonedge.example.scan

import io.bigmoeonedge.example.AppSettings
import io.bigmoeonedge.example.chat.data.CellKind
import io.bigmoeonedge.example.chat.data.CellStatus
import io.bigmoeonedge.example.chat.data.CellLite
import io.bigmoeonedge.example.chat.data.ScanCellEntity
import io.bigmoeonedge.example.chat.data.ScanRunEntity
import java.io.File

fun CellLite.toPCell() = PCell(
    stage = stage, kind = kind, label = label, key = argvSig, status = status, attempt = attempt,
    decodeMedianTokS = decodeMedianTokS, coolMedianTokS = coolMedianTokS, sustainedTokS = sustainedTokS,
    throttledFrac = throttledFrac, peakAnonMb = peakAnonMb, cacheResidentMib = cacheResidentMib,
    cpusAllowed = cpusAllowed, nExpertUsed = nExpertUsed, gateGaveUp = gateGaveUp,
)

fun ScanCellEntity.lite() = CellLite(
    runId, stage, kind, label, argvSig, status, attempt, startedAt, finishedAt, decodeMedianTokS, coolMedianTokS,
    sustainedTokS, throttledFrac, peakAnonMb, cacheResidentMib, cpusAllowed, nExpertUsed, gateGaveUp, settingsJson,
)

/** Where a scan stands, derived from its cells alone: what is done, what is left, how long it will take. */
data class Outline(
    val stages: List<StagePlan>,
    /** Cells of the plan, measured or not. A projection: it grows if a setting wins and confirmation is added. */
    val totalCells: Int,
    val measuredCells: Int,
    /** Confirmation cells the plan does not contain yet because nothing has won so far. */
    val extraIfWin: Int,
    /** Null when there is nothing to base an estimate on. */
    val remainingMs: Long?,
    val finished: Boolean,
) {
    /** 1-based number of the cell being worked on now. */
    val currentOrdinal: Int get() = (measuredCells + 1).coerceAtMost(totalCells.coerceAtLeast(1))
    val fraction: Float get() = if (totalCells == 0) 0f else measuredCells.toFloat() / totalCells
    /** The stage that holds the first unmeasured cell, null when everything is measured. */
    val currentStage: StagePlan? get() = stages.firstOrNull { !it.isDone }
}

/**
 * Pure helpers behind the scan screens. They read the stored run and cells and never the settings of
 * today, so a scan from yesterday explains itself the same way.
 */
object ScanProgress {
    const val CONFIRM_CELLS = 4
    private const val BURST_FALLBACK_SMALL_MS = 3 * 60_000L
    private const val BURST_FALLBACK_BIG_MS = 8 * 60_000L

    /**
     * The settings the scan started from. The run stores them (since database version 3); an older run
     * has them in its CUR cell, or failing that in its baseline cell, which differs only in the lossy
     * knobs and therefore plans the same cells.
     */
    fun inputFor(run: ScanRunEntity, cells: List<CellLite>, ramBytes: Long): PlanInput {
        val current = when {
            run.currentJson.isNotEmpty() -> SettingsJson.fromJson(run.currentJson)
            else -> (cells.firstOrNull { it.stage == "CUR" } ?: cells.firstOrNull { it.stage == "BASE" })
                ?.let { runCatching { SettingsJson.fromJson(it.settingsJson) }.getOrNull() } ?: AppSettings()
        }
        return PlanInput(current, File(run.modelPath).length(), ramBytes, run.includeLossy, run.includeSustained)
    }

    fun outline(run: ScanRunEntity, cells: List<CellLite>, ramBytes: Long, now: Long): Outline {
        val inp = inputFor(run, cells, ramBytes)
        val plan = ScanPlanner.plan(inp, cells.map { it.toPCell() })
        val steps = plan.steps
        val unmeasured = steps.filter { it.have == null }
        val hasConfirm = plan.stages.any { it.stage == "Z" }
        val extra = if (!hasConfirm && unmeasured.isNotEmpty()) CONFIRM_CELLS else 0
        val big = inp.ramBytes > 0 && inp.modelBytes >= inp.ramBytes / 2
        val typical = typicalMs(cells, run, big)

        var remaining = 0L
        var any = unmeasured.isEmpty()
        val running = cells.firstOrNull { it.status == CellStatus.RUNNING && it.startedAt > 0 }
        for ((i, pc) in unmeasured.withIndex()) {
            val t = typical(pc.spec.kind)
            // The cell being worked on has already used part of its time.
            remaining += if (i == 0 && running != null) (t - (now - running.startedAt)).coerceAtLeast(30_000L) else t
            any = true
        }
        return Outline(
            stages = plan.stages, totalCells = steps.size, measuredCells = steps.size - unmeasured.size,
            extraIfWin = extra, remainingMs = if (any) remaining else null, finished = unmeasured.isEmpty(),
        )
    }

    /** How long a cell of each kind takes on this phone, from the cells already finished, with fallbacks. */
    private fun typicalMs(cells: List<CellLite>, run: ScanRunEntity, big: Boolean): (String) -> Long {
        fun median(kind: String): Long? {
            val d = cells.filter { it.kind == kind && it.finishedAt != null && it.startedAt > 0 && it.status != CellStatus.PENDING }
                .map { it.finishedAt!! - it.startedAt }.filter { it > 0 }.sorted()
            return d.getOrNull(d.size / 2)
        }
        val burst = median(CellKind.BURST) ?: if (big) BURST_FALLBACK_BIG_MS else BURST_FALLBACK_SMALL_MS
        val sustained = median(CellKind.SUSTAINED) ?: ((run.sustainedMinutes + 5) * 60_000L)
        val confirm = median(CellKind.CONFIRM) ?: burst
        return { kind -> when (kind) { CellKind.SUSTAINED -> sustained; CellKind.CONFIRM -> confirm; else -> burst } }
    }

    /**
     * The one-line state of a stage for the checklist: what it found, or how far it has got. For a scan
     * that is no longer running (`ended`), a stage with nothing measured was never reached, not waiting.
     */
    fun stageLine(sp: StagePlan, activeStage: String?, ended: Boolean = false): String {
        fun unmeasured() = when {
            !ended -> if (sp.stage == activeStage) "measuring…" else "waiting"
            sp.stage == activeStage -> "not reached: the scan stopped here"
            else -> "not run"
        }
        return stageLineOf(sp, ::unmeasured, ended)
    }

    private fun stageLineOf(sp: StagePlan, unmeasured: () -> String, ended: Boolean): String = when {
        sp.cells.isEmpty() -> "not applicable to this model"
        sp.stage == "BASE" || sp.stage == "CUR" -> sp.cells.single().have?.let { c ->
            if (c.status != CellStatus.DONE) "failed" else fmt(ScanPlanner.score(c)) + " tok/s"
        } ?: unmeasured()
        !sp.isDone -> if (sp.measured == 0) unmeasured()
        else if (ended) "${sp.measured} of ${sp.cells.size} measured, then it stopped"
        else "${sp.measured} of ${sp.cells.size} measured"
        sp.stage == "S" -> sp.winnerLabel?.let { "${it} was fastest hot (${fmt(sp.bestScore)} tok/s)" }
            ?: "${sp.bestLabel ?: "no result"} stays (${fmt(sp.bestScore)} tok/s hot)"
        sp.stage == "Z" -> if (sp.winnerLabel != null) "confirmed (${fmt(sp.bestScore)} vs ${fmt(sp.incumbentScore)} tok/s)"
            else "within noise (${fmt(sp.bestScore)} vs ${fmt(sp.incumbentScore)} tok/s)"
        sp.stage == "H" -> "${sp.cells.size} measured, not applied"
        sp.winnerLabel != null -> "${sp.winnerLabel} won (%+.0f%%)".format(sp.bestGainPct)
        sp.bestLabel != null -> "kept ${sp.incumbentLabel.lowercase()} (best alternative %+.0f%%, needs +5%%)".format(sp.bestGainPct)
        else -> "kept ${sp.incumbentLabel.lowercase()}; every try failed"
    }

    /** The recommendation's gain over the lossless baseline's burst speed, in percent, or null when unknown. */
    fun gainOverBaselinePct(cells: List<CellLite>, recommendedKey: String?): Double? {
        val base = cells.firstOrNull { it.stage == "BASE" && it.status == CellStatus.DONE } ?: return null
        val b = ScanPlanner.score(base.toPCell())
        if (b <= 0) return null
        val rec = cells.filter { it.status == CellStatus.DONE && it.argvSig == recommendedKey && it.kind != CellKind.SUSTAINED }
            .maxByOrNull { ScanPlanner.score(it.toPCell()) } ?: return null
        return (ScanPlanner.score(rec.toPCell()) / b - 1) * 100
    }

    private fun fmt(v: Double) = if (v <= 0) "—" else "%.2f".format(v)

    /** "2:31" for under an hour, "1:02:31" above. */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** "28 h 25 min", "42 min", "under a minute": an elapsed span, not an estimate. */
    fun span(ms: Long): String {
        val m = Math.round(ms / 60_000.0).toInt()
        return when {
            ms < 60_000 -> "under a minute"
            m >= 120 -> "%d h %02d min".format(m / 60, m % 60)
            else -> "$m min"
        }
    }

    /** "about 1 h 40 min", "about 12 min", "under a minute". */
    fun duration(ms: Long): String {
        val m = (ms / 60_000.0).let { Math.round(it) }.toInt()
        return when {
            ms < 60_000 -> "under a minute"
            m >= 90 -> "about %d h %02d min".format(m / 60, m % 60)
            else -> "about $m min"
        }
    }
}
