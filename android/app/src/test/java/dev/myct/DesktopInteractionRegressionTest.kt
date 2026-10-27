package dev.myct

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.android.asCoroutineDispatcher
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.IOException
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DesktopInteractionRegressionTest {
    internal class Harness : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Handler(Looper.getMainLooper()).asCoroutineDispatcher())
        val events = CopyOnWriteArrayList<String>()
        val end = CountDownLatch(1)
        val model = DesktopModel(scope, { DesktopTunnel(24000) {} }, { _, _ -> SshExecResult("active", "", 0) }, {
            object : DesktopPeer {
                override fun connect(port: Int, password: CharArray) { password.fill('\u0000') }
                override fun read(onFrame: (RfbFramebuffer) -> Unit) {
                    onFrame(RfbFramebuffer(1280, 720)); end.await(); throw IOException("closed fixture")
                }
                override fun pointer(x: Int, y: Int, mask: Int) { events.add("p:$x:$y:$mask") }
                override fun key(sym: Int, down: Boolean) { events.add("k:$sym:$down") }
                override fun close() { end.countDown() }
                override fun dispose() {}
            }
        })
        val controller = Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
        val image = DesktopImage()
        val view = DesktopView(RuntimeEnvironment.getApplication(), model, image)
        init {
            controller.get().setContentView(view, android.view.ViewGroup.LayoutParams(320, 200))
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            model.attach(SshConnection()); model.show({ image.update(it, model.attempt) }, image::clear, image)
            view.layout(0, 0, 320, 200); model.connect("fixture".toCharArray()); await { model.frames > 0 }
            draw()
        }
        fun draw() {
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(b)); b.recycle()
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun touch(action: Int, vararg positions: Pair<Float, Float>) {
            val props = Array(positions.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(positions.size) { i -> MotionEvent.PointerCoords().apply { x = positions[i].first; y = positions[i].second; pressure = 1f; size = 1f } }
            val e = MotionEvent.obtain(100, 120, action, positions.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            view.onTouchEvent(e); e.recycle()
        }
        fun tap(x: Float, y: Float) { touch(MotionEvent.ACTION_DOWN, x to y); touch(MotionEvent.ACTION_UP, x to y) }
        fun await(predicate: () -> Boolean) {
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(3500)
            while (!predicate() && System.nanoTime() < until) {
                shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.MILLISECONDS); Thread.sleep(5)
            }
            assertTrue("Fixture link=${model.link} events=$events", predicate())
        }
        override fun close() { model.hide(); view.release(); end.countDown(); scope.cancel(); controller.pause().stop().destroy() }
    }
    @Test fun directCoordinatesLetterboxZoomAndStaleSurfaceAreSafe() = Harness().use { h ->
        h.tap(160f, 100f); h.await { h.events.contains("p:640:360:1") && h.events.last() == "p:640:360:0" }
        h.events.clear(); h.tap(160f, 1f); Thread.sleep(30); assertTrue(h.events.isEmpty())
        // DOWN inside and UP just outside (without MOVE/slop) must not click the previous pointer.
        h.touch(MotionEvent.ACTION_DOWN, 160f to 189f); h.touch(MotionEvent.ACTION_UP, 160f to 192f)
        h.model.press(65481); h.await { h.events.contains("k:65481:false") }
        assertFalse(h.events.any { it.startsWith("p:") && it.endsWith(":1") })
        h.view.zoomBy(2f); h.view.panBy(.2f, 0f)
        val vp = h.view.viewport(); val x = vp.left + 500 * vp.scale; val y = vp.top + 300 * vp.scale
        h.tap(x, y); h.await { h.events.any { it == "p:500:300:1" } }
        h.model.detachSurface(h.image, true); h.await { h.events.last().startsWith("k:") }; h.events.clear()
        h.tap(100f, 100f); Thread.sleep(30); assertTrue("Detached View must not inject input", h.events.isEmpty())
    }
    @Test fun trackpadIsRelativeAndDragCancelReleasesButton() = Harness().use { h ->
        h.model.changeInteraction(DesktopInteraction.TRACKPAD); h.model.changeSensitivity(1.5f)
        h.await { h.events.size >= 4 }; h.events.clear()
        h.touch(MotionEvent.ACTION_DOWN, 20f to 20f); h.touch(MotionEvent.ACTION_MOVE, 40f to 30f); h.touch(MotionEvent.ACTION_UP, 40f to 30f)
        h.await { h.events.contains("p:670:375:0") }; assertFalse(h.events.any { it.endsWith(":1") })
        h.tap(2f, 2f); h.await { h.events.contains("p:670:375:1") }
        h.model.changeDrag(true); h.events.clear()
        h.touch(MotionEvent.ACTION_DOWN, 80f to 80f); h.touch(MotionEvent.ACTION_MOVE, 100f to 100f)
        h.touch(MotionEvent.ACTION_CANCEL, 100f to 100f)
        h.await { h.events.contains("p:670:375:1") && h.events.any { it.startsWith("p:") && it.endsWith(":0") } }
        val pointers = h.events.filter { it.startsWith("p:") }
        assertEquals("0", pointers.last().substringAfterLast(':'))
    }
    @Test fun pinchPanAndScrollNeverBecomeClicksAndHoldIsRightClick() = Harness().use { h ->
        fun two(move: List<Pair<Float, Float>>) {
            h.touch(MotionEvent.ACTION_DOWN, 100f to 80f)
            h.touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f to 80f, 200f to 80f)
            h.touch(MotionEvent.ACTION_MOVE, *move.toTypedArray())
            h.touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), *move.toTypedArray())
            h.touch(MotionEvent.ACTION_UP, move.first())
        }
        two(listOf(70f to 80f, 230f to 80f)); Thread.sleep(30)
        assertTrue(h.view.viewport().zoom > 1); assertTrue(h.events.isEmpty())
        h.model.changePan(true); h.await { h.events.size >= 4 }; h.events.clear()
        val before = h.view.viewport(); two(listOf(130f to 110f, 230f to 110f)); Thread.sleep(30)
        assertTrue(h.view.viewport().left != before.left || h.view.viewport().top != before.top); assertTrue(h.events.isEmpty())
        h.model.changePan(false); h.await { h.events.size >= 4 }; h.events.clear()
        two(listOf(100f to 160f, 200f to 160f)); h.await { h.events.any { it.endsWith(":8") } }
        assertFalse(h.events.any { it.endsWith(":1") || it.endsWith(":4") })
        h.events.clear(); h.touch(MotionEvent.ACTION_DOWN, 160f to 100f)
        shadowOf(Looper.getMainLooper()).idleFor(ViewConfiguration.getLongPressTimeout().toLong() + 20, TimeUnit.MILLISECONDS)
        h.touch(MotionEvent.ACTION_UP, 160f to 100f); h.await { h.events.any { it.endsWith(":4") } }
        assertFalse(h.events.any { it.endsWith(":1") })
    }
}
