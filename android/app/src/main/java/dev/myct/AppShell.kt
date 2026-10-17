package dev.myct

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// Small original line icons, built once; no icon pack, bitmap assets or runtime downloads.
private fun shellIcon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(path), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()

private val sectionIcons = mapOf(
    AppSection.Terminal to shellIcon("Terminal", "M4,5 L20,5 L20,19 L4,19 Z M7,9 L10,12 L7,15 M13,15 L17,15"),
    AppSection.Files to shellIcon("Files", "M3,7 L3,19 L21,19 L21,7 L12,7 L10,4 L3,4 Z"),
    AppSection.Health to shellIcon("Health", "M3,12 L7,12 L10,5 L14,19 L17,12 L21,12"),
    AppSection.Vault to shellIcon("Vault", "M5,10 L19,10 L19,21 L5,21 Z M8,10 L8,7 C8,2 16,2 16,7 L16,10 M12,14 L12,17"),
    AppSection.Downloads to shellIcon("Downloads", "M12,3 L12,15 M7,10 L12,15 L17,10 M4,16 L4,21 L20,21 L20,16")
)
private val optionsIcon = shellIcon("Options", "M4,6 L9,6 M13,6 L20,6 M9,3 L9,9 M4,12 L15,12 M19,12 L20,12 M15,9 L15,15 M4,18 L7,18 M11,18 L20,18 M7,15 L7,21")
private val powerIcon = shellIcon("Disconnect", "M12,3 L12,11 M7,6 C0,11 4,21 12,21 C20,21 24,11 17,6")

/** Only owns presentation. Connection, service and screen models remain with their existing owners. */
@Composable
internal fun AppShell(
    section: AppSection,
    phase: TerminalModel.Phase,
    host: String,
    user: String,
    port: String,
    backgroundEnabled: Boolean,
    protectScreenshots: Boolean,
    onSelect: (AppSection) -> Unit,
    onOptions: () -> Unit,
    onCaptureChange: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openMenu: () -> Unit = { scope.launch { drawer.open() }; Unit }
    val closeMenu: () -> Unit = { scope.launch { drawer.close() }; Unit }
    val connected = phase == TerminalModel.Phase.CONNECTED
    val active = connected || phase == TerminalModel.Phase.CONNECTING
    val status = when (phase) {
        TerminalModel.Phase.CONNECTED -> if (backgroundEnabled) "SSH conectado · persistente" else "SSH conectado"
        TerminalModel.Phase.CONNECTING -> "SSH conectando…"
        TerminalModel.Phase.ERROR -> "Falha na conexão SSH"
        TerminalModel.Phase.ENDED -> "SSH encerrado"
        TerminalModel.Phase.IDLE -> "SSH desconectado"
    }
    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = drawer,
        drawerContent = {
            // The state-aware overload supplies Back/predictive Back; outer insets are consumed once.
            ModalDrawerSheet(
                drawerState = drawer,
                modifier = Modifier.widthIn(max = 320.dp).fillMaxHeight(),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerTonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("My-CT", style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Text(status, style = MaterialTheme.typography.labelLarge, color = when {
                            connected -> MaterialTheme.colorScheme.tertiary
                            phase == TerminalModel.Phase.ERROR -> MaterialTheme.colorScheme.error
                            phase == TerminalModel.Phase.CONNECTING -> colorResource(R.color.myct_warning)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        })
                        if (host.isNotBlank()) Text(
                            "${user.takeIf { it.isNotBlank() }?.let { "$it@" } ?: ""}$host:$port",
                            style = MaterialTheme.typography.bodySmall, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    AppSection.entries.forEach { destination ->
                        NavigationDrawerItem(
                            label = { Text(destination.label) },
                            icon = { Icon(checkNotNull(sectionIcons[destination]), contentDescription = null) },
                            selected = section == destination,
                            onClick = {
                                // Register navigation immediately; never wait for animation before accepting input.
                                onSelect(destination)
                                closeMenu()
                            }
                        )
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    NavigationDrawerItem(
                        label = { Text("Opções") }, selected = false,
                        icon = { Icon(optionsIcon, contentDescription = null) },
                        onClick = { closeMenu(); onOptions() }
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Proteger capturas", style = MaterialTheme.typography.bodyMedium)
                            Text(if (section == AppSection.Vault) "Cofre sempre protegido"
                                else if (protectScreenshots) "Captura protegida" else "Captura livre", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = protectScreenshots, onCheckedChange = onCaptureChange,
                            modifier = Modifier.semantics { contentDescription = "Proteção global de capturas" })
                    }
                    TextButton(onClick = { onDisconnect(); closeMenu() }, enabled = active,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(powerIcon, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(if (connected) "Encerrar SSH" else if (active) "Cancelar conexão" else "SSH desconectado")
                    }
                    TextButton(onClick = closeMenu, modifier = Modifier.fillMaxWidth()) { Text("Fechar menu") }
                }
            }
        }
    ) {
        Box(Modifier.fillMaxSize().semantics {
            customActions = listOf(CustomAccessibilityAction("Abrir menu de navegação") { openMenu(); true })
        }) {
            content()
            if (drawer.isClosed) {
                // Reserve only a small segment against Android Back. Material3 handles all drags;
                // no pointer interceptor steals taps/selection from the underlying TerminalView.
                // This semantic action also opens navigation through TalkBack/Voice/Switch Access.
                Box(Modifier.align(Alignment.CenterStart).width(24.dp).height(160.dp)
                    .systemGestureExclusion()
                    .semantics {
                        contentDescription = "Menu de navegação My-CT"
                        onClick(label = "Abrir menu de navegação") { openMenu(); true }
                    })
            }
        }
    }
}
