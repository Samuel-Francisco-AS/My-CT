package dev.myct

import org.json.JSONObject
import java.net.URI
import java.util.Locale

internal val torrentHash = Regex("[0-9a-fA-F]{40}")
internal enum class TorrentState(val label: String) {
    STOPPED("Pausado"), CHECK_WAIT("Na fila de verificação"), CHECK("Verificando"),
    DOWNLOAD_WAIT("Na fila de download"), DOWNLOAD("Baixando"), SEED_WAIT("Na fila de compartilhamento"), SEED("Compartilhando (seeding)")
}
internal data class ServerTorrent(val id: Long, val hash: String, val name: String, val state: TorrentState,
    val total: Long?, val bytes: Long?, val progress: Float?, val down: Long?, val up: Long?,
    val peers: Long?, val directory: String, val error: String,
    val downloadedEver: Long? = null, val uploadedEver: Long? = null, val ratio: Double? = null) {
    val paused get() = state == TorrentState.STOPPED
}
internal fun parseTorrents(text: String): List<ServerTorrent> {
    val rows = JSONObject(text).getJSONArray("torrents")
    fun number(row: JSONObject, key: String): Long? = if (row.isNull(key)) null else row.getLong(key).also { require(it >= 0) }
    return (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        val hash = row.getString("hash_string"); require(torrentHash.matches(hash))
        val directory = row.getString("download_dir")
        val folder = directory.substringAfterLast('/')
        val legacy = folder == hash.lowercase()
        val named = folder.endsWith("-${hash.take(8).lowercase()}") && folder.toByteArray(Charsets.UTF_8).size <= 160 &&
            !folder.startsWith('.') && folder.codePoints().allMatch { Character.isLetterOrDigit(it) || Character.getType(it) in
                setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt()) || it in intArrayOf(32, 46, 95, 45, 40, 41) }
        require(directory.startsWith('/') && directory.substringBeforeLast('/').endsWith("/Downloads/My-CT/Torrents") &&
            (legacy || named) && directory.split('/').none { it == "." || it == ".." } && directory.none { it.isISOControl() })
        val metadata = row.optDouble("metadata_percent_complete", Double.NaN)
        val known = metadata.isFinite() && metadata >= 1.0
        val total = if (known) number(row, "total_size") else null
        val size = if (known) number(row, "size_when_done") else null
        val left = if (known) number(row, "left_until_done") else null
        val percent = row.optDouble("percent_done", Double.NaN)
        val progress = if (known && percent.isFinite()) percent.also { require(it in 0.0..1.0) }.toFloat() else null
        ServerTorrent(row.getLong("id").also { require(it >= 0) }, hash, row.getString("name"),
            TorrentState.entries[row.getInt("status").also { require(it in 0..6) }], total,
            if (size != null && left != null) (size - left).also { require(it >= 0) } else null,
            progress, number(row, "rate_download"), number(row, "rate_upload"), number(row, "peers_connected"),
            directory, row.optString("error_string", ""),
            optionalTorrentCounter(row, "downloaded_ever"), optionalTorrentCounter(row, "uploaded_ever"),
            (row.opt("upload_ratio") as? Number)?.toDouble()?.takeIf { it.isFinite() && (it >= 0 || it == -1.0 || it == -2.0) })
    }.also { require(it.map { row -> row.hash }.distinct().size == it.size) }
        .sortedByDescending { it.id }
}
internal sealed interface TorrentAction {
    data object List : TorrentAction
    data class Add(val magnet: String) : TorrentAction
    data class Control(val verb: String, val hash: String) : TorrentAction
}
internal fun torrentCommand(action: TorrentAction): String {
    fun quote(value: String): String {
        require(value.length <= 8192 && value.none { it.isISOControl() })
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
    val args = when (action) {
        TorrentAction.List -> "list"
        is TorrentAction.Add -> {
            require(URI(action.magnet).scheme == "magnet")
            "add ${quote(action.magnet)}"
        }
        is TorrentAction.Control -> {
            require(action.verb in setOf("pause", "resume", "remove", "delete-data") && torrentHash.matches(action.hash))
            "${action.verb} ${quote(action.hash)}"
        }
    }
    return "if [ -x \"\$HOME/.local/bin/myct-torrent\" ]; then exec \"\$HOME/.local/bin/myct-torrent\" $args; else exit 127; fi"
}
internal fun executeTorrent(action: TorrentAction, open: () -> DownloadExec, timeoutMs: Long = 25000): String =
    executeSshCommand(torrentCommand(action), open, timeoutMs) { status, reason ->
        DownloadFailure(when {
            status == 127 -> "CLI myct-torrent ausente. Atualize o helper no Fedora."
            status == 3 -> "Transmission indisponível. Confira o serviço myct-transmission no Fedora."
            action is TorrentAction.Control && action.verb == "delete-data" &&
                (reason.contains("hardlinks") || reason.contains("vinculada ao Jellyfin")) ->
                "Exclusão bloqueada: há hardlinks na biblioteca. Retire-os em Mídia → Armazenamento."
            action is TorrentAction.Control && action.verb == "delete-data" ->
                "Não foi possível confirmar a exclusão. Consulte o Transmission antes de tentar novamente."
            else -> "Pedido recusado pelo motor. Confira magnet, diretório e Transmission 4.1+. Atualize antes de repetir."
        })
    }

// Optional on purpose: the installed pre-R2B CLI does not request these RPC fields.
private fun optionalTorrentCounter(row: JSONObject, key: String): Long? {
    val value = row.opt(key)
    return if (value is Int || value is Long) (value as Number).toLong().takeIf { it >= 0 } else null
}
internal fun torrentRatioLabel(value: Double?): String = when {
    value == -2.0 -> "∞"
    value == null || !value.isFinite() || value < 0 -> "Indisponível"
    else -> String.format(Locale.getDefault(), "%.2f", value)
}
