package dev.myct

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Keep Material input, ripple, focus and disabled behavior; replace full-round button geometry. */
@Composable
internal fun MyCtButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                        content: @Composable RowScope.() -> Unit) {
    Button(onClick, modifier, enabled, shape = MyCtGeometry.Control, content = content)
}

@Composable
internal fun MyCtTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                            colors: ButtonColors = ButtonDefaults.textButtonColors(),
                            content: @Composable RowScope.() -> Unit) {
    TextButton(onClick, modifier, enabled, shape = MyCtGeometry.Control, colors = colors, content = content)
}

/** Purely visual mark: the enclosing toggleable owns the single action and accessible state. */
@Composable
private fun MyCtSwitchMark(checked: Boolean, enabled: Boolean) {
    val position = animateFloatAsState(if (checked) 1f else 0f, tween(140), label = "Interruptor")
    val palette = MaterialTheme.colorScheme
    val track = if (checked) palette.primaryContainer else palette.background
    val outline = if (enabled && checked) palette.secondary else palette.outlineVariant
    val thumb = if (!enabled) palette.onSurface.copy(alpha = 0.38f)
        else if (checked) palette.primary else palette.onSurfaceVariant
    Box(Modifier.size(40.dp, 22.dp).clip(MyCtGeometry.SwitchTrack).background(track)
        .border(1.dp, outline, MyCtGeometry.SwitchTrack)) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 4.dp.toPx()
            val side = size.height - inset * 2
            val x = inset + (size.width - side - inset * 2) * position.value
            drawRect(thumb, Offset(x, inset), Size(side, side))
        }
    }
}

/** Shared compact operational row, with a full-size touch target and no optimistic checked state. */
@Composable
internal fun MyCtOperationalToggle(label: String, description: String, checked: Boolean,
                                   onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val palette = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(MyCtGeometry.Control)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        .semantics { stateDescription = if (checked) "Ativado" else "Desativado" }
        .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge,
                color = if (enabled) palette.onSurface else palette.onSurface.copy(alpha = 0.38f))
            Text(description, style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
        }
        MyCtSwitchMark(checked, enabled)
    }
}

/** Standalone version for existing settings rows, without changing their layout or actions. */
@Composable
internal fun MyCtSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String,
                        modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(MyCtGeometry.Control)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        .semantics { contentDescription = label; stateDescription = if (checked) "Ativado" else "Desativado" },
        contentAlignment = Alignment.Center) {
        MyCtSwitchMark(checked, enabled)
    }
}

@Composable
internal fun MyCtPanelRule() {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(18.dp, 2.dp).background(MaterialTheme.colorScheme.secondary))
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.size(3.dp).background(MaterialTheme.colorScheme.outline))
    }
}
