package dev.myct

import org.junit.Assert.assertEquals
import org.junit.Test

class FilesScrollAnchorTest {
    private fun entries(vararg names: String): List<RemoteEntry> =
        names.map { RemoteEntry(it, RemoteKind.FILE, 0, 0) }

    @Test fun returnsToNamedItemAfterOrderingChanges() {
        val previous = FilesScrollAnchor("arquivo-c", 2, 18)
        assertEquals(0, previous.restoredIndex(entries("arquivo-c", "arquivo-a", "arquivo-b")))
        assertEquals(18, previous.offset)
    }

    @Test fun missingItemUsesClampedIndex() {
        val previous = FilesScrollAnchor("removido", 99, 0)
        assertEquals(1, previous.restoredIndex(entries("primeiro", "ultimo")))
        assertEquals(0, previous.restoredIndex(emptyList()))
    }

    @Test fun dotFilesHiddenUntilEnabled() {
        val source = entries(".config", "documento.txt", ".ssh", "anotacoes.md")
        assertEquals(listOf("documento.txt", "anotacoes.md"),
            visibleSftpEntries(source, false).map { it.name })
        assertEquals(source.map { it.name }, visibleSftpEntries(source, true).map { it.name })
    }

    @Test fun restoreNamedAnchorWhenSwitchingHiddenVisibility() {
        val source = entries(".git", "aaa", ".local", "bbb", "ccc")
        val anchor = FilesScrollAnchor("bbb", 3, 12)
        assertEquals(1, anchor.restoredIndex(visibleSftpEntries(source, false)))
        assertEquals(3, anchor.restoredIndex(visibleSftpEntries(source, true)))
    }

    @Test fun newDirectoryStartsAtBeginning() {
        val newFolder = FilesScrollAnchor(null, 0, 0)
        assertEquals(0, newFolder.restoredIndex(entries("um", "dois")))
    }
}
