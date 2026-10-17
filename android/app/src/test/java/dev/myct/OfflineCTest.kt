package dev.myct

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors

class OfflineCTest {
    @get:Rule val temp = TemporaryFolder()
    private fun workspace() = LocalWorkspace(File(temp.root, "local"))
    private fun doc(code: String, path: String = "teste.c"): IdeDocument {
        val w = workspace(); w.create("", path, false)
        return IdeDocument(LocalIdeModel.LOCAL_ID, w.save(w.load(path), code.toByteArray()))
    }
    private fun worker(): File {
        val value = System.getenv("MYCT_TEST_OFFLINE_C")
        assumeTrue("Requires real host worker; see offline-c/README.md", value != null)
        return File(value!!).absoluteFile.also { assertTrue(it.canExecute()) }
    }
    private fun model(worker: File, dispatcher: CoroutineDispatcher = Dispatchers.Unconfined, runTimeout: Long = 60_000) =
        OfflineCModel(workspace(), File(temp.root, "artifacts"), worker, dispatcher, Dispatchers.IO, 30_000, runTimeout)
    private suspend fun awaitDone(m: OfflineCModel) = withTimeout(10_000) { while (m.active) delay(5) }
    private suspend fun awaitInput(m: OfflineCModel) = withTimeout(10_000) { while (!m.acceptsInput) { assertTrue(m.active); delay(5) } }

