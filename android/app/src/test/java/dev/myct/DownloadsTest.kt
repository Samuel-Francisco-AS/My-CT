package dev.myct

import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream
import java.nio.file.Files

class DownloadsTest {
    private val id = "158ab86e-1fbe-4d6f-b337-65c96b3c353a"
    private fun record(state: String = "running", total: Long = 100) =
        "id: $id\nstate: $state\nbytes: 25\ntotal: $total\npath: /home/sam/Downloads/My-CT/$id/prova.bin" +
            (if (state == "completed") "" else ".part") + "\nmessage: \n"

    @Test fun parsesAllStatesAndProgressWithoutInventingUnknownTotal() {
        DownloadState.entries.forEach { state ->
            val item = parseDownloads(record(state.name)).single()
            assertEquals(state, item.state)
            assertEquals("prova.bin", item.name)
            assertEquals(0.25f, item.progress!!, 0.001f)
            assertEquals("/home/sam/Downloads/My-CT/$id", item.directory)
        }
        assertNull(parseDownloads(record(total = -1)).single().progress)
        assertNull(parseDownloads(record(total = 0)).single().progress)
        assertEquals(emptyList<ServerDownload>(), parseDownloads(""))
    }
    @Test fun rejectsTruncatedInvalidAndDuplicateHistory() {
        listOf(record().replace("bytes: 25", "bytes: -1"), record().replace("running", "unknown"),
            record().substringBefore("message:"), record().replace("prova.bin", "../escape"),
            record().replace("total: 100", "total: nope"), record() + "\n" + record()).forEach {
            assertThrows(IllegalArgumentException::class.java) { parseDownloads(it) }
        }
    }
    @Test fun shellExecKeepsUserTextInArgumentsWithoutExecutingIt() {
        val root = Files.createTempDirectory("myct-download-args").toFile()
        try {
            val cli = root.resolve(".local/bin/myct-download")
            checkNotNull(cli.parentFile).mkdirs()
            cli.writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\"\n")
            assertTrue(cli.setExecutable(true))
            val safeUrl = "https://example.com/a?q=';\$(id)&x=1"
            val name = "x'; touch ${root.path}/injected; echo '"
            val process = ProcessBuilder("/bin/sh", "-c", downloadCommand(DownloadAction.Start(safeUrl, name)))
                .apply { environment()["HOME"] = root.path }.start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor())
            assertEquals("start\n$safeUrl\n$name\n", output)
            assertFalse(root.resolve("injected").exists())
            assertThrows(IllegalArgumentException::class.java) { downloadCommand(DownloadAction.Start("https://example.com/", "--allow-private")) }
            assertThrows(IllegalArgumentException::class.java) { downloadCommand(DownloadAction.Cancel("$id; id")) }
            assertThrows(IllegalArgumentException::class.java) { downloadCommand(DownloadAction.Start("file:///etc/passwd", "")) }
        } finally { root.deleteRecursively() }
    }
    private class Exec(private val emit: (OutputStream, OutputStream) -> Unit = { _, _ -> },
                       override val exitStatus: Int = 0, override val done: Boolean = true) : DownloadExec {
        var closed = false
        var command = ""
        override fun connect(command: String, output: OutputStream, error: OutputStream) { this.command = command; emit(output, error) }
        override fun close() { closed = true }
    }
    @Test fun independentExecClosesOnSuccessTimeoutOverflowAndMissingCli() {
        val exec = Exec({ out, _ -> out.write(record().toByteArray()) })
        assertEquals(record(), executeDownload(DownloadAction.List, { exec }))
        assertTrue(exec.command.contains("exec \"\$HOME/.local/bin/myct-download\" list"))
        assertTrue(exec.closed)
        listOf(Exec(done = false), Exec({ out, _ -> out.write(ByteArray(262145)) }), Exec(exitStatus = 127)).forEach { channel ->
            assertThrows(DownloadFailure::class.java) { executeDownload(DownloadAction.List, { channel }, 40) }
            assertTrue(channel.closed)
        }
    }
    @Test fun stderrNeverLeaksSensitiveUrlInUiError() {
        val exec = Exec({ _, err -> err.write("https://sensitive.example/token".toByteArray()) }, exitStatus = 1)
        val failure = assertThrows(DownloadFailure::class.java) { executeDownload(DownloadAction.Start("https://example.com", ""), { exec }) }
        assertFalse(failure.safeMessage.contains("sensitive"))
    }
}
