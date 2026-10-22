package dev.myct

import io.github.rosemoe.sora.text.Content
import org.junit.Assert.*
import org.junit.Test

class IdeContentTest {
    @Test fun soraPreservesBomAndOriginalLineEndingsAndSupportsUndoRedo() {
        val original = "\uFEFF// ação 😀\r\nint main() {\n    return 0;\r}\r\n"
        val content = Content(original)
        assertEquals(original, content.toString())
        content.insert(2, 4, "// edit\r\n    ")
        assertNotEquals(original, content.toString())
        assertTrue(content.canUndo())
        content.undo(); assertEquals(original, content.toString())
        content.redo(); assertTrue(content.toString().contains("// edit\r\n"))
    }
    @Test fun lateSaveAcknowledgementCannotClearNewerLocalChanges() {
        val initial = EditSnapshot("/work/test.py", "a\n".toByteArray(), EditMetadata(2, 1, 0x1A4, 1000, 1000))
        val doc = IdeDocument("connection-a", initial)
        doc.content.insert(0, 1, "b")
        val version = doc.content.documentVersion
        val saved = EditSaved(initial.copy(bytes = encodeCode(doc.content.toString())))
        doc.content.insert(0, 2, "c") // Queued IME after snapshot, before save response.
        doc.confirmSaved(saved, version)
        assertTrue(doc.dirty)
        assertEquals("abc\n", doc.content.toString())
        assertEquals("ab\n", doc.snapshot.bytes.toString(Charsets.UTF_8))
        doc.confirmSaved(EditSaved(initial.copy(bytes = encodeCode(doc.content.toString()))), doc.content.documentVersion)
        assertFalse(doc.dirty)
    }
    @Test fun visualStateChangesKeepSoraContentSelectionVersionDirtyAndUndo() {
        val doc = IdeDocument("same-host", EditSnapshot("/work/a.c", "int x;\n".toByteArray(), EditMetadata(7, 1, 0x1A4, 1000, 1000)))
        doc.content.insert(0, 5, "y")
        doc.content.cursor.setLeft(0, 1); doc.content.cursor.setRight(0, 3)
        val version = doc.content.documentVersion
        val content = doc.content
        repeat(5) { doc.viewport = IdeViewport(0, 200); doc.viewport = doc.viewport!!.bounded(300, 400) }
        assertSame(content, doc.content); assertEquals(version, doc.content.documentVersion)
        assertEquals(1, doc.content.cursor.leftColumn); assertEquals(3, doc.content.cursor.rightColumn)
        assertTrue(doc.dirty); assertTrue(doc.content.canUndo())
        doc.content.undo(); assertEquals("int x;\n", doc.content.toString())
    }

}
