package dev.myct

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class DesktopPanel { CONTROLS, SESSION, DIAGNOSTICS }

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopScreen(model: DesktopModel, openMenu: () -> Unit, modifier: Modifier = Modifier,
    onExit: () -> Unit = {}, backEnabled: Boolean = true) {
    val activity = checkNotNull(LocalActivity.current)
    val owner = LocalLifecycleOwner.current
    val image = remember { DesktopImage() }
    var view by remember { mutableStateOf<DesktopView?>(null) }
    var password by remember { mutableStateOf("") }
    var askPassword by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var immersive by rememberSaveable { mutableStateOf(false) }
    var controls by rememberSaveable { mutableStateOf(true) }
    var extraKeys by remember { mutableStateOf(false) }
    var imageControls by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<DesktopPanel?>(null) }
    var memory by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val connected = model.link == DesktopLink.CONNECTED
    val bars = remember(activity) { WindowCompat.getInsetsController(activity.window, activity.window.decorView) }

    DisposableEffect(model, owner) {
        fun show() = model.show({ frame -> image.update(frame, model.attempt) }, image::clear, image)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) show()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                model.detachSurface(image, activity.isChangingConfigurations)
                view?.cancelInput(); password = ""; askPassword = false
            }
            if (event == Lifecycle.Event.ON_START) show()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            model.detachSurface(image, activity.isChangingConfigurations)
            view?.release(); image.close(); password = ""
        }
    }
    DisposableEffect(immersive) {
        val previousBehavior = bars.systemBarsBehavior
        if (immersive) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (immersive) { bars.show(WindowInsetsCompat.Type.systemBars()); bars.systemBarsBehavior = previousBehavior }
        }
    }
    LaunchedEffect(connected) { if (!connected) { extraKeys = false; imageControls = false; bars.hide(WindowInsetsCompat.Type.ime()) } }
    val imeVisible = WindowInsets.isImeVisible
    val goBack = {
        when (desktopBackAction(imeVisible, immersive)) {
            DesktopBackAction.CLOSE_KEYBOARD -> { view?.finishEditorComposition(); bars.hide(WindowInsetsCompat.Type.ime()) }
            DesktopBackAction.RESTORE_CONTROLS -> { immersive = false; controls = true }
            DesktopBackAction.EXIT_SECTION -> onExit()
        }
    }
    BackHandler(enabled = backEnabled, onBack = goBack)
    val openOptions = {
        view?.finishEditorComposition(); view?.cancelInput()
        bars.hide(WindowInsetsCompat.Type.ime())
        panel = if (connected) DesktopPanel.CONTROLS else DesktopPanel.SESSION
    }
    val toolbar: @Composable () -> Unit = {
        DesktopToolbar(connected, model.link == DesktopLink.CONNECTING,
            connectEnabled = model.ssh && !model.retiring && !model.changing,
            immersive = immersive, back = goBack, menu = openMenu,
            connection = { if (connected || model.link == DesktopLink.CONNECTING) model.disconnect() else askPassword = true },
            keyboard = { if (imeVisible) { view?.finishEditorComposition(); bars.hide(WindowInsetsCompat.Type.ime()) } else view?.showKeyboard() },
            keyboardVisible = imeVisible, options = openOptions,
            fullscreen = {
                immersive = !immersive; controls = !immersive
                extraKeys = false; imageControls = false; model.releaseInput()
            })
    }
    Box(modifier.fillMaxSize().clipToBounds()) {
        Column(Modifier.fillMaxSize()) {
            if (!immersive) {
                toolbar()
                DesktopSummary(model)
            }
            // The only native viewport. It is never recreated when panels/controls change.
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                AndroidView(factory = { context -> DesktopView(context, model, image).also { view = it } },
                    modifier = Modifier.fillMaxSize().clipToBounds())
                DesktopPlaceholder(model, openOptions)
            }
            if (!immersive && connected) {
                if (extraKeys) DesktopExtraKeys(model, view) { extraKeys = false; model.releaseInput() }
                if (imageControls) DesktopImageControls(view) { imageControls = false }
            }
        }
        if (immersive) {
            if (controls) Surface(Modifier.align(Alignment.TopCenter), color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
                Column {
                    toolbar()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { DesktopSummary(model) }
                        DesktopAction("Recolher controles", DesktopIcons.close, onClick = { controls = false; extraKeys = false; imageControls = false; model.releaseInput() })
                    }
                }
            } else Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), shape = MyCtGeometry.Control,
                color = MaterialTheme.colorScheme.surface.copy(alpha = .92f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                DesktopAction("Mostrar controles", DesktopIcons.controls, onClick = { controls = true })
            }
            // An error must also be visible when the overlay toolbar is hidden.
            if (!controls && (model.link == DesktopLink.ERROR || model.adminOutcome in desktopAdminErrors)) {
                Surface(modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp), color = MaterialTheme.colorScheme.errorContainer,
                    onClick = { controls = true; openOptions() }) { Text("Falha · toque para ver opções", Modifier.padding(12.dp)) }
            } else if (connected && (extraKeys || imageControls)) Surface(Modifier.align(Alignment.BottomCenter)) {
                Column {
                    if (extraKeys) DesktopExtraKeys(model, view) { extraKeys = false; model.releaseInput() }
                    if (imageControls) DesktopImageControls(view) { imageControls = false }
                }
            }
        }
    }
    if (panel != null) ModalBottomSheet(onDismissRequest = { panel = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, shape = MyCtGeometry.Panel) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Desktop", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                DesktopAction("Fechar opções", DesktopIcons.close, onClick = { panel = null })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connected) FilterChip(panel == DesktopPanel.CONTROLS, { panel = DesktopPanel.CONTROLS }, label = { Text("Controles") })
                FilterChip(panel == DesktopPanel.SESSION, { panel = DesktopPanel.SESSION }, label = { Text("Sessão") })
                FilterChip(panel == DesktopPanel.DIAGNOSTICS, { panel = DesktopPanel.DIAGNOSTICS }, label = { Text("Diagnóstico") })
            }
            when (panel) {
                DesktopPanel.CONTROLS -> if (connected) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DesktopInteraction.entries.forEach { mode ->
                            FilterChip(model.interaction == mode, {
                                view?.cancelInput(); model.changeInteraction(mode); view?.updateInteractionDescription()
                            }, label = { Text(mode.label) })
                        }
                    }
                    if (model.interaction == DesktopInteraction.TRACKPAD) {
                        Text("Sensibilidade do trackpad · pixels remotos por dp", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(.75f to "Precisa", 1.5f to "Normal", 2.25f to "Rápida").forEach { (gain, label) ->
                                FilterChip(model.trackpadGain == gain, { model.changeSensitivity(gain) }, label = { Text(label) })
                            }
                        }
                    }
                    FilterChip(model.localPan, {
                        view?.cancelInput(); model.changePan(!model.localPan); view?.updateInteractionDescription()
                    }, label = { Text(if (model.localPan) "Dois dedos: pan local" else "Dois dedos: rolar Fedora") })
                    Text(if (model.drag) "Modo arrastar · botão esquerdo pressionado ao mover" else "Modo mover · toque clica, segurar abre clique direito",
                        style = MaterialTheme.typography.bodySmall)
                    FilterChip(model.drag, { model.changeDrag(!model.drag) }, label = { Text(if (model.drag) "Desativar arraste" else "Ativar arraste") })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MyCtTextButton(onClick = { model.click(4); panel = null }) { Text("Clique direito") }
                        MyCtTextButton(onClick = { extraKeys = !extraKeys; imageControls = false; panel = null }) { Text(if (extraKeys) "Recolher teclas extras" else "Teclas extras") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Imagem", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MyCtTextButton(onClick = { view?.resetZoom(); panel = null }) { Text("Ajustar à área") }
                        MyCtTextButton(onClick = { imageControls = true; extraKeys = false; model.releaseInput(); panel = null }) { Text("Zoom e pan") }
                    }
                    Text("Pinça amplia até 4× o ajuste. Escolha rolagem ou pan local para dois dedos. A faixa Zoom e pan oferece setas para qualquer direção.", style = MaterialTheme.typography.bodySmall)
                } else Text("Conecte para usar mouse e teclado remotos.")
                DesktopPanel.SESSION -> {
                    Text("Unidade Fedora: ${desktopServiceLabel(model.service)}", style = MaterialTheme.typography.labelLarge)
                    Text(model.administration.message, style = MaterialTheme.typography.bodySmall)
                    if (model.adminMessage.isNotEmpty()) Text(model.adminMessage, color = if (model.adminOutcome in desktopAdminErrors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MyCtTextButton(onClick = model::refresh, enabled = model.ssh && !model.busy) { Text("Atualizar estado") }
                        MyCtTextButton(onClick = model::start, enabled = model.ssh && !model.busy && model.administration.canStart && model.link != DesktopLink.CONNECTING && model.service == DesktopService.OFF) { Text("Iniciar Desktop") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Desconectar preserva os aplicativos do Fedora. Encerrar fecha a sessão e seus programas.", style = MaterialTheme.typography.bodySmall)
                    MyCtTextButton(onClick = { panel = null; confirmStop = true }, enabled = model.ssh && !model.busy && model.administration.canStop && model.link != DesktopLink.CONNECTING && model.service in listOf(DesktopService.AVAILABLE, DesktopService.STARTING),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Encerrar Desktop…") }
                }
                DesktopPanel.DIAGNOSTICS -> {
                    DesktopDiagnostics(model)
                    MyCtTextButton(onClick = { scope.launch {
                        val pss = withContext(Dispatchers.IO) { android.os.Debug.getPss() }
                        memory = "PSS de todo o My-CT: ${pss / 1024} MiB"
                    } }) { Text("Medir memória") }
                    memory?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                null -> Unit
            }
        }
    }
    if (askPassword) AlertDialog(onDismissRequest = { askPassword = false; password = "" },
        title = { Text("Senha VNC do display :1") },
        text = { Column {
            Text("Senha já configurada no TigerVNC. Até 8 caracteres Latin-1; somente em memória durante a conexão.")
            OutlinedTextField(value = password, onValueChange = { password = it.take(8) }, label = { Text("Senha VNC") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
        } },
        confirmButton = { MyCtTextButton(onClick = { val secret = password.toCharArray(); password = ""; askPassword = false; model.connect(secret) },
            enabled = password.isNotEmpty()) { Text("Conectar") } },
        dismissButton = { MyCtTextButton(onClick = { password = ""; askPassword = false }) { Text("Cancelar") } })
    if (confirmStop) AlertDialog(onDismissRequest = { confirmStop = false },
        title = { Text("Encerrar a sessão gráfica?") },
        text = { Text("Isso para vncserver@:1.service e fecha os aplicativos gráficos do Fedora; trabalho não salvo pode ser perdido. Desconectar apenas a visualização preserva a sessão.") },
        confirmButton = { MyCtTextButton(onClick = { confirmStop = false; model.stop() }, enabled = model.administration.canStop && !model.busy) { Text("Encerrar Desktop") } },
        dismissButton = { MyCtTextButton(onClick = { confirmStop = false }) { Text("Cancelar") } })
}

private val desktopAdminErrors = setOf(DesktopAdminOutcome.DENIED, DesktopAdminOutcome.UNAVAILABLE, DesktopAdminOutcome.FAILED, DesktopAdminOutcome.UNCERTAIN)

/** Reads only stable summary state, never the per-frame counter/message. */
@Composable
private fun DesktopSummary(model: DesktopModel) {
    val state = when (model.link) {
        DesktopLink.CONNECTING -> "Conectando…"
        DesktopLink.CONNECTED -> if (model.presented) "Conectado · ${model.resolution}" else "Aguardando imagem…"
        DesktopLink.ERROR -> model.diagnostics.state.failure?.label ?: "Falha de conexão"
        DesktopLink.DISCONNECTED -> when {
            !model.ssh -> "Sem SSH · conecte no Terminal"
            model.service == DesktopService.OFF -> "Desktop desligado"
            model.service == DesktopService.STARTING -> "Desktop iniciando…"
            model.service == DesktopService.STOPPING -> "Desktop encerrando…"
            else -> "Desconectado · ${desktopServiceLabel(model.service)}"
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        Text("Desktop · $state${if (model.link == DesktopLink.CONNECTED) " · ${model.interaction.label}" else ""}${if (model.drag) " · Arraste ativo" else ""}", style = MaterialTheme.typography.labelMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (model.link == DesktopLink.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (model.adminOutcome in desktopAdminErrors || model.changing) Text(model.adminMessage,
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (model.adminOutcome in desktopAdminErrors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopToolbar(connected: Boolean, connecting: Boolean, connectEnabled: Boolean, immersive: Boolean,
    back: () -> Unit, menu: () -> Unit, connection: () -> Unit, keyboard: () -> Unit, keyboardVisible: Boolean,
    options: () -> Unit, fullscreen: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        // Six 48dp targets fit at 288dp. At narrower widths FlowRow wraps instead of truncating.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            DesktopAction("Voltar", DesktopIcons.back, onClick = back)
            DesktopAction("Menu", DesktopIcons.menu, onClick = menu)
            DesktopAction(if (connected || connecting) "Desconectar" else "Conectar", if (connected || connecting) DesktopIcons.disconnect else DesktopIcons.connect,
                enabled = connected || connecting || connectEnabled, onClick = connection)
            if (connected) DesktopAction(if (keyboardVisible) "Ocultar teclado" else "Teclado", DesktopIcons.keyboard, onClick = keyboard)
            DesktopAction("Opções do Desktop", DesktopIcons.controls, onClick = options)
            DesktopAction(if (immersive) "Sair da tela cheia" else "Tela cheia", DesktopIcons.fullscreen, onClick = fullscreen)
        }
    }
}

@Composable
private fun DesktopAction(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = label, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
    }
}

@Composable
private fun DesktopPlaceholder(model: DesktopModel, options: () -> Unit) {
    if (model.link == DesktopLink.CONNECTED && model.presented) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (model.link == DesktopLink.CONNECTING || model.link == DesktopLink.CONNECTED) CircularProgressIndicator(Modifier.size(24.dp))
            Text(when (model.link) {
                DesktopLink.ERROR -> model.diagnostics.state.failure?.label ?: "Falha de conexão"
                DesktopLink.CONNECTING -> "Conectando por túnel SSH…"
                DesktopLink.CONNECTED -> "Aguardando o primeiro quadro real…"
                else -> if (model.ssh) "Conecte para visualizar o Fedora." else "Conecte o SSH pelo Terminal."
            }, style = MaterialTheme.typography.bodyMedium)
            if (model.link != DesktopLink.CONNECTING && model.link != DesktopLink.CONNECTED)
                MyCtTextButton(onClick = options) { Text("Sessão e diagnóstico") }
        }
    }
}

@Composable
private fun DesktopExtraKeys(model: DesktopModel, view: DesktopView?, close: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DesktopAction("Recolher teclas extras", DesktopIcons.close, onClick = close)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Ctrl" to DesktopKeys.CTRL, "Alt" to DesktopKeys.ALT, "Shift" to DesktopKeys.SHIFT).forEach { (name, sym) ->
                FilterChip(selected = when (sym) { DesktopKeys.CTRL -> model.ctrl; DesktopKeys.ALT -> model.alt; else -> model.shift },
                    onClick = { view?.finishEditorComposition(); model.toggleModifier(sym) }, label = { Text(name) }, modifier = Modifier.heightIn(min = 48.dp))
            }
            listOf("Esc" to DesktopKeys.ESC, "Tab" to DesktopKeys.TAB, "Enter" to DesktopKeys.ENTER,
                "⌫" to DesktopKeys.BACKSPACE, "←" to 0xff51, "↑" to 0xff52, "↓" to 0xff54, "→" to 0xff53).forEach { (name, sym) ->
                MyCtTextButton(onClick = { view?.specialKey(sym) ?: model.press(sym) }, modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = when (name) { "⌫" -> "Backspace"; "←" -> "Seta esquerda"; "↑" -> "Seta cima"; "↓" -> "Seta baixo"; "→" -> "Seta direita"; else -> name }
                }) { Text(name) }
            }
        }
    }
}

@Composable
private fun DesktopImageControls(view: DesktopView?, close: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DesktopAction("Recolher ajuste de imagem", DesktopIcons.close, onClick = close)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            MyCtTextButton(onClick = { view?.resetZoom() }) { Text("Ajustar") }
            MyCtTextButton(onClick = { view?.zoomBy(.8f) }) { Text("Zoom −") }
            MyCtTextButton(onClick = { view?.zoomBy(1.25f) }) { Text("Zoom +") }
            DesktopAction("Deslocar imagem à esquerda", DesktopIcons.left, onClick = { view?.panBy(-.2f, 0f) })
            DesktopAction("Deslocar imagem para cima", DesktopIcons.up, onClick = { view?.panBy(0f, -.2f) })
            DesktopAction("Deslocar imagem para baixo", DesktopIcons.down, onClick = { view?.panBy(0f, .2f) })
            DesktopAction("Deslocar imagem à direita", DesktopIcons.right, onClick = { view?.panBy(.2f, 0f) })
        }
    }
}

