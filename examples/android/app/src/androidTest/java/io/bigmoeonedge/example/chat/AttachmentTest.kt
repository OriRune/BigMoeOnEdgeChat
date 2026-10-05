package io.bigmoeonedge.example.chat

import android.app.Application
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.bigmoeonedge.example.chat.ui.ThreadViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AttachmentTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private fun attach(body: String): String {
        val f = File(app.cacheDir, "attach-test.txt").apply { writeText(body) }
        return runBlocking { ThreadViewModel(app, 0).readAttachment(Uri.fromFile(f)) }!!
    }

    @Test fun aSmallFileIsFencedWholeAndKeepsUnicode() {
        val out = attach("héllo 日本 😀\nline two\n")
        assertTrue(out.startsWith("<attached file: "))
        assertTrue(out.contains("héllo 日本 😀\nline two"))
        assertTrue(out.trimEnd().endsWith("</attached file>"))
    }

    @Test fun aBigFileIsCutAt64KB() {
        val out = attach("a".repeat(200_000))
        assertTrue(out.contains("cut at 64 KB"))
        assertEquals(64 * 1024, out.lines()[1].length)
    }
}
