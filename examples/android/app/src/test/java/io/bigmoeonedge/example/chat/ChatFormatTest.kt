package io.bigmoeonedge.example.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class ChatFormatTest {
    private val zone = ZoneId.of("UTC")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun rel(now: Long, then: Long) = ChatFormat.relativeTime(now, then, zone, Locale.US)

    @Test fun recentTimesAreRelative() {
        val now = at(2026, 10, 4, 12, 0)
        assertEquals("now", rel(now, now - 30_000))
        assertEquals("5 min", rel(now, now - 5 * 60_000))
        assertEquals("3 h", rel(now, now - 3 * 3_600_000))
    }

    @Test fun olderTimesAreCalendarWords() {
        val now = at(2026, 10, 4, 12, 0)
        assertEquals("Yesterday", rel(now, at(2026, 10, 3, 23, 0)))
        assertEquals("Wed", rel(now, at(2026, 9, 30, 9, 0)))
        assertEquals("Sep 1", rel(now, at(2026, 9, 1, 9, 0)))
    }

    @Test fun anEarlyMorningMessageFromLastNightIsYesterdayNotHoursAgo() {
        // 00:30 now, 23:30 before: an hour and a bit, but the day changed.
        assertEquals("Yesterday", rel(at(2026, 10, 4, 0, 30), at(2026, 10, 3, 22, 0)))
    }

    @Test fun modelNames() {
        assertEquals("Ling-3.0-tiny-Q4_0", ChatFormat.modelShortName("/sdcard/Download/Ling-3.0-tiny-Q4_0.gguf"))
        assertEquals("Big-Q4", ChatFormat.modelShortName("/x/Big-Q4-00001-of-00003.gguf"))
    }

    @Test fun previewIsOneLineAndCut() {
        assertEquals("a b", ChatFormat.preview(" a\n\n b "))
        assertEquals(91, ChatFormat.preview("x".repeat(200)).length)
    }

    @Test fun queueLabels() {
        assertEquals("Queued · next", ChatFormat.queueLabel(0))
        assertEquals("Queued · 3 ahead", ChatFormat.queueLabel(3))
    }

    @Test fun attachmentIsFenced() {
        val a = ChatFormat.attachment("notes.txt", "hello\n", false)
        assertEquals("<attached file: notes.txt>\nhello\n</attached file>\n", a)
        assertTrue(ChatFormat.attachment("n", "x", true).contains("cut at 64 KB"))
    }
}
