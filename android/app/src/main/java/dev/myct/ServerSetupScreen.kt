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
internal fun ServerSetupSection(model: ServerSetupModel, onOAuthTerminal: () -> Unit = {}) {
    var onlineApprove by remember { mutableStateOf(false) }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var approve by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    LaunchedEffect(model.connected) { if (model.connected) model.diagnose() }
    DisposableEffect(Unit) { onDispose { password = "" } }
    ScreenHeading("Configurar servidor", "Base Fedora e componentes opcionais. Diagnóstico sem alterar configurações. Requer Fedora tradicional 43/44, SSH e python3. Nenhuma IA é necessária.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SetupTarget.entries.forEach { value ->
            MyCtTextButton(onClick = { model.select(value) }, enabled = !model.busy && !model.backup.busy) {
                Text(if (model.target == value) "● ${value.label}" else value.label)
            }
        }
    }
    if (model.target == SetupTarget.backup) { BackupSetupSection(model.backup); return }
    if (model.target == SetupTarget.rclone) {
        OutlinedTextField(value = model.cloudRemote, onValueChange = { model.editCloud(it, model.cloudConfig) },
            label = { Text("Remote escolhido (NOME:)") }, singleLine = true, enabled = !model.busy)
        OutlinedTextField(value = model.cloudConfig, onValueChange = { model.editCloud(model.cloudRemote, it) },
            label = { Text("Caminho do rclone.conf") }, singleLine = true, enabled = !model.busy)
        Text("Deixe vazio para descobrir automaticamente.")
        Text("Somente nome e caminho. Nunca cole tokens, Client Secret, códigos OAuth ou conteúdo da configuração nestes campos.")
        ExpandDetails("Configurar Google Drive · etapa humana") {
            Text("Instalar Rclone não autentica uma conta. Se já existe remote configurado, preserve-o; não recrie nem reautorize automaticamente.")
            Text("Para conta nova: crie/selecione seu projeto Google Cloud, habilite Drive API, configure consentimento e crie Client ID OAuth próprio do tipo aplicativo para computador. Revise usuários de teste/publicação para evitar expiração de tokens de teste. Informe Client ID e Secret somente nos prompts interativos do Rclone.")
            Text("O Fedora não precisa de navegador. Abra o túnel temporário abaixo; no Terminal SSH use rclone config (com --config e o caminho escolhido, se personalizado). Crie somente o remote ausente, escolha drive e responda Y ao navegador: a autenticação passará pelo túnel SSH. Abra no navegador deste Android a URL localhost:53682 exibida pelo Rclone. Ela é privada. A porta fica limitada ao loopback; não abrir firewall.")
            Text("O método oficial alternativo com N utiliza outra máquina com Rclone e navegador. Não copie comandos authorize que contenham Client Secret ou blob de configuração em argumentos; prefira o túnel e os prompts interativos. Nunca envie tokens a agentes, relatórios ou capturas. Não usar gravação de Terminal.")
            MyCtTextButton(onClick = { uri.openUri("https://rclone.org/drive/#making-your-own-client-id") }) { Text("Guia oficial: projeto e Client ID") }
            MyCtTextButton(onClick = { uri.openUri("https://rclone.org/remote_setup/#configuring-using-ssh-tunnel") }) { Text("Guia oficial: Fedora sem navegador") }
            MyCtTextButton(onClick = { model.tunnel(!model.oauthTunnel) }, enabled = model.connected && !model.busy) {
                Text(if (model.oauthTunnel) "Encerrar túnel OAuth" else "Abrir túnel OAuth temporário")
            }
            MyCtTextButton(onClick = onOAuthTerminal, enabled = model.connected && !model.busy) { Text("Ir ao Terminal SSH privado") }
            Text("Depois de concluir, volte aqui, encerre o túnel e diagnostique novamente. Configuração protegida/criptografada exige revisão humana; não é aberta pelo instalador.")
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
            plan.cloud?.let { c ->
                Text("Configuração: " + when (c.configState) {
                    "configured" -> "remote Drive reconhecido localmente · autenticação ainda não testada"
                    "pending" -> "aguarda configuração humana/OAuth · não é falha de instalação"
                    "protected" -> "protegida, criptografada ou ilegível · requer intervenção humana"
                    else -> "remote selecionado pertence a outro backend · requer intervenção"
                })
                Text("Caminho: ${c.configPath}")
                Text("Trabalhos independentes: systemd de usuário ${if (c.manager) "disponível" else "não confirmado"}; nenhum trabalho foi iniciado.")
                Text("Arquivo remoto: não verificado. O diagnóstico não consulta catálogos ou Google Drive.")
                ExpandDetails("Helpers de arquivamento") { c.helpers.forEach { Text(it) } }
            }
            plan.inventory?.let { v ->
                Text(if (plan.target == SetupTarget.rclone) { if (v.managed) "Provisionamento gerenciado pelo SETUP-3; configuração OAuth humana" else if (v.installed) "Rclone anterior não gerenciado · pacote preservado" else "Rclone ausente" } else when { v.managed && v.installed -> "Instalado e gerenciado pelo SETUP-2"; v.managed -> "Instalação gerenciada incompleta · requer nova consulta"; v.detected -> "Instalação anterior não gerenciada · somente inspeção"; else -> "Ausente" })
                Text("Versão: ${v.version.ifBlank { "não confirmada" }}")
                if (plan.target == SetupTarget.rclone) Text("Persistência após logout/boot (linger): ${if (v.linger) "disponível" else "não habilitada"}")
                if (plan.target != SetupTarget.rclone) Text("Em execução: ${if (v.running) "sim" else "não"} · início automático: ${if (v.enabled) "habilitado" else "não habilitado"}")
                if (plan.target != SetupTarget.rclone) Text("API local: ${if (v.apiReady) "disponível" else "não confirmada"} · linger: ${if (v.linger) "sim" else "não"}")
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
            if (plan.target != SetupTarget.rclone) Text("DNF resolve dependências pelos repositórios configurados e pode atualizá-las. Compilação com um processo. Reserva mínima: 2 GiB de disco e 768 MiB RAM disponíveis.")
            Text("O plano inteiro será autorizado. Serviços existentes divergentes bloqueiam a execução. Não há reboot automático.")
            Text("Identificador: ${plan.id}", style = MaterialTheme.typography.bodySmall)
        }
        ExpandDetails("Componentes opcionais / próximas fases") { plan.optional.forEach { Text(it) } }
        if (plan.journal.isNotEmpty()) ExpandDetails("Últimas etapas registradas no Fedora") { plan.journal.forEach { Text(it) } }
        if (plan.complete) StatusNotice(when (plan.target) {
            SetupTarget.backup -> "Consulte o fluxo Backup / Recuperação."
            SetupTarget.base -> "Base verificada. Abra Saúde para consultar o Core."
            SetupTarget.transmission -> "Transmission verificado. Use Downloads → Torrents."
            SetupTarget.rclone -> "Instalação offline concluída. OAuth pode estar pendente. Use o arquivamento existente após configuração e verificação explícita."
            SetupTarget.prowlarr -> "Prowlarr verificado. Configure indexadores manualmente por túnel SSH; depois use Busca."
        })
        if (plan.target == SetupTarget.rclone) {
            MyCtTextButton(onClick = { onlineApprove = true }, enabled = plan.cloud?.configured == true && !model.busy) { Text("Verificar acesso online…") }
            if (model.onlineMessage.isNotEmpty()) StatusNotice(model.onlineMessage)
        }
        MyCtTextButton(onClick = { password = ""; approve = true }, enabled = plan.canApply && !model.busy) { Text("Revisar e aprovar instalação") }
    }
    if (model.events.isNotEmpty()) ExpandDetails("Progresso desta execução") { model.events.forEach { Text(it) } }
    if (onlineApprove) AlertDialog(onDismissRequest = { onlineApprove = false },
        title = { Text("Autorizar consulta ao Google Drive?") },
        text = { Text("Consulta disponibilidade/cota e somente a pasta My-CT-Archive, sem listar seu conteúdo. O Rclone pode renovar OAuth e escrever tokens no rclone.conf. Não transfere arquivos, não verifica integridade do acervo e não comprova recuperação. Só prossiga se autorizar esses efeitos na conta selecionada.") },
        confirmButton = { MyCtTextButton(onClick = { onlineApprove = false; model.verifyCloud() }) { Text("Autorizar verificação online") } },
        dismissButton = { MyCtTextButton(onClick = { onlineApprove = false }) { Text("Cancelar") } })
    if (approve) AlertDialog(onDismissRequest = { approve = false; password = "" },
        title = { Text("Aprovar alterações no Fedora?") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(if (model.target == SetupTarget.rclone)
                "Autoriza instalar apenas Rclone ausente via DNF, publicar helpers ausentes e seleção não secreta. Não configura OAuth nem consulta Drive. Arquivos anteriores divergentes bloqueiam a execução."
                else if (model.target == SetupTarget.base)
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
