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
        val saved = EditSaved(initial.copy(bytes = encodeCode(doc.content.toString())), "/work/backup")
        doc.content.insert(0, 2, "c") // Queued IME after snapshot, before save response.
        doc.confirmSaved(saved, version)
        assertTrue(doc.dirty)
        assertEquals("abc\n", doc.content.toString())
        assertEquals("ab\n", doc.snapshot.bytes.toString(Charsets.UTF_8))
        doc.confirmSaved(EditSaved(initial.copy(bytes = encodeCode(doc.content.toString())), "/work/backup2"), doc.content.documentVersion)
        assertFalse(doc.dirty)
    }
}
