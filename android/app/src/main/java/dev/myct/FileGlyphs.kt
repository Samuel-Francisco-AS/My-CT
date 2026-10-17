package dev.myct

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

internal object FileGlyphs {
    private fun glyph(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()
    private val Folder = glyph("Pasta", "M3,7 L3,20 L21,20 L21,7 L12,7 L10,4 L3,4 Z")
    private val File = glyph("Arquivo", "M5,3 L14,3 L20,9 L20,21 L5,21 Z M14,3 L14,9 L20,9 M8,13 L16,13 M8,17 L16,17")
    private val Link = glyph("Link", "M9,15 L15,9 M8,10 L5,13 C1,17 7,23 11,19 L14,16 M10,8 L13,5 C17,1 23,7 19,11 L16,14")
    private val Other = glyph("Outro", "M12,3 L21,12 L12,21 L3,12 Z M12,8 L12,13 M12,17 L12,17")
    val Parent = glyph("Subir", "M12,20 L12,4 M6,10 L12,4 L18,10")
    fun forKind(kind: RemoteKind) = when (kind) {
        RemoteKind.DIRECTORY -> Folder; RemoteKind.FILE -> File; RemoteKind.LINK -> Link; RemoteKind.OTHER -> Other
    }
}
