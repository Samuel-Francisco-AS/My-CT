package dev.myct

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun VaultScreen(model: VaultModel, modifier: Modifier = Modifier) {
    val state = model.state
    var password by remember { mutableStateOf("") }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.phase) { if (state.phase != VaultPhase.PASSWORD) password = "" }
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { password = ""; model.cancelUnlock() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // This screen owns an ephemeral password. Never preserve it in saved state.
    DisposableEffect(model) {
        onDispose {
            password = ""
            model.cancelUnlock()
        }
    }
    LaunchedEffect(model, state.phase == VaultPhase.NO_SSH) {
        if (state.phase == VaultPhase.IDLE || state.phase == VaultPhase.ERROR) model.refresh()
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeading("Cofre", "GNOME Keyring · coleção login. A senha segue somente pelo SSH autenticado a um terminal privado do myct-vault. Não é salva pelo My-CT. A captura desta seção é sempre protegida.")
        ScreenPanel {
            Text(when (state.phase) {
                VaultPhase.NO_SSH -> "SSH desconectado"
                VaultPhase.IDLE -> "Não consultado"
                VaultPhase.UNAVAILABLE -> "Indisponível"
                VaultPhase.DENIED -> "Recusado"
                VaultPhase.ERROR -> "Falha"
                VaultPhase.INPUT_REJECTED -> "Entrada recusada"
                VaultPhase.UNLOCKED -> "Desbloqueado"
                VaultPhase.LOCKED -> "Bloqueado"
                VaultPhase.PASSWORD -> "Aguardando senha"
                else -> if (state.busy) "Em andamento" else "Estado do cofre"
            }, style = MaterialTheme.typography.titleLarge,
                color = if (state.phase == VaultPhase.UNLOCKED) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
            StatusNotice(state.message, state.phase in setOf(VaultPhase.ERROR, VaultPhase.DENIED, VaultPhase.INPUT_REJECTED, VaultPhase.UNAVAILABLE))
            Text("login · captura protegida", style = MaterialTheme.typography.labelSmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MyCtButton(onClick = model::refresh, enabled = state.phase != VaultPhase.NO_SSH &&
                !state.busy && state.phase != VaultPhase.PASSWORD) { Text("Consultar estado") }
            if (state.phase == VaultPhase.LOCKED && state.compatible) {
                MyCtButton(onClick = model::beginUnlock) { Text("Desbloquear") }
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.phase == VaultPhase.PREPARING || state.phase == VaultPhase.UNLOCKING) {
            MyCtTextButton(onClick = model::cancelUnlock) { Text("Cancelar") }
        }
        if (state.phase == VaultPhase.PASSWORD) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Senha do cofre") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MyCtButton(onClick = {
                    val chars = password.toCharArray()
                    password = ""
                    model.submitPassword(chars)
                }, enabled = password.isNotEmpty()) { Text("Confirmar") }
                MyCtTextButton(onClick = { password = ""; model.cancelUnlock() }) { Text("Cancelar") }
            }
        }
        if (state.phase == VaultPhase.PASSWORD || state.phase == VaultPhase.LOCKED) {
            StatusNotice("Desbloquear permite que aplicativos do mesmo usuário acessem seus próprios itens no Secret Service. Não concede permissões individuais a agentes.")
            if (state.phase == VaultPhase.PASSWORD) StatusNotice("Senha apenas nesta tentativa; enviada somente pelo SSH autenticado.")
        }
    }
}
