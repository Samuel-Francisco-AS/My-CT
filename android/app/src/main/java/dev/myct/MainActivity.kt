package dev.myct

import android.content.Context.INPUT_METHOD_SERVICE
import android.os.Bundle
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivity
import android.util.TypedValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import com.termux.view.TerminalView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val model = ViewModelProvider(this)[TerminalModel::class.java]
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { TerminalApp(model) } }
    }
}

@Composable
private fun TerminalApp(model: TerminalModel) {
    val activity = checkNotNull(LocalActivity.current as? ComponentActivity)
    var password by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var forgetDialog by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    val active = model.phase == TerminalModel.Phase.CONNECTING || model.phase == TerminalModel.Phase.CONNECTED
    val pin = model.pinForHost()
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) password = ""
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer); password = "" }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("My-CT · SSH", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
            TextButton(onClick = { licenses = true }) { Text("Licenças") }
        }
        Text(model.message, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = if (model.phase == TerminalModel.Phase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground)
        if (!active) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Text("Identidade do host: confira o fingerprint da chave Ed25519 diretamente no Fedora antes de conectar.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(fingerprint, { fingerprint = it.trim() }, label = { Text("Fingerprint SHA256 confiável") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                    Text("No servidor: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Host fixado: $pin", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { forgetDialog = true }) { Text("Refazer pareamento…") }
                }
                Button(onClick = { val secret = password.toCharArray(); password = ""; model.connect(secret, fingerprint) },
                    modifier = Modifier.fillMaxWidth()) { Text(if (model.terminal == null) "Conectar" else "Reconectar") }
                Text("Use uma rede privada. A senha é enviada pelo canal SSH autenticado e não é salva. Esta prova não mantém serviço Android em segundo plano.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${model.user}@${model.host}:${model.port}", modifier = Modifier.weight(1f).padding(top = 14.dp))
                TextButton(onClick = { model.disconnect() }) { Text(if (model.phase == TerminalModel.Phase.CONNECTING) "Cancelar" else "Encerrar") }
            }
            model.terminal?.let { session ->
                var view by remember(session) { mutableStateOf<TerminalView?>(null) }
                DisposableEffect(view) {
                    val current = view
                    model.callbacks.screenChanged = { current?.onScreenUpdated() }
                    onDispose { model.callbacks.screenChanged = null }
                }
                AndroidView(factory = { context ->
                    TerminalView(context, null).apply {
                        setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics).toInt())
                        setTypeface(Typeface.MONOSPACE)
                        setTerminalViewClient(ViewCallbacks(model) {
                            requestFocus()
                            (context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                        })
                        isFocusableInTouchMode = true
                        attachSession(session)
                        requestFocus()
                        view = this
                    }
                }, modifier = Modifier.fillMaxWidth().weight(1f), update = { terminalView ->
                    if (terminalView.currentSession !== session) terminalView.attachSession(session)
                })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { model.toggleCtrl(); view?.requestFocus() }) { Text(if (model.ctrl) "Ctrl ●" else "Ctrl") }
                    TextButton(onClick = { model.sendCtrlC(); view?.requestFocus() }) { Text("Ctrl-C") }
                    TextButton(onClick = { model.sendKey(KeyEvent.KEYCODE_ESCAPE); view?.requestFocus() }) { Text("Esc") }
                    TextButton(onClick = { model.sendKey(KeyEvent.KEYCODE_TAB); view?.requestFocus() }) { Text("Tab") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("←" to KeyEvent.KEYCODE_DPAD_LEFT, "↑" to KeyEvent.KEYCODE_DPAD_UP,
                        "↓" to KeyEvent.KEYCODE_DPAD_DOWN, "→" to KeyEvent.KEYCODE_DPAD_RIGHT).forEach { (label, key) ->
                        TextButton(onClick = { model.sendKey(key); view?.requestFocus() }) { Text(label) }
                    }
                    TextButton(onClick = {
                        view?.let { terminalView ->
                            terminalView.requestFocus()
                            (activity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }) { Text("Teclado") }
                }
            }
        }
    }
    if (forgetDialog) AlertDialog(onDismissRequest = { forgetDialog = false }, title = { Text("Refazer pareamento do host?") },
        text = { Text("Uma chave alterada pode indicar ataque. Só remova o pin após confirmar a mudança diretamente no Fedora. Será necessário informar novamente o fingerprint confiável.") },
        confirmButton = { TextButton(onClick = { model.forgetPin(); fingerprint = ""; forgetDialog = false }) { Text("Remover pin") } },
        dismissButton = { TextButton(onClick = { forgetDialog = false }) { Text("Cancelar") } })
    if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Licenças de terceiros") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(activity.assets.open("THIRD_PARTY_LICENSES.txt").bufferedReader().use { it.readText() }, style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = { licenses = false }) { Text("Fechar") } })
}
