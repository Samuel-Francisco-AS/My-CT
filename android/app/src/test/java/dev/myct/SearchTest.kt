package dev.myct

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SearchTest {
    private val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)
    private fun row(magnet: String? = this.magnet) = JSONObject().put("title", "Ubuntu")
        .put("size_bytes", 1234L).put("source", "Knaben").put("seeders", 0)
        .put("leechers", JSONObject.NULL).put("magnet", magnet ?: JSONObject.NULL)
        .put("magnet_source", if (magnet == null) JSONObject.NULL else "infohash")
    private fun reply(rows: JSONArray = JSONArray().put(row()).put(row(null))) = JSONObject()
        .put("schema_version", 1).put("ok", true).put("state", if (rows.length() == 0) "empty" else "results")
        .put("results", rows).put("count", rows.length()).put("received_count", rows.length())
        .put("result_limit", 20).put("truncated", false)

    @Test fun parsesV1NumbersNullsOriginsAndEmptyWithoutInventingMagnet() {
        val parsed = parseSearchReply(reply().toString()) as SearchReply.Results
        assertEquals(2, parsed.items.size)
        assertEquals(1234L, parsed.items[0].size)
        assertEquals(0L, parsed.items[0].seeders)
        assertNull(parsed.items[0].leechers)
        assertEquals(magnet, parsed.items[0].magnet)
        assertEquals("infohash", parsed.items[0].magnetSource)
        assertNull(parsed.items[1].magnet)
        assertNull(parsed.items[1].magnetSource)
        assertTrue((parseSearchReply(reply(JSONArray()).toString()) as SearchReply.Results).items.isEmpty())
        assertEquals(SearchReply.Ready("2.6.5"), parseSearchReply("""{"schema_version":1,"ok":true,"state":"ready","version":"2.6.5"}"""))
        val direct = row().put("magnet_source", "direct")
        assertEquals("direct", (parseSearchReply(reply(JSONArray().put(direct)).toString()) as SearchReply.Results).items.single().magnetSource)
    }
    @Test fun rejectsMalformedOversizeUnsupportedAndInconsistentContracts() {
        val samples = listOf("not json", "{}", "x".repeat(262145),
            reply().put("schema_version", 2).toString(), reply().put("count", 9).toString(),
            reply().put("state", "empty").toString(),
            reply(JSONArray().apply { repeat(21) { put(row()) } }).toString(),
            reply(JSONArray().put(row().put("seeders", -1))).toString(),
            reply(JSONArray().put(row().put("size_bytes", "1234"))).toString())
        samples.forEach { assertThrows(Exception::class.java) { parseSearchReply(it) } }
    }
    @Test fun onlyUsableV1MagnetEnablesDownloadAndIndirectUrlsNeverDo() {
        listOf(null, "https://127.0.0.1/download?apikey=synthetic-secret", "file:///tmp/a.torrent",
            "magnet:?xt=invalid", "$magnet\n", "$magnet&xs=https://example.org",
            "$magnet&xt=urn:btih:" + "b".repeat(40)).forEach {
            assertNull(usableSearchMagnet(it))
            if (it != null && !it.contains('\n')) {
                assertNull((parseSearchReply(reply(JSONArray().put(row(it))).toString()) as SearchReply.Results).items.single().magnet)
            }
        }
        assertEquals(magnet, usableSearchMagnet(magnet))
        val base32 = "magnet:?xt=urn:btih:ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        assertEquals(base32, usableSearchMagnet(base32))
    }
    @Test fun errorMessagesAreMappedWithoutEchoingSecrets() {
        for (code in listOf("config", "authentication", "connection", "timeout", "no_indexers", "response_limit", "unknown")) {
            val text = """{"schema_version":1,"ok":false,"state":"error","error":{"code":"$code","message":"apikey=synthetic-secret"}}"""
            val failure = assertThrows(SearchFailure::class.java) { parseSearchReply(text) }
            assertFalse(failure.safeMessage.contains("synthetic-secret"))
        }
    }
    @Test fun queriesRejectControlsAndShellQuotingPreservesSingleArgument() {
        listOf("", " ", "x".repeat(201), "Ubuntu\n", "a\u0000b", "a\u2028b").forEach {
            assertFalse(validSearchQuery(it))
            assertThrows(IllegalArgumentException::class.java) { searchCommand(SearchAction.Query(it)) }
        }
        val root = Files.createTempDirectory("myct-search-args").toFile()
        try {
            val cli = root.resolve(".local/bin/myct-search")
            checkNotNull(cli.parentFile).mkdirs()
            cli.writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\"\n")
            assertTrue(cli.setExecutable(true))
            val query = "Ubuntu'; touch ${root.path}/injected; echo '\$(id) `id` & Linux"
            val process = ProcessBuilder("/bin/sh", "-c", searchCommand(SearchAction.Query(query)))
                .apply { environment()["HOME"] = root.path }.start()
            assertEquals("search\n$query\n", process.inputStream.bufferedReader().readText())
            assertEquals(0, process.waitFor())
            assertFalse(root.resolve("injected").exists())
        } finally { root.deleteRecursively() }
    }
    private class Exec(val emit: (OutputStream, OutputStream) -> Unit, override val exitStatus: Int = 0,
                       override val done: Boolean = true) : DownloadExec {
        var closed = false
        override fun connect(command: String, output: OutputStream, error: OutputStream) = emit(output, error)
        override fun close() { closed = true }
    }
    @Test fun execClosesChannelOnSuccessMissingHelperTimeoutAndCancellation() {
        val success = Exec({ out, _ -> out.write(reply().toString().toByteArray()) })
        assertTrue(executeSearch(SearchAction.Query("Ubuntu"), { success }) is SearchReply.Results)
        assertTrue(success.closed)
        val missing = Exec({ _, err -> err.write("secret".toByteArray()) }, 127)
        assertThrows(SearchFailure::class.java) { executeSearch(SearchAction.Status, { missing }) }
        assertTrue(missing.closed)
        val timeout = Exec({ _, _ -> }, done = false)
        assertThrows(DownloadFailure::class.java) { executeSearch(SearchAction.Status, { timeout }, 1) }
        assertTrue(timeout.closed)
        val entered = CountDownLatch(1)
        val blocking = Exec({ _, _ -> entered.countDown() }, done = false)
        runBlocking {
            val job = launch { runInterruptible(Dispatchers.IO) { executeSearch(SearchAction.Status, { blocking }) } }
            withContext(Dispatchers.IO) { assertTrue(entered.await(2, TimeUnit.SECONDS)) }
            job.cancelAndJoin()
        }
        assertTrue(blocking.closed)
    }
}
