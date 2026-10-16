package dev.myct

import java.util.Locale

/** Numeric-only, opt-in 60s sample. No terminal bytes, key codes or account data. */
class TerminalDiagnostics(private val clock: () -> Long = System::nanoTime) {
    enum class Stage(val label: String) {
        QUEUE("Fila write"), IDLE_QUEUE("Fila write sem ação anterior"),
        FLUSH("SSH write+flush"), RESIZE_QUEUE("Fila resize"), RESIZE("SSH resize"),
        MAIN("Entrega à main"), READER("Retorno main → leitor IO"), TEXT("onTextChanged + UI"), APPEND("Append + callbacks"),
        COMMIT("IME commit"), COMPOSE("IME finish"), DELETE("IME deleteSurroundingText"),
        KEY("onKeyDown"), DELETE_KEY("onKeyDown DEL"), KEY_AGE("KeyEvent idade na entrega"),
        UPDATE("onScreenUpdated"), DRAW("onDraw CPU"), DRAW_WAIT("Atualização → início draw")
    }
    @Volatile var viewport = "Ainda não medido"
    @Volatile private var sample: Sample? = null
    val isSampling: Boolean get() = sample?.let { clock() - it.start in 0 until SAMPLE_NANOS } == true
    fun reset() { sample = null }
    fun startSample() { sample = Sample(clock()) }
    fun record(stage: Stage, startedAt: Long, duration: Long) {
        val current = sample ?: return
        val now = clock()
        if (now - current.start in 0 until SAMPLE_NANOS) current.record(stage, startedAt, now, duration)
    }
    fun admitted(startedAt: Long, depth: Int) {
        val current = sample ?: return
        val now = clock()
        if (now - current.start in 0 until SAMPLE_NANOS) current.admitted(startedAt, now, depth)
    }
    fun deletion(startedAt: Long, duration: Long, left: Int, right: Int) {
        val current = sample ?: return
        val now = clock()
        if (now - current.start in 0 until SAMPLE_NANOS) current.deletion(startedAt, now, duration, left, right)
    }
    fun key(startedAt: Long, duration: Long, age: Long, delete: Boolean, repeat: Boolean) {
        val current = sample ?: return
        val now = clock()
        if (now - current.start in 0 until SAMPLE_NANOS) current.key(startedAt, now, duration, age, delete, repeat)
    }
    fun snapshot(): String = "$viewport\n" + (sample?.snapshot(clock())
        ?: "Sem amostra. Em Opções, toque Amostrar engasgos · 60 s e reproduza o sintoma.")

