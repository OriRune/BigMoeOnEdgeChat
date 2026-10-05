package io.bigmoeonedge.example.scan

import io.bigmoeonedge.example.chat.data.ChatDb
import io.bigmoeonedge.example.chat.data.ModelProfileEntity
import io.bigmoeonedge.example.chat.data.ScanCellEntity
import io.bigmoeonedge.example.chat.data.ScanRunEntity
import io.bigmoeonedge.example.chat.data.ScanRunStatus
import kotlinx.coroutines.flow.Flow

/** Writes the scan screens make. The engine process does the work; these only change rows and kick it. */
class ScanRepository(private val db: ChatDb, private val kick: () -> Unit, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao get() = db.scan()

    fun observeRuns(): Flow<List<ScanRunEntity>> = dao.observeRuns()
    fun observeRun(id: Long): Flow<ScanRunEntity?> = dao.observeRun(id)
    fun observeCells(id: Long): Flow<List<ScanCellEntity>> = dao.observeCells(id)
    fun observeProfile(path: String): Flow<ModelProfileEntity?> = dao.observeProfile(path)

    /** One run per model; they run back to back, oldest first. */
    suspend fun start(models: List<String>, sustained: Boolean, lossy: Boolean, sustainedMinutes: Int): List<Long> {
        val now = clock()
        val ids = models.mapIndexed { i, m ->
            dao.insertRun(
                ScanRunEntity(
                    modelPath = m, startedAt = now + i, includeSustained = sustained, includeLossy = lossy,
                    sustainedMinutes = sustainedMinutes,
                ),
            )
        }
        kick()
        return ids
    }

    /** Stops every running or queued scan. Finished cells are kept. */
    suspend fun stopAll() {
        val running = mutableListOf<ScanRunEntity>()
        dao.runningRun()?.let { first ->
            // runningRun returns the oldest; the rest are queued behind it.
            running += first
        }
        // Mark all RUNNING rows STOPPED so none starts next; the executor ends the current one itself.
        var r = dao.runningRun()
        while (r != null) {
            dao.updateRun(r.copy(status = ScanRunStatus.STOPPED, detail = "", finishedAt = clock()))
            r = dao.runningRun()
        }
    }

    suspend fun resume(id: Long) {
        val r = dao.run(id) ?: return
        if (r.status == ScanRunStatus.STOPPED || r.status == ScanRunStatus.FAILED) {
            dao.updateRun(r.copy(status = ScanRunStatus.RUNNING, finishedAt = null, verdict = ""))
            kick()
        }
    }

    suspend fun delete(id: Long) = dao.deleteRun(id)

    /** Chats with this model use the scan's recommendation from now on. */
    suspend fun useRecommended(runId: Long) {
        val r = dao.run(runId) ?: return
        if (r.recommendedJson.isEmpty()) return
        dao.putProfile(ModelProfileEntity(r.modelPath, r.recommendedJson, "SCAN", runId, clock()))
    }

    /** Use the settings of one cell (a lossy candidate the user picked knowingly). */
    suspend fun useCell(runId: Long, cell: ScanCellEntity) {
        val r = dao.run(runId) ?: return
        dao.putProfile(ModelProfileEntity(r.modelPath, cell.settingsJson, "SCAN_CELL", runId, clock()))
    }

    suspend fun resetProfile(modelPath: String) = dao.clearProfile(modelPath)
}