    @Test fun actionOnlyForLocalCAndKnownIdentity() {
        val c = doc("int main(void) { return 0; }")
        assertTrue(canCompileC(IdeEnvironment.LOCAL, c))
        assertFalse(canCompileC(IdeEnvironment.REMOTE, c))
        assertFalse(canCompileC(IdeEnvironment.LOCAL, null))
        val cpp = IdeDocument(LocalIdeModel.LOCAL_ID, c.snapshot.copy(path = "teste.cpp"))
        assertFalse(canCompileC(IdeEnvironment.LOCAL, cpp))
        assertFalse(canCompileC(IdeEnvironment.LOCAL, IdeDocument("fedora", c.snapshot)))
        c.uncertain = true; assertFalse(canCompileC(IdeEnvironment.LOCAL, c))
    }
    @Test fun captureMatchesSavedBytesPreservesProjectAndIdentifiesOrigin() {
        val d = doc("int main(void) { return 4; }")
        val artifacts = File(temp.root, "artifacts")
        val a = captureC(workspace(), d.snapshot, artifacts)
        val b = captureC(workspace(), d.snapshot, artifacts)
        assertNotEquals(a, b)
        assertArrayEquals(d.snapshot.bytes, File(a, "programa.c").readBytes())
        assertTrue(File(a, "origem.txt").readText().contains(cSha(d.snapshot.bytes)))
        assertEquals(listOf("teste.c"), workspace().list().entries.map { it.name })
        assertArrayEquals(d.snapshot.bytes, workspace().load(d.path).bytes)
    }
    @Test fun changedSourceRefusesStaleSnapshotBeforeCreatingArtifact() {
        val d = doc("int main(void) { return 0; }")
        workspace().save(d.snapshot, "int main(void) { return 8; }".toByteArray())
        val artifacts = File(temp.root, "artifacts")
        assertThrows(EditConflict::class.java) { captureC(workspace(), d.snapshot, artifacts) }
        assertFalse(artifacts.exists())
    }
    @Test fun dirtySourceNeverStartsCompilerAndRemoteNeverCreatesArtifacts() {
        val d = doc("int main(void) { return 0; }")
        val m = model(File("/does-not-exist"))
        m.start(IdeEnvironment.REMOTE, d); assertEquals(CPhase.IDLE, m.phase)
        d.content.insert(0, 0, "// unsaved\n")
        m.start(IdeEnvironment.LOCAL, d)
        assertEquals(CPhase.FAILED, m.phase); assertTrue(m.output.contains("Salve")); assertFalse(m.active)
        assertFalse(File(temp.root, "artifacts").exists()); assertTrue(d.dirty); m.close()
    }
    @Test fun realCompilerLifecycleRecompileAndSyntaxFailureNeverRunsOldModule() {
        val w = worker()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher -> runBlocking(dispatcher) {
            val d = doc("#include <stdio.h>\nint main(void) { puts(\"primeiro\"); return 7; }")
            val m = model(w, dispatcher)
            try {
                m.start(IdeEnvironment.LOCAL, d); awaitDone(m)
                assertEquals(CPhase.FINISHED, m.phase); assertEquals(7, m.exitCode); assertTrue(m.output.contains("primeiro"))
                val first = m.artifact!!
                d.snapshot = workspace().save(d.snapshot, "int main(void) { return 9; }".toByteArray())
                m.start(IdeEnvironment.LOCAL, d); awaitDone(m)
                assertEquals(9, m.exitCode); assertNotEquals(first, m.artifact)
                d.snapshot = workspace().save(d.snapshot, "int main(void) { return }".toByteArray())
                m.start(IdeEnvironment.LOCAL, d); awaitDone(m)
                assertEquals(CPhase.COMPILE_ERROR, m.phase); assertNull(m.artifact)
                assertFalse(m.output.contains("executando pelo WAMR")); assertTrue(File(first).isFile)
            } finally { m.close() }
        } }
    }
    @Test fun realConsoleInputEofCancellationAndTimeout() {
        val w = worker()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher -> runBlocking(dispatcher) {
            val d = doc("#include <stdio.h>\nint main(void) { int n; printf(\"Numero: \"); if (scanf(\"%d\",&n)!=1) return 2; printf(\"dobro=%d\\n\",n*2); return 0; }")
            val m = model(w, dispatcher, runTimeout = 1000)
            try {
                m.start(IdeEnvironment.LOCAL, d); awaitInput(m)
                withTimeout(2000) { while (!m.output.contains("Numero: ")) delay(5) }
                m.send("21"); awaitDone(m); assertEquals(0, m.exitCode); assertTrue(m.output.contains("dobro=42"))
                m.start(IdeEnvironment.LOCAL, d); awaitInput(m); m.endInput(); awaitDone(m); assertEquals(2, m.exitCode)
                d.snapshot = workspace().save(d.snapshot, "int main(void) { while(1) {} return 0; }".toByteArray())
                m.start(IdeEnvironment.LOCAL, d); awaitInput(m); m.cancel(); awaitDone(m)
                assertEquals(CPhase.CANCELLED, m.phase)
                m.start(IdeEnvironment.LOCAL, d); awaitDone(m); assertEquals(CPhase.LIMIT, m.phase)
                assertArrayEquals(d.snapshot.bytes, workspace().load(d.path).bytes)
            } finally { m.close() }
        } }
    }
    @Test fun realRuntimeTrapAndOutputLimitLeaveControllerUsable() {
        val w = worker()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher -> runBlocking(dispatcher) {
            val d = doc("int main(void) { int *p=(int*)0; return p[99999999]; }")
            val m = model(w, dispatcher)
            try {
                m.start(IdeEnvironment.LOCAL, d); awaitDone(m); assertEquals(CPhase.FAILED, m.phase)
                d.snapshot = workspace().save(d.snapshot, "#include <stdio.h>\nint main(void) { while(1) puts(\"saida\"); return 0; }".toByteArray())
                m.start(IdeEnvironment.LOCAL, d); awaitDone(m); assertEquals(CPhase.LIMIT, m.phase)
                assertTrue(m.output.length < OfflineCModel.MAX_OUTPUT + 100)
                d.snapshot = workspace().save(d.snapshot, "int main(void) { return 125; }".toByteArray())
                m.start(IdeEnvironment.LOCAL, d); awaitDone(m); assertEquals(CPhase.FINISHED, m.phase); assertEquals(125, m.exitCode)
            } finally { m.close() }
        } }
    }
}
