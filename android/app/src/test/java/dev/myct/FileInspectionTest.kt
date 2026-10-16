package dev.myct

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.OutputStream
import java.nio.file.Files

class FileInspectionTest {
    private val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    @Test fun comparesOptionalChecksumWithoutAcceptingMalformedDigest() {
        assertEquals("NÃO INFORMADO", compareChecksum(hash, "  "))
        assertEquals("CORRESPONDE", compareChecksum(hash, " ${hash.uppercase()}\n"))
        assertEquals("DIVERGENTE", compareChecksum(hash, "0".repeat(64)))
        assertTrue(compareChecksum(hash, "z".repeat(64)).contains("64 caracteres"))
    }
    @Test fun shellReceivesOnlyEncodedPathAndNeverExecutesFilename() {
        val root = Files.createTempDirectory("myct-inspect-args").toFile()
        try {
            val cli = root.resolve(".local/bin/myct-inspect")
            cli.parentFile!!.mkdirs(); cli.writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\"\n"); cli.setExecutable(true)
            val path = "/home/sam/espaços ' \$(touch ${root.path}/injected); [*]?.bin"
            val process = ProcessBuilder("/bin/sh", "-c", inspectionCommand(path))
                .apply { environment()["HOME"] = root.path }.start()
            val args = process.inputStream.bufferedReader().readLines()
            assertEquals(0, process.waitFor())
            val decoded = args[0].chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
            assertEquals(path, decoded); assertEquals("--watch-stdin", args[1])
            assertFalse(root.resolve("injected").exists())
        } finally { root.deleteRecursively() }
    }
    private class Exec(val reply: String = "", override val exitStatus: Int = 0) : DownloadExec {
        var closed = false
        override val done = true
        override fun connect(command: String, output: OutputStream, error: OutputStream) {
            (if (exitStatus == 0) output else error).write(reply.toByteArray())
        }
        override fun close() { closed = true }
    }
    @Test fun reportsRemoteFailureAndClosesOnlyOperationThenAllowsNextCommand() {
        val path = "/home/sam/abc"
        listOf(Exec("Sem permissão de leitura.", 1), Exec(exitStatus = 127), Exec("broken")).forEach { channel ->
            assertThrows(Exception::class.java) { executeInspection(path) { channel } }
            assertTrue(channel.closed)
        }
        val json = JSONObject().put("version", 1).put("path", path).put("name", "abc").put("bytes", 3)
            .put("extension", "").put("type", "text/plain").put("sha256", hash).put("warning", "")
        val next = Exec(json.toString())
        assertEquals(hash, executeInspection(path) { next }.sha256)
        assertTrue(next.closed)
        json.put("path", "/other")
        assertThrows(IllegalArgumentException::class.java) { parseInspection(json.toString(), path) }
        json.put("path", path).put("sha256", "bad")
        assertThrows(IllegalArgumentException::class.java) { parseInspection(json.toString(), path) }
    }
    @Test fun interruptedOperationClosesItsChannel() {
        val channel = Exec()
        Thread.currentThread().interrupt()
        try { assertThrows(InterruptedException::class.java) { executeInspection("/home/sam/abc") { channel } } }
        finally { Thread.interrupted() }
        assertTrue(channel.closed)
    }
}
