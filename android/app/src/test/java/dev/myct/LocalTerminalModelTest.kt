package dev.myct

import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class LocalTerminalModelTest {
    @get:Rule val temp = TemporaryFolder()
    private class MainQueue : CoroutineDispatcher() {
        val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun until(done: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!done()) {
                assertTrue("Timed out waiting for model", System.nanoTime() < deadline)
                tasks.poll(20, TimeUnit.MILLISECONDS)?.run()
            }
        }
        fun drain() { while (true) (tasks.poll() ?: break).run() }
    }
    private class Child : PtyProcess {
        val output = LinkedBlockingQueue<ByteArray>()
        val input = ByteArrayOutputStream()
        val exit = CountDownLatch(1)
        @Volatile var stopped = false
        @Volatile var closed = false
        @Volatile var status = 0
        @Volatile var failRead = false
        @Volatile var failWrite = false
        @Volatile var size: Pair<Int, Int>? = null
        override fun read(bytes: ByteArray): Int {
            if (failRead) throw IOException("synthetic")
            val next = output.take()
            if (next.isEmpty()) return -1
            next.copyInto(bytes); return next.size
        }
        override fun write(bytes: ByteArray, count: Int) {
            if (failWrite) throw IOException("synthetic")
            synchronized(input) { input.write(bytes, 0, count) }
        }
        override fun resize(columns: Int, rows: Int, width: Int, height: Int) { size = columns to rows }
        override fun waitForExit(): Int { exit.await(); return status }
        override fun stop() { stopped = true; exit.countDown(); output.offer(byteArrayOf()) }
        override fun close() { stop(); closed = true }
        fun end(code: Int) { status = code; output.add(byteArrayOf()); exit.countDown() }
        fun written() = synchronized(input) { input.toByteArray().toString(Charsets.UTF_8) }
    }
    private class Fixture(val model: LocalTerminalModel, val main: MainQueue, val children: MutableList<Child>) {
        fun start(): Child {
            model.start(); main.until { model.phase == LocalTerminalModel.Phase.RUNNING }; return children.last()
        }
        fun stop() {
            model.stop(); main.until { model.phase == LocalTerminalModel.Phase.ENDED }; main.drain()
            assertTrue(children.all { it.closed })
        }
    }
    private fun fixture(open: ((String) -> PtyProcess)? = null): Fixture {
        val main = MainQueue(); val children = mutableListOf<Child>()
        val model = LocalTerminalModel(temp.root, main, Dispatchers.IO, open ?: { directory ->
            assertEquals(java.io.File(temp.root, "projetos").absolutePath, directory)
            Child().also { synchronized(children) { children.add(it) } }
        }, { main.tasks.add(it) })
        return Fixture(model, main, children)
    }
    @Test fun explicitStartDuplicateProtectionCwdInputOutputResizeExitAndRestart() {
        val f = fixture(); assertNull(f.model.terminal); assertTrue(f.children.isEmpty())
        val child = f.start(); val first = f.model.terminal!!
        try {
            f.model.start(); assertEquals(1, f.children.size)
            first.write("echo local\r\u0003"); first.updateSize(42, 17, 8, 16)
            child.output.add("LOCAL_OUTPUT\r\n".toByteArray())
            f.main.until { child.written() == "echo local\r\u0003" && child.size == 42 to 17 && first.emulator.getSelectedText(0, 0, 11, 0) == "LOCAL_OUTPUT" }
            child.end(7); f.main.until { f.model.phase == LocalTerminalModel.Phase.ENDED && child.closed }
            assertEquals(7, f.model.exitStatus); assertFalse(first.isRunning)
            f.start(); assertNotSame(first, f.model.terminal)
            val oldInput = child.written(); first.write("late"); assertEquals(oldInput, child.written())
        } finally { f.stop() }
    }
    @Test fun selectionRemoteConnectionAndIdeReturnKeepSeparateEmulatorsModifiersAndProcesses() {
        val f = fixture(); val child = f.start()
        val selection = TerminalSelection(); val local = f.model.terminal!!; val remoteInput = ByteArrayOutputStream()
        val remote = TerminalSession(object : TerminalSession.Transport {
            override fun send(data: ByteArray) { remoteInput.write(data) }
            override fun resize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) = Unit
        }, SessionCallbacks())
        val remoteModifiers = TerminalModifiers(); var remoteConnected = false
        var selected = local
        fun bind() = selection.bind(selected, if (selected === local) f.model.modifiers else remoteModifiers,
            { selected }, { if (selected === local) f.model.running else remoteConnected })
        try {
            val old = bind(); assertTrue(old.accepts())
            assertFalse(old.accepts(remote))
            f.model.modifiers.toggleCtrl(); assertFalse(remoteModifiers.ctrl)
            selection.select(IdeEnvironment.REMOTE); selected = remote
            assertFalse(old.accepts()); assertFalse(bind().accepts())
            remoteConnected = true; assertTrue(bind().accepts())
            remote.write("REMOTE"); local.write("LOCAL")
            child.output.add("LOCAL_SCREEN".toByteArray()); remote.append("REMOTE_SCREEN".toByteArray())
            f.main.until { child.written() == "LOCAL" && local.emulator.getSelectedText(0, 0, 11, 0) == "LOCAL_SCREEN" }
            assertEquals("REMOTE", remoteInput.toString("UTF-8"))
            assertEquals("REMOTE_SCREEN", remote.emulator.getSelectedText(0, 0, 12, 0))
            selection.select(IdeEnvironment.LOCAL); selected = local
            assertFalse(old.accepts()); assertTrue(bind().accepts()) // returning can't revive stale actions
            // IDE and rotation have no owner teardown hook; returning binds this same session.
            assertSame(local, f.model.terminal); assertFalse(child.stopped)
            f.model.stop(); f.main.until { child.closed && f.model.phase == LocalTerminalModel.Phase.ENDED }
            assertTrue(remote.isRunning); remote.write("_ALIVE")
            assertEquals("REMOTE_ALIVE", remoteInput.toString("UTF-8"))
        } finally { f.stop() }
    }
    @Test fun stopWhileOpeningCleansAcquiredChildAndRejectsNewStartUntilStopped() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val child = Child()
        val f = fixture { entered.countDown(); release.await(); child }
        f.model.start(); f.main.until { entered.count == 0L }
        f.model.stop(); f.model.start(); assertEquals(LocalTerminalModel.Phase.STOPPING, f.model.phase)
        release.countDown(); f.main.until { child.closed && f.model.phase == LocalTerminalModel.Phase.ENDED }
        assertFalse(f.model.terminal!!.isRunning)
    }
    @Test fun processAndLibraryErrorsAreRecoverableWithoutRemoteSideEffects() {
        repeat(2) { kind ->
            val f = fixture { if (kind == 0) throw IOException("synthetic openpty/exec") else throw UnsatisfiedLinkError("synthetic ABI") }
            repeat(2) {
                f.model.start(); f.main.until { f.model.phase == LocalTerminalModel.Phase.ERROR }
                f.main.drain(); assertFalse(f.model.terminal!!.isRunning)
            }
        }
    }
    @Test fun readerAndWriterFailureStopChildAndAllowRestart() {
        repeat(2) { writer ->
            val f = fixture { Child().also { it.failRead = writer == 0; it.failWrite = writer == 1 } }
            f.model.start(); f.main.until { f.model.phase == LocalTerminalModel.Phase.RUNNING }
            if (writer == 1) f.model.terminal!!.write("fail")
            f.main.until { f.model.phase == LocalTerminalModel.Phase.ERROR }
            f.main.drain(); assertFalse(f.model.terminal!!.isRunning)
        }
    }
    @Test fun staleOutputAndResizeCannotReachRestartedSession() {
        val f = fixture(); val child = f.start(); val old = f.model.terminal!!
        child.output.add("OLD_OUTPUT".toByteArray())
        // Deliberately retire while reader/main delivery is pending.
        f.model.stop(); f.main.until { child.closed && f.model.phase == LocalTerminalModel.Phase.ENDED }
        val next = f.start(); val fresh = f.model.terminal!!
        try {
            old.updateSize(66, 11, 8, 16); old.write("wrong\u0003")
            f.main.drain(); assertEquals("", next.written())
            assertEquals("", fresh.emulator.getSelectedText(0, 0, 20, 0))
            fresh.updateSize(55, 12, 8, 16); fresh.write("right")
            f.main.until { next.size == 55 to 12 && next.written() == "right" }
        } finally { f.stop() }
    }
}
