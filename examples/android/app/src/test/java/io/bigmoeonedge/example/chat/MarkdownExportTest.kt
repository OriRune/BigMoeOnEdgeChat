package io.bigmoeonedge.example.chat

import io.bigmoeonedge.example.chat.data.ConversationEntity
import io.bigmoeonedge.example.chat.data.MessageEntity
import io.bigmoeonedge.example.chat.data.MessageStatus
import io.bigmoeonedge.example.chat.data.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class MarkdownExportTest {
    private val conv = ConversationEntity(
        id = 1, title = "Sea story", modelPath = "/x/Ling-Q4.gguf", systemPrompt = "Be brief.\nPlease.",
        createdAt = 0, updatedAt = 0,
    )

    private fun m(role: String, text: String, status: String = MessageStatus.DONE, reasoning: String = "") =
        MessageEntity(conversationId = 1, role = role, text = text, status = status, reasoning = reasoning, createdAt = 0)

    @Test fun rendersTitleModelSystemAndTurns() {
        val md = MarkdownExport.render(
            conv, listOf(m(Role.USER, "hi"), m(Role.ASSISTANT, "hello", reasoning = "thinking…")), ZoneId.of("UTC"),
        )
        assertTrue(md.startsWith("# Sea story\n\n_Model: Ling-Q4 · started 1970-01-01 00:00_"))
        assertTrue(md.contains("> **System:** Be brief.\n> Please."))
        assertTrue(md.contains("**You:**\n\nhi"))
        assertTrue(md.contains("<details><summary>Thinking</summary>\n\nthinking…\n\n</details>"))
        assertTrue(md.trimEnd().endsWith("hello"))
    }

    @Test fun unfinishedRepliesAreLeftOut() {
        val md = MarkdownExport.render(
            conv, listOf(m(Role.USER, "q"), m(Role.ASSISTANT, "", MessageStatus.QUEUED), m(Role.ASSISTANT, "half", MessageStatus.FAILED)),
        )
        assertFalse(md.contains("**Model:**"))
    }

    @Test fun fileNames() {
        assertEquals("sea-story.md", MarkdownExport.fileName("Sea story!"))
        assertEquals("chat.md", MarkdownExport.fileName("???"))
        assertEquals(40 + 3, MarkdownExport.fileName("x".repeat(100)).length)
    }
}
