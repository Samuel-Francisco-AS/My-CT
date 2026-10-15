package dev.myct

import java.util.concurrent.atomic.AtomicLong

/** Numbers only. Never stores keystrokes, terminal output, host, user or fingerprint. */
class TerminalDiagnostics {
    private val writes = AtomicLong()
    private val maxWriteWait = AtomicLong()
    private val deliveries = AtomicLong()
    private val maxMainWait = AtomicLong()
    @Volatile var viewport = "Ainda não medido"
    fun wrote(queuedAt: Long) { writes.incrementAndGet(); maximum(maxWriteWait, System.nanoTime() - queuedAt) }
    fun delivered(queuedAt: Long) { deliveries.incrementAndGet(); maximum(maxMainWait, System.nanoTime() - queuedAt) }
    fun snapshot(): String = "$viewport\nEnvios: ${writes.get()} · espera máxima IO: ${maxWriteWait.get() / 1_000_000.0} ms\n" +
        "Blocos recebidos: ${deliveries.get()} · espera máxima main: ${maxMainWait.get() / 1_000_000.0} ms\n" +
        "Tempos locais, sem medir latência Wi-Fi/SSH ou echo. Nenhum conteúdo foi registrado."
    private fun maximum(target: AtomicLong, value: Long) { target.accumulateAndGet(value, ::maxOf) }
}
