package dev.myct

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream
import java.util.Base64

internal const val CLOUD_HASH = "cccccccccccccccccccccccccccccccccccccccc"
internal val CLOUD_ARCHIVE = "a".repeat(64)
internal val CLOUD_REQUEST = "b".repeat(32)
internal const val CLOUD_LIB = "Filmes/Fixture/Fixture.mkv"
internal const val CLOUD_NOW = 1792454400000L
internal val CLOUD_TOKEN = "${CLOUD_NOW / 1000}-" + "d".repeat(64)
internal fun cloudPlan(paths: List<String> = emptyList(), canRelease: Boolean = true,
    choices: List<String> = listOf(CLOUD_LIB)): JSONObject {
    val root = "/home/user/Downloads/My-CT/Torrents/tree"
    val aliases = JSONArray(); val inodes = JSONArray()
    fun group(index: Int, names: List<Pair<String, String?>>, blocks: Long) {
        names.forEach { (name, relative) -> aliases.put(JSONObject().put("path", name)
            .put("role", if (relative == null) "original" else "library").apply { if (relative != null) put("relative", relative) }) }
        inodes.put(JSONObject().put("dev", 1).put("ino", index).put("nlink", names.size)
            .put("physical_bytes", blocks).put("known_paths", JSONArray(names.map { it.first })).put("unknown_links", 0))
    }
    group(1, listOf("$root/video.mkv" to null), 80)
    choices.forEachIndexed { index, relative -> group(index + 2,
        listOf("$root/linked$index.mkv" to null, "/home/user/Media/$relative" to relative), 20) }
    val links = JSONArray(choices.map { JSONObject().put("path", it).put("present", true)
        .put("managed", true).put("linked_to_package", true) })
    val blockers = JSONArray().apply { if (!canRelease) put("TORRENT_STATE") }
    val inventory = JSONObject().put("version", 2).put("id", CLOUD_ARCHIVE).put("paths", aliases).put("inodes", inodes)
        .put("trees", JSONArray()).put("library", links).put("library_complete", true).put("blockers", blockers)
        .put("content_verified", false).put("remote", JSONObject().put("revalidated", false))
    return JSONObject().put("ok", true).put("version", 2).put("inventory", inventory)
        .put("id", CLOUD_ARCHIVE).put("preview", CLOUD_TOKEN).put("confirmation", CLOUD_TOKEN)
        .put("library_confirmation", if (paths.isEmpty()) JSONObject.NULL else CLOUD_TOKEN)
        .put("expires_in_seconds", 600).put("can_release", canRelease).put("remove_library", JSONArray(paths))
        .put("physical_bytes", 80 + 20 * choices.size).put("reclaim_estimate_bytes", 80).put("retained_known_bytes", 20 * choices.size)
        .put("remove_paths", JSONArray()).put("trees", inventory.getJSONArray("trees")).put("library", links)
        .put("library_complete", true).put("blockers", blockers)
}
internal fun cloudTaskJson(work: CloudWork, state: String = "succeeded", result: JSONObject? = null): JSONObject =
    JSONObject().put("id", CLOUD_REQUEST).put("work", work.json()).put("state", state).apply { if (result != null) put("result", result) }
internal fun cloudSnapshot(task: JSONObject? = null, entryState: String = "verified", catalogOk: Boolean = true): String {
    val row = JSONObject().put("id", CLOUD_ARCHIVE).put("name", "Fixture").put("size", 100)
        .put("state", entryState).put("infohash", CLOUD_HASH).put("library_links", JSONArray().put(JSONObject().put("path", CLOUD_LIB)))
    val catalog = JSONObject().put("ok", catalogOk).put("entries", JSONArray().put(row))
    if (!catalogOk) catalog.put("error", JSONObject().put("code", "BUSY"))
    return JSONObject().put("ok", true).put("tasks", if (task == null) JSONArray() else JSONArray().put(task))
        .put("catalogs", JSONObject().put("torrent", catalog).put("file", JSONObject().put("ok", true).put("entries", JSONArray())))
        .put("transmission", JSONObject().put("ok", true).put("torrents", JSONArray())).toString()
}

