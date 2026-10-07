package io.bigmoeonedge.example.scan

import androidx.test.platform.app.InstrumentationRegistry
import io.bigmoeonedge.example.chat.data.CellStatus
import io.bigmoeonedge.example.chat.data.ChatDb
import io.bigmoeonedge.example.chat.data.ConversationEntity
import io.bigmoeonedge.example.chat.data.ScanPhase
import io.bigmoeonedge.example.chat.data.ScanRunEntity
import io.bigmoeonedge.example.chat.data.ScanRunStatus
import io.bigmoeonedge.example.chat.engine.EngineBackend
import io.bigmoeonedge.example.chat.engine.EngineHost
import io.bigmoeonedge.example.chat.engine.FakeEngineBackend
import io.bigmoeonedge.example.chat.engine.JobConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A whole scan against the fake engine and the fake phone, in seconds. */
class ScanExecutorTest {
    private lateinit var db: ChatDb
    private val finished = mutableListOf<ScanRunEntity>()

    // What "your current settings" are today; a test changes it between a stop and a resume.
    private var currentThreads = 4

    // A backend that loads and then says nothing: the first token is half an hour away.
    @Volatile private var hang = false

    private val host = object : EngineHost {
        override suspend fun scanCurrentSettings(modelPath: String) = io.bigmoeonedge.example.AppSettings(threads = currentThreads)
        override suspend fun jobConfig(conv: ConversationEntity) = error("not used")
        override fun createBackend(cfg: JobConfig): EngineBackend =
            if (hang) FakeEngineBackend(tokPerSec = { 0.001 }, loadMs = 50) else FakeEngineBackend(loadMs = 50)
        override suspend fun clearOtherEngines(selfPid: Int) {}
        override fun foregroundText(text: String) {}
        override fun holdWake(on: Boolean) {}
        override fun pauseReason(): String? = null
        override fun thermalStatus() = 0
        override fun keepLoadedMinutes() = -1
        override suspend fun replyFinished(messageId: Long, conversationId: Long, status: String) {}
        override fun idle(modelLoaded: Boolean) {}
        override fun scanFinished(run: ScanRunEntity) {
            finished += run
        }
    }

    private val timing = ScanTiming(
        refIdleMs = 300, refExtendMs = 600, sampleMs = 50, gatePollMs = 50, gateMaxMs = 8_000, nPredict = 256, sustainedMs = 1_500,
    )

    @Before fun open() {
        db = ChatDb.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        FakeThermal.reset()
        FakeThermal.timeScale = 150.0
        FakeThermal.speedScale = 300.0
    }

    @After fun close() {
        FakeThermal.timeScale = 1.0
        FakeThermal.speedScale = 1.0
        FakeThermal.reset()
        db.close()
    }

    private suspend fun newRun(sustained: Boolean = true, lossy: Boolean = false): ScanRunEntity {
        val r = ScanRunEntity(modelPath = "/data/local/tmp/fake.gguf", startedAt = 1, includeLossy = lossy, includeSustained = sustained)
        return r.copy(id = db.scan().insertRun(r))
    }

