package dev.myct

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupSetupTest {
    @Test fun backupIdentityUsesOnlyStdinAndFixedRemoteAction() {
        val identity = "SYNTHETIC-KEY-ONLY-IN-STDIN".toByteArray()
        val request = JSONObject().put("operation", "inspect").put("path", "/home/alice/private ' \\$() backup.age")
        val source = """
            import json,sys
            request=json.loads(sys.stdin.buffer.readline())
            key=sys.stdin.buffer.readline().strip()
            assert key==b'SYNTHETIC-KEY-ONLY-IN-STDIN'
            assert sys.argv==['myct-setup','backup','--component','backup']
            print(json.dumps({'schema':1,'operation':'inspect','plan_id':'a'*64,'path':request['path']}))
        """.trimIndent().toByteArray()
        val command = setupBackupCommand(source)
        assertFalse(command.contains(identity.toString(Charsets.UTF_8)))
        assertFalse(command.contains(request.getString("path")))
        val process = ProcessBuilder("sh", "-c", command).start()
        process.outputStream.use { it.write(backupWire(request, identity)) }
        val reply = validateBackupReply(JSONObject(process.inputStream.bufferedReader().readText()))
        assertEquals(0, process.waitFor())
        assertEquals(request.getString("path"), reply.getString("path"))
    }
    @Test fun credentialFieldsNewlinesAndOversizedRequestsAreRejected() {
        for (key in listOf("identity", "password")) assertThrows(Exception::class.java) {
            backupWire(JSONObject().put("operation", "restore").put(key, "private"), byteArrayOf())
        }
        assertThrows(Exception::class.java) { backupWire(JSONObject(), "bad\nkey".toByteArray()) }
        assertThrows(Exception::class.java) { backupWire(JSONObject().put("path", "x".repeat(17000)), byteArrayOf()) }
    }
    @Test fun plansRequireKnownSchemaAndExactApprovalHash() {
        val plan = JSONObject().put("schema", 1).put("operation", "plan-export").put("plan_id", "a".repeat(64))
        validateBackupReply(plan)
        for (bad in listOf(JSONObject(plan.toString()).put("schema", 2), JSONObject(plan.toString()).put("plan_id", "bad"),
            JSONObject(plan.toString()).put("operation", "shell"))) {
            assertThrows(Exception::class.java) { validateBackupReply(bad) }
        }
    }
    @Test fun identityIsClearedEvenWhenSshIsUnavailable() {
        val key = "SYNTHETIC-RUNTIME-KEY".toByteArray()
        assertThrows(Exception::class.java) {
            SshConnection().setupBackup(SetupBundle(byteArrayOf(), byteArrayOf(), "a".repeat(64)), JSONObject().put("operation", "inspect"), key)
        }
        assertTrue(key.all { it == 0.toByte() })
    }

}
