package dev.myct

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.foundation.verticalScroll
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

@OptIn(ExperimentalLayoutApi::class)
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
    var immersive by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var memory by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val connected = model.link == DesktopLink.CONNECTED

    DisposableEffect(model, owner) {
        fun show() = model.show({ frame -> image.update(frame, model.attempt) }, image::clear)
        show()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { model.hide(); password = ""; askPassword = false }
            if (event == Lifecycle.Event.ON_START) show()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.hide(); view?.release(); image.close(); password = "" }
    }
    DisposableEffect(immersive) {
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (immersive) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (immersive) bars.show(WindowInsetsCompat.Type.systemBars()) }
    }
    val imeVisible = WindowInsets.isImeVisible
    val goBack = {
        when (desktopBackAction(imeVisible, immersive)) {
            DesktopBackAction.CLOSE_KEYBOARD -> WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
            DesktopBackAction.RESTORE_CONTROLS -> { immersive = false; controls = true }
            DesktopBackAction.EXIT_SECTION -> onExit()
        }
    }
    BackHandler(enabled = backEnabled, onBack = goBack)
    val diagnostic = model.diagnostics.state
    val state = when (model.link) {
        DesktopLink.CONNECTING -> "Conectando"
        DesktopLink.CONNECTED -> if (model.presented) "Conectada · imagem apresentada" else "Autenticada · aguardando imagem"
        DesktopLink.ERROR -> "Erro · ${diagnostic.failure?.label ?: "conexão"}"
        DesktopLink.DISCONNECTED -> if (model.ssh) "Desconectada · SSH disponível" else "Desconectada · sem SSH"
    }
    Column(modifier.fillMaxSize()) {
        // This toolbar and state never belong to the framebuffer, immersive mode or optional controls.
        Text("Desktop", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MyCtTextButton(onClick = goBack) { Text("Voltar") }
            MyCtTextButton(onClick = openMenu) { Text("Menu") }
            MyCtTextButton(onClick = { controls = !controls }) { Text(if (controls) "Recolher" else "Controles") }
            if (connected) MyCtTextButton(onClick = { view?.showKeyboard() }) { Text("Teclado") }
        }
        Text(state, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(model.message, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${if (diagnostic.failure != null) "Falha em" else if (diagnostic.current in diagnostic.completed) "Concluída" else "Aguardando"}: ${diagnostic.current.label}", Modifier.weight(1f).padding(start = 8.dp),
                style = MaterialTheme.typography.bodySmall, maxLines = 2)
            MyCtTextButton(onClick = { showDiagnostics = !showDiagnostics }) { Text("Diagnóstico") }
        }
        if (showDiagnostics) Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(8.dp)) {
            Text("Última concluída: ${diagnostic.lastCompleted?.label ?: "nenhuma"}", style = MaterialTheme.typography.labelMedium)
            DesktopStep.entries.forEach { step ->
                val mark = if (step in diagnostic.completed) "OK" else if (step == diagnostic.current) "AGUARDE" else "—"
                Text("$mark · ${step.label}", style = MaterialTheme.typography.bodySmall)
            }
            diagnostic.failure?.let { Text("ERRO: ${it.label}", color = MaterialTheme.colorScheme.error) }
        }
        if (controls) Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                MyCtTextButton(onClick = { immersive = !immersive }) { Text(if (immersive) "Sair tela cheia" else "Tela cheia") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MyCtTextButton(onClick = model::refresh, enabled = model.ssh && !model.busy) { Text("Atualizar estado") }
                if (!connected && model.link != DesktopLink.CONNECTING) {
                    MyCtTextButton(onClick = { askPassword = true }, enabled = model.ssh && !model.retiring && !model.changing) { Text(if (model.link == DesktopLink.ERROR) "Tentar novamente" else "Conectar") }
                    MyCtTextButton(onClick = model::start, enabled = model.ssh && !model.busy && model.administration.canStart && model.link != DesktopLink.CONNECTING && model.service == DesktopService.OFF) { Text("Iniciar Desktop") }
                } else MyCtTextButton(onClick = model::disconnect) { Text("Desconectar") }
                MyCtTextButton(onClick = { confirmStop = true }, enabled = model.ssh && !model.busy && model.administration.canStop && model.link != DesktopLink.CONNECTING && model.service in listOf(DesktopService.AVAILABLE, DesktopService.STARTING)) { Text("Encerrar Desktop") }
            }
            Text("Unidade Fedora: ${desktopServiceLabel(model.service)}", Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodySmall)
            if (model.adminMessage.isNotEmpty()) Text(model.adminMessage, Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodySmall)
            Text(model.administration.message, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
            if (connected) {
                memory?.let { Text(it, modifier = Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = model.drag, onClick = { model.changeDrag(!model.drag) }, label = { Text(if (model.drag) "Arrastar: botão pressionado" else "Mover: sem pressionar") })
                    MyCtTextButton(onClick = { model.click(4) }) { Text("Clique direito") }
                    MyCtTextButton(onClick = { view?.resetZoom() }) { Text("Ajustar imagem") }
                    MyCtTextButton(onClick = { scope.launch {
                        val pss = withContext(Dispatchers.IO) { android.os.Debug.getPss() }
                        memory = "PSS de todo o My-CT: ${pss / 1024} MiB"
                    } }) { Text("Medir memória") }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Ctrl" to DesktopKeys.CTRL, "Alt" to DesktopKeys.ALT, "Shift" to DesktopKeys.SHIFT).forEach { (name, sym) ->
                        FilterChip(selected = when (sym) { DesktopKeys.CTRL -> model.ctrl; DesktopKeys.ALT -> model.alt; else -> model.shift },
                            onClick = { model.toggleModifier(sym) }, label = { Text(name) })
                    }
                    listOf("Esc" to DesktopKeys.ESC, "Tab" to DesktopKeys.TAB, "Enter" to DesktopKeys.ENTER,
                        "⌫" to DesktopKeys.BACKSPACE, "←" to 0xff51, "↑" to 0xff52, "↓" to 0xff54, "→" to 0xff53).forEach { (name, sym) ->
                        MyCtTextButton(onClick = { model.press(sym) }) { Text(name) }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            AndroidView(factory = { context -> DesktopView(context, model, image).also { view = it } },
                modifier = Modifier.fillMaxSize().clipToBounds())
            if (!connected || model.frames == 0L) Column(Modifier.align(Alignment.Center).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (model.link == DesktopLink.CONNECTING || connected) CircularProgressIndicator(Modifier.size(24.dp))
                Text(when (model.link) {
                    DesktopLink.ERROR -> "A conexão falhou. Use Tentar novamente ou Voltar."
                    DesktopLink.CONNECTING -> "Aguardando ${diagnostic.current.label.lowercase()}…"
                    DesktopLink.CONNECTED -> "Aguardando o primeiro quadro real…"
                    else -> "Use Conectar para abrir a sessão gráfica já iniciada."
                }, style = MaterialTheme.typography.bodySmall)
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
