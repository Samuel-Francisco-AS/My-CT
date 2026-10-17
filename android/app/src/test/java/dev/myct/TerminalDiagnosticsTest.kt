package dev.myct

import org.junit.Assert.*
import org.junit.Test

class TerminalDiagnosticsTest {
    @Test fun histogramReportsWholeSampleQuantileAndOutlierFrequency() {
        val stats = LatencyDistribution()
        repeat(95) { stats.add(1_000_000) }
        repeat(5) { stats.add(500_000_000) }
        val result = stats.summary()
        assertTrue(result.contains("N=100"))
        assertTrue(result.contains("25.95/≤1.00/500.00"))
        assertTrue(result.contains("≥16/50/100=5/5/5"))
        val copy = stats.copy()
        stats.add(2_000_000_000)
        assertEquals(result, copy.summary())
        val overflow = LatencyDistribution().also { it.add(1_000_000_001) }.summary()
        assertTrue(overflow.contains(">1000"))
        assertEquals("N=0", LatencyDistribution().summary())
    }

    @Test fun sampleIsOptInBoundedExpiresAndRejectsStaleOperations() {
        var now = 1L
        val metrics = TerminalDiagnostics { now }
        assertFalse(metrics.isSampling)
        metrics.record(TerminalDiagnostics.Stage.QUEUE, now, 500_000_000)
        assertTrue(metrics.snapshot().contains("Sem amostra"))
        metrics.startSample()
        metrics.admitted(now, 6)
        metrics.record(TerminalDiagnostics.Stage.QUEUE, now, 500_000_000)
        now = 60_000_000_001L
        assertFalse(metrics.isSampling)
        metrics.record(TerminalDiagnostics.Stage.QUEUE, now, 900_000_000)
        assertTrue(metrics.snapshot().contains("Fila write: N=1"))
        metrics.startSample()
        metrics.record(TerminalDiagnostics.Stage.QUEUE, 1, 900_000_000)
        assertTrue(metrics.snapshot().contains("Fila write: N=0"))
        metrics.reset()
        assertFalse(metrics.isSampling)
        assertTrue(metrics.snapshot().contains("Sem amostra"))
    }

    @Test fun numericBinsDistinguishDeletionRequestsDeliveryAndCoincidentPeaks() {
        var now = 1L
        val metrics = TerminalDiagnostics { now }
        metrics.startSample()
        now += 1_500_000_000
        metrics.deletion(now, 2_000_000, 8, 0)
        repeat(8) { metrics.key(now, 500_000, 55_000_000, true, it > 0) }
        metrics.admitted(now, 8)
        metrics.record(TerminalDiagnostics.Stage.QUEUE, now, 550_000_000)
        metrics.record(TerminalDiagnostics.Stage.DRAW_WAIT, now, 70_000_000)
        val result = metrics.snapshot()
        assertTrue(result.contains("IME deleteSurroundingText: N=1"))
        assertTrue(result.contains("onKeyDown DEL: N=8"))
        assertTrue(result.contains("DEL pedidos pela IME: 8 · máximo/chamada: 8"))
        assertTrue(result.contains("DEL com repeatCount>0: 7"))
        assertTrue(result.contains("1–2s: Fila write=550.00ms"))
        assertTrue(result.contains("fila=8 · delete IME=1 · DEL entregues=8"))
    }

    @Test fun concurrentNumericRecordingAndSnapshotsDoNotLoseEvents() {
        val metrics = TerminalDiagnostics { 1L }
        metrics.startSample()
        val threads = List(2) {
            Thread { repeat(1000) { metrics.record(TerminalDiagnostics.Stage.QUEUE, 1, 1_000_000) } }
        }
        threads.forEach { it.start() }
        repeat(4) { metrics.snapshot() }
        threads.forEach { it.join(2000); assertFalse(it.isAlive) }
        assertTrue(metrics.snapshot().contains("Fila write: N=2000"))
    }
}
