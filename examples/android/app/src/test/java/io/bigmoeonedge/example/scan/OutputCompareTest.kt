package io.bigmoeonedge.example.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputCompareTest {
    @Test fun identicalTextIsFullySimilarAndNeverDiverges() {
        assertEquals(1.0, OutputCompare.similarity("same text", "same text"), 0.0)
        assertEquals(-1, OutputCompare.firstDivergence("same", "same"))
    }

    @Test fun divergenceIsTheFirstDifferingCharacter() {
        assertEquals(4, OutputCompare.firstDivergence("The cat sat", "The dog sat"))
        assertEquals(-1, OutputCompare.firstDivergence("The cat", "The cat sat"))
    }

    @Test fun similarityFallsWithEdits() {
        assertEquals(0.9, OutputCompare.similarity("abcdefghij", "abcdefghix"), 1e-9)
        assertEquals(0.0, OutputCompare.similarity("aaaa", "bbbb"), 1e-9)
        assertEquals(1.0, OutputCompare.similarity("", ""), 0.0)
    }
}
