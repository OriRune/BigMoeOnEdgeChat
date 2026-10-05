package io.bigmoeonedge.example.scan

import io.bigmoeonedge.example.chat.data.ScanCellEntity
import io.bigmoeonedge.example.chat.data.ScanRunEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanCsvTest {
    private val run = ScanRunEntity(id = 3, modelPath = "/m.gguf", startedAt = 0)

    @Test fun quotesLabelsWithCommasAndLeavesUnknownEmpty() {
        val cell = ScanCellEntity(
            id = 9, runId = 3, stage = "B", kind = "BURST", label = "cache 3000, \"big\"", settingsJson = "{}", argvSig = "k",
            decodeMedianTokS = 3.14159,
        )
        val csv = ScanCsv.cells(run, listOf(cell)).lines()
        assertEquals(ScanCsv.CELL_HEADER.split(',').size, csv[0].split(',').size)
        assertTrue(csv[1].contains("\"cache 3000, \"\"big\"\"\""))
        assertTrue(csv[1].contains(",3.142,"))
        // cool median -1 => empty field
        assertTrue(csv[1].contains(",3.142,,"))
    }

    @Test fun samplesAreOneRowEach() {
        val s = Sample(1000, 1, 0.8, 2_000_000, 2_400_000, 500_000, 31.5, 1900, charging = false, screenOn = false)
        val cell = ScanCellEntity(id = 4, runId = 3, stage = "A", kind = "BURST", label = "x", settingsJson = "{}", argvSig = "k",
            samplesJson = Sample.listToJson(listOf(s, s.copy(tMs = 6000))))
        val lines = ScanCsv.samples(listOf(cell)).trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("4,1000,1,0.800,"))
    }
}