    @Test fun aFullScanFinishesWithARecommendationAndEveryStage() = runBlocking {
        val run = newRun(lossy = true)
        withTimeout(240_000) { ScanExecutor(db, host, timing).run(run) }
        val r = db.scan().run(run.id)!!
        assertEquals(ScanRunStatus.DONE, r.status)
        assertTrue(r.recommendedJson.isNotEmpty())
        assertTrue(r.verdict.isNotEmpty())
        assertEquals(1, finished.size)
        val cells = db.scan().cells(run.id)
        val stages = cells.map { it.stage }.distinct()
        // B, D, E and F only run while streaming is the incumbent, which the fake's noise can flip.
        for (st in listOf("BASE", "A", "C", "S", "H")) assertTrue("missing stage $st in $stages", st in stages)
        assertTrue(cells.none { it.status == CellStatus.PENDING || it.status == CellStatus.RUNNING })
        val burst = cells.first { it.stage == "BASE" }
        assertEquals(CellStatus.DONE, burst.status)
        assertTrue(burst.decodeMedianTokS > 0 && burst.tokens > 0)
        assertTrue(burst.samplesJson.length > 2)
        assertTrue(cells.first { it.stage == "S" }.sustainedTokS > 0)
        // The fake phone heats while generating, so burst cells are contaminated; they are re-run once, but
        // only when the gate had not given up (a re-run from the same warm state would measure the same).
        assertTrue(cells.none { it.attempt > 1 })
        for (rerun in cells.filter { it.attempt == 1 }) {
            val first = cells.first { it.argvSig == rerun.argvSig && it.attempt == 0 }
            assertTrue("re-ran ${rerun.label} after the gate gave up", !first.gateGaveUp)
        }
        // The reference state was taken before the first cell.
        assertTrue(r.referenceMemAvailMb > 0)
    }

    @Test fun aKilledScanResumesWithoutRepeatingFinishedCells() = runBlocking {
        val run = newRun(sustained = false)
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch { ScanExecutor(db, host, timing).run(run) }
        // Let a few cells finish, then kill it mid-cell, as a dying engine process would.
        withTimeout(120_000) { while (db.scan().cells(run.id).count { it.status == CellStatus.DONE } < 3) delay(50) }
        job.cancel()
        job.join()
        // A dead process also leaves the interrupted cell RUNNING in the database.
        val running = db.scan().cells(run.id).filter { it.status == CellStatus.RUNNING }
        val doneBefore = db.scan().cells(run.id).filter { it.status == CellStatus.DONE }.map { it.id }

        withTimeout(240_000) { ScanExecutor(db, host, timing).run(db.scan().run(run.id)!!) }
        val cells = db.scan().cells(run.id)
        assertEquals(ScanRunStatus.DONE, db.scan().run(run.id)!!.status)
        // Finished cells were not re-run, and the interrupted one finished.
        assertTrue(doneBefore.all { id -> cells.first { it.id == id }.status == CellStatus.DONE })
        running.forEach { r -> assertEquals(CellStatus.DONE, cells.first { it.id == r.id }.status) }
        val doneIdentities = cells.filter { it.status == CellStatus.DONE }.map { Triple(it.stage, it.label, it.attempt) }
        assertEquals(doneIdentities.size, doneIdentities.toSet().size)
    }

    @Test fun aResumedScanKeepsItsBaselineWhenTheGlobalSettingsChange() = runBlocking {
        val run = newRun(sustained = false)
        val exec = ScanExecutor(db, host, timing)
        val job = CoroutineScope(Dispatchers.Default).launch { exec.run(run) }
        withTimeout(120_000) { while (db.scan().cells(run.id).count { it.status == CellStatus.DONE } < 2) delay(50) }
        exec.stop()
        job.join()
        val before = db.scan().cells(run.id).filter { it.status == CellStatus.DONE }.map { it.id }
        assertTrue(before.isNotEmpty())

        // The user edits their global settings while the scan is paused.
        currentThreads = 2
        db.scan().updateRun(db.scan().run(run.id)!!.copy(status = ScanRunStatus.RUNNING))
        withTimeout(240_000) { ScanExecutor(db, host, timing).run(db.scan().run(run.id)!!) }

        // Every cell recorded before the pause still counts, and nothing was planned from the new baseline.
        val cells = db.scan().cells(run.id)
        assertTrue(before.all { id -> cells.first { it.id == id }.status == CellStatus.DONE })
        assertTrue("a cell was re-planned from the changed settings", cells.none { it.label == "Your current settings" && it.attempt == 0 && it.id !in before && it.stage == "CUR" })
        val bases = cells.filter { it.stage == "BASE" }.map { it.argvSig }.toSet()
        assertEquals(1, bases.size)
    }

