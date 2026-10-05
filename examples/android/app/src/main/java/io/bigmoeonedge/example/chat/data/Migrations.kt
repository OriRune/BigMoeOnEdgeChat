package io.bigmoeonedge.example.chat.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Version 2 adds the scan: runs, cells and the per-model profile. Nothing existing changes. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `scan_runs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `modelPath` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `status` TEXT NOT NULL, `includeLossy` INTEGER NOT NULL, `includeSustained` INTEGER NOT NULL, `sustainedMinutes` INTEGER NOT NULL, `referenceHeadroom` REAL NOT NULL, `referenceThermal` INTEGER NOT NULL, `referenceBatteryC` REAL NOT NULL, `referenceMemAvailMb` INTEGER NOT NULL, `startedWarm` INTEGER NOT NULL, `stage` TEXT NOT NULL, `detail` TEXT NOT NULL, `note` TEXT NOT NULL, `recommendedJson` TEXT NOT NULL, `recommendedLabel` TEXT NOT NULL, `verdict` TEXT NOT NULL, `confirmed` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `scan_cells` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` INTEGER NOT NULL, `stage` TEXT NOT NULL, `kind` TEXT NOT NULL, `label` TEXT NOT NULL, `settingsJson` TEXT NOT NULL, `argvSig` TEXT NOT NULL, `status` TEXT NOT NULL, `attempt` INTEGER NOT NULL, `lossy` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `error` TEXT, `nExpertUsed` INTEGER NOT NULL, `loadS` REAL NOT NULL, `prefillS` REAL NOT NULL, `prefillTps` REAL NOT NULL, `nPrompt` INTEGER NOT NULL, `tokens` INTEGER NOT NULL, `tokS` REAL NOT NULL, `effTokS` REAL NOT NULL, `cacheHitPct` REAL NOT NULL, `readMib` REAL NOT NULL, `majfltPerTok` REAL NOT NULL, `cpuSPerTok` REAL NOT NULL, `cacheResidentMib` REAL NOT NULL, `decodeMedianTokS` REAL NOT NULL, `coolMedianTokS` REAL NOT NULL, `sustainedTokS` REAL NOT NULL, `firstMinuteTokS` REAL NOT NULL, `timeToThrottleS` REAL NOT NULL, `throttledFrac` REAL NOT NULL, `maxThermalStatus` INTEGER NOT NULL, `headroomStart` REAL NOT NULL, `headroomEnd` REAL NOT NULL, `minCpu6MaxKHz` INTEGER NOT NULL, `peakAnonMb` INTEGER NOT NULL, `cpuset` TEXT NOT NULL, `cpusAllowed` INTEGER NOT NULL, `ioMode` TEXT NOT NULL, `charging` INTEGER NOT NULL, `screenOn` INTEGER NOT NULL, `gateWaitS` REAL NOT NULL, `gateGaveUp` INTEGER NOT NULL, `gateNote` TEXT NOT NULL, `outputText` TEXT NOT NULL, `tokenMsJson` TEXT NOT NULL, `tokenAtJson` TEXT NOT NULL, `samplesJson` TEXT NOT NULL, FOREIGN KEY(`runId`) REFERENCES `scan_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_scan_cells_runId` ON `scan_cells` (`runId`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `model_profiles` (`modelPath` TEXT NOT NULL, `settingsJson` TEXT NOT NULL, `source` TEXT NOT NULL, `runId` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`modelPath`))",
        )
    }
}
