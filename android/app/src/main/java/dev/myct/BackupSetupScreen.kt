package dev.myct

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
internal fun BackupSetupSection(model: BackupSetupModel) {
    var identity by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(model.connected) { if (!model.connected) { identity = ""; confirm = false } }
    DisposableEffect(Unit) { onDispose { identity = "" } }
    ScreenHeading("Backup / Recuperação", "Pacotes locais verificáveis, sem upload automático. Dados privados exigem age; chave secreta nunca é salva. Serviços são reconstruídos nos alvos Base/Transmission/Prowlarr/Rclone.")
    if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    StatusNotice(model.message, model.reply?.optString("state") == "failure")
    MyCtTextButton(onClick = { identity = ""; model.inventory() }, enabled = model.connected && !model.busy) { Text("Inventariar sem exportar") }
    model.reply?.optJSONArray("categories")?.let { categories ->
        for (i in 0 until categories.length()) {
            val row = categories.getJSONObject(i)
            Text("${row.getString("id")} · ${if (row.getBoolean("available")) "disponível" else "ausente ou requer intervenção"}")
            Text(row.getString("description"))
        }
        Text(if (model.reply!!.optBoolean("age")) "age disponível para pacotes privados." else "age ausente: banco/catálogos serão recusados. Em host novo autorizado, instale pelo Terminal: sudo dnf install age. O aplicativo não executa esse comando nesta etapa.")
    }
    ExpandDetails("Exportar · escolher categorias") {
        Text("Básico: versões, serviços desejados e preferências sem segredos. Não inclui credenciais, configurações completas, mídia ou dados de torrents.")
        Row { Checkbox(model.database, { model.edit(database = it) }, enabled = !model.busy); Text("Banco My-CT · privado") }
        Row { Checkbox(model.catalogs, { model.edit(catalogs = it) }, enabled = !model.busy); Text("Catálogos e manifestos · privados") }
        Text("Selecionar dados privados é consentimento para lê-los na prévia/exportação, não para enviá-los. Catálogos contêm caminhos e metadados pessoais. Nenhum dado do Drive é consultado.")
        OutlinedTextField(model.destination, { model.edit(destination = it) }, label = { Text("Destino absoluto do pacote") }, singleLine = true, enabled = !model.busy)
        Text("Dentro do HOME, fora do Git. Pasta nova será privada 0700. Não substitui pacote existente. Use .myct.zip para básico ou .myct.age para privado.")
        OutlinedTextField(model.recipient, { model.edit(recipient = it) }, label = { Text("Destinatário público age1…") }, singleLine = true, enabled = !model.busy)
        Text("Guarde a identidade secreta separadamente antes do backup: em máquina confiável e fora do Git, age-keygen -o /caminho/privado/chave.txt gera identidade e destinatário público. Não envie a identidade a agentes, Git ou relatórios. Sem a chave, um pacote privado não é recuperável.")
        MyCtTextButton(onClick = { identity = ""; model.planExport() }, enabled = model.connected && !model.busy) { Text("Gerar plano de backup") }
    }
    ExpandDetails("Inspecionar / recuperar pacote existente") {
        OutlinedTextField(model.packagePath, { model.edit(packagePath = it) }, label = { Text("Pacote já disponível no Fedora") }, singleLine = true, enabled = !model.busy)
        Text("Use Arquivos/SFTP para copiar o pacote ao novo Fedora. O instalador não implementa outra transferência.")
        OutlinedTextField(model.mediaRoot, { model.edit(mediaRoot = it) }, label = { Text("Novo caminho de mídia (opcional)") }, singleLine = true, enabled = !model.busy)
        Text("Remapeamento é registrado separadamente. Não move mídia nem reescreve identidades dos catálogos.")
        OutlinedTextField(identity, { identity = it }, label = { Text("Identidade age") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), enabled = !model.busy)
        Text("Deixe vazio para pacote básico. Para age, cole somente a linha AGE-SECRET-KEY-1…, sem comentários. A chave é enviada somente pelo SSH, nunca em argumentos ou logs. Será apagada do campo ao enviar. Inspeção não restaura dados.")
        MyCtTextButton(onClick = { val bytes = identity.trim().toByteArray(); identity = ""; model.inspect(bytes) }, enabled = model.connected && !model.busy) { Text("Verificar integridade e prévia") }
    }
    model.reply?.let { reply ->
        reply.optJSONObject("facts")?.optJSONObject("basic")?.let { basic ->
            ExpandDetails("Versões e conteúdo básico selecionado") {
                Text("My-CT/manifesto: ${basic.getString("release")}")
                val versions = basic.getJSONObject("versions")
                versions.keys().forEach { key -> Text("$key · ${versions.getString(key)}") }
                Text("Preferências sem segredos: ${basic.getJSONObject("preferences").keys().asSequence().joinToString()}")
            }
        }
        reply.optJSONObject("facts")?.optJSONObject("catalogs")?.let { files ->
            if (files.length() > 0) ExpandDetails("Metadados privados selecionados") { files.keys().forEach { name -> Text(name) } }
        }
        for (key in listOf("summary", "warnings", "blockers", "exclusions", "pending")) reply.optJSONArray(key)?.let { rows ->
            ExpandDetails(when (key) { "summary" -> "Prévia"; "warnings" -> "Limitações e dados faltantes"; "blockers" -> "Conflitos"; "pending" -> "Pendências humanas"; else -> "Exclusões" }) {
                for (i in 0 until rows.length()) Text(rows.getString(i).take(2048))
            }
        }
        reply.optJSONObject("facts")?.optJSONArray("components")?.let { rows ->
            ExpandDetails("Componentes presentes/ausentes no Fedora atual") {
                for (i in 0 until rows.length()) { val row = rows.getJSONObject(i); Text("${row.getString("name")} · ${if (row.getBoolean("target_present")) "presente" else "ausente"} · boot atual ${row.getBoolean("target_boot")}") }
            }
        }
        reply.optJSONObject("facts")?.optJSONArray("destinations")?.let { rows ->
            ExpandDetails("Caminhos e arquivos existentes") {
                for (i in 0 until rows.length()) { val row = rows.getJSONObject(i); Text("${row.getString("state")} · ${row.getString("path")}") }
            }
        }
        reply.optJSONArray("services")?.let { rows ->
            ExpandDetails("Serviços desejados da origem · reconstruir pelos outros alvos") {
                for (i in 0 until rows.length()) { val row = rows.getJSONObject(i); Text("${row.getString("name")} · ${row.getString("scope")} · boot ${row.getBoolean("enabled")}") }
            }
        }
        if (reply.has("encrypted")) Text(if (reply.getBoolean("encrypted")) "Proteção: criptografia age. Guarde a identidade fora do pacote." else "Básico sem segredos, sem criptografia; manter privado.")
        if (reply.optString("path").isNotEmpty()) {
            Text("Localização: ${reply.getString("path")}")
            Text("Copie pela seção Arquivos/SFTP. Backups privados não devem ser publicados em GitHub ou enviados a agentes.")
        }
        if (reply.optString("sha256").isNotEmpty()) Text("SHA-256: ${reply.getString("sha256")}")
        if (reply.optBoolean("can_apply")) MyCtTextButton(onClick = { confirm = true }, enabled = !model.busy) { Text("Revisar e aprovar esta prévia") }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false; identity = "" },
        title = { Text("Aprovar ${if (model.reply?.optString("operation") == "inspect") "recuperação" else "exportação"}?") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text("Autoriza exclusivamente a prévia exibida. Exportação lê as categorias selecionadas e cria pacote privado. Recuperação publica apenas arquivos ausentes/iguais e dados em banco gerenciado vazio; divergências bloqueiam. Não instala/reinicia serviços nem transfere mídias. Para recuperar pacote age, informe novamente a identidade no campo privado antes de confirmar. Checksums e destinatário age não autenticam quem produziu o pacote: use somente sua origem confiável.")
            if (model.reply?.optString("operation") == "inspect") OutlinedTextField(identity, { identity = it },
                label = { Text("Identidade age (vazia para básico)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
        } },
        confirmButton = { MyCtTextButton(onClick = { val bytes = identity.trim().toByteArray(); identity = ""; confirm = false; model.approve(bytes) }) { Text("Aprovar") } },
        dismissButton = { MyCtTextButton(onClick = { confirm = false; identity = "" }) { Text("Cancelar") } })
}
