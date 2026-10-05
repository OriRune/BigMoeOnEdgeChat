package io.bigmoeonedge.example.chat

import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Small text rules of the chat screens, free of Android so they are tested on the JVM. */
object ChatFormat {
    /** "now", "5 min", "3 h", "Yesterday", "Mon", "Oct 3" — as a messaging app shows it. */
    fun relativeTime(now: Long, then: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val ms = now - then
        if (ms < 60_000) return "now"
        if (ms < 3_600_000) return "${ms / 60_000} min"
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = Instant.ofEpochMilli(then).atZone(zone).toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(day, today)
        return when {
            days <= 0 -> "${ms / 3_600_000} h"
            days == 1L -> "Yesterday"
            days < 7 -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            else -> day.month.getDisplayName(TextStyle.SHORT, locale) + " " + day.dayOfMonth
        }
    }

    /** "Ling-3.0-tiny-Q4_0" from ".../Ling-3.0-tiny-Q4_0.gguf" (a split model's shard suffix too). */
    fun modelShortName(path: String): String =
        path.substringAfterLast('/')
            .removeSuffix(".gguf")
            .replace(Regex("-0*1-of-\\d+$"), "")

    /** One line of a message for a list row. */
    fun preview(text: String, max: Int = 90): String {
        val one = text.trim().replace(Regex("\\s+"), " ")
        return if (one.length <= max) one else one.take(max).trimEnd() + "…"
    }

    /** "Queued", "Queued · 2 ahead". */
    fun queueLabel(ahead: Int): String = if (ahead <= 0) "Queued · next" else "Queued · $ahead ahead"

    /** Roughly four characters a token: only a hint for how long a pasted text takes to read. */
    fun approxTokens(chars: Int): Int = (chars + 3) / 4

    /** The text a file attachment adds to the composer. */
    fun attachment(name: String, body: String, truncated: Boolean): String = buildString {
        append("<attached file: ").append(name).append(">\n")
        append(body.trimEnd())
        if (truncated) append("\n[…cut at 64 KB]")
        append("\n</attached file>\n")
    }
}
