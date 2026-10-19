package dev.myct

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

internal data class SearchResult(val title: String, val size: Long?, val source: String?,
    val seeders: Long?, val leechers: Long?, val magnet: String?, val magnetSource: String?)
internal sealed interface SearchReply {
    data class Ready(val version: String) : SearchReply
    data class Results(val items: List<SearchResult>, val truncated: Boolean, val received: Int) : SearchReply
}
internal sealed interface SearchAction {
    data object Status : SearchAction
    data class Query(val text: String) : SearchAction
}
internal class SearchFailure(val safeMessage: String) : Exception(safeMessage)

internal fun validSearchQuery(value: String): Boolean = value.trim(' ').let {
    it.codePointCount(0, it.length) in 1..200 && Regex("[\\p{L}\\p{M}\\p{N}\\p{P}\\p{S} ]+").matches(it)
}

internal fun searchCommand(action: SearchAction): String {
    val args = when (action) {
        SearchAction.Status -> "status"
        is SearchAction.Query -> {
            require(validSearchQuery(action.text))
            "search '" + action.text.trim(' ').replace("'", "'\"'\"'") + "'"
        }
    }
    return "if [ -x \"\$HOME/.local/bin/myct-search\" ]; then exec \"\$HOME/.local/bin/myct-search\" $args; else exit 127; fi"
}

/** Only a v1 magnet usable by the existing torrent helper can enable the action. */
internal fun usableSearchMagnet(value: String?): String? = runCatching {
    require(value != null && value.length <= 8192 && value.none { it.isWhitespace() || it.isISOControl() })
    val uri = URI(value)
    require(uri.scheme == "magnet" && uri.rawFragment == null && value.startsWith("magnet:?"))
    val params = value.substringAfter('?').split('&').filter { it.isNotEmpty() }.map {
        require('=' in it)
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }
    require(params.all { it.first in setOf("xt", "dn", "tr") })
    val hashes = params.filter { it.first == "xt" }.map { it.second }
    require(hashes.size == 1 && Regex("urn:btih:(?:[a-fA-F0-9]{40}|[A-Za-z2-7]{32})").matches(hashes.single()))
    value
}.getOrNull()

internal fun parseSearchReply(output: String): SearchReply {
    require(output.toByteArray(Charsets.UTF_8).size <= 262144)
    val root = JSONObject(output)
    require(root.get("schema_version") == 1)
    if (root.get("ok") == false) {
        val message = when (root.getJSONObject("error").getString("code")) {
            "config" -> "Configuração privada do Prowlarr indisponível no Fedora. Confira o helper."
            "authentication" -> "Prowlarr recusou a autenticação local. Confira a configuração no Fedora."
            "connection" -> "Prowlarr indisponível no Fedora."
            "timeout" -> "Tempo limite da pesquisa. Tente novamente por ação manual."
            "no_indexers" -> "Nenhum indexador torrent habilitado no Prowlarr."
            "response_limit" -> "Resposta excessiva. Refine a pesquisa."
            "usage" -> "Use uma consulta de 1 a 200 caracteres, sem quebras de linha."
            else -> "Falha na pesquisa do Fedora. Confira o Prowlarr e os indexadores."
        }
        throw SearchFailure(message) // Never render arbitrary helper stderr/error.message.
    }
    require(root.get("ok") == true)
    if (root.getString("state") == "ready") {
        val version = root.getString("version")
        require(version.length <= 64 && version.none { it.isISOControl() })
        return SearchReply.Ready(version)
    }
    val state = root.getString("state")
    require(state in setOf("results", "empty") && root.getInt("result_limit") == 20)
    val rows = root.getJSONArray("results")
    require(rows.length() <= 20 && root.getInt("count") == rows.length())
    require((state == "empty") == (rows.length() == 0))
    val received = root.getInt("received_count").also { require(it >= rows.length()) }
    val items = (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        fun number(key: String): Long? {
            val value = row.opt(key)
            if (value == null || value == JSONObject.NULL) return null
            require(value is Int || value is Long)
            return (value as Number).toLong().also { require(it >= 0) }
        }
        fun text(key: String, limit: Int): String? {
            if (row.isNull(key)) return null
            return row.getString(key).also { require(it.length <= limit && it.none { c -> c.isISOControl() }) }
        }
        val title = checkNotNull(text("title", 1024)).also { require(it.isNotBlank()) }
        val magnet = usableSearchMagnet(text("magnet", 8192))
        val origin = text("magnet_source", 16)
        require(origin == null || origin in setOf("direct", "infohash"))
        SearchResult(title, number("size_bytes"), text("source", 256), number("seeders"), number("leechers"),
            magnet, origin.takeIf { magnet != null })
    }
    return SearchReply.Results(items, root.getBoolean("truncated"), received)
}

internal fun executeSearch(action: SearchAction, open: () -> DownloadExec, timeoutMs: Long = 45000): SearchReply {
    val result = executeSshResult(searchCommand(action), open, timeoutMs)
    if (result.status == 127) throw SearchFailure("Helper myct-search ausente. Instale-o no Fedora conforme o relatório MYCT-S1.")
    val reply = parseSearchReply(result.output)
    require(result.status == 0)
    require((action == SearchAction.Status) == (reply is SearchReply.Ready))
    return reply
}
