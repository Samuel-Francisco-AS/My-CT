package dev.myct

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class MediaBatchTest {
    private val hash = "a".repeat(40)
    private val id = "$hash:Pack/episode_01.mkv"

    @Test fun enumeratedSourcesIncludeTorrentAndFolder() {
        val json = """{"files":[{"id":"$id","torrent":"$hash","name":"episode_01.mkv","path":"Pack/episode_01.mkv"}]}"""
        val found = parseMediaSources(json)
        assertEquals(1, found.size)
        assertEquals("Pack/episode_01.mkv", found.single().path)
        assertThrows(IllegalArgumentException::class.java) {
            parseMediaSources(json.replace("Pack/episode_01.mkv", "../outside.mkv"))
        }
    }

    @Test fun bulkRequestHasExplicitCategoryAndEpisodeWithoutShellInjection() {
        val spec = ManualBatch("anime", "FMA'; touch injected; echo '", 1, null,
            listOf(ManualSelection(id, 1)))
        val token = manualToken(spec)
        val json = JSONObject(String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8))
        assertEquals("anime", json.getString("kind"))
        assertEquals(1, json.getInt("season"))
        assertEquals(id, json.getJSONArray("files").getJSONObject(0).getString("id"))
        val command = mediaCommand(MediaAction.BulkPlan(spec))
        assertTrue(command.contains("myct-media-bulk"))
        assertTrue(command.contains("plan '$token'"))
        assertFalse(command.contains("touch injected"))
        assertTrue(mediaCommand(MediaAction.BulkSources).contains(" sources;"))
    }

    @Test fun previewFlagsCollisionsWithoutImportingAndAcceptsTvRoot() {
        val json = """{"kind":"series","items":[{"id":"$id","name":"episode_01.mkv","target":"Series-TV/Show/Season 01/Show S01E01.mkv","episode":1,"status":"collision"}],"ready":0,"existing":0,"collisions":1}"""
        val data = parseManualPlan(json)
        assertEquals(1, data.collisions)
        assertEquals("collision", data.items.single().status)
        assertTrue(validMediaPath(data.items.single().target))
        assertThrows(IllegalArgumentException::class.java) {
            parseManualPlan(json.replace("Series-TV/Show/", "Series-TV/../"))
        }
    }

    @Test fun movieIsSingleFileAndEpisodesAreUnique() {
        val another = "$hash:Pack/episode_02.mkv"
        assertThrows(IllegalArgumentException::class.java) {
            manualToken(ManualBatch("movie", "Film", null, 2024,
                listOf(ManualSelection(id, null), ManualSelection(another, null))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            manualToken(ManualBatch("anime", "Show", 1, null,
                listOf(ManualSelection(id, 1), ManualSelection(another, 1))))
        }
    }
}
