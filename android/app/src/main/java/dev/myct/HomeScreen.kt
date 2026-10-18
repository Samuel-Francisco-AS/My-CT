package dev.myct

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
    val entrance = rememberHomeEntrance()
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
            Row(Modifier.fillMaxWidth().then(entrance(0)), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(3.dp).height(40.dp).background(palette.primary))
                Column(Modifier.weight(1f)) {
                    Text("My-CT", style = MaterialTheme.typography.headlineLarge, color = palette.primary)
                    Text("LOCAL + REMOTO", style = MaterialTheme.typography.labelSmall,
                        color = palette.secondary, letterSpacing = 1.8.sp)
                }
                MyCtTextButton(onClick = onMenu) { Text("Menu") }
            }
            Column(entrance(1), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Seu espaço de trabalho.", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
                Text("Edite e use o terminal no Android.\nConecte ao Fedora quando precisar.",
                    style = MaterialTheme.typography.bodyMedium, color = palette.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("NO ANDROID", modifier = entrance(2), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.4.sp, color = palette.secondary)
                HomeAction(AppSection.Ide, "IDE Local", "Seus projetos e arquivos, mesmo offline.", onIde, modifier = entrance(2), prominent = true)
                HomeAction(AppSection.Terminal, "Terminal Local", "Abrir ou retomar o shell Android.", onTerminal, modifier = entrance(3))
            }
            Column(entrance(4), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                       modifier: Modifier = Modifier, prominent: Boolean = false) {
    val palette = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f,
        tween(120, easing = NavigationEnterEasing), label = "Home press")
    Surface(onClick = onClick, interactionSource = interactions,
        modifier = modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }, shape = MyCtGeometry.Panel,
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
