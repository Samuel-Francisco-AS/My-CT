package dev.myct

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

internal val cloudId = Regex("[0-9a-f]{64}")
internal val cloudRequestId = Regex("[0-9a-f]{32}")
internal val cloudToken = Regex("[0-9]{1,12}-[0-9a-f]{64}")
internal fun cloudPath(path: String) = path.toByteArray().size in 1..4096 && path.startsWith('/') &&
    path.split('/').none { it == "." || it == ".." } && path.none { it.isISOControl() }

internal data class CloudWork(val kind: String, val args: List<String>) {
    val action get() = args.first()
    val target get() = args.getOrNull(1).orEmpty()
    val librarySelection get() = args.drop(2).chunked(2).filter { it.first() == "--remove-library" }.map { it[1] }.sorted()
    val title get() = when (action) {
        "archive" -> "Arquivar"; "space" -> "Consultar espaço"; "release" -> "Liberar espaço"
        "restore" -> "Restaurar"; "recover" -> if ("--resume" in args) "Retomar seeding" else "Recuperar torrent"
        "relink" -> "Reconstruir vínculo"; "inspect" -> "Conferir torrent"; else -> "Consultar Drive"
    }
    fun json(): JSONObject {
        require(kind in setOf("torrent", "file") && args.isNotEmpty() && args.size <= 520)
        require(args.all { it.toByteArray().size in 1..4096 && it.none(Char::isISOControl) })
        if (kind == "file") {
            require((action == "status" && args.size == 1) ||
                (action == "archive" && args.size == 2 && cloudPath(target)) ||
                (action == "restore" && args.size == 2 && cloudId.matches(target)))
        } else {
            require(action in setOf("inspect", "archive", "space", "release", "restore", "recover", "relink"))
            require(if (action in setOf("inspect", "archive")) Regex("[0-9a-f]{40}").matches(target) else cloudId.matches(target))
            when (action) {
                "inspect", "archive", "restore" -> require(args.size == 2)
                "recover" -> require(args.drop(2) in listOf(emptyList(), listOf("--resume")))
                else -> {
                    val tail = args.drop(2)
                    require(tail.size % 2 == 0)
                    val allowed = when (action) {
                        "space" -> setOf("--remove-library")
                        "relink" -> setOf("--library")
                        else -> setOf("--preview", "--confirm", "--remove-library", "--confirm-library")
                    }
                    val options = mutableMapOf<String, String>(); val paths = mutableListOf<String>()
                    tail.chunked(2).forEach { (flag, value) ->
                        require(flag in allowed)
                        if (flag in setOf("--library", "--remove-library")) {
                            require(validMediaPath(value)); paths += value
                        } else { require(flag !in options && cloudToken.matches(value)); options[flag] = value }
                    }
                    require(paths.size <= 256 && paths.distinct().size == paths.size)
                    if (action == "relink") require(paths.isNotEmpty())
                    if (action == "release") {
                        require(options["--preview"] != null && options["--preview"] == options["--confirm"])
                        require(paths.isEmpty() || options["--preview"] == options["--confirm-library"])
                    }
                }
            }
        }
        return JSONObject().put("kind", kind).put("args", JSONArray(args))
    }
}
internal sealed interface CloudQuery {
    data object Snapshot : CloudQuery
    data class Task(val id: String) : CloudQuery
    data class Cancel(val id: String) : CloudQuery
    data class Start(val id: String, val work: CloudWork) : CloudQuery
}
internal fun cloudCommand(query: CloudQuery): String {
    val args = when (query) {
        CloudQuery.Snapshot -> "snapshot"
        is CloudQuery.Cancel -> { require(cloudRequestId.matches(query.id)); "cancel ${query.id}" }
        is CloudQuery.Task -> { require(cloudRequestId.matches(query.id)); "task ${query.id}" }
        is CloudQuery.Start -> {
            require(cloudRequestId.matches(query.id))
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(query.work.json().toString().toByteArray(Charsets.UTF_8))
            "start ${query.id} $encoded"
        }
    }
    return "if [ -x \"\$HOME/.local/bin/myct-cloud\" ]; then exec \"\$HOME/.local/bin/myct-cloud\" $args; else exit 127; fi"
}
internal fun cloudReply(text: String): JSONObject = JSONObject(text).also {
    if (!it.getBoolean("ok")) throw DownloadFailure(cloudError(it.optJSONObject("error")))
}
internal fun cloudError(error: JSONObject?): String {
    val code = error?.optString("code").orEmpty()
    val hint = when (code) {
        "DEPENDENCIES" -> "Helper ausente. Instale myct-cloud e seus complementos no Fedora."
        "BUSY" -> "Transferência em andamento; catálogo temporariamente ocupado"
        "CANCEL_UNSUPPORTED" -> "Este trabalho não oferece cancelamento seguro com os helpers atuais."
        "CANCEL_UNCERTAIN" -> "Cancelamento não confirmado. Consulte o resultado; nada será repetido automaticamente."
        "LOCAL_DIVERGENCE" -> "Arquivos locais não correspondem ao pacote arquivado; liberação bloqueada"
        "NO_LOCAL_DATA" -> "Nenhuma árvore local íntegra disponível para liberação"
        "STALE_PREVIEW", "CONFIRMATION", "LIBRARY_CONFIRMATION" -> "A prévia expirou ou mudou. Consulte espaço novamente."
        "RCLONE_NOT_FOUND" -> "Rclone indisponível no Fedora."
        "AUTH" -> "Drive indisponível. Confira a autorização privada do Rclone no Fedora."
        "NETWORK" -> "Falha de rede no Fedora."
        "RELEASE_UNCERTAIN", "JOURNAL_OR_SYSTEMD" -> "Resultado incerto. Confira o diário no Fedora antes de repetir."
        "LINGER" -> "Persistência indisponível: confira linger no Fedora."
        else -> error?.optString("message")?.take(500)?.ifBlank { null } ?: "Operação não confirmada. Atualize."
    }
    return if (code.isBlank()) hint else "$hint ($code)"
}
internal fun executeCloud(query: CloudQuery, open: () -> DownloadExec): String {
    val reply = executeSshResult(cloudCommand(query), open, maxOutputBytes = 4 * 1024 * 1024)
    if (reply.status == 127) throw DownloadFailure("Helper myct-cloud ausente. Instale os helpers C1C no Fedora.")
    cloudReply(reply.output)
    if (reply.status != 0) throw DownloadFailure("SSH/helper sem resultado confirmado. Atualize antes de repetir.")
    return reply.output
}
internal fun JSONObject.cloudObjects(key: String): List<JSONObject> {
    val array = optJSONArray(key) ?: return emptyList()
    require(array.length() <= 4000)
    return (0 until array.length()).map { array.getJSONObject(it) }
}
internal fun JSONObject.cloudStrings(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    require(array.length() <= 4000)
    return (0 until array.length()).map { array.getString(it) }
}
internal data class CloudEntry(val id: String, val kind: String, val name: String, val size: Long,
    val state: String, val hash: String, val local: Boolean, val verifiedAt: String,
    val restoreState: String, val recoveryState: String, val releaseState: String,
    val links: List<String>, val error: String, val existingLinks: Int = 0, val linkedFiles: Int = 0, val libraryChecked: Boolean = false) {
    val restorable get() = state == "verified" && releaseState !in setOf("pending", "uncertain")
}
internal fun parseCloudEntries(json: JSONObject, kind: String): List<CloudEntry> = json.cloudObjects("entries").map { row ->
    CloudEntry(row.getString("id"), kind, row.getString("name"), row.getLong("size"), row.getString("state"),
        row.optString("infohash"), row.optBoolean("local_present"), row.optString("verified_at", row.optString("created_at")),
        row.optJSONObject("restore")?.optString("state").orEmpty(), row.optJSONObject("recovery")?.optString("state").orEmpty(),
        row.optJSONObject("release")?.optString("state").orEmpty(),
        row.cloudObjects("library_links").map { it.getString("path") }, row.optString("error_code", ""),
        row.optInt("library_existing"), row.optInt("library_linked"), row.optBoolean("library_checked")).also {
        require(cloudId.matches(it.id) && it.size >= 0 && (kind == "file" || Regex("[0-9a-f]{40}").matches(it.hash)))
        require(it.links.all(::validMediaPath))
    }
}
internal data class CloudProgress(val phase: String, val bytesDone: Long, val bytesTotal: Long?,
    val percent: Double?, val speed: Double?, val eta: Double?, val updatedAt: Double,
    val fileIndex: Int?, val totalFiles: Int?, val metric: String? = null, val readPass: Int? = null) {
    val label get() = when (phase) {
        "uploading" -> "Enviando"; "downloading" -> "Baixando"; "hashing" -> "Calculando hashes"
        "remote_check" -> "Verificando Google Drive"
        "local_inventory" -> "Inventariando arquivos locais"
        "local_read" -> "Verificando arquivos locais"
        "library_inventory" -> "Inventariando hardlinks"
        "library_read" -> "Verificando vínculo local"
        "transmission_check" -> "Consultando Transmission"
        "finalizing" -> "Concluindo consulta"
        "verifying" -> "Conferindo integridade"; "done" -> "Concluído"; "failed" -> "Falhou"
        else -> "Preparando"
    }
    fun fresh(now: Long) = now / 1000.0 - updatedAt in -5.0..8.0
    fun transferring(now: Long) = (phase in setOf("uploading", "downloading") || (metric == "read" && phase in setOf("local_read", "library_read"))) && fresh(now)
    fun estimate(now: Long): String {
        if (!transferring(now) || eta == null || speed == null || speed <= 0) return "Tempo estimado indisponível"
        val seconds = kotlin.math.ceil(eta).toLong()
        val duration = when {
            seconds >= 3600 -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
            seconds >= 60 -> "${seconds / 60} min ${seconds % 60} s"
            else -> "$seconds s"
        }
        return if (metric == "read") "Tempo estimado desta leitura: $duration" else "Tempo estimado: $duration"
    }
}
internal fun parseCloudProgress(row: JSONObject): CloudProgress? = try {
    fun value(key: String): Double? {
        if (row.isNull(key)) return null
        val number = row.get(key) as? Number ?: throw IllegalArgumentException()
        return number.toDouble().also { require(it.isFinite() && it >= 0 && it <= 9007199254740991.0) }
    }
    fun integer(key: String): Long? = value(key)?.also { require(it % 1 == 0.0) }?.toLong()
    val phase = row.getString("phase")
    require(phase in setOf("preparing", "hashing", "uploading", "downloading", "verifying", "done", "failed",
        "remote_check", "local_inventory", "local_read", "library_inventory", "library_read", "transmission_check", "finalizing"))
    val metric = if (row.isNull("metric")) null else row.getString("metric").also {
        require(it == "read" && row.getString("scope") == "phase")
    }
    if (metric == "read") require(phase !in setOf("uploading", "downloading", "hashing", "verifying"))
    require(phase !in setOf("remote_check", "local_inventory", "local_read", "library_inventory", "library_read", "transmission_check", "finalizing") || metric == "read")
    val done = requireNotNull(integer("bytes_done"))
    val total = integer("bytes_total")
    val percent = value("percent")
    require(total == null || done <= total)
    require(percent == null || (total != null && total > 0 && percent <= 100 && (percent < 100 || (phase == "done" && metric != "read"))))
    CloudProgress(phase, done, total, percent, value("speed_bps"), value("eta_seconds"),
        requireNotNull(value("updated_at")), integer("current_file_index")?.also { require(it <= Int.MAX_VALUE) }?.toInt(),
        integer("total_files")?.also { require(it <= Int.MAX_VALUE) }?.toInt(), metric, integer("read_pass")?.also { require(it <= Int.MAX_VALUE) }?.toInt())
} catch (_: Exception) { null }

