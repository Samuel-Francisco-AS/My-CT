package dev.myct

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TorrentsTest {
    private val hash = "a".repeat(40)
    private fun record(status: Int, metadata: Double = 1.0) = """{"torrents":[{"id":1,"hash_string":"$hash","name":"fixture","status":$status,"total_size":100,"size_when_done":100,"left_until_done":75,"percent_done":0.25,"metadata_percent_complete":$metadata,"download_dir":"/home/sam/Downloads/My-CT/Torrents/$hash","rate_download":12,"rate_upload":3,"peers_connected":2,"error_string":"tracker indisponível"}]}"""
    @Test fun statesProgressAndMissingMetadataReflectMotor() {
        TorrentState.entries.forEachIndexed { i, state ->
            val item = parseTorrents(record(i)).single()
            assertEquals(state, item.state)
            assertEquals(25L, item.bytes)
            assertEquals(0.25f, item.progress!!, 0.001f)
            assertEquals(12L, item.down)
            assertEquals(3L, item.up)
            assertEquals(2L, item.peers)
            assertEquals("tracker indisponível", item.error)
        }
        val pending = parseTorrents(record(4, 0.0)).single()
        assertNull(pending.total); assertNull(pending.bytes); assertNull(pending.progress)
        assertEquals(emptyList<ServerTorrent>(), parseTorrents("{\"torrents\":[]}"))
        listOf(record(8), record(4).replace("Torrents", "Torrents/../outside"), record(4).replace(hash, "all")).forEach {
            assertThrows(IllegalArgumentException::class.java) { parseTorrents(it) }
        }
    }
    @Test fun acceptsRegisteredStyleReadablePathsAndStillRejectsEscape() {
        val original = "/Downloads/My-CT/Torrents/$hash"
        for (folder in listOf("Título-real-aaaaaaaa", "中文-𐐀-ação.txt-aaaaaaaa")) {
            val item = parseTorrents(record(6).replace(original, "/Downloads/My-CT/Torrents/$folder")).single()
            assertTrue(item.directory.endsWith(folder))
        }
        for (folder in listOf(".hidden-aaaaaaaa", "title-bbbbbbbb", "nested/title-aaaaaaaa", "title-aaaaaaaa/..", "a\\b-aaaaaaaa", "x".repeat(170)+"-aaaaaaaa")) {
            assertThrows(IllegalArgumentException::class.java) {
                parseTorrents(record(6).replace(original, "/Downloads/My-CT/Torrents/$folder"))
            }
        }
    }
    @Test fun magnetIsSingleQuotedArgumentAndControlsAreClosed() {
        val root = Files.createTempDirectory("myct-torrent-args").toFile()
        try {
            val cli = root.resolve(".local/bin/myct-torrent")
            checkNotNull(cli.parentFile).mkdirs()
            cli.writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\"\n")
            assertTrue(cli.setExecutable(true))
            val magnet = "magnet:?xt=urn:btih:$hash&dn=';\$(id);'"
            val process = ProcessBuilder("/bin/sh", "-c", torrentCommand(TorrentAction.Add(magnet)))
                .apply { environment()["HOME"] = root.path }.start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor())
            assertEquals("add\n$magnet\n", output)
            assertFalse(root.resolve("injected").exists())
            for (action in listOf(TorrentAction.Control("remove --delete", hash), TorrentAction.Control("remove", "all"), TorrentAction.Add("file:///etc/passwd"))) {
                assertThrows(IllegalArgumentException::class.java) { torrentCommand(action) }
            }
            for (verb in listOf("pause", "resume", "remove")) {
                assertTrue(torrentCommand(TorrentAction.Control(verb, hash)).contains("$verb '$hash'"))
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun missingStoppedAndDisconnectedEngineErrorsAreSafe() {
        for (status in listOf(127, 3, 2)) {
            var closed = false
            val channel = object : DownloadExec {
                override fun connect(command: String, output: java.io.OutputStream, error: java.io.OutputStream) {
                    error.write("private tracker secret".toByteArray())
                }
                override val done = true
                override val exitStatus = status
                override fun close() { closed = true }
            }
            val failure = assertThrows(DownloadFailure::class.java) { executeTorrent(TorrentAction.List, { channel }) }
            assertTrue(closed); assertFalse(failure.safeMessage.contains("secret"))
            if (status == 3) assertTrue(failure.safeMessage.contains("indisponível"))
        }
        assertThrows(IllegalStateException::class.java) { executeTorrent(TorrentAction.List, { error("SSH disconnected") }) }
    }
}
