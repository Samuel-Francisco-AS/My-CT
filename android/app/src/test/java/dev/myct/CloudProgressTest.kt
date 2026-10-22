package dev.myct

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal fun cloudProgressJson(phase: String = "uploading", done: Long = 50, total: Long? = 100,
    percent: Double? = 50.0, speed: Double? = 25.0, eta: Double? = 2.0): JSONObject = JSONObject()
    .put("phase", phase).put("bytes_done", done).put("bytes_total", total ?: JSONObject.NULL)
    .put("percent", percent ?: JSONObject.NULL).put("speed_bps", speed ?: JSONObject.NULL)
    .put("eta_seconds", eta ?: JSONObject.NULL).put("updated_at", CLOUD_NOW / 1000.0)
    .put("current_file_index", 1).put("total_files", 2)

class CloudProgressTest {
    @Test fun spaceReadMetricsHaveExplicitPhaseScopeAndNeverBorrowTransferSemantics() {
        val row = cloudProgressJson("local_read").put("metric", "read").put("scope", "phase").put("read_pass", 2)
        val read = parseCloudProgress(row)!!
        assertEquals("Verificando arquivos locais", read.label); assertEquals(2, read.readPass)
        assertEquals("Tempo estimado desta leitura: 2 s", read.estimate(CLOUD_NOW))
        assertEquals("Tempo estimado indisponível", read.estimate(CLOUD_NOW + 9000))
        assertNull(parseCloudProgress(JSONObject(row.toString()).apply { remove("scope") }))
        assertNull(parseCloudProgress(JSONObject(row.toString()).put("metric", "network")))
        for (phase in listOf("remote_check", "local_inventory", "library_inventory", "transmission_check", "finalizing")) {
            val unknown = parseCloudProgress(cloudProgressJson(phase, 0, null, null, null, null)
                .put("metric", "read").put("scope", "phase"))!!
            assertNull(unknown.percent); assertEquals("Tempo estimado indisponível", unknown.estimate(CLOUD_NOW))
        }
        assertNull(parseCloudProgress(row.put("percent", 100)))
        val active = parseCloudTask(cloudTaskJson(CloudWork("torrent", listOf("space", CLOUD_ARCHIVE)), "running")
            .put("progress", cloudProgressJson("done", 100, percent = 100.0).put("metric", "read").put("scope", "phase")))
        assertEquals("Em andamento", active.label); assertFalse(active.canCancel)
    }

