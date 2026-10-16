package dev.myct

import org.json.JSONObject

internal const val AV_MAX_BYTES = 2_000_000_000L
internal const val AV_LIMIT_NOTICE = "Limite antivírus: 2.000.000.000 bytes (2 GB decimais). Os demais limites podem impedir uma análise completa."
internal enum class AvState(val label: String) {
    CLEAN("Nenhuma ameaça detectada"), DETECTED("Ameaça detectada"), INCOMPLETE("Análise incompleta"),
    ERROR("Erro"), UNAVAILABLE("Indisponível")
}
internal data class AvInfo(val available: Boolean, val engine: String?, val stale: Boolean,
                           val dailyVersion: Long?, val dailyDate: String?, val lastSuccess: Long?)
internal data class AvScanResult(val state: AvState, val message: String = "", val detections: List<String> = emptyList(),
                                 val info: AvInfo? = null, val durationMs: Long? = null, val rssKib: Long? = null)
internal enum class AvCommand(val argument: String) { STATUS("status"), UPDATE("update"), UPDATE_STATUS("update-status") }
internal enum class AvUpdateResult { NEVER, RUNNING, SUCCESS, ERROR, INTERRUPTED }
internal data class AvUpdate(val info: AvInfo?, val result: AvUpdateResult, val running: Boolean,
                             val phase: String?, val message: String)
private const val avExecutable = "\"\$HOME/.local/bin/myct-av\""
private fun avExec(args: String) = "if [ -x $avExecutable ]; then exec $avExecutable $args; else exit 127; fi"
internal fun avCommand(command: AvCommand): String = avExec(command.argument)
internal fun avScanCommand(path: String): String {
    RemotePath.absolute(path)
    val hex = path.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 255) }
    require(hex.length <= 32768)
    return avExec("scan $hex --watch-stdin")
}
private fun JSONObject.optionalLong(key: String): Long? = if (has(key) && !isNull(key))
    getLong(key).also { require(it >= 0) } else null
private fun JSONObject.optionalText(key: String, max: Int = 512): String? = if (has(key) && !isNull(key))
    getString(key).take(max) else null
private fun avJson(reply: SshExecResult): JSONObject {
    if (reply.status == 126 || reply.status == 127) throw DownloadFailure("CLI myct-av ausente ou sem permissão no Fedora.")
    require(reply.status in 0..1) { "Falha no comando remoto." }
    return JSONObject(reply.output).also { require(it.getInt("version") == 1) }
}
private fun parseAvInfo(j: JSONObject): AvInfo? {
    if (!j.has("available")) return null // Busy/early failure responses deliberately omit engine metadata.
    val bases = j.optJSONArray("databases")
    require(bases == null || bases.length() <= 16)
    val daily = (0 until (bases?.length() ?: 0)).map { bases!!.getJSONObject(it) }
        .firstOrNull { it.getString("name") == "daily" }
    return AvInfo(j.getBoolean("available"), j.optionalText("engine"), j.getBoolean("stale"),
        daily?.optionalLong("version"), daily?.optionalText("date"), j.optionalLong("last_success"))
}
internal fun parseAvStatus(reply: SshExecResult): AvInfo {
    val j = avJson(reply)
    return parseAvInfo(j) ?: throw DownloadFailure(j.optionalText("message") ?: "Estado do antivírus indisponível.")
}
internal fun parseAvScan(reply: SshExecResult): AvScanResult {
    if (reply.status in setOf(126, 127)) return AvScanResult(AvState.UNAVAILABLE, "CLI myct-av ausente ou sem permissão no Fedora.")
    val j = avJson(reply)
    val state = AvState.valueOf(j.getString("state"))
    val list = j.optJSONArray("detections")
    require(list == null || list.length() <= 1000)
    val detections = (0 until (list?.length() ?: 0)).map { list!!.getString(it).take(512) }.distinct()
    val info = parseAvInfo(j)
    val duration = j.optionalLong("duration_ms")
    if (state == AvState.CLEAN || state == AvState.DETECTED) {
        require(info?.available == true && !info.engine.isNullOrBlank() && info.dailyVersion != null && duration != null)
        require(if (state == AvState.CLEAN) detections.isEmpty() else detections.isNotEmpty())
        require(reply.status == 0 && j.optInt("signal", 0) == 0 && j.optionalText("incomplete_reason").isNullOrBlank())
        if (state == AvState.CLEAN) require(j.optInt("exit_code", 0) == 0)
    }
    val message = j.optionalText("message")?.takeIf { it.isNotBlank() }
        ?: j.optionalText("incomplete_reason")?.takeIf { it.isNotBlank() }
        ?: j.optionalText("error") ?: ""
    return AvScanResult(state, message, detections, info, duration, j.optionalLong("peak_rss_kib"))
}
internal fun executeAvScan(path: String, open: () -> DownloadExec): AvScanResult =
    parseAvScan(executeSshResult(avScanCommand(path), open, 200_000))
internal fun parseAvRequest(reply: SshExecResult): Boolean {
    val j = avJson(reply)
    if (!j.has("requested")) throw DownloadFailure(j.optionalText("message") ?: "Pedido não confirmado; consulte o estado antes de repetir.")
    return j.getBoolean("requested") // Never infer completion from this response's historical result.
}
internal fun parseAvUpdate(reply: SshExecResult): AvUpdate {
    val j = avJson(reply)
    if (!j.has("result")) return AvUpdate(parseAvInfo(j), AvUpdateResult.ERROR, false, null,
        j.optionalText("message") ?: "Não foi possível consultar a atualização.")
    val running = j.getBoolean("running")
    val result = AvUpdateResult.valueOf(j.getString("result"))
    require(running == (result == AvUpdateResult.RUNNING))
    val phase = j.optionalText("phase")
    require(phase == null || phase in setOf("WAITING", "UPDATING", "FINISHED"))
    val message = j.optionalText("message")?.takeIf { it.isNotBlank() } ?: when {
        phase == "WAITING" && running -> "Aguardando a análise em andamento no Fedora."
        running -> "Atualizando assinaturas no Fedora…"
        result == AvUpdateResult.SUCCESS -> "Atualização concluída com sucesso."
        result == AvUpdateResult.ERROR -> "Falha na atualização. Confira rede, DNS/CDN e permissões no Fedora; último sucesso preservado."
        result == AvUpdateResult.INTERRUPTED -> "Atualização interrompida; último sucesso preservado."
        else -> "Nenhuma atualização registrada."
    }
    return AvUpdate(parseAvInfo(j), result, running, phase, message)
}
