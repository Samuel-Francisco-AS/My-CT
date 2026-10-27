package dev.myct

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DesktopCanvasRegressionTest {
    @Test fun viewportMustNotPaintTheComposeToolbarOrOtherContent() {
        val image = DesktopImage()
        val view = DesktopView(RuntimeEnvironment.getApplication(), DesktopModel(), image)
        view.layout(0, 0, 320, 200)
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.MAGENTA) // Sentinel: standing in for the Compose controls above it.
        canvas.save(); canvas.translate(40f, 100f); view.draw(canvas); canvas.restore()
        try {
            assertEquals("Desktop overpainted the toolbar before any VNC connection", Color.MAGENTA, bitmap.getPixel(100, 30))
            assertEquals(Color.MAGENTA, bitmap.getPixel(10, 150))
            assertEquals(Color.MAGENTA, bitmap.getPixel(100, 350))
            assertEquals(Color.BLACK, bitmap.getPixel(100, 150))
        } finally { image.close(); bitmap.recycle() }
    }
    @Test fun actualFramebufferIsFittedAndClippedWhileTheSurroundingControlsSurvive() {
        val image = DesktopImage()
        val view = DesktopView(RuntimeEnvironment.getApplication(), DesktopModel(), image)
        view.layout(0, 0, 320, 200)
        val frame = RfbFramebuffer(64, 40)
        frame.fill(0, 0, 64, 40, 0xff123456.toInt())
        image.update(frame)
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.MAGENTA)
        canvas.save(); canvas.translate(40f, 100f); view.draw(canvas); canvas.restore()
        try {
            assertEquals(Color.MAGENTA, bitmap.getPixel(100, 30))
            assertEquals(0xff123456.toInt(), bitmap.getPixel(200, 200))
            image.clear()
            canvas.save(); canvas.translate(40f, 100f); view.draw(canvas); canvas.restore()
            assertEquals(Color.BLACK, bitmap.getPixel(200, 200))
            assertEquals(Color.MAGENTA, bitmap.getPixel(100, 30))
        } finally { image.close(); bitmap.recycle() }
    }

    @Test fun zoomPanResizeAndFitKeepDrawAndInputTransformsIdentical() {
        val image = DesktopImage()
        val view = DesktopView(RuntimeEnvironment.getApplication(), DesktopModel(), image)
        val frame = RfbFramebuffer(1280, 720).apply { fill(0, 0, 1280, 720, Color.BLUE) }
        image.update(frame)
        try {
            listOf(360 to 700, 800 to 280, 800 to 140, 280 to 600).forEach { (w, h) ->
                view.layout(0, 0, w, h)
                view.resetZoom()
                val fit = view.viewport()
                assertEquals(minOf(w / 1280f, h / 720f), fit.scale, .0001f)
                assertEquals(640 to 360, fit.remote(w / 2f, h / 2f))
                view.zoomBy(4f); view.panBy(100f, -100f)
                val vp = view.viewport()
                val expectedX = 600f; val expectedY = 350f
                val x = vp.left + expectedX * vp.scale; val y = vp.top + expectedY * vp.scale
                assertEquals(600 to 350, vp.remote(x, y, clamp = true))
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val sampleX = (w / 2).toFloat(); val sampleY = (h / 2).toFloat()
                assertNotNull(view.viewport().remote(sampleX, sampleY))
                assertEquals(Color.BLUE, bitmap.getPixel(sampleX.toInt(), sampleY.toInt()))
                bitmap.recycle()
                view.panBy(-100f, 100f)
                view.resetZoom()
                assertEquals(fit, view.viewport())
            }
        } finally { view.release() }
    }

}
