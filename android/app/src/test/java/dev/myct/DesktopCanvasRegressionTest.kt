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

}
