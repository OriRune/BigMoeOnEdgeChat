package io.bigmoeonedge.example.chat

import io.bigmoeonedge.example.AppSettings
import io.bigmoeonedge.example.chat.ChatSettings
import io.bigmoeonedge.example.chat.engine.EngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineConfigTest {
    private val gb = 1L shl 30
    private val chat = ChatSettings()

    @Test fun aBigModelGetsTheSmallFootprint() {
        val s = EngineConfig.resolve(AppSettings(), chat, 20 * gb)
        assertEquals(500, s.cacheMb)
        assertEquals(2048, s.sessionCtx)
        val argv = EngineConfig.argv(s, chat, "/cli", "/m.gguf")
        assertTrue(argv.containsAll(listOf("--cache-mb", "500", "--force-cache")))
    }

    @Test fun foregroundModeKeepsTheBigModelsFullFootprint() {
        val s = EngineConfig.resolve(AppSettings(), chat, 20 * gb, foreground = true)
        assertEquals(2000, s.cacheMb)
        assertEquals(4096, s.sessionCtx)
        assertFalse(EngineConfig.argv(s, chat, "/cli", "/m.gguf").contains("--force-cache"))
    }

    @Test fun foregroundModeIsADifferentSessionFromTheBackgroundOne() {
        val bg = EngineConfig.resolve(AppSettings(), chat, 20 * gb)
        val fg = EngineConfig.resolve(AppSettings(), chat, 20 * gb, foreground = true)
        assertNotEquals(EngineConfig.signature(bg, chat, "/m.gguf"), EngineConfig.signature(fg, chat, "/m.gguf"))
    }

    @Test fun aSmallModelKeepsTheUsersEngineSettings() {
        val s = EngineConfig.resolve(AppSettings(), chat, 4 * gb)
        assertEquals(2000, s.cacheMb)
        assertEquals(4096, s.sessionCtx)
    }

    @Test fun anExplicitContextWinsEvenOnABigModel() {
        val s = EngineConfig.resolve(AppSettings(), chat.copy(sessionCtx = 8192), 20 * gb)
        assertEquals(8192, s.sessionCtx)
    }

    @Test fun aSmallerCacheTheUserChoseIsNotRaised() {
        val s = EngineConfig.resolve(AppSettings(cacheMb = 0), chat, 20 * gb)
        assertEquals(0, s.cacheMb)
    }

    @Test fun mmapModeIsLeftAlone() {
        val s = EngineConfig.resolve(AppSettings(mmap = true), chat, 20 * gb)
        assertEquals(4096, s.sessionCtx)
    }

    @Test fun aProfileReplacesTheGlobalEngineSettings() {
        val profile = AppSettings(cacheMb = 3000, threads = 6, dropColdPct = 0)
        val s = EngineConfig.resolve(AppSettings(), chat, 20 * gb, override = profile)
        assertEquals(3000, s.cacheMb)
        assertEquals(6, s.threads)
        assertEquals(chat.nPredict, s.nPredict)
    }

    @Test fun samplingFlagsAreAbsentForGreedy() {
        assertTrue(EngineConfig.samplingArgs(chat.copy(temperature = 0f)).isEmpty())
        assertEquals(
            listOf("--temp", "0.7", "--top-p", "0.9", "--top-k", "40"),
            EngineConfig.samplingArgs(chat),
        )
    }

    @Test fun theSignatureTracksSamplingAndEngineSettingsButNotTheCliPath() {
        val s = AppSettings()
        val base = EngineConfig.signature(s, chat, "/m.gguf")
        assertEquals(base, EngineConfig.signature(s, chat, "/m.gguf"))
        assertNotEquals(base, EngineConfig.signature(s, chat.copy(temperature = 0.2f), "/m.gguf"))
        assertNotEquals(base, EngineConfig.signature(s.copy(threads = 6), chat, "/m.gguf"))
        assertNotEquals(base, EngineConfig.signature(s, chat, "/other.gguf"))
        assertFalse(base.contains("/cli"))
    }
}
