package dev.myct

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Run on Android explicitly; compilation alone is never recorded as a device pass. */
class LocalPtyInstrumentedTest {
    private class Harness(directory: String) : AutoCloseable {
        val child = AndroidPty(directory) // Also verifies System.loadLibrary + JNI symbols on this ABI.
        val executor = Executors.newSingleThreadExecutor()
        private val output = StringBuffer()
        private val reader = executor.submit {
            val bytes = ByteArray(8192)
            while (true) {
                val count = child.read(bytes)
                if (count < 0) break
                output.append(String(bytes, 0, count, Charsets.UTF_8))
            }
        }
        fun send(text: String) { val bytes = text.toByteArray(); child.write(bytes, bytes.size) }
        fun expect(text: String) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!output.contains(text) && System.nanoTime() < end) Thread.sleep(10)
            assertTrue("Expected PTY output marker: $text", output.contains(text))
        }
        override fun close() {
            child.stop()
            try { reader.get(3, TimeUnit.SECONDS) } finally { executor.shutdownNow(); child.close() }
        }
    }
    private fun directory(): File = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "l1b-test-${System.nanoTime()}").apply { check(mkdir()) }

    @Test fun androidShellRealTtyWorkspacePipesResizeInputAndCtrlC() {
        val root = directory()
        try {
            File(root, "ProjetoTeste").mkdir()
            File(root, "ProjetoTeste/teste.c").writeText("ANDROID_IDE_FILE\n")
            Harness(root.absolutePath).use { t ->
                t.send("test -t 0 && test -t 1 && test -t 2 && echo REAL_ANDROID_TTY; pwd; echo \$HOME; echo \$TERM; echo \$PATH\n")
                t.expect("REAL_ANDROID_TTY\r\n"); t.expect(root.absolutePath + "\r\n")
                t.expect("xterm-256color\r\n"); t.expect("/system/bin:/system/xbin\r\n")
                t.send("ls; cd ProjetoTeste; cat teste.c; echo ANDROID_PIPE | cat\n")
                t.expect("ANDROID_IDE_FILE\r\n"); t.expect("ANDROID_PIPE\r\n")
                t.child.resize(49, 15, 392, 240)
                t.send("stty size\n"); t.expect("15 49\r\n")
                t.send("echo READ_READY; read value; echo READ_\$value\n"); t.expect("READ_READY\r\n")
                t.send("myct\n"); t.expect("READ_myct\r\n")
                t.send("echo SLEEP_READY; sleep 20\n"); t.expect("SLEEP_READY\r\n")
                Thread.sleep(150); t.send("\u0003"); Thread.sleep(100)
                t.send("echo CTRL_C_\$?\n"); t.expect("CTRL_C_130\r\n")
                t.send("exit 7\n"); assertEquals(7, t.child.waitForExit())
            }
        } finally { root.deleteRecursively() } // Only this test's private cache fixture.
    }
    @Test fun independentPtysExplicitCloseBlockedReadAndRestart() {
        val root = directory()
        try {
            Harness(root.absolutePath).use { survivor ->
                repeat(3) {
                    Harness(root.absolutePath).use { local ->
                        local.send("echo LOCAL_SESSION\n"); local.expect("LOCAL_SESSION\r\n")
                        local.child.stop(); assertEquals(137, local.child.waitForExit())
                    }
                    survivor.send("echo SURVIVED_$it\n"); survivor.expect("SURVIVED_$it\r\n")
                }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun processSetupErrorsDoNotLeakDescriptors() {
        val root = directory()
        try {
            // Warm the runtime/library before counting FDs.
            Harness(root.absolutePath).use { it.child.stop(); it.child.waitForExit() }
            val before = File("/proc/self/fd").list()!!.size
            repeat(8) {
                try { AndroidPty(File(root, "missing").absolutePath).close(); fail("Expected chdir failure") }
                catch (_: IOException) { /* JNI reports child setup failure. */ }
            }
            assertEquals(before, File("/proc/self/fd").list()!!.size)
        } finally { root.deleteRecursively() }
    }
}
