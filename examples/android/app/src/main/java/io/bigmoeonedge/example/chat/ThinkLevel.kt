package io.bigmoeonedge.example.chat

/**
 * How long a chat lets its model think before the engine ends the reasoning and the model must
 * answer. The models offer no effort levels of their own (their templates read one on/off flag), so
 * the level is a token budget the engine enforces. Slow models make thinking expensive: at under one
 * token a second, 256 tokens is a few minutes and 4096 is over an hour.
 */
enum class ThinkLevel(val label: String, val tokens: Int) {
    LOW("Low", 256),
    MEDIUM("Medium", 1024),
    HIGH("High", 4096),
    ;

    companion object {
        val DEFAULT = LOW

        /** Stored as the enum name; an unknown or empty value reads as the default. */
        fun of(name: String?): ThinkLevel = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
