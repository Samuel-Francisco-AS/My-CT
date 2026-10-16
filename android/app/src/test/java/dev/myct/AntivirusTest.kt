package dev.myct

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream
import java.nio.file.Files

internal fun avFixture(): JSONObject = JSONObject().put("version", 1).put("available", true)
    .put("engine", "ClamAV 1.4.6/28155").put("stale", false).put("last_success", 1792177627)
    .put("databases", JSONArray().put(JSONObject().put("name", "daily").put("version", 28155).put("date", "16 Oct 2026")))
internal fun scanFixture(state: String = "CLEAN"): JSONObject = avFixture().put("state", state)
    .put("duration_ms", 10071).put("detections", JSONArray()).put("message", "")
internal fun updateFixture(result: String, phase: String = "FINISHED"): JSONObject = avFixture()
    .put("result", result).put("running", result == "RUNNING").put("phase", phase)
internal fun reply(j: JSONObject, status: Int = 0) = SshExecResult(j.toString(), "", status)

class AntivirusTest {
    private class Exec(val text: String = "", override val exitStatus: Int = 0, override val done: Boolean = true,
                       val stderr: String = "") : DownloadExec {
        var closed = false
        override fun connect(command: String, output: OutputStream, error: OutputStream) {
            output.write(text.toByteArray()); error.write(stderr.toByteArray())
        }
        override fun close() { closed = true }
    }
    @Test fun parsesEveryScanStateIncludingExitOneAndPartialDetections() {
        assertEquals(AvState.CLEAN, parseAvScan(reply(scanFixture())).state)
        assertEquals(AvState.DETECTED, parseAvScan(reply(scanFixture("DETECTED").put("detections", JSONArray().put("Eicar")))) .state)
        for (state in listOf("INCOMPLETE", "ERROR", "UNAVAILABLE")) {
            val json = scanFixture(state).put("message", "Motivo legível").put("detections", JSONArray().put("Eicar parcial"))
            val channel = Exec(json.toString(), 1)
            val result = executeAvScan("/home/sam/test") { channel }
            assertEquals(AvState.valueOf(state), result.state)
            assertEquals(listOf("Eicar parcial"), result.detections)
            assertEquals("Motivo legível", result.message); assertTrue(channel.closed)
        }
        assertEquals(AvState.INCOMPLETE, parseAvScan(reply(JSONObject().put("version", 1)
            .put("state", "INCOMPLETE").put("message", "BUSY"), 1)).state)
        assertEquals(AvState.UNAVAILABLE, parseAvScan(SshExecResult("", "missing", 127)).state)
    }
    @Test fun refusesMalformedContradictoryOrUnsupportedReplies() {
        for (j in listOf(scanFixture().put("version", 2), scanFixture().put("state", "NEW"),
                        scanFixture().put("available", false), scanFixture().put("duration_ms", -1),
                        scanFixture().put("incomplete_reason", "partial"), scanFixture().put("signal", 9),
                        scanFixture("DETECTED"), JSONObject().put("version", 1).put("state", "CLEAN"))) {
            assertThrows(Exception::class.java) { parseAvScan(reply(j)) }
        }
        assertThrows(Exception::class.java) { parseAvScan(reply(scanFixture(), 1)) }
        assertThrows(Exception::class.java) { parseAvScan(SshExecResult("bad", "", 0)) }
    }
    @Test fun keepsStaleSignatureWarningAndMetrics() {
        val result = parseAvScan(reply(scanFixture().put("stale", true).put("peak_rss_kib", 984612)))
        assertTrue(result.info!!.stale); assertEquals(28155L, result.info.dailyVersion)
        assertEquals(10071L, result.durationMs); assertEquals(984612L, result.rssKib)
    }
    @Test fun acceptedRequestIsIndependentOfHistoricalSuccess() {
        assertTrue(parseAvRequest(reply(updateFixture("SUCCESS").put("requested", true))))
        assertFalse(parseAvRequest(reply(updateFixture("SUCCESS").put("requested", false), 1)))
        for (state in listOf("SUCCESS", "ERROR", "INTERRUPTED", "NEVER")) {
            val update = parseAvUpdate(reply(updateFixture(state), if (state in listOf("ERROR", "INTERRUPTED")) 1 else 0))
            assertEquals(AvUpdateResult.valueOf(state), update.result); assertFalse(update.running)
            assertEquals(1792177627L, update.info!!.lastSuccess)
        }
        for (phase in listOf("WAITING", "UPDATING")) {
            val update = parseAvUpdate(reply(updateFixture("RUNNING", phase)))
            assertTrue(update.running); assertEquals(phase, update.phase)
        }
        assertThrows(Exception::class.java) { parseAvUpdate(reply(updateFixture("SUCCESS").put("running", true))) }
    }
    @Test fun rawExecKeepsExitOneAndBoundsBothStreams() {
        val channel = Exec(scanFixture("ERROR").toString(), 1, stderr = "diagnostic")
        val raw = executeSshResult("fixed", { channel })
        assertEquals(1, raw.status); assertEquals("diagnostic", raw.error); assertTrue(raw.output.contains("ERROR")); assertTrue(channel.closed)
        for (errorStream in listOf(false, true)) {
            val overflow = Exec(if (errorStream) "" else "x".repeat(262145), stderr = if (errorStream) "x".repeat(262145) else "")
            assertThrows(DownloadFailure::class.java) { executeSshResult("fixed", { overflow }) }
            assertTrue(overflow.closed)
        }
        val missingExit = Exec(exitStatus = -1)
        assertThrows(DownloadFailure::class.java) { executeSshResult("fixed", { missingExit }) }
        assertTrue(missingExit.closed)
    }
    @Test fun timeoutAndCancellationCloseOnlyTheOperationAndNextExecStillWorks() {
        val timed = Exec(done = false)
        assertThrows(DownloadFailure::class.java) { executeSshResult("fixed", { timed }, 1) }
        assertTrue(timed.closed)
        val cancel = Exec()
        Thread.currentThread().interrupt()
        try { assertThrows(InterruptedException::class.java) { executeAvScan("/home/sam/test") { cancel } } }
        finally { Thread.interrupted() }
        assertTrue(cancel.closed)
        assertEquals(AvState.CLEAN, executeAvScan("/home/sam/test") { Exec(scanFixture().toString()) }.state)
        assertThrows(IllegalStateException::class.java) { executeAvScan("/home/sam/test") { error("disconnected") } }
    }
    @Test fun shellReceivesHexOnlyForHostileUtf8Path() {
        val root = Files.createTempDirectory("myct-av-args").toFile()
        try {
            val cli = root.resolve(".local/bin/myct-av"); cli.parentFile!!.mkdirs()
            cli.writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\"\n"); cli.setExecutable(true)
            val path = "/home/sam/ação ' \$(touch ${root.path}/injected); [*]?.txt"
            val process = ProcessBuilder("/bin/sh", "-c", avScanCommand(path)).apply { environment()["HOME"] = root.path }.start()
            val args = process.inputStream.bufferedReader().readLines(); assertEquals(0, process.waitFor())
            assertEquals("scan", args[0]); assertEquals("--watch-stdin", args[2])
            assertTrue(Regex("[0-9a-f]+").matches(args[1]))
            assertEquals(path, args[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8))
            assertFalse(root.resolve("injected").exists())
        } finally { root.deleteRecursively() }
    }
}
