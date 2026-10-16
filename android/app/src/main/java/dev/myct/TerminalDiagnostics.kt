package dev.myct

import java.util.concurrent.atomic.AtomicLong

/** Numeric, ephemeral timing only. Never stores keystrokes, terminal output or account data. */
class TerminalDiagnostics {
    private val writes = AtomicLong()
    private val maxWriteWait = AtomicLong()
    private val flushCount = AtomicLong()
    private val totalFlushNanos = AtomicLong()
    private val maxFlushNanos = AtomicLong()
    private val deliveries = AtomicLong()
    private val maxMainWait = AtomicLong()
    private val appendCount = AtomicLong()
    private val totalAppendNanos = AtomicLong()
    private val maxAppendNanos = AtomicLong()
    @Volatile var viewport = "Ainda não medido"

    /** Clear old maxima so a new SSH session gets a meaningful comparison. */
    fun reset() {
        listOf(writes, maxWriteWait, flushCount, totalFlushNanos, maxFlushNanos,
            deliveries, maxMainWait, appendCount, totalAppendNanos, maxAppendNanos).forEach { it.set(0) }
    }
    fun wrote(queuedAt: Long) {
        writes.incrementAndGet()
        maximum(maxWriteWait, System.nanoTime() - queuedAt)
    }
    fun flushed(durationNanos: Long) {
        flushCount.incrementAndGet()
        totalFlushNanos.addAndGet(durationNanos.coerceAtLeast(0))
        maximum(maxFlushNanos, durationNanos)
    }
    fun delivered(queuedAt: Long) {
        deliveries.incrementAndGet()
        maximum(maxMainWait, System.nanoTime() - queuedAt)
    }
    fun appended(durationNanos: Long) {
        appendCount.incrementAndGet()
        totalAppendNanos.addAndGet(durationNanos.coerceAtLeast(0))
        maximum(maxAppendNanos, durationNanos)
    }
    private fun averageMs(total: AtomicLong, count: AtomicLong): String =
        if (count.get() == 0L) "n/d" else "%.2f".format(java.util.Locale.US, total.get() / count.get().toDouble() / 1_000_000)
    fun snapshot(): String =
        "$viewport\n" +
        "Envios: ${writes.get()} · espera máxima na fila IO: ${maxWriteWait.get() / 1_000_000.0} ms\n" +
        "SSH write+flush: ${flushCount.get()} · média ${averageMs(totalFlushNanos, flushCount)} ms · máximo ${maxFlushNanos.get() / 1_000_000.0} ms\n" +
        "Blocos recebidos: ${deliveries.get()} · espera máxima na main: ${maxMainWait.get() / 1_000_000.0} ms\n" +
        "Emulador append: ${appendCount.get()} · média ${averageMs(totalAppendNanos, appendCount)} ms · máximo ${maxAppendNanos.get() / 1_000_000.0} ms\n" +
        "Medições locais desta conexão; sem latência ponta a ponta, rede/eco ou tempo de desenho. Nenhum conteúdo registrado."
    private fun maximum(target: AtomicLong, value: Long) { target.accumulateAndGet(value.coerceAtLeast(0), ::maxOf) }
}
