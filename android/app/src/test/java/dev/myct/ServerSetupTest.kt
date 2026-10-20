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
    @Test fun optionalPlansAreSeparateAndLegacyNeverAuthorizesInstallation() {
        for (target in listOf(SetupTarget.transmission, SetupTarget.prowlarr)) {
            val j = fixture()
            j.getJSONObject("facts").put("target", target.name)
            j.put("components", JSONArray(listOf("packages", target.name, "services", "verify").map {
                JSONObject().put("id", it).put("state", "missing").put("description", "specific plan").put("privilege", "user")
            }))
            j.put("inventory", JSONObject().put("detected", false).put("managed", false).put("installed", false)
                .put("running", false).put("enabled", false).put("linger", false).put("api_ready", false).put("version", ""))
            val plan = parseSetupPlan(j.toString())
            assertEquals(target, plan.target); assertTrue(plan.canApply)
            j.getJSONArray("components").getJSONObject(1).put("state", "unmanaged")
            j.getJSONObject("inventory").put("detected", true).put("running", true).put("enabled", true).put("api_ready", true)
            j.put("can_apply", false).put("blockers", JSONArray().put("intervention: instalação anterior preservada"))
            val legacy = parseSetupPlan(j.toString())
            assertFalse(legacy.canApply); assertEquals(SetupState.unmanaged, legacy.components[1].state)
            assertTrue(legacy.inventory!!.running); assertFalse(legacy.inventory.managed)
            j.getJSONObject("facts").put("target", "base")
            assertThrows(Exception::class.java) { parseSetupPlan(j.toString()) }
        }
    }
    @Test fun sshCommandsBindTheChosenComponentAndCompressLargeDiagnosticSource() {
        val source = "print('large-source-safe')\n" + "# filler\n".repeat(15000)
        for (target in SetupTarget.entries) {
            val cmd = setupDiagnosticCommand(source.toByteArray(), target)
            assertTrue(cmd.length < 32000)
            val p = ProcessBuilder("sh", "-c", cmd).start()
            assertEquals("large-source-safe\n", p.inputStream.bufferedReader().readText())
            assertEquals(0, p.waitFor())
            val receiver = setupReceiverCommand(SetupBundle(byteArrayOf(), byteArrayOf(), "a".repeat(64)), "b".repeat(64), target)
            val hex = receiver.substringAfter("bytes.fromhex(\"").substringBefore("\")")
            val python = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
            assertTrue(python.contains("\"--component\",\"${target.name}\""))
        }
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
    @Test fun cloudSelectionTravelsAsDataAndOnlineRequiresExactApproval() {
        val selection = SetupCloudSelection("other-drive:", "/home/alice/private ' \$() config.conf")
        val source = "import json,sys; print(json.dumps(sys.argv[1:]))".toByteArray()
        for (approval in listOf(null, "c".repeat(64))) {
            val cmd = setupDiagnosticCommand(source, SetupTarget.rclone, selection, approval)
            assertFalse(cmd.contains(selection.config))
            val p = ProcessBuilder("sh", "-c", cmd).start()
            val args = JSONArray(p.inputStream.bufferedReader().readText())
            assertEquals(0, p.waitFor())
            assertEquals(if (approval == null) "diagnose" else "verify-cloud", args.getString(0))
            val values = (0 until args.length()).map { args.getString(it) }
            assertEquals(selection.remote, values[values.indexOf("--remote") + 1])
            assertEquals(selection.config, values[values.indexOf("--config") + 1])
        }
        assertThrows(Exception::class.java) { SetupCloudSelection("bad:path", "") }
        assertThrows(Exception::class.java) { SetupCloudSelection("drive:", "relative") }
        assertThrows(Exception::class.java) { setupDiagnosticCommand(source, SetupTarget.rclone, selection, "bad") }
        assertThrows(Exception::class.java) { setupDiagnosticCommand(source, SetupTarget.base, selection, "c".repeat(64)) }
    }
    @Test fun cloudOnlineNeverRendersRawSecretsOrClaimsArchiveRecovery() {
        val secret = "SYNTHETIC-OAUTH-MUST-NOT-APPEAR"
        val failure = JSONObject().put("schema", 1).put("state", "failure").put("code", "oauth").put("message", secret)
        assertFalse(setupCloudVerificationMessage(failure.toString()).contains(secret))
        val verified = JSONObject().put("schema", 1).put("state", "verified").put("access", true)
            .put("archive_recoverable", false).put("archive_accessible", true).put("quota_free", 0).put("message", secret)
        val result = setupCloudVerificationMessage(verified.toString())
        assertFalse(result.contains(secret)); assertTrue(result.contains("sem espaço")); assertTrue(result.contains("não foram verificadas"))
        verified.put("archive_recoverable", true)
        assertThrows(Exception::class.java) { setupCloudVerificationMessage(verified.toString()) }
    }
    @Test fun cloudConfigurationPendingIsNotAnInstallationFailure() {
        val j = fixture()
        j.getJSONObject("facts").put("target", "rclone")
        j.put("components", JSONArray(listOf("packages", "rclone", "services", "verify").map {
            JSONObject().put("id", it).put("state", "existing").put("description", "offline installed").put("privilege", "user")
        })).put("can_apply", false).put("complete", true)
        j.put("inventory", JSONObject().put("detected", true).put("managed", true).put("installed", true)
            .put("running", false).put("enabled", false).put("linger", true).put("api_ready", false).put("version", "1.74.3"))
        j.put("cloud", JSONObject().put("selection", JSONObject().put("remote", "other:").put("config", "/home/alice/custom.conf"))
            .put("config", JSONObject().put("state", "pending").put("path", "/home/alice/custom.conf").put("configured", false))
            .put("manager", true).put("helpers", JSONObject().put("myct-archive", "compatible")))
        val plan = parseSetupPlan(j.toString())
        assertTrue(plan.complete); assertFalse(plan.cloud!!.configured); assertEquals("other:", plan.cloud.selection.remote)
        j.remove("cloud")
        assertThrows(Exception::class.java) { parseSetupPlan(j.toString()) }
    }

}