    private class Sample(val start: Long) {
        private val stats = Array(Stage.entries.size) { LatencyDistribution() }
        private val maxima = Array(60) { LongArray(Stage.entries.size) }
        private val counts = Array(60) { IntArray(Stage.entries.size) }
        private val depths = IntArray(60)
        private var maxDepth = 0
        private var admittedCount = 0L
        private var requestedDeletes = 0L
        private var maxDeletesPerCall = 0
        private var requestedRight = 0L
        private var repeatedDeletes = 0L
        private fun accepts(at: Long, now: Long) = at >= start && now - start in 0 until SAMPLE_NANOS
        private fun add(stage: Stage, now: Long, duration: Long) {
            val index = stage.ordinal
            val second = ((now - start) / 1_000_000_000).toInt()
            val value = duration.coerceAtLeast(0)
            stats[index].add(value)
            maxima[second][index] = maxOf(maxima[second][index], value)
            counts[second][index]++
        }
        @Synchronized fun record(stage: Stage, at: Long, now: Long, duration: Long) {
            if (accepts(at, now)) add(stage, now, duration)
        }
        @Synchronized fun admitted(at: Long, now: Long, depth: Int) {
            if (!accepts(at, now)) return
            val second = ((at - start) / 1_000_000_000).toInt()
            admittedCount++
            depths[second] = maxOf(depths[second], depth)
            maxDepth = maxOf(maxDepth, depth)
        }
        @Synchronized fun deletion(at: Long, now: Long, duration: Long, left: Int, right: Int) {
            if (!accepts(at, now)) return
            add(Stage.DELETE, now, duration)
            requestedDeletes += left.coerceAtLeast(0)
            requestedRight += right.coerceAtLeast(0)
            maxDeletesPerCall = maxOf(maxDeletesPerCall, left)
        }
        @Synchronized fun key(at: Long, now: Long, duration: Long, age: Long, delete: Boolean, repeat: Boolean) {
            if (!accepts(at, now)) return
            add(Stage.KEY, now, duration)
            add(Stage.KEY_AGE, now, age)
            if (delete) {
                add(Stage.DELETE_KEY, now, duration)
                if (repeat) repeatedDeletes++
            }
        }
        fun snapshot(now: Long): String {
            // Copy bounded numerical state under lock; format outside the hot-path lock.
            val distributions: Array<LatencyDistribution>
            val peakCopy: Array<LongArray>
            val countCopy: Array<IntArray>
            val depthCopy: IntArray
            val counters: String
            synchronized(this) {
                distributions = Array(stats.size) { stats[it].copy() }
                peakCopy = Array(60) { maxima[it].clone() }
                countCopy = Array(60) { counts[it].clone() }
                depthCopy = depths.clone()
                counters = "Ações admitidas: $admittedCount\nFila máxima observada: $maxDepth ações (exclui ação em execução).\n" +
                    "DEL pedidos pela IME: $requestedDeletes · máximo/chamada: $maxDeletesPerCall · direita: $requestedRight · DEL com repeatCount>0: $repeatedDeletes\n"
            }
            val rows = Stage.entries.map { "${it.label}: ${distributions[it.ordinal].summary()}" }
            val slowSeconds = (0 until 60).filter { s -> peakCopy[s].any { it >= 50_000_000 } }.map { s ->
                val peaks = Stage.entries.filter { peakCopy[s][it.ordinal] >= 50_000_000 }
                    .joinToString { "${it.label}=${ms(peakCopy[s][it.ordinal])}ms" }
                "${s}–${s + 1}s: $peaks · fila=${depthCopy[s]} · delete IME=${countCopy[s][Stage.DELETE.ordinal]} · DEL entregues=${countCopy[s][Stage.DELETE_KEY.ordinal]}"
            }
            return "Amostra ${if (now - start < SAMPLE_NANOS) "em curso" else "encerrada"} · ${(now - start).coerceIn(0, SAMPLE_NANOS) / 1_000_000_000}/60 s\n" +
                "N, média/p95≤/máximo em ms; p95 é limite superior do histograma. Contagens ≥16/50/100 ms da amostra inteira.\n" +
                rows.joinToString("\n") + "\n" + counters +
                "Segundos com pico ≥50 ms (tempo de conclusão, coincidência não prova causa):\n" +
                slowSeconds.ifEmpty { listOf("Nenhum") }.joinToString("\n") +
                "\nSem RTT/eco, GPU/apresentação ou tempo toque→IME. DEL pedidos e entregues podem cruzar os limites da amostra. Nenhum conteúdo registrado."
        }
    }
    companion object {
        private const val SAMPLE_NANOS = 60_000_000_000L
        internal fun ms(value: Long) = String.format(Locale.US, "%.2f", value / 1_000_000.0)
    }
}

/** Fixed histogram: no allocation/sort on input; quantile covers the entire sample. */
internal class LatencyDistribution {
    private val buckets = LongArray(LIMITS.size + 1)
    private var count = 0L
    private var total = 0L
    private var maximum = 0L
    private val slow = LongArray(3)
    fun add(nanos: Long) {
        val value = nanos.coerceAtLeast(0)
        count++; total += value; maximum = maxOf(maximum, value)
        buckets[LIMITS.indexOfFirst { value <= it }.let { if (it < 0) LIMITS.size else it }]++
        if (value >= 16_000_000) slow[0]++
        if (value >= 50_000_000) slow[1]++
        if (value >= 100_000_000) slow[2]++
    }
    fun copy() = LatencyDistribution().also {
        buckets.copyInto(it.buckets); slow.copyInto(it.slow)
        it.count = count; it.total = total; it.maximum = maximum
    }
    fun summary(): String {
        if (count == 0L) return "N=0"
        var cumulative = 0L
        val rank = count - count / 20 // ceil(0.95*N), without floating point.
        val bucket = buckets.indices.first { cumulative += buckets[it]; cumulative >= rank }
        val p95 = if (bucket == LIMITS.size) ">1000" else "≤${TerminalDiagnostics.ms(LIMITS[bucket])}"
        return "N=$count · ${TerminalDiagnostics.ms(total / count)}/$p95/${TerminalDiagnostics.ms(maximum)} · ≥16/50/100=${slow.joinToString("/")}"
    }
    companion object {
        private val LIMITS = longArrayOf(125_000, 250_000, 500_000, 1_000_000, 2_000_000, 4_000_000,
            8_000_000, 16_000_000, 32_000_000, 50_000_000, 100_000_000, 250_000_000, 500_000_000, 1_000_000_000)
    }
}