    @Test fun parsesBytesPercentRateAndNullsForBothDirections() {
        for (phase in listOf("uploading", "downloading")) {
            val p = parseCloudProgress(cloudProgressJson(phase))!!
            assertEquals(50L, p.bytesDone); assertEquals(100L, p.bytesTotal)
            assertEquals(50.0, p.percent!!, 0.0); assertEquals(25.0, p.speed!!, 0.0)
            assertEquals("Tempo estimado: 2 s", p.estimate(CLOUD_NOW))
            assertEquals(if (phase == "uploading") "Enviando" else "Baixando", p.label)
        }
        val unknown = parseCloudProgress(cloudProgressJson(total = null, percent = null, speed = null, eta = null))!!
        assertNull(unknown.percent); assertNull(unknown.bytesTotal)
        assertEquals("Tempo estimado indisponível", unknown.estimate(CLOUD_NOW))
    }
    @Test fun zeroRateMissingEtaStaleDataAndVerificationHaveNoEstimate() {
        for (json in listOf(cloudProgressJson(speed = 0.0), cloudProgressJson(eta = null), cloudProgressJson(phase = "hashing"), cloudProgressJson(phase = "verifying"))) {
            assertEquals("Tempo estimado indisponível", parseCloudProgress(json)!!.estimate(CLOUD_NOW))
        }
        assertEquals("Tempo estimado indisponível", parseCloudProgress(cloudProgressJson())!!.estimate(CLOUD_NOW + 9000))
    }
    @Test fun rejectsInvalidNumbersAndEarlyHundredButAllowsVerifiedCompletion() {
        for (json in listOf(cloudProgressJson(done = -1), cloudProgressJson(percent = 101.0), cloudProgressJson(percent = 100.0),
            cloudProgressJson(done = 101), cloudProgressJson().put("bytes_done", "50"), cloudProgressJson().put("speed_bps", -1),
            cloudProgressJson().put("bytes_done", 1.5), cloudProgressJson().put("phase", "SECRET"))) {
            assertNull(json.toString(), parseCloudProgress(json))
        }
        val work = CloudWork("torrent", listOf("restore", CLOUD_ARCHIVE))
        val row = cloudTaskJson(work, "running").put("progress", cloudProgressJson("verifying", 100, percent = null, eta = null))
        assertEquals("Conferindo integridade", parseCloudTask(row).label)
        row.put("state", "succeeded").put("progress", cloudProgressJson("done", 100, percent = 100.0))
        assertEquals("Concluído", parseCloudTask(row).label)
        row.put("state", "failed")
        assertEquals("Falhou", parseCloudTask(row).label)
    }
    @Test fun divergenceExplainsBlockAndOmitsOnlyRedundantMissingData() {
        val labels = cloudBlockers(listOf("LOCAL_DIVERGENCE", "NO_LOCAL_DATA", "RPC_UNAVAILABLE"))
        assertEquals(2, labels.size)
        assertEquals("Arquivos locais não correspondem ao pacote arquivado; liberação bloqueada", labels.first())
        assertEquals("RPC_UNAVAILABLE", labels.last())
        assertEquals(1, cloudBlockers(listOf("NO_LOCAL_DATA")).size)
    }
    @Test fun busyAndDisconnectPreserveTelemetryAndReconnectNeverReplays() = runBlocking {
        val calls = java.util.concurrent.CopyOnWriteArrayList<CloudQuery>()
        val work = CloudWork("torrent", listOf("archive", CLOUD_HASH))
        var task = cloudTaskJson(work, "running").put("progress", cloudProgressJson())
        var catalogOk = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = CloudModel(scope, { CLOUD_NOW }) { _, query ->
            calls += query
            when (query) {
                CloudQuery.Snapshot -> cloudSnapshot(task, catalogOk = catalogOk)
                is CloudQuery.Task -> JSONObject().put("ok", true).put("task", task).toString()
                else -> error("Queries cannot replay uploads")
            }
        }
        suspend fun waitForQuery() { withTimeout(3000) { while (model.busy) delay(5) } }
        try {
            model.attach(SshConnection()); model.refresh(); waitForQuery()
            assertEquals(50L, model.tasks.single().progress!!.bytesDone)
            catalogOk = false; task.put("progress", cloudProgressJson(done = 75, percent = 75.0))
            model.attach(SshConnection()); model.refresh(); waitForQuery()
            assertEquals(1, model.entries.size); assertEquals(75L, model.tasks.single().progress!!.bytesDone)
            assertTrue(model.message.contains("Transferência em andamento; catálogo temporariamente ocupado"))
            assertFalse(model.actionsEnabled); assertFalse(model.stale)
            model.attach(null)
            assertTrue(model.stale); assertEquals(75L, model.tasks.single().progress!!.bytesDone)
            model.attach(SshConnection()); model.refresh(); waitForQuery()
            model.viewTask(model.tasks.single()); waitForQuery()
            assertEquals("Enviando", model.tasks.single().label)
            assertTrue(calls.none { it is CloudQuery.Start })
            // A new model (process death) also recovers the same remote progress.
            val next = CloudModel(scope, { CLOUD_NOW }) { _, _ -> cloudSnapshot(task, catalogOk = false) }
            next.attach(SshConnection()); next.refresh()
            withTimeout(3000) { while (next.busy) delay(5) }
            assertEquals(75L, next.tasks.single().progress!!.bytesDone)
        } finally { scope.cancel() }
    }
}
