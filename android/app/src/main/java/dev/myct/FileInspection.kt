package dev.myct

import org.json.JSONObject

internal data class FileInspection(val path: String, val name: String, val bytes: Long,
    val extension: String, val type: String, val sha256: String, val warning: String)
internal val sha256Pattern = Regex("[0-9a-fA-F]{64}")
internal fun compareChecksum(actual: String, supplied: String): String {
    val expected = supplied.trim()
    return when {
        expected.isEmpty() -> "NÃO INFORMADO"
        !sha256Pattern.matches(expected) -> "Informe exatamente 64 caracteres hexadecimais."
        actual.equals(expected, ignoreCase = true) -> "CORRESPONDE"
        else -> "DIVERGENTE"
    }
}
// Only hexadecimal data reaches the remote shell. No path text is interpreted as shell syntax.
internal fun inspectionCommand(path: String): String {
    RemotePath.absolute(path)
    val hex = path.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 255) }
    require(hex.length <= 32768)
    return "exec \"\$HOME/.local/bin/myct-inspect\" $hex --watch-stdin"
}
internal fun parseInspection(output: String, path: String): FileInspection {
    val j = JSONObject(output)
    require(j.getInt("version") == 1 && j.getString("path") == path)
    val name = j.getString("name"); val bytes = j.getLong("bytes"); val hash = j.getString("sha256")
    require(name == path.substringAfterLast('/') && bytes >= 0 && sha256Pattern.matches(hash))
    return FileInspection(path, name, bytes, j.getString("extension"), j.getString("type"), hash, j.getString("warning"))
}
internal fun executeInspection(path: String, open: () -> DownloadExec): FileInspection {
    val text = executeSshCommand(inspectionCommand(path), open, 30 * 60 * 1000L + 10000) { status, error ->
        DownloadFailure(if (status == 127 || status == 126) "Ferramenta myct-inspect ausente ou sem permissão. Consulte a instalação manual no relatório MYCT-5D-A."
            else error.trim().take(512).ifEmpty { "Inspeção incompleta: falha no comando remoto." })
    }
    return parseInspection(text, path)
}
