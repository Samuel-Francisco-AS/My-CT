package dev.myct

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Must run on Android explicitly. Building this test APK does not execute it. */
class OfflineCInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val worker get() = File(context.applicationInfo.nativeLibraryDir, "libmyct-c-worker.so")
    private fun fixture(test: (File) -> Unit) {
        val dir = File(context.cacheDir, "l1c-test-${UUID.randomUUID()}"); check(dir.mkdir())
        try { assertTrue("APK-installed worker missing", worker.canExecute()); test(dir) }
        finally { dir.deleteRecursively() } // only this test's generated fixture
    }
    private fun compile(dir: File, source: String): Pair<Int, String> {
        File(dir, "programa.c").writeText(source)
        return command(dir, "compile", "programa.c", "programa.wasm")
    }
    private fun assertCompiled(dir: File, source: String) {
        val (code, output) = compile(dir, source)
        assertEquals(output, 0, code)
    }
    private fun command(dir: File, vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(listOf(worker.absolutePath) + args).directory(dir).redirectErrorStream(true).start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            p.outputStream.close()
            val output = reader.submit<String> { p.inputStream.bufferedReader().use { it.readText() } }
            assertTrue("Worker timed out", p.waitFor(30, TimeUnit.SECONDS))
            val text = output.get(5, TimeUnit.SECONDS)
            android.util.Log.i("MyCtCProbe", "worker ${args.first()} exit=${p.exitValue()}\n" +
                text.lineSequence().filter { it.startsWith("[myct-worker:v2]") }.joinToString("\n"))
            return p.exitValue() to text
        } finally { p.destroyForcibly(); reader.shutdownNow() }
    }
    @Test fun motoHelloThroughSavedWorkspaceAndCapture() = fixture { dir ->
        val code = "#include <stdio.h>\n\nint main(void) {\n    puts(\"My-CT compilando offline!\");\n    return 0;\n}\n"
        val workspace = LocalWorkspace(File(dir, "ide-local"))
        workspace.create("", "teste", true); workspace.create("teste", "teste.c", false)
        val saved = workspace.save(workspace.load("teste/teste.c"), code.toByteArray())
        val captured = captureC(workspace, saved, File(dir,"compilacoes"))
        val (compiled, diagnostic) = command(captured,"compile","programa.c","programa.wasm")
        assertEquals(diagnostic, 0, compiled)
        val (exit, output) = command(captured,"run","programa.wasm")
        assertEquals(output, 0, exit); assertTrue(output.contains("My-CT compilando offline!"))
        assertArrayEquals(saved.bytes, workspace.load(saved.path).bytes)
    }
    @Test fun installedWorkerActuallyCompilesCAndRunsWasmOnAndroid() = fixture { dir ->
        val source = "#include <stdio.h>\nint twice(int x) { return x*2; }\nint main(void) { int n=3; for(int i=0;i<2;++i) { if(n>0) printf(\"valor=%d\\n\",twice(n)); else puts(\"erro\"); } return 7; }"
        assertCompiled(dir, source)
        val wasm = File(dir, "programa.wasm").readBytes()
        assertArrayEquals(byteArrayOf(0,97,115,109,1,0,0,0), wasm.take(8).toByteArray())
        val (code, output) = command(dir, "run", "programa.wasm")
        assertEquals(output, 7, code); assertTrue(output.contains("valor=6"))
        assertEquals(source, File(dir, "programa.c").readText())
        assertNotEquals(0, compile(dir, "int main(void) { return }").first)
        assertNotEquals(0, compile(dir, "").first)
        assertCompiled(dir, "int main(void) { return 22; }")
        assertEquals(22, command(dir, "run", "programa.wasm").first)
    }
    @Test fun interactiveScanfPromptArrivesBeforeInput() = fixture { dir ->
        assertCompiled(dir, "#include <stdio.h>\nint main(void) { int n; printf(\"Numero: \"); if(scanf(\"%d\",&n)!=1) return 2; printf(\"dobro=%d\\n\",n*2); return 0; }")
        val p = ProcessBuilder(worker.absolutePath, "run", "programa.wasm").directory(dir).redirectErrorStream(true).start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val prompt = reader.submit<String> {
                val bytes = ByteArray(8); var size = 0
                while(size < bytes.size) { val n=p.inputStream.read(bytes,size,bytes.size-size); check(n>0); size+=n }
                String(bytes, Charsets.UTF_8)
            }
            assertEquals("Numero: ", prompt.get(10, TimeUnit.SECONDS))
            p.outputStream.write("21\n".toByteArray()); p.outputStream.flush()
            val output = reader.submit<String> { p.inputStream.bufferedReader().use { it.readText() } }
            assertTrue(p.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, p.exitValue())
            assertTrue(output.get(3, TimeUnit.SECONDS).contains("dobro=42"))
        } finally { p.destroyForcibly(); p.outputStream.close(); reader.shutdownNow() }
    }
    @Test fun endlessWasmIsKillableWithoutTouchingSource() = fixture { dir ->
        val source = "int main(void) { while(1) {} return 0; }"
        assertCompiled(dir, source)
        val p = ProcessBuilder(worker.absolutePath, "run", "programa.wasm").directory(dir).start()
        try {
            assertFalse(p.waitFor(100, TimeUnit.MILLISECONDS))
            p.destroyForcibly(); assertTrue(p.waitFor(3, TimeUnit.SECONDS))
            assertEquals(source, File(dir, "programa.c").readText())
        } finally { p.destroyForcibly(); p.outputStream.close(); p.inputStream.close(); p.errorStream.close() }
        assertCompiled(dir, "int main(void) { return 0; }")
        assertEquals(0, command(dir, "run", "programa.wasm").first)
    }
}
