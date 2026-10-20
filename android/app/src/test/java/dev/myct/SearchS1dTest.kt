package dev.myct

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SearchS1dTest {
    private val ref = "a".repeat(64)
    private val hash = "b".repeat(40)
    private val file = SearchResult("Ubuntu", 123, "Fixture", 1, 0, null, null,
        id = ref, indexerId = 12, publicUrl = "https://example.org/view/1", resultRef = ref, torrentAvailable = true)
    private fun await(predicate: () -> Boolean) = runBlocking { withTimeout(3000) { while (!predicate()) delay(5) } }

    @Test fun optionalContractEnablesIndependentActionsAndPreservesOldHelper() {
        val row = JSONObject().put("title", "Ubuntu").put("indexer_id",12).put("result_ref",ref)
            .put("public_url",file.publicUrl).put("torrent_available",true)
        val reply = JSONObject().put("schema_version",1).put("ok",true).put("state","results")
            .put("result_limit",20).put("count",1).put("received_count",1).put("truncated",false)
            .put("results",JSONArray().put(row))
        val parsed = (parseSearchReply(reply.toString()) as SearchReply.Results).items.single()
        assertNull(parsed.magnet);assertEquals(ref,parsed.resultRef);assertTrue(parsed.torrentAvailable)
        assertEquals(file.publicUrl,parsed.publicUrl)
        row.put("public_url","http://127.0.0.1:9696/1/download?apikey=secret")
        assertNull((parseSearchReply(reply.toString()) as SearchReply.Results).items.single().publicUrl)
        row.remove("torrent_available");row.remove("result_ref");row.remove("public_url")
        val old = (parseSearchReply(reply.toString()) as SearchReply.Results).items.single()
        assertFalse(old.torrentAvailable);assertNull(old.resultRef);assertNull(old.publicUrl)
    }
    @Test fun publicPagesRejectAuthPrivateEndpointsAndOpaqueLinks() {
        for (url in listOf("https://example.org/view/123", "https://example.org/torrent/12/ubuntu",
            "https://example.org/viewtopic.php?t=12", "https://example.org/ubuntu-torrent-12.html"))
            assertEquals(url, publicSearchPage(url))
        for (url in listOf("http://127.0.0.1/view/1", "http://10.0.0.1/view/1", "https://[::1]/view/1",
            "https://host.local/view/1", "https://user:pass@example.org/view/1", "file:///tmp/test",
            "https://example.org/view/1?passkey=secret", "https://example.org/api/view/1",
            "https://example.org/download/1", "https://example.org/view/1#token", "https://example.org/view/1%0a",
            "https://example.org/view/${"a".repeat(32)}", "https://example.org:9696/view/1")) assertNull(url,publicSearchPage(url))
    }
    @Test fun commandQuotesQueryAndAcceptsOnlyOpaqueReference() {
        val root = Files.createTempDirectory("myct-s1d-args").toFile()
        try {
            val cli=root.resolve(".local/bin/myct-search");checkNotNull(cli.parentFile).mkdirs()
            cli.writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\"\n");assertTrue(cli.setExecutable(true))
            val query="Ubuntu'; touch ${root.path}/injected; echo '\$(id) `id`"
            val process=ProcessBuilder("/bin/sh","-c",searchTorrentCommand(query,12,ref)).apply { environment()["HOME"]=root.path }.start()
            assertEquals("add-torrent\n$query\n--indexer\n12\n--result\n$ref\n",process.inputStream.bufferedReader().readText())
            assertEquals(0,process.waitFor());assertFalse(root.resolve("injected").exists())
        } finally { root.deleteRecursively() }
        assertThrows(IllegalArgumentException::class.java) { searchTorrentCommand("Ubuntu",12,"https://example.org/torrent") }
        assertThrows(IllegalArgumentException::class.java) { searchTorrentCommand("Ubuntu",0,ref) }
    }
    private class Exec(private val output: String, override val exitStatus: Int = 0,
                       override val done: Boolean = true) : DownloadExec {
        var closed=false
        override fun connect(command: String, output: OutputStream, error: OutputStream) { output.write(this.output.toByteArray()) }
        override fun close() { closed=true }
    }
    @Test fun sshReturnsSanitizedErrorsClosesChannelsAndAcceptsDuplicate() {
        val action=TorrentAction.AddFile("Ubuntu",12,ref)
        for (code in listOf("stale_result","download_redirect","html_response","file_limit","metainfo_rejected","addition_uncertain")) {
            val exec=Exec("""{"schema_version":1,"ok":false,"state":"error","error":{"code":"$code","message":"passkey=secret"}}""",3)
            val err=assertThrows(DownloadFailure::class.java) { executeSearchTorrent(action,{exec}) }
            assertTrue(exec.closed);assertFalse(err.safeMessage.contains("secret"))
        }
        val exec=Exec("""{"schema_version":1,"ok":true,"state":"added","hash":"$hash","id":1,"duplicate":true}""")
        assertTrue(JSONObject(executeSearchTorrent(action,{exec})).getBoolean("duplicate"));assertTrue(exec.closed)
        val blocked=Exec("",done=false);val entered=CountDownLatch(1)
        runBlocking {
            val job=launch { runInterruptible(Dispatchers.IO) { entered.countDown(); executeSearchTorrent(action,{blocked}) } }
            withContext(Dispatchers.IO) { assertTrue(entered.await(2,TimeUnit.SECONDS)) }
            job.cancelAndJoin()
        }
        assertTrue(blocked.closed)
    }
    @Test fun explicitAdditionBlocksDuplicatesAndPreservesResultsOnError() {
        for (outcome in listOf("accepted","duplicate","failure")) {
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            val calls=ConcurrentLinkedQueue<TorrentAction>();val entered=CountDownLatch(1);val release=CountDownLatch(1)
            val torrents=TorrentsModel(scope) { _,action ->
                calls.add(action)
                if (action is TorrentAction.AddFile) {
                    entered.countDown();release.await()
                    if (outcome=="failure") throw DownloadFailure("HTML recusado")
                    """{"hash":"$hash","id":1,"duplicate":${outcome=="duplicate"}}"""
                } else """{"torrents":[]}"""
            }
            val model=SearchModel(torrents,scope) { _,_ -> SearchReply.Results(listOf(file),false,1,hasMore=true) }
            try {
                val ssh=SshConnection();torrents.attach(ssh);model.attach(ssh);model.edit("Ubuntu");model.search();await { !model.busy }
                assertTrue(calls.isEmpty());assertFalse(model.canAdd(file));assertTrue(model.canAddFile(file))
                model.addFile(file);assertTrue(entered.await(2,TimeUnit.SECONDS));model.addFile(file)
                assertEquals(1,calls.size);assertFalse(model.canAddFile(file))
                release.countDown();await { !torrents.busy }
                assertEquals(listOf(file),model.items);assertTrue(model.hasMore)
                assertTrue(torrents.message.contains(when(outcome) { "failure" -> "HTML"; "duplicate" -> "já existente"; else -> "aceito" }))
            } finally { release.countDown();scope.cancel() }
        }
    }
    @Test fun navigationCancelAndReconnectNeverReplayOrPublishLateAddition() {
        for (change in listOf("hide","disconnect","edit")) {
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            val calls=ConcurrentLinkedQueue<TorrentAction>();val entered=CountDownLatch(1);val release=CountDownLatch(1)
            val torrents=TorrentsModel(scope) { _,action ->
                calls.add(action);entered.countDown()
                while(true) { try { release.await();break } catch (_: InterruptedException) { } }
                """{"hash":"$hash","id":1,"duplicate":false}"""
            }
            val model=SearchModel(torrents,scope) { _,_ -> SearchReply.Results(listOf(file),false,1) }
            try {
                val ssh=SshConnection();torrents.attach(ssh);model.attach(ssh);model.edit("Ubuntu");model.search();await { !model.busy }
                model.addFile(file);assertTrue(entered.await(2,TimeUnit.SECONDS))
                when(change) { "hide" -> model.hide(); "disconnect" -> { model.attach(null);torrents.attach(null) }; else -> model.edit("Fedora") }
                release.countDown();await { !torrents.busy };runBlocking { delay(50) }
                assertEquals(1,calls.size);assertFalse(torrents.message.contains("aceito"))
                if(change=="hide") assertEquals(listOf(file),model.items)
                torrents.attach(ssh);model.attach(ssh);runBlocking { delay(40) };assertEquals(1,calls.size)
            } finally { release.countDown();scope.cancel() }
        }
    }
}
