package io.bigmoeonedge.example.scan

import io.bigmoeonedge.example.AppSettings
import io.bigmoeonedge.example.DenseWeights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonTest {
    @Test fun everyFieldSurvivesARoundTrip() {
        val s = AppSettings(
            mmap = true, cacheMb = 1500, cacheCeilMb = 4000, ioThreads = 6, threads = 6, nExpertUsed = 4,
            nPredict = 256, sessionCtx = 2048, oDirect = false, overlap = false, denseWeights = DenseWeights.AHWB,
            prefetchLayers = 2, predictPrefetch = true, predictSpecMax = 2, routeAhead = 1, dropColdPct = 50,
            rowStream = true, releaseMmap = true, npuPrefill = true, npuLoaders = 12, substitutePct = 15,
            spec = AppSettings.SPEC_NGRAM, mtpDraft = 5, mtpPMinPct = 60, thinking = true, metricsCsv = false,
        )
        assertEquals(s, SettingsJson.fromJson(SettingsJson.toJson(s)))
    }

    @Test fun aMissingOrUnknownFieldReadsAsItsDefault() {
        val s = SettingsJson.fromJson("""{"threads":6,"denseWeights":"NOPE","spec":"weird"}""")
        assertEquals(6, s.threads)
        assertEquals(AppSettings().denseWeights, s.denseWeights)
        assertEquals(AppSettings.SPEC_OFF, s.spec)
        assertEquals(AppSettings().cacheMb, s.cacheMb)
    }

    @Test fun losslessZeroesOnlyTheLossyKnobs() {
        val s = AppSettings(dropColdPct = 75, substitutePct = 15, nExpertUsed = 4, routeAhead = 2, threads = 6)
        val l = SettingsJson.lossless(s)
        assertFalse(SettingsJson.isLossy(l))
        assertEquals(6, l.threads)
        assertTrue(SettingsJson.isLossy(s))
    }
}
