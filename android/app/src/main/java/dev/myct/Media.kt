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
    value.length in 1..4096 && (value.startsWith("Series/") || value.startsWith("Filmes/")) &&
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

internal sealed interface MediaAction {
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
    fun quote(value: String): String {
        require(value.isNotEmpty() && value.length <= 8192 && value.none { it.isISOControl() })
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
    fun id(value: String) = quote(value.also { require(validMediaId(it)) })
    fun path(value: String) = quote(value.also { require(validMediaPath(it)) })
    fun title(value: String) = quote(value.trim().also {
        require(it.length in 1..100 && it.none { c -> c == '/' || c == '\\' || c.isISOControl() })
    })
    val arguments = when (action) {
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
    return "if [ -x \"\$HOME/.local/bin/myct-media\" ]; then exec \"\$HOME/.local/bin/myct-media\" $arguments; else exit 127; fi"
}

internal fun executeMedia(action: MediaAction, open: () -> DownloadExec): String =
    executeSshCommand(mediaCommand(action), open, 25000) { status, _ ->
        DownloadFailure(if (status == 127) "Instale a CLI myct-media no Fedora."
            else "A operação falhou. Consulte o terminal Fedora; atualize antes de tentar novamente.")
    }
