package dev.myct

import org.json.JSONObject

internal data class MediaProposal(val id: String, val target: String)
internal data class MediaPending(val id: String, val name: String, val reason: String)
internal data class MediaEntry(val path: String, val id: String)
internal data class MediaPreview(val proposals: List<MediaProposal>, val existing: Int,
    val pending: List<MediaPending>, val ignored: Int)

private val mediaId = Regex("[0-9a-f]{40}:.{1,4096}")
internal fun validMediaId(value: String): Boolean =
    value.length <= 4096 && mediaId.matches(value) && value.none { it.isISOControl() }

internal fun validMediaPath(value: String): Boolean =
    value.length in 1..4096 && (value.startsWith("Series/") || value.startsWith("Series-TV/") || value.startsWith("Filmes/")) &&
        value.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
        value.none { it.isISOControl() || it == '\\' }

internal fun parseMediaPreview(text: String): MediaPreview {
    val json = JSONObject(text)
    fun array(name: String) = json.getJSONArray(name)
    val additions = array("added")
    val existing = array("existing")
    val pending = array("pending")
    val proposals = (0 until additions.length()).map { index ->
        val entry = additions.getJSONObject(index)
        MediaProposal(entry.getString("id"), entry.getString("target")).also {
            require(validMediaId(it.id) && validMediaPath(it.target))
        }
    }
    val needsReview = (0 until pending.length()).map { index ->
        val entry = pending.getJSONObject(index)
        MediaPending(entry.getString("id"), entry.optString("name"), entry.getString("reason")).also {
            require(validMediaId(it.id) && it.name.length <= 8192 && it.reason.length <= 8192)
        }
    }
    require(existing.length() >= 0)
    val ignored = json.getInt("ignored")
    require(ignored >= 0 && proposals.size <= 2000 && needsReview.size <= 2000)
    return MediaPreview(proposals, existing.length(), needsReview, ignored)
}
internal fun parseMediaEntries(text: String): List<MediaEntry> {
    val links = JSONObject(text).getJSONObject("links")
    require(links.length() <= 2000)
    return links.keys().asSequence().map { path ->
        val id = links.getJSONObject(path).getString("id")
        require(validMediaPath(path) && validMediaId(id))
        MediaEntry(path, id)
    }.sortedBy { it.path }.toList()
}

internal data class MediaSource(val id: String, val torrent: String, val name: String, val path: String)
internal data class ManualSelection(val id: String, val episode: Int?)
internal data class ManualBatch(val kind: String, val title: String, val season: Int?, val year: Int?,
                                val files: List<ManualSelection>)
internal data class ManualResult(val id: String, val name: String, val target: String, val episode: Int?,
                                 val status: String)
internal data class ManualPlan(val kind: String, val items: List<ManualResult>, val ready: Int,
                               val existing: Int, val collisions: Int)

internal fun parseMediaSources(text: String): List<MediaSource> {
    val array = JSONObject(text).getJSONArray("files")
    require(array.length() <= 400)
    return (0 until array.length()).map { n ->
        val value = array.getJSONObject(n)
        MediaSource(value.getString("id"), value.getString("torrent"),
            value.getString("name"), value.getString("path")).also {
            require(validMediaId(it.id) && Regex("[0-9a-f]{40}").matches(it.torrent) &&
                it.id == it.torrent + ":" + it.path && it.name.length <= 8192 &&
                it.path.length in 1..4096 && !it.path.startsWith('/') &&
                it.path.split('/').none { part -> part.isEmpty() || part == "." || part == ".." } &&
                it.path.none { ch -> ch.isISOControl() })
        }
    }
}
internal fun parseManualPlan(text: String): ManualPlan {
    val json = JSONObject(text)
    val list = json.getJSONArray("items")
    require(list.length() in 1..128)
    val items = (0 until list.length()).map { n ->
        val item = list.getJSONObject(n)
        ManualResult(item.getString("id"), item.getString("name"), item.getString("target"),
            if (item.isNull("episode")) null else item.getInt("episode"),
            item.getString("status")).also {
            require(validMediaId(it.id) && validMediaPath(it.target) && it.status in
                setOf("ready", "existing", "collision"))
        }
    }
    val ready = json.getInt("ready")
    val existing = json.getInt("existing")
    val collisions = json.getInt("collisions")
    require(ready >= 0 && existing >= 0 && collisions >= 0 &&
        ready + existing + collisions == items.size)
    return ManualPlan(json.getString("kind"), items, ready, existing, collisions)
}
/** Small bounded selection passed as a single shell-safe Base64URL argument. */
internal fun manualToken(value: ManualBatch): String {
    require(value.kind in setOf("anime", "series", "movie") &&
        value.title.trim().length in 1..100 && value.title.none { it.isISOControl() || it == '/' || it == '\\' } &&
        value.files.size in 1..128 && value.files.map { it.id }.distinct().size == value.files.size)
    if (value.kind == "movie") {
        require(value.files.size == 1 && value.year != null && value.year in 1900..2100)
    } else {
        require(value.season != null && value.season in 0..99)
        val episodes = value.files.map { it.episode }
        require(episodes.all { it != null && it in 1..999 } && episodes.distinct().size == episodes.size)
    }
    val json = JSONObject().put("version", 1).put("kind", value.kind).put("title", value.title.trim())
    if (value.kind == "movie") json.put("year", value.year) else json.put("season", value.season)
    val files = org.json.JSONArray()
    value.files.forEach { item ->
        require(validMediaId(item.id))
        files.put(JSONObject().put("id", item.id).also {
            if (value.kind != "movie") it.put("episode", item.episode)
        })
    }
    json.put("files", files)
    val token = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    require(token.length <= 48000 && Regex("[A-Za-z0-9_-]+").matches(token))
    return token
}

