package dev.myct

import org.junit.Assert.*
import org.junit.Test

class MediaUxTest {
    @Test fun nonPaddedEpisodesSortNaturally() {
        val files = listOf("Brotherhood 10.mkv", "Brotherhood 2.mkv", "Brotherhood 1.mkv")
        assertEquals(
            listOf("Brotherhood 1.mkv", "Brotherhood 2.mkv", "Brotherhood 10.mkv"),
            files.sortedWith { a, b -> mediaNaturalCompare(a, b) }
        )
    }

    @Test fun fansubWithResolutionAndSuffixSortsByEpisode() {
        val files = listOf(
            "[CR] Sousou no Frieren - 10 [720p][A1].mkv",
            "[CR] Sousou no Frieren - 02 [720p][A2].mkv",
            "[CR] Sousou no Frieren - 01 [720p][A3].mkv"
        )
        val sorted = files.sortedWith { a, b -> mediaNaturalCompare(a, b) }
        assertTrue(sorted[0].contains(" - 01 "))
        assertTrue(sorted[1].contains(" - 02 "))
        assertTrue(sorted[2].contains(" - 10 "))
    }

    @Test fun sameDigitGroupingIsCaseInsensitive() {
        assertTrue(mediaNaturalCompare("Ep 02.mkv", "ep 10.mkv") < 0)
        assertEquals(0, mediaNaturalCompare("EP 01.mkv", "ep 1.mkv"))
    }
}
