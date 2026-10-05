package io.bigmoeonedge.example.chat.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object ScanRunStatus {
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
    const val STOPPED = "STOPPED"
    const val FAILED = "FAILED"
}

object CellStatus {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
    const val THERMAL_ABORT = "THERMAL_ABORT"
}

object CellKind {
    const val BURST = "BURST"
    const val SUSTAINED = "SUSTAINED"
    const val CONFIRM = "CONFIRM"
}

/** One scan of one model. A stopped run keeps its finished cells and can be resumed. */
@Entity(tableName = "scan_runs")
data class ScanRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelPath: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: String = ScanRunStatus.RUNNING,
    val includeLossy: Boolean = false,
    val includeSustained: Boolean = true,
    val sustainedMinutes: Int = 12,
    // The cool state every cell is gated back to, measured once before the first cell. -1 = unknown
    // (SQLite would store NaN as NULL).
    val referenceHeadroom: Double = -1.0,
    val referenceThermal: Int = 0,
    val referenceBatteryC: Double = -1.0,
    val referenceMemAvailMb: Int = 0,
    val startedWarm: Boolean = false,
    // Where the scan is, for the screen and the notification ("stage C 3/4").
    val stage: String = "",
    val detail: String = "",
    val note: String = "",
    // Filled when the run finishes: the recommendation, as AppSettings JSON, and a one-line verdict.
    val recommendedJson: String = "",
    val recommendedLabel: String = "",
    val verdict: String = "",
    val confirmed: Boolean = false,
)

@Entity(
    tableName = "scan_cells",
    foreignKeys = [
        ForeignKey(
            entity = ScanRunEntity::class, parentColumns = ["id"], childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("runId")],
)
data class ScanCellEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val stage: String,
    val kind: String,
    val label: String,
    val settingsJson: String,
    // Identity of the configuration (the session signature), to match a plan step to its result.
    val argvSig: String,
    val status: String = CellStatus.PENDING,
    val attempt: Int = 0,
    val lossy: Boolean = false,
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
    val error: String? = null,
    // From BMOE_READY / BMOE_DONE.
    val nExpertUsed: Int = 0,
    val loadS: Double = -1.0,
    val prefillS: Double = -1.0,
    val prefillTps: Double = -1.0,
    val nPrompt: Int = -1,
    val tokens: Int = 0,
    val tokS: Double = -1.0,
    val effTokS: Double = -1.0,
    val cacheHitPct: Double = -1.0,
    val readMib: Double = -1.0,
    val majfltPerTok: Double = -1.0,
    val cpuSPerTok: Double = -1.0,
    val cacheResidentMib: Double = -1.0,
    // Derived from the per-token times (CellStats).
    val decodeMedianTokS: Double = -1.0,
    val coolMedianTokS: Double = -1.0,
    val sustainedTokS: Double = -1.0,
    val firstMinuteTokS: Double = -1.0,
    val timeToThrottleS: Double = -1.0,
    val throttledFrac: Double = 0.0,
    val maxThermalStatus: Int = 0,
    val headroomStart: Double = -1.0,
    val headroomEnd: Double = -1.0,
    val minCpu6MaxKHz: Int = -1,
    // Child process: peak anonymous memory (RssAnon + VmSwap), its cpuset and core count.
    val peakAnonMb: Int = -1,
    val cpuset: String = "",
    val cpusAllowed: Int = 0,
    val ioMode: String = "",
    val charging: Boolean = false,
    val screenOn: Boolean = false,
    val gateWaitS: Double = 0.0,
    val gateGaveUp: Boolean = false,
    val gateNote: String = "",
    val outputText: String = "",
    // JSON arrays: per-token wall_ms and arrival time (ms since the generation started), and the samples.
    val tokenMsJson: String = "[]",
    val tokenAtJson: String = "[]",
    val samplesJson: String = "[]",
)

/** The settings chats use for one model: saved from a scan, or cleared to fall back to the global ones. */
@Entity(tableName = "model_profiles")
data class ModelProfileEntity(
    @PrimaryKey val modelPath: String,
    val settingsJson: String,
    val source: String,
    val runId: Long? = null,
    val createdAt: Long = 0,
)
