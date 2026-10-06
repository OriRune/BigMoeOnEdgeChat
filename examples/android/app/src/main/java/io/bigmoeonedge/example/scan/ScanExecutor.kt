package io.bigmoeonedge.example.scan

import io.bigmoeonedge.example.AppSettings
import io.bigmoeonedge.example.chat.data.CellKind
import io.bigmoeonedge.example.chat.data.CellStatus
import io.bigmoeonedge.example.chat.data.ChatDb
import io.bigmoeonedge.example.chat.data.EngineStateName
import io.bigmoeonedge.example.chat.data.ScanCellEntity
import io.bigmoeonedge.example.chat.data.ScanRunEntity
import io.bigmoeonedge.example.chat.data.ScanRunStatus
import io.bigmoeonedge.example.chat.engine.EngineEvent
import io.bigmoeonedge.example.chat.engine.EngineHost
import io.bigmoeonedge.example.chat.engine.EngineProtocol
import io.bigmoeonedge.example.chat.engine.EngineSession
import io.bigmoeonedge.example.chat.engine.DoneInfo
import io.bigmoeonedge.example.chat.engine.SessionFailed
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.File

/** Every duration of a scan in one place, so tests can run a whole scan in seconds. */
data class ScanTiming(
    val refIdleMs: Long = 3 * 60_000L,
    val refExtendMs: Long = 15 * 60_000L,
    val sampleMs: Long = 5_000L,
    val gatePollMs: Long = 15_000L,
    val gateMaxMs: Long = 10 * 60_000L,
    val nPredict: Int = 256,
    // A load that has not finished by then is thrashing (swap full of other apps) and is recorded as failed.
    val loadTimeoutMs: Long = 45 * 60_000L,
    // Overrides the run's sustained minutes (tests and the fast fake mode).
    val sustainedMs: Long? = null,
)

/**
 * Runs one scan to its end inside the engine process: measures the cool reference state, then asks
 * [ScanPlanner] for the next cell until it is done. Each cell waits at the cooldown gate, opens a
 * fresh engine session, generates, samples the phone while it does, and writes everything down.
 * The database is the state, so a killed process resumes at the first unfinished cell.
 */
