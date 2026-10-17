package dev.myct

import org.junit.Assert.*
import org.junit.Test

class IdeScrollHistoryTest {
    private fun entries(vararg names: String) = names.map { RemoteEntry(it, RemoteKind.FILE, 0, 0) }
    @Test fun parentRestoresNamedAnchorAndOffsetBeforeDrawingAfterSubdirectoryVisit() {
        val history = IdeScrollHistory()
        history.remember("/project", entries("a", "b", "sub", "c"), 2, 19)
        history.remember("/project/sub", entries("x", "y"), 1, 7)
        assertEquals(0, history.position("/project")!!.restoredIndex(entries("sub", "a", "b", "c")))
        assertEquals(19, history.position("/project")!!.offset)
        assertEquals(1, history.position("/project/sub")!!.index)
        assertNull(history.position("/other"))
    }
    @Test fun missingNamesClampAndEachBrowserHasIndependentBoundedHistory() {
        val history = IdeScrollHistory(); val another = IdeScrollHistory()
        history.remember("/project", entries("a", "b"), 1, 8)
        assertEquals(0, history.position("/project")!!.restoredIndex(entries("only")))
        assertEquals(0, history.position("/project")!!.restoredIndex(emptyList()))
        assertNull(another.position("/project"))
        repeat(64) { history.remember("/p$it", emptyList(), 0, 0) }
        assertNull(history.position("/project"))
        history.clear(); assertNull(history.position("/p63"))
    }
}
