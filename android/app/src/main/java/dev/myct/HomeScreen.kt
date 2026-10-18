package dev.myct

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Entry points into the existing sections; owns no connection, buffer or shell state. */
@Composable
internal fun HomeScreen(phase: TerminalModel.Phase, onIde: () -> Unit, onTerminal: () -> Unit,
                        onFedora: () -> Unit, onMenu: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    val remoteStatus = when (phase) {
        TerminalModel.Phase.CONNECTED -> "SSH conectado"
        TerminalModel.Phase.CONNECTING -> "SSH conectando…"
        TerminalModel.Phase.ERROR -> "Falha na conexão SSH"
        TerminalModel.Phase.ENDED -> "SSH encerrado"
        TerminalModel.Phase.IDLE -> "SSH desconectado"
    }
    val statusColor = when (phase) {
        TerminalModel.Phase.CONNECTED -> palette.tertiary
        TerminalModel.Phase.ERROR -> palette.error
        else -> palette.onSurfaceVariant
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(3.dp).height(40.dp).background(palette.primary))
                Column(Modifier.weight(1f)) {
                    Text("My-CT", style = MaterialTheme.typography.headlineLarge, color = palette.primary)
                    Text("LOCAL + REMOTO", style = MaterialTheme.typography.labelSmall,
                        color = palette.secondary, letterSpacing = 1.8.sp)
                }
                MyCtTextButton(onClick = onMenu) { Text("Menu") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Seu espaço de trabalho.", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
                Text("Edite e use o terminal no Android.\nConecte ao Fedora quando precisar.",
                    style = MaterialTheme.typography.bodyMedium, color = palette.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("NO ANDROID", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.4.sp, color = palette.secondary)
                HomeAction(AppSection.Ide, "IDE Local", "Seus projetos e arquivos, mesmo offline.", onIde, prominent = true)
                HomeAction(AppSection.Terminal, "Terminal Local", "Abrir ou retomar o shell Android.", onTerminal)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MyCtPanelRule()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(5.dp).background(statusColor))
                    Text("FEDORA · $remoteStatus", style = MaterialTheme.typography.labelSmall, color = statusColor)
                }
                HomeAction(AppSection.Terminal,
                    if (phase == TerminalModel.Phase.CONNECTED) "Abrir Fedora"
                    else if (phase == TerminalModel.Phase.CONNECTING) "Ver conexão ao Fedora" else "Conectar ao Fedora",
                    "Terminal SSH, arquivos e ferramentas remotas.", onFedora)
            }
        }
    }
}

@Composable
private fun HomeAction(section: AppSection, title: String, description: String, onClick: () -> Unit,
                       prominent: Boolean = false) {
    val palette = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = MyCtGeometry.Panel,
        color = if (prominent) palette.primaryContainer else palette.surface,
        border = BorderStroke(1.dp, if (prominent) palette.outline else palette.outlineVariant)) {
        Row(Modifier.heightIn(min = 88.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(checkNotNull(sectionIcons[section]), contentDescription = null,
                modifier = Modifier.size(26.dp), tint = palette.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = palette.primary)
        }
    }
}
