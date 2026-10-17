package dev.myct

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.app.NotificationManager
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.PersistableBundle
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val model get() = (application as MyCtApplication).terminal
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startPersistentConnection()
        else Toast.makeText(this, "Notificação visível é necessária para o modo persistente. A sessão atual continua na tela.", Toast.LENGTH_LONG).show()
    }
    fun enablePersistentConnection() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startPersistentConnection()
    }
    private fun startPersistentConnection() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || model.phase != TerminalModel.Phase.CONNECTED) return
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            Toast.makeText(this, "Ative as notificações do My-CT para manter conexão com aviso visível.", Toast.LENGTH_LONG).show(); return
        }
        try { startForegroundService(Intent(this, SshForegroundService::class.java).setAction(SshForegroundService.ENABLE)) }
        catch (_: Exception) { Toast.makeText(this, "O Android recusou o serviço. Tente novamente com o app visível.", Toast.LENGTH_LONG).show() }
    }
    fun stopPersistentConnection() {
        startService(Intent(this, SshForegroundService::class.java).setAction(SshForegroundService.DISCONNECT))
    }
    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations && !model.backgroundEnabled && !model.files.transferKeepsConnection) model.disconnect("Tela encerrada sem modo persistente; reconecte manualmente.")
        super.onDestroy()
    }
    override fun onStart() {
        super.onStart()
        model.files.onForeground()
    }
    override fun onStop() {
        if (!isChangingConfigurations) model.files.onBackground()
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        applyCaptureProtection(model.protectScreenshots)
        setContent {
            MyCtTheme {
                // Surface supplies the correct onBackground content color to plain Text components.
                Surface(color = MaterialTheme.colorScheme.background) { TerminalApp(model) }
            }
        }
    }
    fun applyCaptureProtection(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

private data class PendingPaste(val session: TerminalSession, val text: String)

@Composable
private fun TerminalApp(model: TerminalModel) {
    val activity = checkNotNull(LocalActivity.current as? MainActivity)
    var password by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var forgetDialog by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    var tmuxHelp by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var captureWarning by remember { mutableStateOf(false) }
    var stopPersistenceFor by remember { mutableStateOf<TerminalSession?>(null) }
    var measurements by remember { mutableStateOf<String?>(null) }
    var pendingPaste by remember { mutableStateOf<PendingPaste?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(AppSection.Terminal) }
    val active = model.phase == TerminalModel.Phase.CONNECTING || model.phase == TerminalModel.Phase.CONNECTED
    val connected = model.phase == TerminalModel.Phase.CONNECTED
    val pin = model.pinForHost()
    fun feedback(text: String) { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show() }
    fun copyToSystemClipboard(text: String) {
        if (!ClipboardPolicy.allowed(text)) { feedback("Selecione texto não vazio de até 64 KiB."); return }
        try {
            val clip = ClipData.newPlainText("My-CT", text)
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            feedback("Texto copiado para a área de transferência do Android.")
        } catch (_: Exception) { feedback("Não foi possível copiar.") }
    }
    fun requestSystemPaste() {
        if (!connected) return
        try {
            val clip = (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            if (text == null || !ClipboardPolicy.allowed(text)) {
                feedback("A área de transferência precisa conter texto simples de até 64 KiB."); return
            }
            model.terminal?.let { pendingPaste = PendingPaste(it, text) }
        } catch (_: Exception) { feedback("Não foi possível ler o clipboard nesta tela.") }
    }
    // Always block screenshots on the vault tab, regardless of the general preference.
    val captureProtected = captureProtectionRequired(model.protectScreenshots, selectedTab)
    DisposableEffect(activity, captureProtected) {
        activity.applyCaptureProtection(captureProtected)
        onDispose { }
    }
    LaunchedEffect(model.phase, model.backgroundEnabled, model.terminal) {
        if (!canConfirmPersistenceStop(model.phase == TerminalModel.Phase.CONNECTED,
                model.backgroundEnabled, stopPersistenceFor, model.terminal)) stopPersistenceFor = null
    }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { password = ""; pendingPaste = null; stopPersistenceFor = null }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    // A single union consumes overlap once; AndroidView receives the actual remaining area.
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout).union(WindowInsets.ime)
    AppShell(
        section = selectedTab, phase = model.phase, host = model.host, user = model.user, port = model.port,
        backgroundEnabled = model.backgroundEnabled, protectScreenshots = model.protectScreenshots,
        onSelect = { destination ->
            if (selectedTab != destination) {
                pendingPaste = null
                if (destination != AppSection.Terminal) (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                selectedTab = destination
            }
        },
        onOptions = { settings = true },
        onCaptureChange = { if (it) model.setCaptureProtection(true) else captureWarning = true },
        onPersistenceChange = { requested ->
            requestPersistenceChange(model.phase == TerminalModel.Phase.CONNECTED, model.backgroundEnabled, requested,
                enable = { activity.enablePersistentConnection() },
                confirmStop = { stopPersistenceFor = model.terminal })
        },
        onDisconnect = { model.disconnect() },
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(insets)
    ) {
        AppSectionContent(selectedTab) {
            Column(Modifier.fillMaxSize()) {
                if (selectedTab == AppSection.Downloads) DownloadsScreen(model.downloads, model.torrents, model.files.busy, { path ->
                    if (!model.files.busy && connected) { model.files.load(path); selectedTab = AppSection.Files }
                }, Modifier.weight(1f))
                else if (selectedTab == AppSection.Vault) VaultScreen(model.vault, Modifier.weight(1f))
                else if (selectedTab == AppSection.Health) HealthScreen(model.health, Modifier.weight(1f))
                else if (!active) {
                    if (model.files.recoveryNotice.isNotEmpty()) Text(model.files.recoveryNotice,
                        modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
                    Text(model.message, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = if (model.phase == TerminalModel.Phase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScreenHeading("Conectar ao Fedora", "Use rede privada. Senha não é salva; perda do processo exige nova autenticação. Pareamento verifica a identidade do servidor antes de conectar.")
                        OutlinedTextField(model.host, { model.host = it; fingerprint = "" }, label = { Text("Endereço do Fedora") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(model.port, { model.port = it; fingerprint = "" }, label = { Text("Porta") },
                                singleLine = true, modifier = Modifier.weight(0.35f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            OutlinedTextField(model.user, { model.user = it }, label = { Text("Usuário Linux") },
                                singleLine = true, modifier = Modifier.weight(0.65f), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                        }
                        OutlinedTextField(password, { password = it }, label = { Text("Senha (somente nesta conexão)") },
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (pin == null) {
                            Text("Confira o fingerprint Ed25519 diretamente no Fedora por canal confiável.", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(fingerprint, { fingerprint = it.trim() }, label = { Text("Fingerprint SHA256 confiável") },
                                singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                            Text("ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256", style = MaterialTheme.typography.bodySmall)
                        } else {
                            DetailValue("Host fixado", pin, maxLines = 1)
                            MyCtTextButton(onClick = { forgetDialog = true }) { Text("Refazer pareamento…") }
                        }
                        MyCtButton(onClick = { val secret = password.toCharArray(); password = ""; model.connect(secret, fingerprint) },
                            modifier = Modifier.fillMaxWidth()) { Text(if (model.terminal == null) "Conectar" else "Reconectar") }
                        Text("Use rede privada. Senha não é salva. Perda do processo exige nova autenticação; trabalho remoto pode ser preservado pelo tmux.", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    if (connected && selectedTab == AppSection.Files) FilesScreen(model.files, Modifier.weight(1f))
                    else {
                    model.terminal?.let { session ->
                        var view by remember(session) { mutableStateOf<TerminalView?>(null) }
                        val fontPx = with(LocalDensity.current) { model.fontSizeSp.sp.toPx().roundToInt() }
                        var appliedCharBasedInput by remember(view) { mutableStateOf(model.enforceCharBasedInput) }
                        LaunchedEffect(view, model.enforceCharBasedInput, settings) {
                            if (!settings && appliedCharBasedInput != model.enforceCharBasedInput) view?.let { terminalView ->
                                terminalView.requestFocus()
                                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(terminalView)
                                appliedCharBasedInput = model.enforceCharBasedInput
                            }
                        }
                        val clipboardActions = rememberUpdatedState(object : TerminalSession.UserClipboard {
                            override fun copy(text: String) { copyToSystemClipboard(text) }
                            override fun requestPaste() { requestSystemPaste() }
                        })
                        DisposableEffect(session, view, activity) {
                            val current = view
                            val redraw: () -> Unit = { current?.onScreenUpdated(); Unit }
                            val manualClipboard = object : TerminalSession.UserClipboard {
                                override fun copy(text: String) { clipboardActions.value.copy(text) }
                                override fun requestPaste() { clipboardActions.value.requestPaste() }
                            }
                            fun attach() {
                                model.callbacks.screenChanged = redraw
                                session.setUserClipboard(manualClipboard)
                                current?.onScreenUpdated()
                            }
                            fun detach() {
                                if (model.callbacks.screenChanged === redraw) model.callbacks.screenChanged = null
                                session.clearUserClipboard(manualClipboard)
                            }
                            val observer = LifecycleEventObserver { _, event ->
                                when (event) { Lifecycle.Event.ON_START -> attach(); Lifecycle.Event.ON_STOP -> detach(); else -> Unit }
                            }
                            activity.lifecycle.addObserver(observer)
                            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) attach()
                            onDispose { activity.lifecycle.removeObserver(observer); detach() }
                        }
                        AndroidView(factory = { context ->
                            TerminalView(context, null).apply {
                                setTextSize(fontPx)
                                tag = fontPx
                                setTerminalViewClient(ViewCallbacks(model) {
                                    requestFocus()
                                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                                })
                                isFocusableInTouchMode = true
                                attachSession(session)
                                requestFocus()
                                view = this
                            }
                        }, modifier = Modifier.fillMaxWidth().weight(1f).onSizeChanged {
                            // TerminalView.onSizeChanged already resizes the local emulator.
                            model.diagnostics.viewport = "Área: ${it.width}×${it.height} px · PTY: ${session.emulator.mRows}×${session.emulator.mColumns} · fonte: ${model.fontSizeSp} sp / $fontPx px"
                        }, update = { terminalView ->
                            if (terminalView.currentSession !== session) terminalView.attachSession(session)
                            if (terminalView.tag != fontPx) {
                                terminalView.setTextSize(fontPx); terminalView.tag = fontPx; terminalView.onScreenUpdated()
                                model.diagnostics.viewport = "Área: ${terminalView.width}×${terminalView.height} px · PTY: ${session.emulator.mRows}×${session.emulator.mColumns} · fonte: ${model.fontSizeSp} sp / $fontPx px"
                            }
                        })
                        Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState())) {
                            MyCtTextButton(onClick = { model.toggleCtrl(); view?.requestFocus() }) { Text(if (model.ctrl) "Ctrl ●" else "Ctrl") }
                            MyCtTextButton(onClick = { model.sendCtrlC(); view?.requestFocus() }) { Text("Ctrl-C") }
                            listOf("Esc" to KeyEvent.KEYCODE_ESCAPE, "Tab" to KeyEvent.KEYCODE_TAB,
                                "←" to KeyEvent.KEYCODE_DPAD_LEFT, "↑" to KeyEvent.KEYCODE_DPAD_UP,
                                "↓" to KeyEvent.KEYCODE_DPAD_DOWN, "→" to KeyEvent.KEYCODE_DPAD_RIGHT).forEach { (label, key) ->
                                MyCtTextButton(onClick = { model.sendKey(key); view?.requestFocus() }) { Text(label) }
                            }
                            MyCtTextButton(onClick = { requestSystemPaste() }, enabled = connected) { Text("Colar…") }
                            MyCtTextButton(onClick = {
                                view?.let { terminalView ->
                                    terminalView.requestFocus()
                                    (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
                                }
                            }) { Text("Teclado") }
                        }
                    }
                    }
                }
            }
        }
    }
    if (settings) {
        var antivirusOptions by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { settings = false }, title = { Text("Opções") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (antivirusOptions) {
                    MyCtTextButton(onClick = { antivirusOptions = false }) { Text("← Voltar às opções") }
                    AntivirusSignaturesSection(model.antivirusSignatures)
                } else {
                    ScreenPanel {
                        ScreenHeading("Terminal", "Entrada por caracteres controla como o teclado envia texto. A preferência existente é aplicada ao fechar Opções. Use A− e A+ para ajustar a fonte da grade ANSI.")
                        MyCtOperationalToggle("Entrada por caracteres", "Preferência de entrada do teclado", model.enforceCharBasedInput,
                            onCheckedChange = { model.setCharBasedInput(it) })
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Fonte · ${model.fontSizeSp} sp", Modifier.weight(1f))
                            MyCtTextButton(onClick = { model.changeFont(-1) }) { Text("A−") }
                            MyCtTextButton(onClick = { model.changeFont(1) }) { Text("A+") }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    ScreenPanel {
                        ScreenHeading("Proteção e clipboard", "A preferência de captura é local. Cofre permanece sempre protegido. A área de transferência pertence ao Android e pode conter dados sensíveis.")
                        MyCtOperationalToggle("Proteger capturas", "Cofre sempre protegido", model.protectScreenshots,
                            onCheckedChange = { if (it) model.setCaptureProtection(true) else captureWarning = true })
                        StatusNotice("Clipboard pode conter dados sensíveis.")
                    }
                    Spacer(Modifier.height(12.dp))
                    ScreenPanel {
                        Text("Ferramentas", style = MaterialTheme.typography.titleMedium)
                        MyCtTextButton(onClick = { antivirusOptions = true }) { Text("Antivírus e assinaturas") }
                        MyCtTextButton(onClick = { tmuxHelp = true; settings = false }) { Text("Recuperar trabalho · tmux") }
                        MyCtTextButton(onClick = { licenses = true; settings = false }) { Text("Licenças") }
                        ExpandDetails("Diagnóstico local") {
                            MyCtTextButton(onClick = { model.diagnostics.startSample(); settings = false }) { Text("Amostrar engasgos · 60 s") }
                            MyCtTextButton(onClick = { measurements = model.diagnostics.snapshot(); settings = false }) { Text("Medições locais") }
                        }
                    }
                }
            }
        }, confirmButton = { MyCtTextButton(onClick = { settings = false }) { Text("Fechar") } })
    }
    stopPersistenceFor?.let { expected ->
        AlertDialog(onDismissRequest = { stopPersistenceFor = null },
            title = { Text("Desativar conexão persistente?") },
            text = { Text("Desativar encerrará a sessão SSH atual e removerá sua notificação. Para voltar a usar o terminal, será necessário conectar novamente.") },
            confirmButton = { MyCtTextButton(
                enabled = canConfirmPersistenceStop(model.phase == TerminalModel.Phase.CONNECTED,
                    model.backgroundEnabled, expected, model.terminal),
                onClick = {
                    val requestedSession = stopPersistenceFor
                    stopPersistenceFor = null
                    confirmPersistenceStop(model.phase == TerminalModel.Phase.CONNECTED,
                        model.backgroundEnabled, requestedSession, model.terminal) { activity.stopPersistentConnection() }
                }
            ) { Text("Desativar e encerrar SSH") } },
            dismissButton = { MyCtTextButton(onClick = { stopPersistenceFor = null }) { Text("Cancelar") } })
    }
    if (tmuxHelp) AlertDialog(onDismissRequest = { tmuxHelp = false }, title = { Text("Trabalho persistente no Fedora") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Requer tmux no host. No prompt de shell vazio do Fedora, cole e execute manualmente:")
            Text(MYCT_TMUX_COMMAND)
            Text("Cria/retoma myct-main em socket dedicado myct, sem trocar sessões do tmux padrão. O app não executará esse comando automaticamente. Para sair sem encerrar o trabalho: Ctrl-B, depois d. Após novo SSH, use o mesmo comando. Reboot do Fedora encerra a sessão.")
        } }, confirmButton = { MyCtTextButton(onClick = { copyToSystemClipboard(MYCT_TMUX_COMMAND); tmuxHelp = false }) { Text("Copiar comando") } },
        dismissButton = { MyCtTextButton(onClick = { tmuxHelp = false }) { Text("Fechar") } })
    if (captureWarning) AlertDialog(onDismissRequest = { captureWarning = false }, title = { Text("Permitir capturas?") },
        text = { Text("Screenshots e a tela de apps recentes poderão expor comandos e segredos. A preferência é local; a conexão SSH não será reiniciada.") },
        confirmButton = { MyCtTextButton(onClick = { model.setCaptureProtection(false); captureWarning = false }) { Text("Permitir") } },
        dismissButton = { MyCtTextButton(onClick = { captureWarning = false }) { Text("Cancelar") } })
    pendingPaste?.let { paste ->
        AlertDialog(onDismissRequest = { pendingPaste = null }, title = { Text("Enviar texto ao terminal?") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Quebras de linha podem executar comandos. Confira antes de enviar.")
                Text(ClipboardPolicy.preview(paste.text))
            } }, confirmButton = { MyCtTextButton(onClick = {
                if (connected && model.terminal === paste.session) paste.session.emulator.paste(paste.text)
                else feedback("A sessão mudou; cole novamente na conexão desejada.")
                pendingPaste = null
            }) { Text("Enviar") } }, dismissButton = { MyCtTextButton(onClick = { pendingPaste = null }) { Text("Cancelar") } })
    }
    measurements?.let { text -> AlertDialog(onDismissRequest = { measurements = null }, title = { Text("Diagnóstico sem conteúdo") },
        text = { Text(text, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { MyCtTextButton(onClick = { measurements = null }) { Text("Fechar") } }) }
    if (forgetDialog) AlertDialog(onDismissRequest = { forgetDialog = false }, title = { Text("Refazer pareamento do host?") },
        text = { Text("Chave alterada pode indicar ataque. Remova o pin somente após confirmar a mudança diretamente no Fedora.") },
        confirmButton = { MyCtTextButton(onClick = { model.forgetPin(); fingerprint = ""; forgetDialog = false }) { Text("Remover pin") } },
        dismissButton = { MyCtTextButton(onClick = { forgetDialog = false }) { Text("Cancelar") } })
    if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Licenças de terceiros") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(activity.assets.open("THIRD_PARTY_LICENSES.txt").bufferedReader().use { it.readText() }, style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { MyCtTextButton(onClick = { licenses = false }) { Text("Fechar") } })
}
