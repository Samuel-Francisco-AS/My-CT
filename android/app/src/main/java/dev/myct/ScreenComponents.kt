package dev.myct

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun ScreenPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MyCtGeometry.Panel, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** Scroll only the body; actions remain reachable in small windows and with large text/IME. */
@Composable
internal fun ReportDialog(title: String, onDismiss: () -> Unit, closeLabel: String = "Fechar",
                          actions: @Composable ColumnScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)).padding(12.dp),
            contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = MyCtGeometry.Panel, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    MyCtPanelRule()
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
                    MyCtPanelRule()
                    actions()
                    MyCtTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(closeLabel) }
                }
            }
        }
    }
}

@Composable
internal fun HelpButton(title: String, text: String) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "Ajuda: $title" }) {
        Text("ⓘ", color = MaterialTheme.colorScheme.secondary)
    }
    if (open) ReportDialog(title, { open = false }) { Text(text) }
}

@Composable
internal fun ScreenHeading(title: String, help: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (help != null) HelpButton(title, help)
    }
}

@Composable
internal fun StatusNotice(text: String, error: Boolean = false) {
    if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun copyDisplayText(context: Context, label: String, value: String) {
    if (!ClipboardPolicy.allowed(value)) return
    val clip = ClipData.newPlainText(label, value)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    try {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(context, "$label copiado", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) { Toast.makeText(context, "Não foi possível copiar.", Toast.LENGTH_SHORT).show() }
}

/** Bounded in lists; the complete value can be selected/copied in a separate scrollable dialog. */
@Composable
internal fun DetailValue(label: String, value: String, modifier: Modifier = Modifier, maxLines: Int = 2, showLabel: Boolean = true) {
    val context = LocalContext.current
    var open by remember(value) { mutableStateOf(false) }
    Column(modifier.clickable(onClickLabel = "Consultar $label completo") { open = true }.padding(vertical = 4.dp)) {
        if (showLabel) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
    if (open) ReportDialog(label, { open = false }, actions = {
        MyCtTextButton(onClick = { copyDisplayText(context, label, value) }, modifier = Modifier.fillMaxWidth()) { Text("Copiar") }
    }) { SelectionContainer { Text(value, fontFamily = FontFamily.Monospace) } }
}

@Composable
internal fun ExpandDetails(label: String = "Detalhes", content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    MyCtTextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "− $label" else "+ $label") }
    if (expanded) Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
internal fun DataLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
