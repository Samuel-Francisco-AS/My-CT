package dev.myct

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ServerSetupTest {
    private fun fixture(): JSONObject {
        val names = listOf("packages", "core", "postgres", "services", "verify")
        return JSONObject().put("schema", 1).put("plan_id", "a".repeat(64)).put("release", "fixture")
            .put("facts", JSONObject().put("os", JSONObject().put("PRETTY_NAME", "Fedora"))
                .put("arch", "x86_64").put("user", "alice").put("missing_packages", JSONArray().put("libpq")))
            .put("components", JSONArray(names.map { JSONObject().put("id", it).put("state", "missing")
                .put("description", "Install $it").put("privilege", "user + sudo") }))
            .put("memory_available", 1024).put("disk_available", 2048).put("admin", "password_required")
            .put("blockers", JSONArray()).put("optional", JSONArray()).put("journal", JSONArray())
            .put("can_apply", true).put("complete", false)
    }
    @Test fun rejectsContradictoryAndUnversionedPlans() {
        assertTrue(parseSetupPlan(fixture().toString()).canApply)
        for (j in listOf(fixture().put("schema", 2), fixture().put("plan_id", "bad"),
            fixture().put("complete", true), fixture().put("blockers", JSONArray().put("incompatible: atomic")))) {
            assertThrows(Exception::class.java) { parseSetupPlan(j.toString()) }
        }
        val blocked = fixture().put("can_apply", false).put("blockers", JSONArray().put("intervention: existing cluster"))
        assertFalse(parseSetupPlan(blocked.toString()).canApply)
    }
    @Test fun diagnosticSourceNeverBecomesShellText() {
        val directory = Files.createTempDirectory("setup-diag").toFile()
        try {
            val source = "print(\"safe ' \\\$() ; text\")\n".toByteArray()
            val cmd = setupDiagnosticCommand(source)
            val p = ProcessBuilder("sh", "-c", cmd).directory(directory).start()
            assertEquals("safe ' \\\$() ; text", p.inputStream.bufferedReader().readText().trim())
            assertEquals(0, p.waitFor())
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun receiverChecksIntegrityAndForwardsStdinWithoutShellInterpolation() {
        val directory = Files.createTempDirectory("setup-wire").toFile()
        try {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use {
                it.putNextEntry(ZipEntry("__main__.py"))
                it.write("import sys\npassword=sys.stdin.buffer.readline()\nassert password==b'fixture-only\\n'\nprint('wire-ok')\n".toByteArray())
                it.closeEntry()
            }
            val payload = bytes.toByteArray()
            val bundle = SetupBundle(byteArrayOf(), payload, sha256(payload))
            fun execute(candidate: SetupBundle): Pair<Int, String> {
                val p = ProcessBuilder("sh", "-c", setupReceiverCommand(candidate, "b".repeat(64)))
                    .apply { environment()["TMPDIR"] = directory.path }.start()
                p.outputStream.use { it.write(candidate.payload + "fixture-only\n".toByteArray()) }
                val result = p.inputStream.bufferedReader().readText()
                p.errorStream.bufferedReader().readText()
                return p.waitFor() to result
            }
            assertEquals(0 to "wire-ok\n", execute(bundle))
            assertNotEquals(0, execute(bundle.copy(payload = payload + byteArrayOf(0))).first)
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun streamingProgressAcceptsFragmentedLinesAndBoundsMemory() {
        val events = mutableListOf<String>()
        val stream = SetupLines { events += it.getString("state") }
        stream.write("{\"sta".toByteArray()); stream.write("te\":\"running\"}\n{\"state\":\"failure\"}\n".toByteArray())
        assertEquals(listOf("running", "failure"), events)
        assertThrows(Exception::class.java) { stream.write(ByteArray(128 * 1024 + 1) { 120 }) }
    }
}