    @Test fun stoppingKeepsTheFinishedCellsAndResumeCompletes() = runBlocking {
        val run = newRun(sustained = false)
        val exec = ScanExecutor(db, host, timing)
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch { exec.run(run) }
        withTimeout(120_000) { while (db.scan().cells(run.id).count { it.status == CellStatus.DONE } < 2) delay(50) }
        exec.stop()
        job.join()
        assertEquals(ScanRunStatus.STOPPED, db.scan().run(run.id)!!.status)
        val kept = db.scan().cells(run.id).count { it.status == CellStatus.DONE }
        assertTrue(kept >= 2)
        assertTrue(db.scan().cells(run.id).none { it.status == CellStatus.RUNNING })

        // Resume: the user starts it again.
        db.scan().updateRun(db.scan().run(run.id)!!.copy(status = ScanRunStatus.RUNNING))
        withTimeout(240_000) { ScanExecutor(db, host, timing).run(db.scan().run(run.id)!!) }
        assertEquals(ScanRunStatus.DONE, db.scan().run(run.id)!!.status)
        assertTrue(db.scan().cells(run.id).count { it.status == CellStatus.DONE } > kept)
    }

    @Test fun aHotPhoneIsCooledBeforeTheNextCell() = runBlocking {
        val run = newRun(sustained = false)
        withTimeout(240_000) { ScanExecutor(db, host, timing).run(run) }
        val cells = db.scan().cells(run.id)
        // Some cell had to wait at the gate (the fake phone heats while generating).
        assertTrue(cells.any { it.gateWaitS > 0.1 })
        assertFalse(cells.any { it.gateGaveUp })
    }

    @Test fun aRunningScanWritesAHeartbeatAndItsPhases() = runBlocking {
        val run = newRun(sustained = false)
        val job = CoroutineScope(Dispatchers.Default).launch { ScanExecutor(db, host, timing).run(run) }
        val phases = linkedSetOf<String>()
        var maxTokens = 0
        var lastBeat = 0L
        var beats = 0
        withTimeout(120_000) {
            while (job.isActive) {
                val r = db.scan().run(run.id)!!
                if (r.phase.isNotEmpty()) phases += r.phase
                maxTokens = maxOf(maxTokens, r.liveTokens)
                if (r.heartbeatAt != lastBeat) {
                    beats++
                    lastBeat = r.heartbeatAt
                }
                if (r.phase == ScanPhase.GENERATING) {
                    assertTrue(r.phaseSince > 0 && r.liveTarget == timing.nPredict)
                }
                delay(15)
            }
        }
        for (p in listOf(ScanPhase.REFERENCE, ScanPhase.LOADING, ScanPhase.GENERATING)) assertTrue("never saw $p in $phases", p in phases)
        assertTrue("tokens were never reported", maxTokens > 0)
        assertTrue("too few heartbeats: $beats", beats > 10)
        val end = db.scan().run(run.id)!!
        assertEquals(ScanRunStatus.DONE, end.status)
        assertEquals("", end.phase)
        // The progress screen's plan agrees with the executor: nothing is left.
        val o = ScanProgress.outline(end, db.scan().cellsLite(run.id), 12L shl 30, System.currentTimeMillis())
        assertTrue(o.finished)
    }

    @Test fun anEngineThatSaysNothingIsFailedInsteadOfWaitedOnForever() = runBlocking {
        hang = true
        val run = newRun(sustained = false)
        withTimeout(120_000) { ScanExecutor(db, host, timing.copy(stallMs = 600)).run(run) }
        val cells = db.scan().cells(run.id)
        val base = cells.first { it.stage == "BASE" }
        assertEquals(CellStatus.FAILED, base.status)
        assertTrue(base.error, base.error!!.contains("produced nothing"))
        // With no baseline there is nothing to compare against: the scan ends, failed, and says why.
        val r = db.scan().run(run.id)!!
        assertEquals(ScanRunStatus.FAILED, r.status)
        assertTrue(r.verdict.contains("baseline"))
    }
}
