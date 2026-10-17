package dev.myct

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verify the actual shared resource palette, without Android rendering or a new test dependency. */
class ThemeContrastTest {
    private val colors = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/res/values/colors.xml")).getElementsByTagName("color").let { nodes ->
            (0 until nodes.length).associate { i ->
                val node = nodes.item(i)
                node.attributes.getNamedItem("name").nodeValue.removePrefix("myct_") to
                    node.textContent.trim().removePrefix("#").toLong(16)
            }
        }

    private fun luminance(name: String): Double {
        val rgb = colors.getValue(name)
        val linear = listOf(16, 8, 0).map { shift ->
            val value = ((rgb shr shift) and 255).toDouble() / 255
            if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722
    }
    private fun checkContrast(text: String, background: String) {
        val a = luminance(text); val b = luminance(background)
        val ratio = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
        assertTrue("$text on $background: $ratio (minimum 4.5:1)", ratio >= 4.5)
    }

    @Test fun bodyAndMetadataRemainReadableOnEverySurface() {
        listOf("background", "surface", "surface_elevated", "surface_raised").forEach { surface ->
            listOf("text", "text_secondary", "error", "success", "warning").forEach { checkContrast(it, surface) }
        }
    }
    @Test fun actionAndSelectionLabelsRemainReadable() {
        checkContrast("on_gold", "gold")
        checkContrast("on_gold", "gold_secondary")
        checkContrast("gold", "gold_container")
        checkContrast("gold_secondary", "gold_container")
        checkContrast("text", "error_container")
        checkContrast("success", "success_container")
    }
}
