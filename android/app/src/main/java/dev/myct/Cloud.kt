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
    data class Start(val id: String, val work: CloudWork) : CloudQuery
}
internal fun cloudCommand(query: CloudQuery): String {
    val args = when (query) {
        CloudQuery.Snapshot -> "snapshot"
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
        "BUSY" -> "O motor está ocupado. Aguarde e atualize."
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
internal data class CloudTask(val id: String, val work: CloudWork, val state: String) {
    val active get() = state in setOf("starting", "running")
    val uncertain get() = state == "uncertain"
    val label get() = when (state) {
        "starting", "running" -> "Em andamento"
        "succeeded" -> "Concluído"; "failed" -> "Erro"; else -> "Resultado incerto — conferir no Fedora"
    }
}
internal fun parseCloudTask(row: JSONObject): CloudTask {
    val work = row.getJSONObject("work")
    return CloudTask(row.getString("id"), CloudWork(work.getString("kind"), work.cloudStrings("args")), row.getString("state")).also {
        require(cloudRequestId.matches(it.id) && it.state in setOf("starting", "running", "succeeded", "failed", "uncertain"))
        it.work.json()
    }
}
internal data class CloudSpace(val id: String, val token: String, val selected: List<String>,
    val canRelease: Boolean, val physical: Long, val estimate: Long, val retained: Long,
    val paths: List<String>, val trees: List<JSONObject>, val library: List<JSONObject>,
    val blockers: List<String>, val expiresAt: Long) {
    fun valid(now: Long) = canRelease && now < expiresAt
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
        row.cloudStrings("blockers"), minOf(now + ttl * 1000, token.substringBefore('-').toLong() * 1000 + 600000)).also {
        require(cloudId.matches(it.id) && cloudToken.matches(token) && it.physical >= 0 && it.estimate >= 0 && it.retained >= 0)
        require(it.selected.all(::validMediaPath) && it.paths.all(::cloudPath))
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
