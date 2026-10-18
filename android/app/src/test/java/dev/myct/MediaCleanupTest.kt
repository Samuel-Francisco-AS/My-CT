package dev.myct

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class MediaCleanupTest {
    private val torrent = "b".repeat(40)
    private val first = "Series/Frieren Beyond Journeys End (2023)/Season 01/Frieren Beyond Journeys End S01E01.mkv"
    private val second = "Series/Frieren Beyond Journeys End (2023)/Season 01/Frieren Beyond Journeys End S01E02.mkv"

    @Test fun fileListShowsManagedAndManuallyLinkedEntries() {
        val payload = """{"files":[{"path":"$first","bytes":123,"torrent":"$torrent","managed":false,"eligible":true},{"path":"$second","bytes":456,"torrent":"$torrent","managed":true,"eligible":true}]}"""
        val files = parseCleanupFiles(payload)
        assertEquals(2, files.size)
        assertFalse(files[0].managed)
        assertTrue(files[0].eligible)
        assertEquals(456L, files[1].bytes)
    }

    @Test fun separateModesAreEncodedWithoutShellMetacharacters() {
        val selection = CleanupSelection("storage", listOf(second, first))
        val code = cleanupToken(selection)
        val json = JSONObject(String(Base64.getUrlDecoder().decode(code), Charsets.UTF_8))
        assertEquals("storage", json.getString("mode"))
        assertEquals(first, json.getJSONArray("paths").getString(0))
        val preview = mediaCommand(MediaAction.CleanupPreview(selection))
        assertTrue(preview.contains("myct-media-cleanup"))
        assertTrue(preview.contains("preview '$code'"))
        assertTrue(mediaCommand(MediaAction.CleanupList).contains(" list;"))
    }

    @Test fun storagePreviewShowsBlockingReasonAndAffectedTorrent() {
        val payload = """{"mode":"storage","selected":1,"bytes":1234,"paths":["$first"],"torrents":[{"hash":"$torrent","name":"Frieren torrent","videos_outside_library":1,"examples":["promo.mkv"],"seeding_will_stop":true}],"blocked":["há outro hardlink"],"can_apply":false,"fingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""
        val parsed = parseCleanupPlan(payload)
        assertFalse(parsed.canApply)
        assertEquals(1, parsed.blockers.size)
        assertEquals(1, parsed.torrents[0].videosOutsideLibrary)
        assertEquals("Frieren torrent", parsed.torrents[0].name)
    }

    @Test fun pathsAndFingerprintsMustPassValidation() {
        assertThrows(IllegalArgumentException::class.java) {
            cleanupToken(CleanupSelection("storage", listOf("Series/../../private")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            cleanupToken(CleanupSelection("links", listOf(first, first)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            mediaCommand(MediaAction.CleanupApply(CleanupSelection("links", listOf(first)), "bad"))
        }
    }
}
