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
        "local_inventory" -> if (metric == "metadata") "Consultando metadados locais" else "Revalidando metadados locais"
        "local_read" -> "Verificando arquivos locais"
        "library_inventory" -> "Inventariando hardlinks"
        "library_read" -> "Verificando vínculo local"
        "transmission_check" -> "Consultando Transmission"
        "pausing" -> "Pausando torrent"; "removing_entry" -> "Retirando entrada do Transmission"
        "unlinking" -> "Removendo caminhos confirmados"
        "finalizing" -> "Concluindo operação"
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
        "remote_check", "local_inventory", "local_read", "library_inventory", "library_read", "transmission_check", "finalizing", "pausing", "removing_entry", "unlinking"))
    val metric = if (row.isNull("metric")) null else row.getString("metric").also {
        require(it in setOf("read", "metadata") && row.getString("scope") == "phase")
    }
    if (metric != null) require(phase !in setOf("uploading", "downloading", "hashing", "verifying"))
    require(phase !in setOf("remote_check", "local_inventory", "local_read", "library_inventory", "library_read", "transmission_check", "finalizing", "pausing", "removing_entry", "unlinking") || metric in setOf("read", "metadata"))
    val done = requireNotNull(integer("bytes_done"))
    val total = integer("bytes_total")
    val percent = value("percent")
    if (metric == "metadata") require(done == 0L && total == null && percent == null && value("speed_bps") == null && value("eta_seconds") == null)
    require(total == null || done <= total)
    require(percent == null || (total != null && total > 0 && percent <= 100 && (percent < 100 || (phase == "done" && metric == null))))
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
/** Numeric filesystem identity is carried as Long, never rounded through Double. */
internal data class CloudInventoryPath(val path: String, val role: String, val relative: String?)
internal data class CloudInode(val dev: Long, val ino: Long, val links: Long, val blocks: Long,
    val names: List<String>, val unknown: Long)
internal data class CloudInventory(val paths: List<CloudInventoryPath>, val inodes: List<CloudInode>,
    val blockers: List<String>, val libraryComplete: Boolean)

internal data class CloudSpace(val id: String, val token: String, val selected: List<String>,
    val canRelease: Boolean, val physical: Long, val estimate: Long, val retained: Long,
    val paths: List<String>, val trees: List<JSONObject>, val library: List<JSONObject>,
    val blockers: List<String>, val expiresAt: Long, val libraryComplete: Boolean = true,
    val inventory: CloudInventory? = null) {
    fun valid(now: Long) = inventory != null && libraryComplete && canRelease && blockers.isEmpty() && now < expiresAt
    fun project(selection: List<String>): CloudSpace {
        val source = requireNotNull(inventory)
        require(selection.size <= 256 && selection.distinct().size == selection.size && selection.all(::validMediaPath))
        val chosen = selection.sorted()
        val chosenSet = chosen.toSet()
        val allowed = library.filter { it.optBoolean("managed") }.map { it.getString("path") }.toSet()
        val codes = source.blockers.toMutableList()
        if (!allowed.containsAll(chosen)) codes += "LIBRARY_SELECTION"
        val removed = source.paths.filter { it.role != "library" || it.relative in chosenSet }.map { it.path }
        val removedSet = removed.toSet()
        if (removed.isEmpty()) codes += "NO_LOCAL_DATA"
        val physical = source.inodes.fold(0L) { total, inode -> Math.addExact(total, inode.blocks) }
        val estimate = source.inodes.filter { inode -> inode.unknown == 0L && inode.names.count { it in removedSet }.toLong() == inode.links }
            .fold(0L) { total, inode -> Math.addExact(total, inode.blocks) }
        return copy(selected = chosen, canRelease = codes.isEmpty() && source.libraryComplete,
            physical = physical, estimate = estimate, retained = physical - estimate, paths = removed,
            blockers = cloudBlockers(codes), libraryComplete = source.libraryComplete)
    }
    fun release(libraryConfirmed: Boolean, now: Long): CloudWork {
        require(valid(now) && (selected.isEmpty() || libraryConfirmed))
        return CloudWork("torrent", listOf("release", id, "--preview", token, "--confirm", token) +
            selected.flatMap { listOf("--remove-library", it) } +
            if (selected.isEmpty()) emptyList() else listOf("--confirm-library", token))
    }
}
internal fun parseCloudSpace(row: JSONObject, now: Long): CloudSpace {
    val token = row.getString("preview").also { require(cloudToken.matches(it)) }
    val ttl = row.getLong("expires_in_seconds").also { require(it in 0..600) }
    val version = row.optInt("version", 1)
    require(version in 1..2)
    val raw = if (version == 2) row.getJSONObject("inventory") else row
    require(version != 2 || (raw.getInt("version") == 2 && raw.getString("id") == row.getString("id")))
    val library = raw.cloudObjects("library")
    val links = library.map { it.getString("path") }
    require(links.distinct().size == links.size && links.all(::validMediaPath))
    val complete = raw.optBoolean("library_complete", false)
    val inventory = if (version == 2) {
        require(!raw.getBoolean("content_verified") && !raw.getJSONObject("remote").getBoolean("revalidated"))
        val paths = raw.cloudObjects("paths").map { item ->
            val role = item.getString("role").also { require(it in setOf("original", "restored", "library")) }
            CloudInventoryPath(item.getString("path").also { require(cloudPath(it)) }, role,
                if (role == "library") item.getString("relative").also { require(validMediaPath(it) && it in links) } else null)
        }
        val names = paths.map { it.path }
        require(names.distinct().size == names.size)
        val inodes = raw.cloudObjects("inodes").map { item ->
            CloudInode(item.getLong("dev"), item.getLong("ino"), item.getLong("nlink"), item.getLong("physical_bytes"),
                item.cloudStrings("known_paths"), item.getLong("unknown_links")).also {
                require(it.dev >= 0 && it.ino >= 0 && it.links > 0 && it.blocks >= 0 && it.unknown >= 0)
                require(it.names.isNotEmpty() && it.names.distinct().size == it.names.size && names.containsAll(it.names))
            }
        }
        require(inodes.flatMap { it.names }.sorted() == names.sorted())
        require(inodes.map { it.dev to it.ino }.distinct().size == inodes.size)
        CloudInventory(paths, inodes, raw.cloudStrings("blockers"), complete)
    } else null // Old results can be viewed; never authorize with the old contract.
    val plan = CloudSpace(row.getString("id"), token, row.cloudStrings("remove_library"), row.getBoolean("can_release"),
        row.optLong("physical_bytes"), row.optLong("reclaim_estimate_bytes"), row.optLong("retained_known_bytes"),
        row.cloudObjects("remove_paths").map { it.getString("path") }, raw.cloudObjects("trees"), library,
        cloudBlockers(raw.cloudStrings("blockers")), minOf(now + ttl * 1000, token.substringBefore('-').toLong() * 1000 + 600000),
        complete, inventory)
    require(cloudId.matches(plan.id) && plan.physical >= 0 && plan.estimate >= 0 && plan.retained >= 0)
    require(plan.selected.size <= 256 && plan.selected.distinct().size == plan.selected.size && plan.selected.all(::validMediaPath) && plan.paths.all(::cloudPath))
    require(row.getString("confirmation") == token && (plan.selected.isEmpty() || row.getString("library_confirmation") == token))
    return if (inventory == null) plan else plan.project(plan.selected)
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
