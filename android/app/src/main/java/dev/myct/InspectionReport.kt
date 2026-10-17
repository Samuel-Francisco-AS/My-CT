package dev.myct

import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun InspectionReport(model: FilesModel) {
    val context = LocalContext.current
    var checksum by remember { mutableStateOf("") }
    ReportDialog("Verificar arquivo", model::closeInspection,
        closeLabel = if (model.inspecting) "Cancelar inspeção" else "Fechar") {
        if (model.inspecting) LinearProgressIndicator(Modifier.fillMaxWidth())
        StatusNotice(model.inspectionMessage)
        model.inspection?.let { result ->
            DetailValue("Nome", result.name)
            DataLine("Tamanho", formatFileSize(result.bytes))
            DataLine("Formato (conteúdo)", result.type.ifEmpty { "Não identificado" })
            DetailValue("SHA-256", result.sha256)
            MyCtTextButton(onClick = { copyDisplayText(context, "SHA-256", result.sha256) }) { Text("Copiar SHA-256") }
            OutlinedTextField(checksum, { if (it.length <= 256) checksum = it },
                label = { Text("SHA-256 conhecido (opcional)") }, modifier = Modifier.fillMaxWidth())
            val comparison = compareChecksum(result.sha256, checksum)
            StatusNotice(comparison, comparison == "DIVERGENTE")
            val declared = MimeTypeMap.getSingleton().getMimeTypeFromExtension(result.extension.lowercase())
            if (declared != null && result.type.isNotEmpty() && declared != result.type)
                StatusNotice("Extensão sugere $declared; conteúdo identificado como ${result.type}.", true)
            StatusNotice(result.warning)
            ExpandDetails("Dados do arquivo") {
                DetailValue("Localização", result.path)
                DataLine("Bytes exatos", result.bytes.toString())
                DataLine("Extensão", result.extension.ifEmpty { "Sem extensão" })
            }
        }
        AntivirusScanSection(model.antivirus, enabled = !model.inspecting)
    }
}
