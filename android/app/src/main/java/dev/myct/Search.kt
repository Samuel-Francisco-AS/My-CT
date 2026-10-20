package dev.myct

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

internal data class SearchResult(val title: String, val size: Long?, val source: String?,
    val seeders: Long?, val leechers: Long?, val magnet: String?, val magnetSource: String?,
    val id: String? = null, val indexerId: Int? = null,
    val publicUrl: String? = null, val resultRef: String? = null, val torrentAvailable: Boolean = false)
internal data class SearchIndexer(val id: Int, val name: String, val available: Boolean = true)
internal fun SearchResult.identity(): String = id ?: (magnet?.substringAfter("xt=")?.substringBefore('&')?.lowercase()
    ?: "metadata:$title:$size")
internal sealed interface SearchReply {
    data class Ready(val version: String, val indexers: List<SearchIndexer> = emptyList()) : SearchReply
    data class Results(val items: List<SearchResult>, val truncated: Boolean, val received: Int,
        val page: Int = 1, val indexerId: Int? = null, val hasMore: Boolean = false,
        val total: Int = items.size, val limited: Boolean = false, val paginated: Boolean = false) : SearchReply
}
internal sealed interface SearchAction {
    data object Status : SearchAction
    data class Query(val text: String, val page: Int = 1, val indexerId: Int? = null) : SearchAction
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
            require(action.page in 1..25 && (action.indexerId == null || action.indexerId > 0))
            "search '" + action.text.trim(' ').replace("'", "'\"'\"'") + "'" +
                (if (action.page > 1) " --page ${action.page}" else "") +
                (action.indexerId?.let { " --indexer $it" } ?: "")
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

/** Additional client boundary: public pages only; Fedora also checks DNS before export. */
internal fun publicSearchPage(value: String?): String? = runCatching {
    require(value != null && value.length in 1..2048 && value.none { it.isWhitespace() || it.isISOControl() })
    val uri = URI(value)
    val host = checkNotNull(uri.host).lowercase()
    require(uri.scheme in setOf("http", "https") && uri.rawUserInfo == null && uri.rawFragment == null)
    require(uri.port == -1 || uri.port == if (uri.scheme == "https") 443 else 80)
    require('.' in host && host.any { it in 'a'..'z' } && ':' !in host && !host.startsWith('['))
    require(listOf(".local", ".localhost", ".internal", ".lan", ".home", ".test", ".invalid").none { host.endsWith(it) })
    val decoded = URLDecoder.decode(value, "UTF-8")
    require('%' !in decoded && '\\' !in decoded && decoded.none { it.isWhitespace() || it.isISOControl() })
    val path = uri.path.lowercase()
    require(listOf("api", "download", "announce", "auth", "login", "token", "passkey", "proxy").none { it in path })
    require(!path.endsWith(".torrent") && path.split('/').none { it == "." || it == ".." })
    require(!Regex("[a-f0-9]{24,}|[a-z0-9_=-]{80,}").containsMatchIn(path))
    val query = uri.rawQuery
    require(query == null || (query.isNotBlank() && query.split('&').all { Regex("(?:id|t|p|page)=[0-9]{1,12}").matches(it) }))
    require(Regex("/(?:view|torrent|torrents|details)/[0-9]+(?:/|$)").containsMatchIn(path) ||
        (listOf("/viewtopic.php", "/viewtorrent.php", "/details.php", "/description.php").any { path.endsWith(it) } && query != null) ||
        Regex("-torrent-[0-9]+\\.html$").containsMatchIn(path))
    value
}.getOrNull()

internal fun searchTorrentCommand(query: String, indexerId: Int, resultRef: String): String {
    require(validSearchQuery(query) && indexerId > 0 && Regex("[a-f0-9]{64}").matches(resultRef))
    val quoted = "'" + query.trim(' ').replace("'", "'\"'\"'") + "'"
    return "if [ -x \"\$HOME/.local/bin/myct-search\" ]; then exec \"\$HOME/.local/bin/myct-search\" add-torrent $quoted --indexer $indexerId --result $resultRef; else exit 127; fi"
}

internal fun executeSearchTorrent(action: TorrentAction.AddFile, open: () -> DownloadExec): String {
    val result = executeSshResult(searchTorrentCommand(action.query, action.indexerId, action.resultRef), open, 80000)
    if (result.status == 127) throw DownloadFailure("Helper myct-search ausente. Atualize os helpers S1D no Fedora.")
    val root = JSONObject(result.output)
    if (root.opt("ok") == false) {
        try { parseSearchReply(result.output) } catch (failure: SearchFailure) { throw DownloadFailure(failure.safeMessage) }
    }
    require(result.status == 0 && root.get("schema_version") == 1 && root.get("ok") == true && root.getString("state") == "added")
    return root.toString()
}

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
            "invalid_indexer" -> "Indexador removido ou desabilitado. Atualize as fontes. Sua pesquisa não será repetida automaticamente."
            "indexer_unavailable" -> "Indexador temporariamente indisponível. Escolha outra fonte ou tente mais tarde."
            "response_limit" -> "Resposta excessiva. Refine a pesquisa."
            "stale_result" -> "Resultado ausente ou alterado. Faça uma nova pesquisa antes de adicionar."
            "unsupported_file" -> "Esta fonte não oferece .torrent pelo proxy Prowlarr suportado."
            "download_redirect" -> "A fonte redirecionou em vez de entregar .torrent. Redirects não são seguidos; tente outra fonte ou abra a página."
            "expired_file" -> "Arquivo expirado ou indisponível. Pesquise novamente."
            "download_unavailable" -> "O indexador não entregou .torrent. Tente outra fonte mais tarde."
            "html_response" -> "A fonte devolveu HTML, login ou CAPTCHA em vez de .torrent."
            "file_limit" -> "Arquivo .torrent excedeu o limite de 4 MiB."
            "invalid_metainfo" -> "Resposta sem metadados .torrent completos."
            "metainfo_rejected" -> "Metadados v1 recusados ou motor indisponível. V2, híbridos, arquivos especiais e web seeds não são suportados. Consulte Downloads antes de repetir."
            "addition_uncertain" -> "Inclusão não confirmada. Consulte Downloads → Torrents antes de repetir; não haverá repetição automática."
            "usage" -> "Use uma consulta de 1 a 200 caracteres, sem quebras de linha."
            else -> "Falha na pesquisa do Fedora. Confira o Prowlarr e os indexadores."
        }
        throw SearchFailure(message) // Never render arbitrary helper stderr/error.message.
    }
    require(root.get("ok") == true)
    if (root.getString("state") == "ready") {
        val version = root.getString("version")
        require(version.length <= 64 && version.none { it.isISOControl() })
        val sources = root.optJSONArray("indexers")
        require(sources == null || sources.length() <= 512)
        val indexers = (0 until (sources?.length() ?: 0)).map { index ->
            val source = checkNotNull(sources).getJSONObject(index)
            val id = source.getInt("id").also { require(it > 0) }
            val name = source.getString("name").also { require(it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl)) }
            SearchIndexer(id, name, if (source.has("available")) source.getBoolean("available") else true)
        }
        require(indexers.map { it.id }.distinct().size == indexers.size)
        return SearchReply.Ready(version, indexers)
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
            magnet, origin.takeIf { magnet != null }, text("id", 64)?.also { require(Regex("[a-f0-9]{64}").matches(it)) },
            number("indexer_id")?.also { require(it in 1..Int.MAX_VALUE.toLong()) }?.toInt(),
            publicSearchPage(text("public_url", 2048)),
            text("result_ref", 64)?.also { require(Regex("[a-f0-9]{64}").matches(it)) },
            if (row.has("torrent_available")) row.getBoolean("torrent_available") else false)
    }
    val paginated = root.has("page")
    val page = if (paginated) root.getInt("page").also { require(it in 1..25) } else 1
    val indexerId = if (root.isNull("indexer_id")) null else root.getInt("indexer_id").also { require(it > 0) }
    val hasMore = paginated && root.getBoolean("has_more")
    val total = if (paginated) root.getInt("total_count").also { require(it in items.size..500 && it <= received) } else items.size
    val limited = if (paginated) root.getBoolean("limited") else root.getBoolean("truncated")
    if (paginated) {
        require(root.getInt("total_limit") == 500)
        require(items.all { it.id != null } && items.map { it.id }.distinct().size == items.size)
        require(items.size == (total - (page - 1) * 20).coerceIn(0, 20))
        require(hasMore == (page * 20 < total))
        require(indexerId == null || items.all { it.indexerId == indexerId })
    }
    return SearchReply.Results(items, root.getBoolean("truncated"), received, page, indexerId, hasMore, total, limited, paginated)
}

internal fun executeSearch(action: SearchAction, open: () -> DownloadExec, timeoutMs: Long = 45000): SearchReply {
    val result = executeSshResult(searchCommand(action), open, timeoutMs)
    if (result.status == 127) throw SearchFailure("Helper myct-search ausente. Instale-o no Fedora conforme o relatório MYCT-S1.")
    val reply = parseSearchReply(result.output)
    require(result.status == 0)
    require((action == SearchAction.Status) == (reply is SearchReply.Ready))
    if (action is SearchAction.Query && reply is SearchReply.Results) {
        require(reply.page == action.page && reply.indexerId == action.indexerId)
    }
    return reply
}
