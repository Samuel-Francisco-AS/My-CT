package dev.myct

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal const val NavigationEnterMillis = 280
internal val NavigationEnterEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/** Home is the root (vertical); sibling destinations follow their order in the menu. */
internal fun navigationDirection(from: AppSection, to: AppSection): Offset = when {
    from == to -> Offset.Zero
    from == AppSection.Home -> Offset(0f, 1f)
    to == AppSection.Home -> Offset(0f, -1f)
    else -> Offset(if (to.ordinal > from.ordinal) 1f else -1f, 0f)
}

// One signed travel value: 0 = rest, -1 = exited, +1 = next screen's starting edge.
internal fun navigationAlpha(travel: Float): Float = (1f - abs(travel)).coerceIn(0f, 1f)

/** A single clock reveals groups over a 67 ms window, without delaying any action or layout. */
internal fun homeGroupProgress(progress: Float, group: Int): Float {
    val start = group.coerceIn(0, 4) * 0.06f
    return NavigationEnterEasing.transform(((progress - start) / (1f - start)).coerceIn(0f, 1f))
}

@Composable
internal fun rememberHomeEntrance(): (Int) -> Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(progress) { progress.animateTo(1f, tween(NavigationEnterMillis, easing = LinearEasing)) }
    return { group -> Modifier.graphicsLayer {
        val p = homeGroupProgress(progress.value, group)
        alpha = p
        translationY = (1f - p) * 28.dp.toPx()
        scaleX = 0.975f + 0.025f * p
        scaleY = scaleX
    } }
}

/** Decorates the current panel; never keys/recreates its content or retains an outgoing copy.
 * Use semantic keys (directory, document, environment), never progress/polling/keystrokes.
 */
@Composable
internal fun Modifier.panelEntrance(key: Any?, horizontal: Float = 0f): Modifier {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(progress) { progress.animateTo(1f, tween(NavigationEnterMillis, easing = NavigationEnterEasing)) }
    return this.graphicsLayer {
        val remaining = 1f - progress.value
        alpha = 1f - 0.65f * remaining
        translationX = horizontal * 24.dp.toPx() * remaining
        translationY = if (horizontal == 0f) 18.dp.toPx() * remaining else 0f
    }
}

@Composable
internal fun rememberNavigationFeedback(): () -> Unit {
    val view = LocalView.current
    return remember(view) { {
        // View feedback observes the user's haptic setting; no vibrator permission/forced flags.
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        Unit
    } }
}
