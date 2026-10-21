package dev.myct

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun ServerSetupSection(model: ServerSetupModel) {
    var approve by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    LaunchedEffect(model.connected) { if (model.connected) model.diagnose() }
    DisposableEffect(Unit) { onDispose { password = "" } }
    ScreenHeading("Configurar servidor", "Base Fedora e componentes opcionais. Diagnóstico sem alterar configurações. Requer Fedora tradicional 43/44, SSH e python3. Nenhuma IA é necessária.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SetupTarget.entries.forEach { value ->
            MyCtTextButton(onClick = { model.select(value) }, enabled = !model.busy) {
                Text(if (model.target == value) "● ${value.label}" else value.label)
            }
        }
    }
    if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    StatusNotice(model.message)
    MyCtTextButton(onClick = { model.diagnose() }, enabled = model.connected && !model.busy) { Text("Diagnosticar novamente") }
    model.plan?.let { plan ->
        ScreenPanel {
            Text("${plan.system} · ${plan.arch}", style = MaterialTheme.typography.titleSmall)
            Text("Conta SSH: ${plan.user}")
            Text("Disponível: ${formatFileSize(plan.memory)} RAM · ${formatFileSize(plan.disk)} em disco")
            Text("Privilégios: ${if (plan.admin == "unavailable") "sudo indisponível" else "sudo sujeito à política do Fedora"}")
            plan.inventory?.let { v ->
                Text(when { v.managed && v.installed -> "Instalado e gerenciado pelo SETUP-2"; v.managed -> "Instalação gerenciada incompleta · requer nova consulta"; v.detected -> "Instalação anterior não gerenciada · somente inspeção"; else -> "Ausente" })
                Text("Versão: ${v.version.ifBlank { "não confirmada" }}")
                Text("Em execução: ${if (v.running) "sim" else "não"} · início automático: ${if (v.enabled) "habilitado" else "não habilitado"}")
                Text("API local: ${if (v.apiReady) "disponível" else "não confirmada"} · linger: ${if (v.linger) "sim" else "não"}")
            }
            plan.components.forEach { component ->
                Text("${component.id} · ${component.state.label}", style = MaterialTheme.typography.titleSmall)
            }
            plan.blockers.forEach { reason ->
                val state = if (reason.startsWith("incompatible:")) SetupState.incompatible else SetupState.intervention
                StatusNotice("${state.label}: ${reason.substringAfter(": ")}", true)
            }
        }
        Spacer(Modifier.height(8.dp))
        ExpandDetails("Plano de instalação · ${plan.release}") {
            Text("Pacotes ausentes: ${plan.packages.joinToString().ifEmpty { "nenhum" }}")
            plan.components.filter { it.state == SetupState.missing }.forEach { component ->
                Text("${component.id} · ${component.privilege}", style = MaterialTheme.typography.titleSmall)
                Text(component.description)
            }
            Text("DNF resolve dependências pelos repositórios configurados e pode atualizá-las. Compilação com um processo. Reserva mínima: 2 GiB de disco e 768 MiB RAM disponíveis.")
            Text("O plano inteiro será autorizado. Serviços existentes divergentes bloqueiam a execução. Não há reboot automático.")
            Text("Identificador: ${plan.id}", style = MaterialTheme.typography.bodySmall)
        }
        ExpandDetails("Componentes opcionais / próximas fases") { plan.optional.forEach { Text(it) } }
        if (plan.journal.isNotEmpty()) ExpandDetails("Últimas etapas registradas no Fedora") { plan.journal.forEach { Text(it) } }
        if (plan.complete) StatusNotice(when (plan.target) {
            SetupTarget.base -> "Base verificada. Abra Saúde para consultar o Core."
            SetupTarget.transmission -> "Transmission verificado. Use Downloads → Torrents."
            SetupTarget.prowlarr -> "Prowlarr verificado. Configure indexadores manualmente por túnel SSH; depois use Busca."
        })
        MyCtTextButton(onClick = { password = ""; approve = true }, enabled = plan.canApply && !model.busy) { Text("Revisar e aprovar instalação") }
    }
    if (model.events.isNotEmpty()) ExpandDetails("Progresso desta execução") { model.events.forEach { Text(it) } }
    if (approve) AlertDialog(onDismissRequest = { approve = false; password = "" },
        title = { Text("Aprovar alterações no Fedora?") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(if (model.target == SetupTarget.base)
                "Autoriza somente as etapas ausentes da Base: pacotes, Core, banco/migração e serviços."
                else "Autoriza somente as etapas ausentes de ${model.target.label}: pacotes, arquivos privados, helper e sua unidade de usuário. Não instala ou altera a Base nem o outro componente opcional.")
            Text("Se necessário, habilita linger nesta conta, mantendo também suas outras unidades já habilitadas após logout/boot. Instalações anteriores não serão adotadas. Se o estado mudar, o pedido será recusado.")
            Text("Senha sudo desta conta, somente se exigida. Uso temporário no SSH, sem salvar. Deixe vazia para NOPASSWD.")
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Senha sudo") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true)
            Text("Se o SSH cair, reconecte e diagnostique antes de retomar.")
        } },
        confirmButton = { MyCtTextButton(onClick = {
            val bytes = password.toByteArray(Charsets.UTF_8)
            password = ""; approve = false; model.apply(bytes)
        }, enabled = model.plan?.canApply == true && !model.busy) { Text("Aprovar e instalar") } },
        dismissButton = { MyCtTextButton(onClick = { password = ""; approve = false }) { Text("Cancelar") } })
}
