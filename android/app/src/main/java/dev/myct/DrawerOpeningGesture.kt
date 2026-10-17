package dev.myct

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.DrawerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

internal val DrawerOpeningEdgeWidth = 24.dp

/** One decision per touch: a vertical/diagonal scroll can never turn into an opening later. */
internal class DrawerOpeningGesture(
    startX: Float,
    edgeWidth: Float,
    private val touchSlop: Float,
    private val longPressTimeoutMillis: Long
) {
    enum class Decision { Waiting, Open, Ignore }

    var decision = if (startX in 0f..edgeWidth) Decision.Waiting else Decision.Ignore
        private set

    fun update(dx: Float, dy: Float, elapsedMillis: Long, pointers: Int = 1, consumed: Boolean = false): Decision {
        if (decision != Decision.Waiting) return decision
        decision = when {
            pointers != 1 || consumed || elapsedMillis >= longPressTimeoutMillis ||
                !dx.isFinite() || !dy.isFinite() -> Decision.Ignore
            max(abs(dx), abs(dy)) < touchSlop -> Decision.Waiting
            dx >= touchSlop && dx >= 3f * abs(dy) -> Decision.Open
            else -> Decision.Ignore
        }
        return decision
    }
}

/** Observes the content's existing hit path; no edge overlay or consumption of down/tap events. */
internal fun Modifier.drawerEdgeOpening(drawer: DrawerState, scope: CoroutineScope): Modifier =
    pointerInput(drawer, scope) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!drawer.isClosed || drawer.isAnimationRunning || down.type != PointerType.Touch) return@awaitEachGesture
            val gesture = DrawerOpeningGesture(down.position.x, DrawerOpeningEdgeWidth.toPx(),
                viewConfiguration.touchSlop, viewConfiguration.longPressTimeoutMillis)
            if (gesture.decision == DrawerOpeningGesture.Decision.Ignore) return@awaitEachGesture
            var claimed = false
            do {
                // Children get Main first: scrolling/selection can claim a movement before us.
                // AndroidView receives unclaimed moves in Final, and gets CANCEL only if we open.
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.singleOrNull { it.id == down.id } ?: break
                if (claimed) {
                    event.changes.forEach { it.consume() }
                } else if (change.position != down.position) {
                    val delta = change.position - down.position
                    when (gesture.update(delta.x, delta.y, change.uptimeMillis - down.uptimeMillis,
                        event.changes.size, change.isConsumed)) {
                        DrawerOpeningGesture.Decision.Ignore -> break
                        DrawerOpeningGesture.Decision.Open -> {
                            if (!change.pressed) break
                            change.consume()
                            claimed = true
                            scope.launch { drawer.open() }
                        }
                        DrawerOpeningGesture.Decision.Waiting -> Unit
                    }
                } else if (event.changes.size != 1) break
            } while (event.changes.any { it.pressed })
        }
    }