@Composable
private fun DesktopDiagnostics(model: DesktopModel) {
    val diagnostic = model.diagnostics.state
    Text(model.message, style = MaterialTheme.typography.bodySmall)
    Text("Atualizações RFB: ${model.frames} · não são FPS", style = MaterialTheme.typography.bodySmall)
    Text("Superfícies apresentadas: ${model.surfacePresentations}", style = MaterialTheme.typography.bodySmall)
    Text("Conectar → primeiro desenho: ${model.connectionFirstFrameMs?.let { "$it ms" } ?: "—"}", style = MaterialTheme.typography.bodySmall)
    Text("Configuração → primeiro desenho: ${model.configurationRecoveryMs?.let { "$it ms" } ?: "—"}", style = MaterialTheme.typography.bodySmall)
    val input = model.inputStats()
    Text("Fila: pico ${input.first}/128 · movimentos agrupados ${input.second} · falhas ${model.inputFailures}", style = MaterialTheme.typography.bodySmall)
    Text("Última concluída: ${diagnostic.lastCompleted?.label ?: "nenhuma"}", style = MaterialTheme.typography.labelMedium)
    DesktopStep.entries.forEach { step ->
        val mark = if (step in diagnostic.completed) "OK" else if (step == diagnostic.current) "AGUARDE" else "—"
        Text("$mark · ${step.label}", style = MaterialTheme.typography.bodySmall)
    }
    diagnostic.failure?.let { Text("ERRO: ${it.label}", color = MaterialTheme.colorScheme.error) }
}

