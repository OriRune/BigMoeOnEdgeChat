package io.bigmoeonedge.example.scan

/**
 * How far a lossy setting's text drifted from the lossless one. Greedy decoding makes the lossless
 * text reproducible, so the first character where they differ and a character-level similarity say
 * how much the setting changed the answer.
 */
object OutputCompare {
    private const val LIMIT = 2000

    /** Index of the first differing character, or -1 when one text is a prefix of the other (or equal). */
    fun firstDivergence(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        for (i in 0 until n) if (a[i] != b[i]) return i
        return -1
    }

    /** 1.0 for identical text, down to 0.0; edit distance over the first 2000 characters. */
    fun similarity(a0: String, b0: String): Double {
        val a = a0.take(LIMIT)
        val b = b0.take(LIMIT)
        if (a.isEmpty() && b.isEmpty()) return 1.0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }
}
