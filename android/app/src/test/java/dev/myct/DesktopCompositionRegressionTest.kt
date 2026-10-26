package dev.myct

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

/** Composes the production screen in a real Android runtime, using native Skia rather than mocked Canvas. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DesktopCompositionRegressionTest {
    private fun desktop(view: View): DesktopView? = if (view is DesktopView) view else
        (view as? ViewGroup)?.let { parent -> (0 until parent.childCount).firstNotNullOfOrNull { desktop(parent.getChildAt(it)) } }
    @Test fun initialScreenPaintsControlsAndAndroidBackReturnsWithoutFinishingThenCanReenter() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val activity = controller.get()
        val shown = mutableStateOf(true)
        val model = DesktopModel()
        activity.setContent { MyCtTheme { Surface(color = MaterialTheme.colorScheme.background) {
            if (shown.value) DesktopScreen(model, {}, onExit = { shown.value = false }) else Text("Destino anterior")
        } } }
        fun frame() {
            shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
            val root = activity.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 360, 800)
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        }
        try {
            frame()
            val viewport = checkNotNull(desktop(activity.window.decorView))
            val origin = IntArray(2); viewport.getLocationOnScreen(origin)
            assertTrue("Framebuffer must leave space for controls", origin[1] > 40)
            assertTrue(viewport.height > 0)
            val bitmap = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888)
            activity.window.decorView.draw(Canvas(bitmap))
            var titlePixels = 0
            for (y in 0 until 24) for (x in 0 until 100) {
                val c = bitmap.getPixel(x, y)
                if (Color.red(c) > 140 && Color.green(c) > 140 && Color.blue(c) > 140) titlePixels++
            }
            assertTrue("Desktop title must be readable", titlePixels > 5)
            var gold = 0
            for (y in 0 until origin[1].coerceAtMost(bitmap.height)) for (x in 0 until bitmap.width) {
                val c = bitmap.getPixel(x, y)
                if (Color.red(c) > 100 && Color.green(c) > 60 && Color.red(c) > Color.blue(c) * 1.2) gold++
            }
            val evidence = java.io.File("build/reports/desktop-f1-composition.png")
            evidence.parentFile?.mkdirs()
            evidence.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            assertTrue("Production Desktop toolbar is composed but not visibly painted", gold > 10)
            activity.onBackPressedDispatcher.onBackPressed(); frame()
            assertFalse(shown.value); assertFalse(activity.isFinishing); assertNull(desktop(activity.window.decorView))
            shown.value = true; frame()
            assertNotNull(desktop(activity.window.decorView)); assertFalse(activity.isFinishing)
        } finally { model.hide(); controller.pause().stop().destroy() }
    }
}
