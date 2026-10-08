package io.bigmoeonedge.example.chat

import io.bigmoeonedge.example.chat.data.ConversationEntity
import io.bigmoeonedge.example.chat.data.MessageEntity
import io.bigmoeonedge.example.chat.data.MessageStatus
import io.bigmoeonedge.example.chat.data.Role
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A conversation as a Markdown document, for the share sheet. Pure, so it is tested on the JVM. */
object MarkdownExport {
    fun render(conv: ConversationEntity, messages: List<MessageEntity>, zone: ZoneId = ZoneId.systemDefault()): String =
        buildString {
            append("# ").append(conv.title.replace('\n', ' ')).append("\n\n")
            append("_Model: ").append(ChatFormat.modelShortName(conv.modelPath)).append(" · started ")
            append(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(Instant.ofEpochMilli(conv.createdAt)))
            append("_\n\n")
            if (conv.systemPrompt.isNotBlank()) append("> **System:** ").append(conv.systemPrompt.trim().replace("\n", "\n> ")).append("\n\n")
            for (m in messages) {
                // A reply that never completed, or is still waiting, is not part of the conversation.
                if (m.role == Role.ASSISTANT && m.status != MessageStatus.DONE) continue
                append(if (m.role == Role.USER) "**You:**" else "**Model:**").append("\n\n")
                if (m.reasoning.isNotBlank()) {
                    append("<details><summary>Thinking</summary>\n\n").append(m.reasoning.trim()).append("\n\n</details>\n\n")
                }
                append(m.text.trim()).append("\n\n")
            }
        }.trimEnd() + "\n"

    /** A file name from a title: letters, digits, dashes. */
    fun fileName(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "chat" } + ".md"
}
