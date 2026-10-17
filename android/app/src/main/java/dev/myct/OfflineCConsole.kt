package dev.myct

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun OfflineCConsole(model: OfflineCModel) {
    if (!model.consoleOpen) return
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(model.output) { scroll.scrollTo(scroll.maxValue) }
    Dialog(onDismissRequest = { model.consoleOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(12.dp), shape = MyCtGeometry.Control) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Console C · Local", style = MaterialTheme.typography.titleMedium)
                Text(model.source, style = MaterialTheme.typography.labelSmall)
                Text(model.phase.label + (model.exitCode?.let { " · código $it" } ?: ""), color = MaterialTheme.colorScheme.primary)
                Text("WCPL → .wasm · WAMR interpretado · offline", style = MaterialTheme.typography.labelSmall)
                SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
                    Text(model.output.ifEmpty { "Aguardando compilador…" }, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                }
                if (!model.active && model.elapsedMs > 0) Text("Total: ${model.elapsedMs} ms", style = MaterialTheme.typography.labelSmall)
                if (model.phase == CPhase.RUNNING) {
                    OutlinedTextField(input, { input = it.take(4096) }, label = { Text("Entrada do programa · uma linha") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = model.acceptsInput)
                    Row {
                        MyCtTextButton(onClick = { model.send(input); input = "" }, enabled = model.acceptsInput) { Text("Enviar ↵") }
                        MyCtTextButton(onClick = model::endInput, enabled = model.acceptsInput) { Text("Fim da entrada") }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (model.active) MyCtTextButton(onClick = model::cancel) { Text("Interromper") }
                    MyCtTextButton(onClick = { model.consoleOpen = false }) { Text("Voltar à IDE") }
                }
            }
        }
    }
}
