package dev.myct

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.view.Choreographer
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.AndroidUiFrameClock
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import kotlinx.coroutines.android.asCoroutineDispatcher
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Production Compose + native Android/Skia. Synthetic peer only; never contacts Fedora. */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DesktopUxRegressionTest {
    private class Harness : AutoCloseable {
        // Robolectric replaces Loopers between tests, while Compose caches its default
        // UI dispatcher. Bind each window to this test's actual Handler/Choreographer.
        private val frameContext = Handler(Looper.getMainLooper()).asCoroutineDispatcher() +
            AndroidUiFrameClock(Choreographer.getInstance())
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val activity = controller.get()
        private val recomposer = activity.window.decorView.createLifecycleAwareWindowRecomposer(frameContext)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val shown = mutableStateOf(true)
        val admin = CopyOnWriteArrayList<DesktopOperation>()
        val events = CopyOnWriteArrayList<String>()
        val peers = mutableListOf<CountDownLatch>()
        var active = true
        var menu = 0
        var width = 360; var height = 800
        val model = DesktopModel(scope, { DesktopTunnel(24000) {} }, { _, op ->
            admin.add(op)
            if (op == DesktopOperation.START) active = true
            if (op == DesktopOperation.STOP) active = false
            SshExecResult(if (active) "active" else "inactive", "", 0)
        }, {
            val closed = CountDownLatch(1); peers.add(closed)
            var sent = false
            object : DesktopPeer {
                override fun connect(port: Int, password: CharArray) { password.fill('\u0000') }
                override fun read(onFrame: (RfbFramebuffer) -> Unit) {
                    if (!sent) {
                        sent = true
                        onFrame(RfbFramebuffer(1280, 720).apply {
                            fill(0, 0, 1280, 720, 0xff123456.toInt())
                            fill(600, 320, 80, 80, 0xffeeeeee.toInt())
                        })
                    }
                    closed.await(); throw IOException("closed fixture")
                }
                override fun pointer(x: Int, y: Int, mask: Int) { events.add("p:$x:$y:$mask") }
                override fun key(sym: Int, down: Boolean) { events.add("k:$sym:$down") }
                override fun close() { closed.countDown() }
                override fun dispose() {}
            }
        }, pollDelayMs = 1, inspectAdministration = { DesktopAdministration(DesktopAdminAccess.READY, true, true) })
        init {
            model.attach(SshConnection())
            activity.setContent(parent = recomposer) { MyCtTheme { Surface(color = MaterialTheme.colorScheme.background) {
                if (shown.value) DesktopScreen(model, { menu++ }, onExit = { shown.value = false }) else Text("Destino anterior")
            } } }
            await { nativeView() != null && find("Voltar") != null }
            frame("initial")
        }
        fun frame(name: String? = null) {
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            fun layout() = roots().forEach { root ->
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
            }
            layout()
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            // Hiding system bars can schedule a WindowManager traversal at the default
            // Robolectric display size. Reapply our test's viewport after it settles.
            layout()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            roots().last().draw(Canvas(bitmap))
            if (name != null) {
                val file = java.io.File("build/reports/desktop-ux1/$name.png"); file.parentFile?.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val v = view(); val xy = IntArray(2); v.getLocationInWindow(xy)
                java.io.File(file.parentFile, "$name.txt").writeText("window=${width}x$height viewport=${v.width}x${v.height} origin=${xy.toList()} scale=${v.viewport().scale}\n")
            }
            bitmap.recycle()
            shadowOf(Looper.getMainLooper()).idle()
            layout()
        }
        fun roots(): List<View> = listOf(activity.window.decorView) + ShadowDialog.getShownDialogs().mapNotNull { it.window?.decorView }
        private fun collect(view: View): List<SemanticsNode> {
            if (view is ViewRootForTest) return view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
            return (view as? ViewGroup)?.let { p -> (0 until p.childCount).flatMap { collect(p.getChildAt(it)) } } ?: emptyList()
        }
        fun nodes() = roots().flatMap(::collect)
        fun find(label: String) = nodes().firstOrNull { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
                n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
        }
        fun click(label: String) {
            await { find(label) != null }
            val node = checkNotNull(find(label)) { "Missing '$label': ${nodes().map { it.config }}" }
            assertNull("Disabled $label", node.config.getOrNull(SemanticsProperties.Disabled))
            assertTrue("No click action $label", node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
            frame()
        }
        fun nativeView(): DesktopView? {
            fun find(v: View): DesktopView? = if (v is DesktopView) v else (v as? ViewGroup)?.let { p ->
                (0 until p.childCount).firstNotNullOfOrNull { find(p.getChildAt(it)) }
            }
            return find(activity.window.decorView)
        }
        fun view(): DesktopView = checkNotNull(nativeView())
        fun await(condition: () -> Boolean) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
            while (!condition() && System.nanoTime() < until) { frame(); Thread.sleep(10) }
            if (!condition()) frame("timeout")
            assertTrue("link=${model.link} frames=${model.frames} presented=${model.presented} message=${model.message} trace=${model.diagnostics.state}", condition())
        }
        fun connect() {
            model.connect("fixture".toCharArray())
            // The fixture uses Unconfined so IO completion does not require advancing
            // Compose's animation clock. Draw the actual native view before draining it.
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
            while (model.frames == 0L && System.nanoTime() < until) Thread.sleep(5)
            assertTrue("Synthetic peer did not produce a frame: ${model.message}", model.frames > 0)
            val v = view(); val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bitmap)); bitmap.recycle()
            await { model.presented }; frame()
        }
        fun back() { activity.onBackPressedDispatcher.onBackPressed(); frame() }
        override fun close() {
            model.hide(); peers.forEach { it.countDown() }; scope.cancel()
            ShadowDialog.getShownDialogs().forEach { it.dismiss() }
            controller.pause().stop().destroy()
            recomposer.cancel()
        }
    }

    @Test fun disconnectedEntryCompactToolbarDiagnosticsAndExitReentry() = Harness().use { h ->
        assertNotNull("Voltar: ${h.nodes().map { it.config }}", h.find("Voltar")); assertNotNull("Menu", h.find("Menu")); assertNotNull("Conectar", h.find("Conectar"))
        assertNull(h.find("Teclado")); assertNull(h.find("Ctrl")); assertNull(h.find("Encerrar Desktop…"))
        val origin = IntArray(2); h.view().getLocationInWindow(origin)
        assertTrue("Collapsed controls occupy too much height: ${origin[1]}", origin[1] < 120)
        h.click("Menu"); assertEquals(1, h.menu)
        h.click("Opções do Desktop"); h.click("Diagnóstico"); h.frame("diagnostics-panel")
        assertNotNull(h.find("OK · Entrada na tela")); h.click("Fechar opções")
        assertNull(h.find("OK · Entrada na tela"))
        h.frame("disconnected-portrait")
        h.back(); assertFalse(h.shown.value); assertFalse(h.activity.isFinishing)
        h.shown.value = true; h.frame(); assertNotNull(h.find("Conectar"))
    }

    @Test fun connectedPortraitLandscapeImmersiveRecoveryAndKeyboardCommandsPreserveView() = Harness().use { h ->
        h.connect()
        val native = h.view()
        assertNotNull(h.find("Teclado")); assertNull(h.find("Ctrl"))
        listOf("Voltar", "Menu", "Desconectar", "Teclado", "Opções do Desktop", "Tela cheia").forEach { label ->
            val b = checkNotNull(h.find(label)).boundsInRoot
            assertTrue("Truncated $label: $b", b.left >= 0 && b.right <= h.width && b.width >= 48f && b.height >= 48f)
        }
        h.frame("connected-portrait")
        h.width = 800; h.height = 360; h.frame(); h.frame("connected-landscape")
        assertSame(native, h.view()); assertEquals(DesktopLink.CONNECTED, h.model.link)
        assertTrue(h.view().height > 260)
        h.click("Tela cheia"); assertNull(h.find("Voltar")); assertNotNull(h.find("Mostrar controles"))
        assertSame(native, h.view()); assertEquals(360, h.view().height)
        h.frame("immersive-landscape")
        h.click("Mostrar controles"); assertNotNull(h.find("Voltar")); h.click("Teclado")
        assertTrue(h.view().hasFocus())
        h.view().onCreateInputConnection(android.view.inputmethod.EditorInfo()).commitText("ação", 1)
        h.await { h.events.contains("k:231:false") }
        h.click("Recolher controles"); assertNotNull(h.find("Mostrar controles"))
        h.back(); assertTrue(h.shown.value); assertNotNull(h.find("Voltar"))
        assertTrue(h.view().height < 360); assertSame(native, h.view())
        h.back(); assertFalse(h.shown.value); assertFalse(h.activity.isFinishing)
    }

    @Test fun contextKeysViewAdjustmentsAndAdministrativeConfirmationAreKeptSeparate() = Harness().use { h ->
        h.connect(); val native = h.view()
        h.click("Opções do Desktop"); h.frame("controls-panel"); h.click("Teclas extras"); h.frame("extra-keys")
        assertNotNull(h.find("Ctrl")); h.click("Ctrl"); h.click("Tab")
        h.await { h.events.contains("k:65289:false") }
        assertTrue(h.events.contains("k:65507:true"))
        h.click("Recolher teclas extras"); assertFalse(h.model.ctrl)
        h.click("Opções do Desktop"); h.click("Clique direito")
        h.await { h.events.any { it.endsWith(":4") } }
        h.click("Opções do Desktop"); h.click("Zoom e pan")
        val initial = native.viewport().scale
        h.click("Zoom +"); assertTrue(native.viewport().scale > initial)
        h.click("Ajustar"); assertEquals(initial, native.viewport().scale, .001f)
        h.click("Recolher ajuste de imagem"); assertSame(native, h.view())
        h.click("Opções do Desktop"); h.click("Sessão"); h.frame("session-panel"); h.click("Encerrar Desktop…")
        assertNotNull(h.find("Encerrar a sessão gráfica?")); assertFalse(h.admin.contains(DesktopOperation.STOP))
        h.click("Cancelar"); assertEquals(DesktopLink.CONNECTED, h.model.link)
        h.click("Opções do Desktop"); h.click("Sessão"); h.frame("session-panel"); h.click("Encerrar Desktop…"); h.click("Encerrar Desktop")
        h.await { h.model.service == DesktopService.OFF && !h.model.busy }
        assertEquals(1, h.admin.count { it == DesktopOperation.STOP })
        h.click("Opções do Desktop"); h.click("Iniciar Desktop")
        h.await { h.model.service == DesktopService.AVAILABLE && !h.model.busy }
        h.click("Fechar opções"); h.await { !h.model.retiring }; h.connect()
        h.click("Desconectar"); h.await { !h.model.retiring }
        assertEquals(1, h.admin.count { it == DesktopOperation.STOP })
        assertNotNull(h.find("Conectar")); h.connect(); assertSame(native, h.view())
    }
}