internal sealed interface MediaAction {
    data object BulkSources : MediaAction
    data class BulkPlan(val spec: ManualBatch) : MediaAction
    data class BulkImport(val spec: ManualBatch) : MediaAction
    data object Scan : MediaAction
    data object List : MediaAction
    data object Sync : MediaAction
    data class Skip(val id: String) : MediaAction
    data class Remove(val path: String) : MediaAction
    data class ApproveSeries(val id: String, val title: String, val season: Int, val episode: Int) : MediaAction
    data class ApproveMovie(val id: String, val title: String, val year: Int) : MediaAction
}

/** Use existing trusted SSH session, with literal command plus individually quoted arguments. */
internal fun mediaCommand(action: MediaAction): String {
    fun quote(value: String, limit: Int = 8192): String {
        require(value.isNotEmpty() && value.length <= limit && value.none { it.isISOControl() })
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
    fun id(value: String) = quote(value.also { require(validMediaId(it)) })
    fun path(value: String) = quote(value.also { require(validMediaPath(it)) })
    fun title(value: String) = quote(value.trim().also {
        require(it.length in 1..100 && it.none { c -> c == '/' || c == '\\' || c.isISOControl() })
    })
    val arguments = when (action) {
        MediaAction.BulkSources -> "sources"
        is MediaAction.BulkPlan -> "plan ${quote(manualToken(action.spec), 48000)}"
        is MediaAction.BulkImport -> "import ${quote(manualToken(action.spec), 48000)}"
        MediaAction.Scan -> "scan"
        MediaAction.List -> "list"
        MediaAction.Sync -> "sync"
        is MediaAction.Skip -> "skip ${id(action.id)}"
        is MediaAction.Remove -> "remove ${path(action.path)}"
        is MediaAction.ApproveSeries -> {
            require(action.season in 0..99 && action.episode in 1..999)
            "approve ${id(action.id)} series ${title(action.title)} ${action.season} ${action.episode}"
        }
        is MediaAction.ApproveMovie -> {
            require(action.year in 1900..2100)
            "approve ${id(action.id)} movie ${title(action.title)} ${action.year}"
        }
    }
    val binary = if (action is MediaAction.BulkSources || action is MediaAction.BulkPlan ||
        action is MediaAction.BulkImport) "myct-media-bulk" else "myct-media"
    return "if [ -x \"\$HOME/.local/bin/$binary\" ]; then exec \"\$HOME/.local/bin/$binary\" $arguments; else exit 127; fi"
}

internal fun executeMedia(action: MediaAction, open: () -> DownloadExec): String =
    executeSshCommand(mediaCommand(action), open,
        if (action is MediaAction.BulkImport) 120000 else 25000) { status, _ ->
        DownloadFailure(if (status == 127) "Instale myct-media e myct-media-bulk no Fedora."
            else "A operação falhou. Consulte o terminal Fedora; atualize antes de tentar novamente.")
    }
