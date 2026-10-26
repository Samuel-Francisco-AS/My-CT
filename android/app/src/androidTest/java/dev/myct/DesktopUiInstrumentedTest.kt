package dev.myct

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.*
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic peer inside My-CT only. Not physical TigerVNC/Moto acceptance. */
class DesktopUiInstrumentedTest {
    @Test fun nativeBitmapTouchDragAndImeCompositionUseDesktopPipeline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val events = CopyOnWriteArrayList<String>(); val closed = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val frame = RfbFramebuffer(1280, 720).also { it.fill(0, 0, 1280, 720, 0xff123456.toInt()) }
        val model = DesktopModel(scope, { DesktopTunnel(24000) {} }, { _, _ -> SshExecResult("active", "", 0) }, {
            object : DesktopPeer {
                override fun connect(port: Int, password: CharArray) { password.fill('\u0000') }
                override fun read(onFrame: (RfbFramebuffer) -> Unit) { onFrame(frame); closed.await(); throw IOException("closed") }
                override fun pointer(x: Int, y: Int, mask: Int) { events.add("p:$x:$y:$mask") }
                override fun key(sym: Int, down: Boolean) { events.add("k:$sym:$down") }
                override fun close() { closed.countDown() }
                override fun dispose() {}
            }
        })
        fun find(view: View): DesktopView? = if (view is DesktopView) view else
            (view as? ViewGroup)?.let { parent -> (0 until parent.childCount).firstNotNullOfOrNull { find(parent.getChildAt(it)) } }
        fun await(done: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!done() && System.nanoTime() < deadline) { instrumentation.waitForIdleSync(); Thread.sleep(10) }
            assertTrue(done())
        }
        try {
            instrumentation.runOnMainSync { model.attach(SshConnection()); activity.setContent { MyCtTheme { androidx.compose.material3.Surface { DesktopScreen(model, {}) } } } }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { model.connect("fixture".toCharArray()) }
            await { model.frames > 0 }
            instrumentation.runOnMainSync {
                val view = checkNotNull(find(activity.window.decorView)); view.layout(0, 0, 1280, 720)
                val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap)); assertEquals(0xff123456.toInt(), bitmap.getPixel(640, 360)); bitmap.recycle()
                fun touch(action: Int, x: Float, y: Float) {
                    val event = MotionEvent.obtain(100, 120, action, x, y, 0); view.onTouchEvent(event); event.recycle()
                }
                touch(MotionEvent.ACTION_DOWN, 640f, 360f); touch(MotionEvent.ACTION_UP, 640f, 360f)
                model.changeDrag(true); touch(MotionEvent.ACTION_DOWN, 100f, 100f)
                touch(MotionEvent.ACTION_MOVE, 200f, 200f); touch(MotionEvent.ACTION_UP, 200f, 200f)
                val ime = view.onCreateInputConnection(EditorInfo())
                ime.setComposingText("a", 1); ime.setComposingText("ã", 1); ime.commitText("ã", 1); ime.finishComposingText()
                ime.commitText("ção", 1); ime.deleteSurroundingText(1, 0)
            }
            await { events.contains("k:65288:false") }
            assertTrue(events.containsAll(listOf("p:640:360:1", "p:640:360:0", "p:100:100:1", "p:200:200:1", "p:200:200:0")))
            assertEquals(2, events.count { it == "k:227:true" })
            instrumentation.runOnMainSync { model.hide() }; await { !model.retiring }
            assertEquals(DesktopLink.DISCONNECTED, model.link)
        } finally {
            instrumentation.runOnMainSync { model.hide(); activity.finish() }; closed.countDown(); scope.cancel()
        }
    }
}
