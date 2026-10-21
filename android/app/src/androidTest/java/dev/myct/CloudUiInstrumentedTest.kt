package dev.myct

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Fake SSH only. Requires an explicitly selected device; compilation is not device acceptance. */
class CloudUiInstrumentedTest {
    @Test fun thirteenSelectionsSurviveActualCompositionDisposalDuringSpaceQuery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val archive = "a".repeat(64); val hash = "c".repeat(40)
        val paths = (1..13).map { "Series/Fixture/E%02d.mkv".format(it) }
        val token = "${System.currentTimeMillis() / 1000}-" + "d".repeat(64)
        fun plan(selected: List<String>) = JSONObject().put("ok", true).put("id", archive)
            .put("preview", token).put("confirmation", token).put("library_confirmation", token)
            .put("expires_in_seconds", 600).put("can_release", true).put("remove_library", JSONArray(selected))
            .put("remove_paths", JSONArray()).put("trees", JSONArray()).put("blockers", JSONArray())
            .put("library", JSONArray(paths.map { JSONObject().put("path", it).put("present", true).put("linked_to_package", true) }))
        val base = CloudWork("torrent", listOf("space", archive))
        val row = AtomicReference(JSONObject().put("id", "b".repeat(32)).put("work", base.json())
            .put("state", "succeeded").put("result", plan(emptyList())).toString())
        val mounted = androidx.compose.runtime.mutableStateOf(true)
        val model = CloudModel(scope) { _, query ->
            when (query) {
                CloudQuery.Snapshot -> {
                    val entry = JSONObject().put("id", archive).put("infohash", hash).put("name", "Fixture").put("size", 100).put("state", "verified")
                    JSONObject().put("ok", true).put("tasks", JSONArray().put(JSONObject(row.get())))
                        .put("catalogs", JSONObject().put("torrent", JSONObject().put("ok", true).put("entries", JSONArray().put(entry)))
                            .put("file", JSONObject().put("ok", true).put("entries", JSONArray())))
                        .put("transmission", JSONObject().put("ok", true).put("torrents", JSONArray())).toString()
                }
                is CloudQuery.Start -> {
                    check(query.work.action == "space") // This test cannot submit release.
                    row.set(JSONObject().put("id", query.id).put("work", query.work.json()).put("state", "running").toString())
                    JSONObject().put("ok", true).put("task", JSONObject(row.get())).toString()
                }
                is CloudQuery.Task -> JSONObject().put("ok", true).put("task", JSONObject(row.get())).toString()
                else -> error("No cancellation or destructive operation")
            }
        }
        fun idle() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            do {
                instrumentation.waitForIdleSync()
                if (!model.busy) return
                Thread.sleep(20)
            } while (System.nanoTime() < deadline)
            error("Model did not become idle")
        }
        fun find(node: AccessibilityNodeInfo?, text: String): Boolean {
            node ?: return false
            if (node.text?.toString() == text) return true
            return (0 until node.childCount).any { find(node.getChild(it), text) }
        }
        fun scroll(node: AccessibilityNodeInfo?): Boolean {
            node ?: return false
            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            return (0 until node.childCount).any { scroll(node.getChild(it)) }
        }
        try {
            instrumentation.runOnMainSync { model.attach(SshConnection()); model.select(archive); model.refresh() }; idle()
            instrumentation.runOnMainSync { model.viewTask(model.tasks.single()) }; idle()
            instrumentation.runOnMainSync {
                paths.forEach { model.toggleLink(archive, it) }
                activity.setContent { if (mounted.value) MyCtTheme { CloudScreen(model, {}) } }
            }; idle()
            instrumentation.runOnMainSync {
                model.start(CloudWork("torrent", listOf("space", archive) + paths.flatMap { listOf("--remove-library", it) }))
            }; idle()
            assertEquals(paths, parseCloudTask(JSONObject(row.get())).work.librarySelection)
            instrumentation.runOnMainSync { mounted.value = false }; idle()
            row.set(JSONObject(row.get()).put("state", "succeeded").put("result", plan(paths)).toString())
            instrumentation.runOnMainSync { model.refresh() }; idle()
            instrumentation.runOnMainSync { mounted.value = true }; idle()
            assertEquals(13, model.selectedLinks(archive).size)
            assertFalse(model.canRelease(model.space!!, paths)) // Disposal revokes consent, retains choices.
            repeat(16) {
                if (!find(instrumentation.uiAutomation.rootInActiveWindow, "13/256 links selecionados")) {
                    scroll(instrumentation.uiAutomation.rootInActiveWindow); instrumentation.waitForIdleSync()
                }
            }
            assertTrue(find(instrumentation.uiAutomation.rootInActiveWindow, "13/256 links selecionados"))
        } finally { instrumentation.runOnMainSync { scope.cancel(); activity.finish() } }
    }

    @Test fun cancelDialogRequiresConfirmationAndSilentPollingNeverDisablesControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val id = "b".repeat(32)
        val work = CloudWork("file", listOf("archive", "/fixture/Upload UI.bin"))
        val row = AtomicReference(JSONObject().put("id", id).put("work", work.json()).put("state", "running")
            .put("cancel_supported", true).toString())
        val snapshots = AtomicInteger(); val cancels = AtomicInteger()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val gate = AtomicReference<CountDownLatch?>(null)
        val model = CloudModel(scope) { _, query ->
            when (query) {
                CloudQuery.Snapshot -> {
                    snapshots.incrementAndGet()
                    val empty = JSONObject().put("ok", true).put("entries", JSONArray())
                    JSONObject().put("ok", true).put("tasks", JSONArray().put(JSONObject(row.get())))
                        .put("catalogs", JSONObject().put("file", empty).put("torrent", empty))
                        .put("transmission", JSONObject().put("ok", true).put("torrents", JSONArray())).toString()
                }
                is CloudQuery.Task -> {
                    gate.get()?.let { entered.countDown(); it.await(5, TimeUnit.SECONDS) }
                    JSONObject().put("ok", true).put("task", JSONObject(row.get())).toString()
                }
                is CloudQuery.Cancel -> {
                    cancels.incrementAndGet()
                    row.set(JSONObject(row.get()).put("cancel_requested", true).toString())
                    JSONObject().put("ok", true).put("task", JSONObject(row.get())).toString()
                }
                else -> error("UI test cannot start remote work")
            }
        }
        fun find(label: String): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (node.text?.toString() == label) return node
                for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
                return null
            }
            return walk(instrumentation.uiAutomation.rootInActiveWindow)
        }
        fun awaitNode(label: String): AccessibilityNodeInfo {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                find(label)?.let { return it }
                Thread.sleep(40)
            }
            throw AssertionError("UI missing: $label")
        }
        fun click(label: String) {
            var node = awaitNode(label)
            while (!node.isClickable) node = node.parent ?: throw AssertionError("Not clickable: $label")
            assertTrue(node.isEnabled); assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        try {
            instrumentation.runOnMainSync {
                model.attach(SshConnection())
                activity.setContent { MyCtTheme { CloudScreen(model, {}) } }
            }
            click("Cancelar transferência…")
            awaitNode("Cancelar transferência?")
            assertEquals(0, cancels.get())
            click("Continuar transferência")
            click("+ Últimas operações")
            gate.set(release)
            instrumentation.runOnMainSync { model.poll() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(6) {
                assertTrue(awaitNode("Atualizar").isEnabled)
                assertTrue(awaitNode("Detalhes / progresso").isEnabled)
                assertFalse(model.busy)
                Thread.sleep(50)
            }
            assertEquals(1, snapshots.get())
            click("Cancelar transferência…")
            repeat(3) { awaitNode("Cancelar transferência?"); Thread.sleep(50) }
            assertEquals(0, cancels.get())
            release.countDown(); gate.set(null)
            click("Continuar transferência")
            click("Cancelar transferência…"); click("Confirmar cancelamento")
            awaitNode("Arquivar · Cancelamento solicitado")
            assertEquals(1, cancels.get()); assertEquals(1, snapshots.get())
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { scope.cancel(); activity.finish() }
        }
    }
}