class ScanExecutor(
    private val db: ChatDb,
    private val host: EngineHost,
    private val timing: ScanTiming = ScanTiming(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val setStatus: suspend (EngineStateName, String) -> Unit = { _, _ -> },
) {
    @Volatile private var stopped = false
    @Volatile private var session: EngineSession? = null
    private val sampler by lazy { host.deviceSampler() }
    private val scan get() = db.scan()
    private val cellDurations = mutableListOf<Long>()

    fun stop() {
        stopped = true
        session?.backend?.send(EngineProtocol.CANCEL)
    }

    val isStopping: Boolean get() = stopped

    suspend fun run(run0: ScanRunEntity) {
        stopped = false
        var run = run0
        scan.resetRunningCells(run.id)
        val size = File(run.modelPath).length()
        val current = host.scanCurrentSettings(run.modelPath)
        val input = PlanInput(current, size, host.ramBytes(), run.includeLossy, run.includeSustained)
        val model = File(run.modelPath).nameWithoutExtension.take(30)
        try {
            while (true) {
                if (stopped) return finish(run, ScanRunStatus.STOPPED, "Stopped. Finished cells are kept; resume to continue.")
                if (run.referenceMemAvailMb == 0) {
                    run = measureReference(run, model) ?: return finish(run, ScanRunStatus.STOPPED, "Stopped.")
                }
                val cells = scan.cells(run.id)
                when (val step = ScanPlanner.next(input, cells.map(::toPlanner))) {
                    is PlanStep.Finished -> return conclude(run, step.outcome)
                    is PlanStep.Run -> {
                        val t0 = clock()
                        val done = runCell(run, step, model, cells.size)
                        if (done) cellDurations += clock() - t0
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            finish(run, ScanRunStatus.FAILED, "The scan stopped on an error: ${t.message ?: t}")
        } finally {
            session?.let { runCatching { it.close() } }
            session = null
        }
    }

    // ── reference state ──

    private suspend fun measureReference(run: ScanRunEntity, model: String): ScanRunEntity? {
        status(run, "reference", "Scan · $model · letting the phone settle", 0)
        val samples = mutableListOf<Sample>()
        val t0 = clock()
        var limit = timing.refIdleMs
        var ref: Reference
        while (true) {
            while (clock() - t0 < limit) {
                if (stopped) return null
                samples += sampler.sample(clock() - t0)
                status(run, "reference", "Scan · $model · settling ${mmss(limit - (clock() - t0))}", 0)
                delay(timing.sampleMs)
            }
            ref = CooldownGate.reference(samples)
            // Not cool yet: give it the longer wait once, then start anyway and flag the run.
            if (CooldownGate.coolEnoughToStart(ref) || limit >= timing.refExtendMs) break
            limit = timing.refExtendMs
        }
        val updated = run.copy(
            referenceHeadroom = ref.headroom, referenceThermal = ref.thermalStatus, referenceBatteryC = ref.batteryC,
            referenceMemAvailMb = maxOf(1, ref.memAvailMb), startedWarm = !CooldownGate.coolEnoughToStart(ref),
        )
        scan.updateRun(updated)
        return updated
    }

    private fun refOf(run: ScanRunEntity) =
        Reference(run.referenceHeadroom, run.referenceThermal, run.referenceBatteryC, run.referenceMemAvailMb)

    // ── one cell ──

    private class Gate(val waitS: Double, val gaveUp: Boolean, val note: String, val stopped: Boolean)

    private suspend fun awaitGate(run: ScanRunEntity, model: String, step: PlanStep.Run): Gate {
        val ref = refOf(run)
        val t0 = clock()
        var paused = 0L
        while (true) {
            if (stopped) return Gate((clock() - t0) / 1000.0, false, "", true)
            val s = sampler.sample(clock())
            val blockers = CooldownGate.blockers(ref, s).toMutableList()
            if (s.thermalStatus >= Sample.THERMAL_SEVERE) blockers += "thermal status SEVERE"
            val pause = host.scanPauseReason()
            if (pause != null) {
                paused += timing.gatePollMs
                blockers += pause
            }
            if (blockers.isEmpty()) return Gate((clock() - t0) / 1000.0, false, "", false)
            if (clock() - t0 - paused > timing.gateMaxMs) {
                return Gate((clock() - t0) / 1000.0, true, blockers.joinToString("; "), false)
            }
            status(run, step.spec.stage, "Scan · $model · stage ${step.spec.stage} ${step.ordinal}/${step.ofTotal} · cooling ${mmss(clock() - t0)} · ${blockers.first()}", step.ordinal)
            delay(timing.gatePollMs)
        }
    }

    private suspend fun runCell(run: ScanRunEntity, step: PlanStep.Run, model: String, doneCount: Int): Boolean {
        val spec = step.spec
        var cell = findOrCreate(run, spec)
        scan.updateCell(cell.copy(status = CellStatus.RUNNING, startedAt = clock()).also { cell = it })

        val gate = awaitGate(run, model, step)
        if (gate.stopped) {
            scan.updateCell(cell.copy(status = CellStatus.PENDING))
            return false
        }
        cell = cell.copy(gateWaitS = gate.waitS, gateGaveUp = gate.gaveUp, gateNote = gate.note).also { scan.updateCell(it) }

        val label = "Scan · $model · stage ${spec.stage} ${step.ordinal}/${step.ofTotal}"
        status(run, spec.stage, "$label · loading", step.ordinal)
        val csv = host.scanCsv(run.id, cell.id)
        val cfg = host.scanJobConfig(run.modelPath, spec.settings.copy(metricsCsv = true), csv)
        val s = EngineSession(host.createBackend(cfg), cfg.sig, run.modelPath)
        session = s
        s.start(cfg)

        var result = cell.copy(argvSig = spec.key)
        val wall = mutableListOf<Double>()
        val at = mutableListOf<Long>()
        val samples = mutableListOf<Sample>()
        var peakMb = -1
        var aborted = false
        var error: String? = null
        var first: DoneInfo? = null
        var totalTokens = 0
        var text = ""
        try {
            val ready = withTimeoutOrNull(timing.loadTimeoutMs) { s.awaitReady() }
                ?: throw SessionFailed("The model did not finish loading in ${timing.loadTimeoutMs / 60_000} minutes (memory pressure?).")
            val (cpuset, cores) = host.cpusetOf(s.backend.pid)
            result = result.copy(
                nExpertUsed = ready.nExpertUsed ?: 0, loadS = ready.loadS, cpuset = cpuset, cpusAllowed = cores,
                ioMode = s.ioMode,
            )
            val genStart = clock()
            val sustained = spec.kind == CellKind.SUSTAINED
            val deadline = genStart + (timing.sustainedMs ?: (run.sustainedMinutes * 60_000L))
            coroutineScope {
                val sampling = launch {
                    while (isActive) {
                        val smp = sampler.sample(clock() - genStart)
                        samples += smp
                        peakMb = maxOf(peakMb, host.childMemoryMb(s.backend.pid))
                        if (smp.thermalStatus >= Sample.THERMAL_SEVERE && !aborted) {
                            aborted = true
                            s.backend.send(EngineProtocol.CANCEL)
                        }
                        delay(timing.sampleMs)
                    }
                }
                val prompt = host.scanPrompt()
                var restarts = 0
                var firstRequest = true
                while (true) {
                    val id = s.nextId++
                    val nPredict = if (sustained) minOf(1500, (spec.settings.sessionCtx - 700).coerceAtLeast(64)) else timing.nPredict
                    val req = if (firstRequest) {
                        EngineProtocol.generate(id, prompt, nPredict, think = false, clearKv = true)
                    } else {
                        EngineProtocol.generate(id, "Continue.", nPredict, think = false, clearKv = false, fitCtx = true)
                    }
                    if (!s.backend.send(req)) throw SessionFailed("The engine is not running.")
                    var cancelSent = false
                    var done: DoneInfo? = null
                    var retryFresh = false
                    while (done == null && !retryFresh) {
                        val remaining = if (sustained) deadline - clock() else Long.MAX_VALUE
                        val ev = if (sustained && !cancelSent) {
                            withTimeoutOrNull(remaining.coerceAtLeast(1)) { s.events.receive() }
                        } else {
                            s.events.receive()
                        }
                        if (ev == null) {
                            // The time is up: cut the reply short; the cancelled Done follows.
                            cancelSent = true
                            s.backend.send(EngineProtocol.CANCEL)
                            continue
                        }
                        if (stopped && !cancelSent) {
                            cancelSent = true
                            s.backend.send(EngineProtocol.CANCEL)
                        }
                        when (ev) {
                            is EngineSession.Ev.Exit -> {
                                s.exited = true
                                throw SessionFailed("The engine stopped unexpectedly (exit ${ev.code}). ${s.tail()}".trim())
                            }
                            is EngineSession.Ev.Line -> when (val p = s.protocol.parse(ev.text)) {
                                is EngineEvent.Progress -> {
                                    wall += p.telemetry.wallMs
                                    at += clock() - genStart
                                }
                                is EngineEvent.Done -> done = p.info
                                is EngineEvent.Error -> {
                                    if (p.fatal) throw SessionFailed(p.msg)
                                    // Out of context: the next request starts from a clean KV.
                                    if (sustained && restarts < 3 && "exceeds" in p.msg) {
                                        restarts++
                                        retryFresh = true
                                    } else throw SessionFailed(p.msg)
                                }
                                else -> {}
                            }
                        }
                    }
                    if (retryFresh) {
                        firstRequest = true
                        continue
                    }
                    val d = done!!
                    if (first == null) first = d
                    totalTokens += d.tokens
                    if (text.isEmpty()) text = d.text
                    firstRequest = false
                    if (!sustained || stopped || aborted || d.cancelled || clock() >= deadline) break
                }
                sampling.cancel()
            }
        } catch (e: SessionFailed) {
            error = e.message
        } finally {
            runCatching { s.close() }
            session = null
        }

        if (stopped) {
            scan.updateCell(cell.copy(status = CellStatus.PENDING))
            return false
        }
        val stats = CellStats.compute(wall, at, samples, spec.kind == CellKind.SUSTAINED)
        val d = first
        val end = clock()
        val finalCell = result.copy(
            status = when {
                error != null -> CellStatus.FAILED
                aborted -> CellStatus.THERMAL_ABORT
                else -> CellStatus.DONE
            },
            error = error ?: if (aborted) "Stopped: the phone reached thermal status SEVERE." else null,
            finishedAt = end,
            prefillS = d?.prefillS ?: -1.0, prefillTps = d?.prefillTps ?: -1.0, nPrompt = d?.nPrompt ?: -1,
            tokens = totalTokens, tokS = d?.tokS ?: -1.0, effTokS = d?.effectiveTokS ?: -1.0,
            cacheHitPct = d?.cacheHitPct ?: -1.0, readMib = d?.readMib ?: -1.0, majfltPerTok = d?.majfltPerTok ?: -1.0,
            cpuSPerTok = d?.cpuSPerTok ?: -1.0, cacheResidentMib = d?.cacheResidentMib ?: -1.0,
            decodeMedianTokS = stats.decodeMedianTokS, coolMedianTokS = stats.coolMedianTokS,
            sustainedTokS = stats.sustainedTokS, firstMinuteTokS = stats.firstMinuteTokS,
            timeToThrottleS = stats.timeToThrottleS, throttledFrac = stats.throttledFrac,
            maxThermalStatus = stats.maxThermalStatus, minCpu6MaxKHz = stats.minCpu6MaxKHz,
            headroomStart = samples.firstOrNull()?.headroom ?: -1.0, headroomEnd = samples.lastOrNull()?.headroom ?: -1.0,
            peakAnonMb = peakMb, charging = samples.any { it.charging }, screenOn = samples.any { it.screenOn },
            outputText = text.take(4000),
            tokenMsJson = JSONArray(wall.map { Math.round(it * 10) / 10.0 }).toString(),
            tokenAtJson = JSONArray(at).toString(),
            samplesJson = Sample.listToJson(samples),
        )
        scan.updateCell(finalCell)
        return true
    }

    private suspend fun findOrCreate(run: ScanRunEntity, spec: CellSpec): ScanCellEntity {
        val existing = scan.cells(run.id).firstOrNull {
            it.stage == spec.stage && it.label == spec.label && it.argvSig == spec.key && it.attempt == spec.attempt &&
                (it.status == CellStatus.PENDING || it.status == CellStatus.RUNNING)
        }
        if (existing != null) return existing
        val e = ScanCellEntity(
            runId = run.id, stage = spec.stage, kind = spec.kind, label = spec.label,
            settingsJson = SettingsJson.toJson(spec.settings), argvSig = spec.key, status = CellStatus.PENDING,
            attempt = spec.attempt, lossy = spec.lossy,
        )
        return e.copy(id = scan.insertCell(e))
    }

    // ── bookkeeping ──

    private suspend fun status(run: ScanRunEntity, stage: String, text: String, ordinal: Int) {
        val eta = etaText(run)
        val line = if (eta.isEmpty()) text else "$text · ETA $eta"
        scan.run(run.id)?.let { scan.updateRun(it.copy(stage = stage, detail = line)) }
        host.foregroundText(line)
        setStatus(EngineStateName.SCANNING, line)
    }

    private fun etaText(run: ScanRunEntity): String {
        if (cellDurations.size < 2) return ""
        val avg = cellDurations.average()
        val size = File(run.modelPath).length()
        val mid = ScanPlanner.estimateMinutes(size, host.ramBytes(), run.includeLossy, run.includeSustained, run.sustainedMinutes)
        val expected = 14 + 4 + (if (run.includeSustained) 3 else 0) + (if (run.includeLossy) 6 else 0)
        val left = (expected - cellDurations.size).coerceAtLeast(1)
        val ms = (avg * left).toLong().coerceAtLeast(0)
        return if (mid.last > 0) mmss(ms) else ""
    }

    private suspend fun finish(run: ScanRunEntity, status: String, verdict: String) {
        scan.run(run.id)?.let {
            scan.updateRun(it.copy(status = status, finishedAt = clock(), detail = "", verdict = verdict))
        }
        setStatus(EngineStateName.IDLE, "")
        scan.run(run.id)?.let { host.scanFinished(it) }
    }

    private suspend fun conclude(run: ScanRunEntity, o: ScanOutcome) {
        scan.run(run.id)?.let {
            scan.updateRun(
                it.copy(
                    status = if (o.failed) ScanRunStatus.FAILED else ScanRunStatus.DONE, finishedAt = clock(), detail = "",
                    recommendedJson = o.recommended?.let(SettingsJson::toJson) ?: "", recommendedLabel = o.recommendedLabel,
                    verdict = o.verdict, confirmed = o.confirmed,
                ),
            )
        }
        setStatus(EngineStateName.IDLE, "")
        scan.run(run.id)?.let { host.scanFinished(it) }
    }

    private fun toPlanner(c: ScanCellEntity) = PCell(
        stage = c.stage, kind = c.kind, label = c.label, key = c.argvSig, status = c.status, attempt = c.attempt,
        decodeMedianTokS = c.decodeMedianTokS, coolMedianTokS = c.coolMedianTokS, sustainedTokS = c.sustainedTokS,
        throttledFrac = c.throttledFrac, peakAnonMb = c.peakAnonMb, cacheResidentMib = c.cacheResidentMib,
        cpusAllowed = c.cpusAllowed, nExpertUsed = c.nExpertUsed, gateGaveUp = c.gateGaveUp,
    )

    private fun mmss(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
