package dev.myct

import android.app.Application
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Actual BaseInputConnection/Editable in Android runtime, with RFB key events recorded by a fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DesktopKeyboardRegressionTest {
    private fun flush(h: DesktopInteractionRegressionTest.Harness) {
        h.events.removeAll { it.startsWith("k:65481:") }
        h.model.press(65481); h.await { h.events.contains("k:65481:false") }
    }
    private fun document(h: DesktopInteractionRegressionTest.Harness): String {
        val value = StringBuilder()
        h.events.filter { it.startsWith("k:") && it.endsWith(":true") }.forEach {
            val key = it.split(':')[1].toInt()
            when {
                key == DesktopKeys.BACKSPACE -> if (value.isNotEmpty()) value.delete(value.offsetByCodePoints(value.length, -1), value.length)
                key in 0x20..0xff -> value.appendCodePoint(key)
                key and 0xff000000.toInt() == 0x01000000 -> value.appendCodePoint(key and 0xffffff)
            }
        }
        return value.toString()
    }
    @Test fun portuguesePreeditCommitAndFinishProduceTextOnce() = DesktopInteractionRegressionTest.Harness().use { h ->
        val info = EditorInfo(); val ic = h.view.onCreateInputConnection(info)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        val words = listOf("ação", "coração", "João", "café", "português", "maçã", "Olá, mundo!")
        words.forEach { word ->
            assertTrue(ic.setComposingText(word.take(1), 1)); assertTrue(ic.setComposingText(word, 1))
            assertTrue(ic.commitText(word, 1)); assertTrue(ic.finishComposingText()); assertTrue(ic.commitText(" ", 1))
        }
        flush(h); assertEquals(words.joinToString(" ", postfix = " "), document(h))
        assertEquals(words.joinToString(" ", postfix = " "), ic.getTextBeforeCursor(2048, 0).toString())
    }
    @Test fun suffixAutocorrectionAndNfcUseOnlyNecessaryErases() = DesktopInteractionRegressionTest.Harness().use { h ->
        val ic = h.view.onCreateInputConnection(EditorInfo())
        ic.commitText("coracao", 1); flush(h)
        val count = h.events.size
        assertTrue(ic.setComposingRegion(0, 7)); assertTrue(ic.setComposingText("coração", 1))
        assertEquals(count, h.events.size)
        ic.commitText("coração", 1); ic.finishComposingText(); flush(h)
        assertEquals("coração", document(h)); assertEquals(3, h.events.count { it == "k:65288:true" })
        ic.commitText(" cafe\u0301", 1); flush(h)
        assertEquals("coração café", document(h)); assertTrue(h.events.contains("k:233:true"))
    }
    @Test fun deletionUsesUtf16OrCodePointsWithoutCorruptingSurrogates() = DesktopInteractionRegressionTest.Harness().use { h ->
        val ic = h.view.onCreateInputConnection(EditorInfo())
        ic.commitText("A😀B", 1); flush(h)
        assertFalse(ic.deleteSurroundingText(2, 0)) // Would split the emoji pair.
        assertTrue(ic.deleteSurroundingTextInCodePoints(1, 0)); flush(h); assertEquals("A😀", document(h))
        assertTrue(ic.deleteSurroundingText(2, 0)); flush(h); assertEquals("A", document(h))
        assertTrue(ic.deleteSurroundingText(1, 0)); flush(h); assertEquals("", document(h))
        val before = h.events.count { it == "k:65288:true" }
        ic.deleteSurroundingText(Int.MAX_VALUE, 0); flush(h)
        assertEquals(before + 1, h.events.count { it == "k:65288:true" })
        assertFalse(ic.commitText("\uD83D", 1))
    }
    @Test fun resizePreservesPreeditButFocusAndSessionLossInvalidateIt() = DesktopInteractionRegressionTest.Harness().use { h ->
        val ic = h.view.onCreateInputConnection(EditorInfo())
        ic.setComposingText("maçã", 1)
        h.view.layout(0, 0, 320, 120) // IME resize: no session change and no local preedit loss.
        assertEquals(DesktopLink.CONNECTED, h.model.link)
        assertTrue(ic.commitText("maçã", 1)); flush(h); assertEquals("maçã", document(h))
        h.view.cancelInput(); assertFalse(ic.commitText("resíduo", 1))
        val fresh = h.view.onCreateInputConnection(EditorInfo()); assertEquals("", fresh.getTextBeforeCursor(32, 0).toString())
        h.model.detachSurface(h.image, true); assertFalse(fresh.commitText("resíduo", 1))
        h.model.show({ h.image.update(it, h.model.attempt) }, h.image::clear, h.image)
        val last = h.view.onCreateInputConnection(EditorInfo()); h.model.disconnect(); assertFalse(last.commitText("resíduo", 1))
    }
    @Test fun batchEditsCommitAtomicallyAndClosedEditorsCannotSendResidualText() = DesktopInteractionRegressionTest.Harness().use { h ->
        val ic = h.view.onCreateInputConnection(EditorInfo())
        ic.beginBatchEdit(); ic.commitText("Joao", 1); ic.setComposingRegion(0, 4); ic.setComposingText("João", 1)
        ic.commitText("João", 1)
        flush(h); assertEquals("", document(h))
        ic.endBatchEdit(); flush(h); assertEquals("João", document(h))
        ic.closeConnection(); assertFalse(ic.commitText("resíduo", 1)); flush(h); assertEquals("João", document(h))
    }
    @Test fun normalImeClosureFinishesPreeditButInvalidationDiscardsIt() = DesktopInteractionRegressionTest.Harness().use { h ->
        val first = h.view.onCreateInputConnection(EditorInfo())
        first.setComposingText("café", 1); first.closeConnection(); flush(h)
        assertEquals("café", document(h)); assertFalse(first.commitText("resíduo", 1))
        val next = h.view.onCreateInputConnection(EditorInfo())
        next.setComposingText("maçã", 1); h.view.cancelInput(); next.closeConnection(); flush(h)
        assertEquals("café", document(h))
    }
    @Test fun controlAltShiftSpecialKeysAndAndroidBackRemainSeparate() = DesktopInteractionRegressionTest.Harness().use { h ->
        val ic = h.view.onCreateInputConnection(EditorInfo())
        h.model.toggleModifier(DesktopKeys.CTRL)
        assertTrue(h.model.ctrl)
        listOf("a", "c", "v").forEach { assertTrue("Shortcut commit $it", ic.commitText(it, 1)); assertTrue("Ctrl lost after $it", h.model.ctrl) }
        h.model.toggleModifier(DesktopKeys.CTRL); h.model.toggleModifier(DesktopKeys.ALT); h.view.specialKey(DesktopKeys.TAB)
        h.model.toggleModifier(DesktopKeys.ALT); h.model.toggleModifier(DesktopKeys.SHIFT); h.view.specialKey(DesktopKeys.ENTER)
        h.model.toggleModifier(DesktopKeys.SHIFT); h.view.specialKey(DesktopKeys.ESC)
        flush(h)
        val keyEvents = h.events.filter { it.startsWith("k:") }
        val pressed = mutableSetOf<Int>()
        keyEvents.forEach { event ->
            val fields = event.split(':'); val key = fields[1].toInt()
            if (fields[2] == "true") assertTrue("Duplicate press $event", pressed.add(key)) else pressed.remove(key)
        }
        assertTrue("Keys remain pressed: $pressed", pressed.isEmpty())
        // releaseInput may emit idempotent modifier releases on focus changes;
        // require each actual shortcut's exact balanced chord, not matching totals.
        fun chord(mod: Int, key: Int) {
            val expected = listOf("k:$mod:true", "k:$key:true", "k:$key:false", "k:$mod:false")
            assertTrue("Missing chord $expected in $keyEvents", keyEvents.windowed(4).any { it == expected })
        }
        listOf('a'.code, 'c'.code, 'v'.code).forEach { chord(DesktopKeys.CTRL, it) }
        chord(DesktopKeys.ALT, DesktopKeys.TAB); chord(DesktopKeys.SHIFT, DesktopKeys.ENTER)
        listOf('a'.code, 'c'.code, 'v'.code, DesktopKeys.TAB, DesktopKeys.ENTER, DesktopKeys.ESC).forEach { key ->
            assertTrue(h.events.contains("k:$key:true")); assertTrue(h.events.contains("k:$key:false"))
        }
        assertFalse(h.view.remoteKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        assertFalse(h.view.remoteKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
    }
}