class CloudTest {
    @Test fun commandsEncodeEveryUserArgumentAndUseOnlyFixedBridge() {
        val path = "/home/user/Meu ' vídeo $(touch bad);.mkv"
        val work = CloudWork("file", listOf("archive", path))
        val command = cloudCommand(CloudQuery.Start(CLOUD_REQUEST, work))
        assertFalse(command.contains(path)); assertFalse(command.contains("touch bad"))
        val payload = command.substringAfter("start $CLOUD_REQUEST ").substringBefore(';')
        val json = JSONObject(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8))
        assertEquals(path, json.getJSONArray("args").getString(1))
        assertTrue(command.contains("myct-cloud")); assertTrue(cloudCommand(CloudQuery.Snapshot).contains("snapshot"))
        assertTrue(cloudCommand(CloudQuery.Task(CLOUD_REQUEST)).contains("task $CLOUD_REQUEST"))
    }
    @Test fun rejectsIdsTraversalAndUnsupportedOperationsBeforeSsh() {
        val invalid = listOf(CloudWork("torrent", listOf("restore", "../x")), CloudWork("torrent", listOf("recover", CLOUD_ARCHIVE, "--force")),
            CloudWork("file", listOf("archive", "/x/../private")), CloudWork("file", listOf("archive", "/x\nprivate")),
            CloudWork("torrent", listOf("relink", CLOUD_ARCHIVE, "--library", "Filmes/../other")),
            CloudWork("torrent", listOf("release", CLOUD_ARCHIVE, "--preview", CLOUD_TOKEN)))
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { it.json() } }
        assertThrows(IllegalArgumentException::class.java) { cloudCommand(CloudQuery.Task("$(id)")) }
    }
    @Test fun releaseCarriesExactPreviewAndSeparateLibraryConsent() {
        val plain = parseCloudSpace(cloudPlan(), CLOUD_NOW)
        val work = plain.release(false, CLOUD_NOW)
        assertEquals(listOf("release", CLOUD_ARCHIVE, "--preview", CLOUD_TOKEN, "--confirm", CLOUD_TOKEN), work.args)
        val withLinks = parseCloudSpace(cloudPlan(listOf(CLOUD_LIB)), CLOUD_NOW)
        assertThrows(IllegalArgumentException::class.java) { withLinks.release(false, CLOUD_NOW) }
        assertTrue(withLinks.release(true, CLOUD_NOW).args.contains("--confirm-library"))
        withLinks.release(true, CLOUD_NOW).json()
        assertThrows(IllegalArgumentException::class.java) { plain.release(false, CLOUD_NOW + 600001) }
        assertThrows(IllegalArgumentException::class.java) { parseCloudSpace(cloudPlan(canRelease = false), CLOUD_NOW).release(false, CLOUD_NOW) }
    }
    @Test fun oldTaskResultNeverRenewsTokenLifetime() {
        val old = parseCloudSpace(cloudPlan(), CLOUD_NOW + 700000)
        assertFalse(old.valid(CLOUD_NOW + 700000))
        assertEquals(CLOUD_NOW + 600000, old.expiresAt)
    }
    @Test fun parsesRealCatalogProjectionAndRejectsWrongIdentity() {
        val json = JSONObject(cloudSnapshot()).getJSONObject("catalogs").getJSONObject("torrent")
        val entry = parseCloudEntries(json, "torrent").single()
        assertEquals("Fixture", entry.name); assertEquals(CLOUD_HASH, entry.hash); assertTrue(entry.restorable)
        assertEquals(listOf(CLOUD_LIB), entry.links)
        json.getJSONArray("entries").getJSONObject(0).put("id", "bad")
        assertThrows(IllegalArgumentException::class.java) { parseCloudEntries(json, "torrent") }
    }
    @Test fun mainStatesDoNotClaimUploadMerelyFromSeedingStarted() {
        val entry = parseCloudEntries(JSONObject(cloudSnapshot()).getJSONObject("catalogs").getJSONObject("torrent"), "torrent").single()
        val rpc = ServerTorrent(1, CLOUD_HASH, "Fixture", TorrentState.SEED, 100, 100, 1f, 0, 0, 0,
            "/home/user/Downloads/My-CT/Torrents/$CLOUD_HASH", "")
        assertEquals("Arquivado", cloudState(entry, null, null))
        assertEquals("Liberado do SSD", cloudState(entry.copy(releaseState = "released"), null, null))
        assertEquals("Pronto para seeding · pausado", cloudState(entry.copy(recoveryState = "verified"), rpc.copy(state = TorrentState.STOPPED), null))
        assertTrue(cloudState(entry, rpc, null).contains("sem upload observado"))
        assertTrue(cloudState(entry, rpc.copy(up = 1), null).contains("upload observado"))
        assertEquals("Verificando", cloudState(entry, rpc.copy(state = TorrentState.CHECK), null))
        assertEquals("Restaurando", cloudState(entry, null, CloudTask(CLOUD_REQUEST, CloudWork("torrent", listOf("restore", CLOUD_ARCHIVE)), "running")))
        assertEquals("Erro", cloudState(entry.copy(state = "failed"), null, null))
    }
    @Test fun missingHelperNetworkAndJsonFailuresHavePortugueseFeedback() {
        class Exec(val code: Int, val text: String) : DownloadExec {
            var closed = false
            override fun connect(command: String, output: OutputStream, error: OutputStream) { output.write(text.toByteArray()) }
            override val done = true
            override val exitStatus get() = code
            override fun close() { closed = true }
        }
        for ((status, text, expected) in listOf(Triple(127, "", "ausente"),
            Triple(2, """{"ok":false,"error":{"code":"AUTH"}}""", "Drive"),
            Triple(2, """{"ok":false,"error":{"code":"NETWORK"}}""", "rede"),
            Triple(-1, "", "SSH"))) {
            val channel = Exec(status, text)
            val failure = assertThrows(DownloadFailure::class.java) { executeCloud(CloudQuery.Snapshot) { channel } }
            assertTrue(failure.safeMessage.contains(expected)); assertTrue(channel.closed)
        }
        assertTrue(cloudError(JSONObject().put("code", "STALE_PREVIEW")).contains("novamente"))
    }
}