internal data class CloudTask(val id: String, val work: CloudWork, val state: String, val progress: CloudProgress? = null,
    val cancelSupported: Boolean = false, val cancelRequested: Boolean = false) {
    val canCancel get() = active && state != "cancelling" && !cancelRequested && cancelSupported && work.action in setOf("archive", "restore")
    val active get() = state in setOf("starting", "running", "cancelling")
    val uncertain get() = state == "uncertain"
    val label get() = if (cancelRequested && active) "Cancelamento solicitado" else when (state) {
        "cancelling" -> "Confirmando cancelamento"
        "cancelled" -> "Cancelada"
        "starting", "running" -> progress?.label?.takeUnless { progress?.phase in setOf("done", "failed") } ?: "Em andamento"
        "succeeded" -> "Concluído"; "failed" -> "Falhou"; else -> "Resultado incerto — conferir no Fedora"
    }
}
internal fun parseCloudTask(row: JSONObject): CloudTask {
    val work = row.getJSONObject("work")
    return CloudTask(row.getString("id"), CloudWork(work.getString("kind"), work.cloudStrings("args")), row.getString("state"),
        row.optJSONObject("progress")?.let(::parseCloudProgress), row.optBoolean("cancel_supported"), row.optBoolean("cancel_requested")).also {
        require(cloudRequestId.matches(it.id) && it.state in setOf("starting", "running", "cancelling", "cancelled", "succeeded", "failed", "uncertain"))
        it.work.json()
    }
}
internal data class CloudSpace(val id: String, val token: String, val selected: List<String>,
    val canRelease: Boolean, val physical: Long, val estimate: Long, val retained: Long,
    val paths: List<String>, val trees: List<JSONObject>, val library: List<JSONObject>,
    val blockers: List<String>, val expiresAt: Long, val libraryComplete: Boolean = true) {
    fun valid(now: Long) = libraryComplete && canRelease && blockers.isEmpty() && now < expiresAt
    fun release(libraryConfirmed: Boolean, now: Long): CloudWork {
        require(valid(now) && (selected.isEmpty() || libraryConfirmed))
        return CloudWork("torrent", listOf("release", id, "--preview", token, "--confirm", token) +
            selected.flatMap { listOf("--remove-library", it) } +
            if (selected.isEmpty()) emptyList() else listOf("--confirm-library", token))
    }
}
internal fun parseCloudSpace(row: JSONObject, now: Long): CloudSpace {
    val token = row.getString("preview")
    val ttl = row.getLong("expires_in_seconds").also { require(it in 0..600) }
    return CloudSpace(row.getString("id"), token, row.cloudStrings("remove_library"), row.getBoolean("can_release"),
        row.optLong("physical_bytes"), row.optLong("reclaim_estimate_bytes"), row.optLong("retained_known_bytes"),
        row.cloudObjects("remove_paths").map { it.getString("path") }, row.cloudObjects("trees"), row.cloudObjects("library"),
        cloudBlockers(row.cloudStrings("blockers")), minOf(now + ttl * 1000, token.substringBefore('-').toLong() * 1000 + 600000),
        row.optBoolean("library_complete", row.getBoolean("can_release") && row.cloudStrings("blockers").isEmpty())).also {
        require(cloudId.matches(it.id) && cloudToken.matches(token) && it.physical >= 0 && it.estimate >= 0 && it.retained >= 0)
        require(it.selected.size <= 256 && it.selected.distinct().size == it.selected.size && it.selected.all(::validMediaPath) && it.paths.all(::cloudPath))
        val links = it.library.map { link -> link.getString("path") }
        require(links.distinct().size == links.size && links.all(::validMediaPath))
        require(row.getString("confirmation") == token && (it.selected.isEmpty() || row.getString("library_confirmation") == token))
    }
}
internal fun cloudState(entry: CloudEntry, torrent: ServerTorrent?, task: CloudTask?): String = when {
    task?.uncertain == true || entry.releaseState == "uncertain" -> "Erro · resultado incerto"
    task?.active == true && task.work.action == "restore" -> "Restaurando"
    task?.active == true && task.work.action == "recover" -> "Verificando"
    task?.active == true -> "Em andamento · ${task.work.title}"
    task?.state == "failed" -> "Erro · última operação"
    entry.state in setOf("failed", "unverified") || entry.releaseState == "pending" -> "Erro"
    torrent?.state in setOf(TorrentState.CHECK, TorrentState.CHECK_WAIT) -> "Verificando"
    torrent?.state == TorrentState.SEED && (torrent.up ?: 0) > 0 -> "Compartilhando · upload observado"
    torrent?.state in setOf(TorrentState.SEED, TorrentState.SEED_WAIT) -> "Seeding iniciado · sem upload observado"
    entry.recoveryState == "verified" && torrent?.paused == true && torrent.progress == 1f -> "Pronto para seeding · pausado"
    entry.releaseState == "released" && !entry.local -> "Liberado do SSD"
    entry.state == "verified" -> "Arquivado"
    else -> "Local"
}

internal fun cloudBlockers(codes: List<String>): List<String> = codes
    .filterNot { it == "NO_LOCAL_DATA" && "LOCAL_DIVERGENCE" in codes }
    .map { code -> when (code) {
        "LIBRARY_SELECTION" -> "Há vínculos da seleção com registro Media ou origem não comprovados. Liberação bloqueada."
        "LIBRARY_INCOMPLETE" -> "Não foi possível concluir o inventário de vínculos. Liberação bloqueada."
        "LOCAL_DIVERGENCE", "NO_LOCAL_DATA" -> cloudError(JSONObject().put("code", code)).substringBefore(" ($code)")
        else -> code
    } }
