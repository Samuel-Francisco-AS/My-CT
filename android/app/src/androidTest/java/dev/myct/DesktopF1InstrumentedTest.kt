package dev.myct

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.util.concurrent.*

/** Actual My-CT UI / Canvas / TCP-RFB pipeline against an in-process fixture. Never the Fedora desktop. */
class DesktopF1InstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun launch(): MainActivity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    private fun await(label: String, done: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!done() && System.nanoTime() < end) { instrumentation.waitForIdleSync(); Thread.sleep(30) }
        assertTrue(label, done())
    }
    private fun find(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString() == text) return node
        for (i in 0 until node.childCount) find(text, node.getChild(i))?.let { return it }
        return null
    }
    private fun click(text: String) {
        await("Missing text: $text") { find(text) != null }
        var node = checkNotNull(find(text))
        while (!node.isClickable) node = checkNotNull(node.parent)
        assertTrue("Cannot click $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); instrumentation.waitForIdleSync() }
    private fun scroll(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): Boolean {
        node ?: return false
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
        return false
    }
    private fun findView(view: View): DesktopView? = if (view is DesktopView) view else
        (view as? ViewGroup)?.let { parent -> (0 until parent.childCount).firstNotNullOfOrNull { findView(parent.getChildAt(it)) } }

    @Test fun realDrawerEntryBackAndReentryWorkWithoutSshOrVnc() {
        val activity = launch()
        try {
            repeat(2) {
                click("Menu")
                repeat(5) { if (find("Desktop") == null) { scroll(); Thread.sleep(80) } }
                click("Desktop"); await("Desktop toolbar absent") { find("Voltar") != null }
                assertFalse(activity.isFinishing)
                // The drawer still owns Back while open; it must not exit the section.
                click("Menu"); await("Drawer absent") { find("Fechar menu") != null }
                back(); await("Drawer failed to close") { find("Fechar menu") == null }
                assertNotNull(find("Voltar"))
                back(); await("Back must leave Desktop") { find("Voltar") == null }
                assertFalse("Desktop Back finished MainActivity", activity.isFinishing)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun realTcpRfbBlackFramebufferKeepsToolbarVisibleAndSupportsInputAndReconnect() {
        val activity = launch(); val fixture = RfbFixture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val model = DesktopModel(scope, { DesktopTunnel(fixture.port) {} }, { _, _ -> SshExecResult("active", "", 0) })
        val mounted = mutableStateOf(true)
        var previousPolicy: StrictMode.ThreadPolicy? = null
        try {
            instrumentation.runOnMainSync {
                previousPolicy = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeath().build())
                model.attach(SshConnection())
                activity.setContent { MyCtTheme { Surface(color = MaterialTheme.colorScheme.background) {
                    if (mounted.value) DesktopScreen(model, {}, onExit = { mounted.value = false }) else Text("Destino anterior")
                } } }
            }
            await("Initial toolbar absent") { find("Voltar") != null }
            instrumentation.runOnMainSync { model.connect("password".toCharArray()) }
            await("First real RFB update was not presented") { model.presented }
            assertTrue(model.diagnostics.state.completed.containsAll(DesktopStep.entries))
            assertNotNull(find("Voltar"))
            assertToolbarPainted(activity)
            instrumentation.runOnMainSync {
                val view = checkNotNull(findView(activity.window.decorView))
                val now = android.os.SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                    val event = MotionEvent.obtain(now, now + 20, action, view.width / 2f, view.height / 2f, 0)
                    view.dispatchTouchEvent(event); event.recycle()
                }
                view.onCreateInputConnection(EditorInfo()).commitText("ação", 1)
            }
            await("Mouse and Portuguese text did not reach the RFB peer") {
                fixture.events.contains("p:32:20:1") && fixture.events.contains("k:231:true") && fixture.events.contains("k:227:true")
            }
            instrumentation.runOnMainSync { model.disconnect() }
            await("Desktop resources not released") { !model.retiring }
            assertNotNull(find("Voltar"))
            instrumentation.runOnMainSync { model.connect("password".toCharArray()) }
            await("Reentry failed") { model.presented && fixture.connections >= 2 }
            back(); await("Connected Desktop did not return to My-CT") { find("Destino anterior") != null }
            assertFalse(activity.isFinishing)
        } finally {
            instrumentation.runOnMainSync { model.hide(); previousPolicy?.let(StrictMode::setThreadPolicy); activity.finish() }
            fixture.close(); scope.cancel()
        }
    }

    @Test fun authFailureKeepsControlsAndBackAvailable() {
        val activity = launch(); val fixture = RfbFixture(deny = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val model = DesktopModel(scope, { DesktopTunnel(fixture.port) {} }, { _, _ -> SshExecResult("active", "", 0) })
        val mounted = mutableStateOf(true)
        try {
            instrumentation.runOnMainSync {
                model.attach(SshConnection()); activity.setContent { MyCtTheme { Surface(color = MaterialTheme.colorScheme.background) {
                    if (mounted.value) DesktopScreen(model, {}, onExit = { mounted.value = false }) else Text("Destino anterior")
                } } }
            }
            await("Toolbar absent") { find("Voltar") != null }
            instrumentation.runOnMainSync { model.connect("password".toCharArray()) }
            await("Auth failure not reported") { model.link == DesktopLink.ERROR && !model.retiring }
            assertEquals(DesktopFailure.AUTH, model.diagnostics.state.failure)
            assertFalse(model.presented); assertNotNull(find("Voltar"))
            back(); await("No exit after authentication failure") { find("Destino anterior") != null }
            assertFalse(activity.isFinishing)
        } finally { instrumentation.runOnMainSync { model.hide(); activity.finish() }; fixture.close(); scope.cancel() }
    }

    private fun assertToolbarPainted(activity: MainActivity) {
        // Own synthetic window only. PixelCopy checks actual on-screen painting, not just semantics.
        val bounds = Rect(); checkNotNull(find("Voltar")).getBoundsInScreen(bounds)
        instrumentation.runOnMainSync { activity.applyCaptureProtection(false) }
        instrumentation.waitForIdleSync(); Thread.sleep(100)
        val bitmap = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
        val ready = CountDownLatch(1); var result = -1
        PixelCopy.request(activity.window, bitmap, { result = it; ready.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue(ready.await(15, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
        try {
            var gold = 0
            for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(bitmap.height))
                for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(bitmap.width)) {
                    val c = bitmap.getPixel(x, y)
                    if (Color.red(c) > 100 && Color.green(c) > 60 && Color.red(c) > Color.blue(c) * 1.2) gold++
                }
            assertTrue("VNC framebuffer overpainted the visible Voltar control", gold > 4)
            File(activity.cacheDir, "desktop-f1-ui.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    private class RfbFixture(private val deny: Boolean = false) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val events = CopyOnWriteArrayList<String>()
        @Volatile var connections = 0; private set
        @Volatile private var closed = false
        @Volatile private var active: Socket? = null
        private val executor = Executors.newSingleThreadExecutor()
        private val worker = executor.submit {
            while (!closed) try {
                server.accept().use { socket ->
                    active = socket; connections++
                    val input = DataInputStream(socket.getInputStream())
                    val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                    out.writeBytes("RFB 003.008\n"); out.flush(); input.readFully(ByteArray(12))
                    out.write(byteArrayOf(1, 2)); out.flush(); check(input.readByte().toInt() == 2)
                    out.write(ByteArray(16) { it.toByte() }); out.flush(); input.readFully(ByteArray(16))
                    out.writeInt(if (deny) 1 else 0); out.flush()
                    if (deny) return@use
                    check(input.readByte().toInt() == 1)
                    out.writeShort(64); out.writeShort(40); out.write(ByteArray(16)); out.writeInt(0); out.flush()
                    input.readFully(ByteArray(20)); check(input.readByte().toInt() == 2); input.readByte()
                    repeat(input.readUnsignedShort()) { input.readInt() }
                    check(input.readByte().toInt() == 3); input.readFully(ByteArray(9))
                    // A legitimate all-black first desktop must never hide My-CT's controls.
                    out.write(byteArrayOf(0, 0)); out.writeShort(1)
                    out.writeShort(0); out.writeShort(0); out.writeShort(64); out.writeShort(40); out.writeInt(0)
                    out.write(ByteArray(64 * 40 * 4)); out.flush()
                    while (!closed) when (input.readUnsignedByte()) {
                        3 -> input.readFully(ByteArray(9)) // Incremental request; idle fixture sends nothing.
                        5 -> { val mask = input.readUnsignedByte(); val x = input.readUnsignedShort(); val y = input.readUnsignedShort(); events.add("p:$x:$y:$mask") }
                        4 -> { val down = input.readBoolean(); input.readShort(); events.add("k:${input.readInt()}:$down") }
                        else -> error("Unexpected RFB input")
                    }
                }
            } catch (_: IOException) { /* Disconnect/reconnect or close. */ }
        }
        override fun close() { closed = true; active?.close(); server.close(); try { worker.get(3, TimeUnit.SECONDS) } finally { executor.shutdownNow() } }
    }
}
