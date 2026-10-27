package dev.myct

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.AndroidUiFrameClock
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Test-only Activity; model is stable, the View/Activity are genuinely recreated. No password state. */
class DesktopRotationActivity : ComponentActivity() {
    companion object { internal lateinit var session: DesktopModel }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parent = window.decorView.createLifecycleAwareWindowRecomposer(
            Handler(Looper.getMainLooper()).asCoroutineDispatcher() + AndroidUiFrameClock(Choreographer.getInstance()), lifecycle)
        setContent(parent = parent) { MyCtTheme { Surface { DesktopScreen(session, {}) } } }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DesktopRotationRegressionTest {
    private fun native(view: View): DesktopView? = if (view is DesktopView) view else (view as? ViewGroup)?.let { p ->
        (0 until p.childCount).firstNotNullOfOrNull { native(p.getChildAt(it)) }
    }
    @Test fun activityRecreationRetainsConnectionRepaintsIdleFrameAndClosesOldBitmaps() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val end = CountDownLatch(1); val auth = AtomicInteger(); val opens = AtomicInteger(); val closes = AtomicInteger()
        val model = DesktopModel(scope, { opens.incrementAndGet(); DesktopTunnel(24000) { closes.incrementAndGet() } },
            { _, _ -> SshExecResult("active", "", 0) }, {
                object : DesktopPeer {
                    override fun connect(port: Int, password: CharArray) { auth.incrementAndGet(); password.fill('\u0000') }
                    override fun read(onFrame: (RfbFramebuffer) -> Unit) {
                        onFrame(RfbFramebuffer(1280, 720).apply { fill(0, 0, 1280, 720, 0xff123456.toInt()) })
                        end.await(); throw IOException("closed fixture")
                    }
                    override fun pointer(x: Int, y: Int, mask: Int) {}
                    override fun key(sym: Int, down: Boolean) {}
                    override fun close() { end.countDown() }
                    override fun dispose() {}
                }
            }, configurationGraceMs = 2500)
        DesktopRotationActivity.session = model
        model.attach(SshConnection())
        var controller = Robolectric.buildActivity(DesktopRotationActivity::class.java).setup().visible()
        var w = 360; var h = 800
        fun frame(): DesktopView? {
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            val root = controller.get().window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, w, h)
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
            return native(root)
        }
        fun await(condition: () -> Boolean) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
            while (!condition() && System.nanoTime() < until) { frame(); Thread.sleep(5) }
            assertTrue(condition())
        }
        fun paint(v: DesktopView) {
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp)); assertEquals(0xff123456.toInt(), bmp.getPixel(v.width / 2, v.height / 2)); bmp.recycle()
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            await { native(controller.get().window.decorView) != null }
            model.connect("fixture".toCharArray())
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (model.frames == 0L && System.nanoTime() < until) Thread.sleep(5)
            assertTrue(model.frames > 0); paint(checkNotNull(native(controller.get().window.decorView)))
            repeat(6) {
                val previous = checkNotNull(frame()); val oldActivity = controller.get()
                w = if (w == 360) 800 else 360; h = if (w == 360) 800 else 360
                // Robolectric 4.13 recreate() focuses a not-yet-attached window in
                // paused Choreographer mode. Exercise the equivalent lifecycle explicitly.
                ReflectionHelpers.setField(oldActivity, "mChangingConfigurations", true)
                val state = Bundle()
                controller.pause().saveInstanceState(state).stop().destroy()
                assertFalse(state.toString().contains("fixture"))
                controller = Robolectric.buildActivity(DesktopRotationActivity::class.java).setup(state).visible()
                await { native(controller.get().window.decorView) != null }
                val current = checkNotNull(frame()); assertNotSame(previous, current); assertNotSame(oldActivity, controller.get())
                await {
                    val f = DesktopImage::class.java.getDeclaredField("bitmap"); f.isAccessible = true; f.get(current.image) != null
                }
                paint(current)
                assertTrue(model.presented); assertEquals(DesktopLink.CONNECTED, model.link)
                assertNull(previous.image.changed)
                val f = DesktopImage::class.java.getDeclaredField("closed"); f.isAccessible = true; assertTrue(f.getBoolean(previous.image))
                assertEquals(1, auth.get()); assertEquals(1, opens.get()); assertEquals(0, closes.get())
            }
            controller.pause().stop() // Real background is NOT a configuration handoff.
            await { model.link == DesktopLink.DISCONNECTED && !model.retiring }
            assertEquals(1, closes.get())
        } finally { model.hide(); end.countDown(); scope.cancel(); controller.destroy(); ShadowChoreographer.setPaused(false) }
    }
}
