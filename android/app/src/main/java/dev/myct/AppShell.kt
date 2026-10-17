package dev.myct

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// Small original line icons, built once; no icon pack, bitmap assets or runtime downloads.
private fun shellIcon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(path), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()

private val sectionIcons = mapOf(
    AppSection.Terminal to shellIcon("Terminal", "M4,5 L20,5 L20,19 L4,19 Z M7,9 L10,12 L7,15 M13,15 L17,15"),
    AppSection.Files to shellIcon("Files", "M3,7 L3,19 L21,19 L21,7 L12,7 L10,4 L3,4 Z"),
    AppSection.Ide to shellIcon("IDE", "M8,5 L2,12 L8,19 M16,5 L22,12 L16,19 M14,3 L10,21"),
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
    onPersistenceChange: (Boolean) -> Unit,
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
        // Closed: only the directional edge observer may open. Open: Material3 retains
        // drag-to-close, scrim click/semantics and arbitration with its vertical scroller.
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            // The state-aware overload supplies Back/predictive Back; outer insets are consumed once.
            ModalDrawerSheet(
                drawerState = drawer,
                modifier = Modifier.widthIn(max = 320.dp).fillMaxHeight(),
                drawerShape = MyCtGeometry.Drawer,
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerTonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val palette = MaterialTheme.colorScheme
                    Surface(shape = MyCtGeometry.Panel, color = palette.background,
                        border = BorderStroke(1.dp, palette.outlineVariant), shadowElevation = 2.dp) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.width(3.dp).height(36.dp).background(palette.primary))
                                Column {
                                    Text("My-CT", style = MaterialTheme.typography.headlineLarge)
                                    Text("PAINEL REMOTO", style = MaterialTheme.typography.labelSmall,
                                        color = palette.secondary, letterSpacing = 1.8.sp)
                                }
                            }
                            MyCtPanelRule()
                            val statusColor = when {
                                connected -> palette.tertiary
                                phase == TerminalModel.Phase.ERROR -> palette.error
                                phase == TerminalModel.Phase.CONNECTING -> colorResource(R.color.myct_warning)
                                else -> palette.onSurfaceVariant
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(6.dp).background(statusColor))
                                Text(status, style = MaterialTheme.typography.labelMedium, color = statusColor)
                            }
                            if (host.isNotBlank()) Text(
                                "${user.takeIf { it.isNotBlank() }?.let { "$it@" } ?: ""}$host:$port",
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, color = palette.onSurfaceVariant)
                        }
                    }
                    DrawerCaption("NAVEGAÇÃO")
                    AppSection.entries.forEach { destination ->
                        DrawerDestination(destination.label, checkNotNull(sectionIcons[destination]), section == destination) {
                            // Accept navigation immediately; the overlay closes without a layout transition.
                            onSelect(destination)
                            closeMenu()
                        }
                    }
                    DrawerCaption("CONTROLES")
                    Surface(shape = MyCtGeometry.Panel, color = palette.surfaceVariant,
                        border = BorderStroke(1.dp, palette.outlineVariant)) {
                        Column {
                            MyCtOperationalToggle(
                                label = "Manter conexão ativa",
                                description = when {
                                    !connected -> if (active) "Aguarde a conexão SSH" else "Disponível após conectar"
                                    backgroundEnabled -> "Notificação SSH ativa"
                                    else -> "Continuar em segundo plano"
                                },
                                checked = backgroundEnabled, enabled = connected, onCheckedChange = onPersistenceChange
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = palette.outlineVariant)
                            MyCtOperationalToggle(
                                label = "Proteger capturas",
                                description = if (section == AppSection.Vault) "Cofre sempre protegido"
                                    else if (protectScreenshots) "Captura protegida" else "Captura livre",
                                checked = protectScreenshots, onCheckedChange = onCaptureChange
                            )
                        }
                    }
                    MyCtPanelRule()
                    Surface(onClick = { closeMenu(); onOptions() }, shape = MyCtGeometry.Control,
                        color = palette.surface, border = BorderStroke(1.dp, palette.outlineVariant)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(optionsIcon, contentDescription = null, tint = palette.secondary)
                            Text("Opções", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    MyCtTextButton(onClick = { onDisconnect(); closeMenu() }, enabled = active,
                        colors = ButtonDefaults.textButtonColors(contentColor = palette.error),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(powerIcon, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(if (connected) "Encerrar SSH" else if (active) "Cancelar conexão" else "SSH desconectado")
                    }
                    MyCtTextButton(onClick = closeMenu, modifier = Modifier.fillMaxWidth()) { Text("Fechar menu") }
                }
            }
        }
    ) {
        Box(Modifier.fillMaxSize().drawerEdgeOpening(drawer, scope).semantics {
            customActions = listOf(CustomAccessibilityAction("Abrir menu de navegação") { openMenu(); true })
        }) {
            content()
            if (drawer.isClosed) {
                // Reserve only a small segment against Android Back; no pointer input overlay.
                // This semantic action also opens navigation through TalkBack/Voice/Switch Access.
                Box(Modifier.align(Alignment.CenterStart).width(DrawerOpeningEdgeWidth).height(160.dp)
                    .systemGestureExclusion()
                    .semantics {
                        contentDescription = "Menu de navegação My-CT"
                        onClick(label = "Abrir menu de navegação") { openMenu(); true }
                    })
            }
        }
    }
}


@Composable
private fun DrawerCaption(label: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun DrawerDestination(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    Surface(selected = selected, onClick = onClick, shape = MyCtGeometry.Control,
        color = if (selected) palette.primaryContainer else palette.surface,
        contentColor = if (selected) palette.primary else palette.onSurfaceVariant,
        border = if (selected) BorderStroke(1.dp, palette.outline) else null,
        shadowElevation = if (selected) 1.dp else 0.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Fixed geometry: selection changes color/weight, never row position or size.
            Box(Modifier.width(3.dp).height(24.dp).background(if (selected) palette.primary else Color.Transparent))
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.weight(1f))
            Box(Modifier.size(5.dp).background(if (selected) palette.secondary else Color.Transparent))
        }
    }
}
