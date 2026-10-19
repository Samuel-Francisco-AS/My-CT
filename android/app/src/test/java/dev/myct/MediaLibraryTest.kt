package dev.myct

import org.junit.Assert.*
import org.junit.Test

class MediaLibraryTest {
    @Test fun recognizeExistingFrierenFolderAndFirstTwoEpisodes() {
        val payload = """{"groups":[{"kind":"anime","folder":"Frieren Beyond Journeys End (2023)","title":"Frieren Beyond Journeys End","path":"Series/Frieren Beyond Journeys End (2023)","year":2023,"files":2,"managed":1,"seasons":[{"number":1,"episodes":[1,2],"missing":[],"files":2,"unidentified":0,"managed":1}]}]}"""
        val items = parseMediaLibrary(payload)
        assertEquals(1, items.size)
        val frieren = items.single()
        assertEquals("Frieren Beyond Journeys End", frieren.title)
        assertEquals("Frieren Beyond Journeys End (2023)", frieren.folder)
        assertEquals("Series/Frieren Beyond Journeys End (2023)", frieren.path)
        assertEquals(listOf(1, 2), frieren.seasons.single().episodes)
        assertEquals(1, frieren.managed)
    }

    @Test fun formatRealEpisodeRangesCompactly() {
        assertEquals("01–03, 07, 09–10", mediaEpisodeRanges(listOf(10, 1, 3, 2, 9, 7)))
        assertEquals("Nenhum episódio identificado", mediaEpisodeRanges(emptyList()))
    }

    @Test fun rejectEscapedPathsAndInvalidCategories() {
        val base = """{"groups":[{"kind":"anime","folder":"Foo","title":"Foo","path":"Series/Foo","year":null,"files":0,"managed":0,"seasons":[]}]}"""
        assertEquals(1, parseMediaLibrary(base).size)
        assertThrows(IllegalArgumentException::class.java) {
            parseMediaLibrary(base.replace("Series/Foo", "Series/../Foo"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseMediaLibrary(base.replace("\"kind\":\"anime\"", "\"kind\":\"other\""))
        }
    }

    @Test fun correctSshCommandUsesReadOnlyHelper() {
        val command = mediaCommand(MediaAction.LibraryList)
        assertTrue(command.contains("myct-media-library"))
        assertTrue(command.contains(" list;"))
    }
}