// Original line icons, matching AppShell. No icon-pack dependency.
private fun desktopIcon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(path), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()
private object DesktopIcons {
    val back = desktopIcon("Back", "M14,5 L7,12 L14,19 M7,12 L21,12")
    val menu = desktopIcon("Menu", "M4,6 L20,6 M4,12 L20,12 M4,18 L20,18")
    val connect = desktopIcon("Connect", "M5,7 L5,17 L19,17 L19,7 Z M9,3 L9,7 M15,3 L15,7 M12,17 L12,21")
    val disconnect = desktopIcon("Disconnect", "M5,7 L5,17 L19,17 L19,7 Z M8,10 L16,14 M16,10 L8,14 M12,17 L12,21")
    val keyboard = desktopIcon("Keyboard", "M2,5 L22,5 L22,19 L2,19 Z M6,9 L7,9 M11,9 L12,9 M16,9 L18,9 M6,13 L7,13 M11,13 L12,13 M16,13 L18,13 M7,16 L17,16")
    val controls = desktopIcon("Controls", "M4,6 L9,6 M13,6 L20,6 M9,3 L9,9 M4,12 L15,12 M19,12 L20,12 M15,9 L15,15 M4,18 L7,18 M11,18 L20,18 M7,15 L7,21")
    val fullscreen = desktopIcon("Fullscreen", "M9,3 L3,3 L3,9 M15,3 L21,3 L21,9 M3,15 L3,21 L9,21 M21,15 L21,21 L15,21")
    val close = desktopIcon("Close", "M6,6 L18,18 M18,6 L6,18")
    val left = desktopIcon("Left", "M14,5 L7,12 L14,19")
    val up = desktopIcon("Up", "M5,14 L12,7 L19,14")
    val down = desktopIcon("Down", "M5,10 L12,17 L19,10")
    val right = desktopIcon("Right", "M10,5 L17,12 L10,19")
}
