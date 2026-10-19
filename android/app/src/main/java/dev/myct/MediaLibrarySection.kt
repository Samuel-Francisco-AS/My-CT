package dev.myct

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Turns e.g. [1, 2, 3, 5] into 01–03, 05 for compact season inventory. */
internal fun mediaEpisodeRanges(numbers: List<Int>): String {
    if (numbers.isEmpty()) return "Nenhum episódio identificado"
    val sorted = numbers.distinct().sorted()
    val spans = mutableListOf<String>()
    var start = sorted.first()
    var end = start
    fun format(n: Int) = n.toString().padStart(2, '0')
    fun appendSpan() {
        spans.add(if (start == end) format(start) else "${format(start)}–${format(end)}")
    }
    for (n in sorted.drop(1)) {
        if (n == end + 1) end = n
        else { appendSpan(); start = n; end = n }
    }
    appendSpan()
    return spans.joinToString(", ")
}

@Composable
internal fun MediaLibraryGroupCard(
    group: MediaLibraryGroup,
    onUse: (MediaLibraryGroup, MediaLibrarySeason?) -> Unit
) {
    var expanded by remember(group.path) { mutableStateOf(false) }
    ScreenPanel {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(group.folder, style = MaterialTheme.typography.titleSmall)
                Text(when(group.kind) {
                    "anime" -> "Anime"
                    "series" -> "Série"
                    else -> "Filme"
                } + " · ${group.files} vídeo(s) · ${group.managed} gerenciado(s) pelo My-CT",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.titleLarge)
        }
        if (expanded) {
            Text("Título a usar no My-CT: ${group.title}",
                style = MaterialTheme.typography.bodyMedium)
            Text("Diretório: Media/${group.path}",
                style = MaterialTheme.typography.bodySmall)
            if (group.kind == "movie") {
                Text("Ano: ${group.year ?: "não identificado"}",
                    style = MaterialTheme.typography.bodySmall)
                MyCtTextButton(onClick = { onUse(group, null) }) {
                    Text("Usar este filme no organizador")
                }
            } else {
                if (group.seasons.isEmpty()) Text(
                    "Nenhuma pasta Season NN encontrada.",
                    style = MaterialTheme.typography.bodySmall
                )
                group.seasons.forEach { season ->
                    HorizontalDivider()
                    Text("Temporada ${season.number.toString().padStart(2, '0')} · ${season.files} arquivo(s)",
                        style = MaterialTheme.typography.titleSmall)
                    Text("Episódios identificados: ${mediaEpisodeRanges(season.episodes)}",
                        style = MaterialTheme.typography.bodySmall)
                    if (season.missing.isNotEmpty()) Text(
                        "Lacunas na numeração: ${mediaEpisodeRanges(season.missing)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (season.unidentified > 0) Text(
                        "${season.unidentified} arquivo(s) sem numeração reconhecida",
                        style = MaterialTheme.typography.bodySmall
                    )
                    MyCtTextButton(onClick = { onUse(group, season) }) {
                        Text("Usar título e temporada ${season.number}")
                    }
                }
                MyCtTextButton(onClick = { onUse(group, null) }) {
                    Text("Usar título (nova temporada)")
                }
            }
        }
    }
}
