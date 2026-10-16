package dev.myct

import org.json.JSONObject
import java.net.URI

internal val torrentHash = Regex("[0-9a-fA-F]{40}")
internal enum class TorrentState(val label: String) {
    STOPPED("Pausado"), CHECK_WAIT("Na fila de verificação"), CHECK("Verificando"),
    DOWNLOAD_WAIT("Na fila de download"), DOWNLOAD("Baixando"), SEED_WAIT("Na fila de compartilhamento"), SEED("Compartilhando (seeding)")
}
internal data class ServerTorrent(val id: Long, val hash: String, val name: String, val state: TorrentState,
    val total: Long?, val bytes: Long?, val progress: Float?, val down: Long?, val up: Long?,
    val peers: Long?, val directory: String, val error: String) {
    val paused get() = state == TorrentState.STOPPED
}
internal fun parseTorrents(text: String): List<ServerTorrent> {
    val rows = JSONObject(text).getJSONArray("torrents")
    fun number(row: JSONObject, key: String): Long? = if (row.isNull(key)) null else row.getLong(key).also { require(it >= 0) }
    return (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        val hash = row.getString("hash_string"); require(torrentHash.matches(hash))
        val directory = row.getString("download_dir")
        require(directory.startsWith('/') && directory.endsWith("/Downloads/My-CT/Torrents/${hash.lowercase()}") &&
            directory.split('/').none { it == "." || it == ".." } && directory.none { it.isISOControl() })
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
            directory, row.optString("error_string", ""))
    }.also { require(it.map { row -> row.hash }.distinct().size == it.size) }
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
            require(action.verb in setOf("pause", "resume", "remove") && torrentHash.matches(action.hash))
            "${action.verb} ${quote(action.hash)}"
        }
    }
    return "if [ -x \"\$HOME/.local/bin/myct-torrent\" ]; then exec \"\$HOME/.local/bin/myct-torrent\" $args; else exit 127; fi"
}
internal fun executeTorrent(action: TorrentAction, open: () -> DownloadExec, timeoutMs: Long = 25000): String =
    executeSshCommand(torrentCommand(action), open, timeoutMs) { status, _ ->
        DownloadFailure(when (status) {
            127 -> "CLI myct-torrent ausente. Consulte a instalação MYCT-5C no Fedora."
            3 -> "Transmission indisponível. Confira pacote e serviço myct-transmission no Fedora."
            else -> "Pedido recusado pelo motor. Confira magnet, diretório e Transmission 4.1+. Atualize antes de repetir."
        })
    }
