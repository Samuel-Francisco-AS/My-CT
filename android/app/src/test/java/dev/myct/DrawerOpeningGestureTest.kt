package dev.myct

import dev.myct.DrawerOpeningGesture.Decision.*
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerOpeningGestureTest {
    private fun gesture(startX: Float = 12f) = DrawerOpeningGesture(startX, 24f, 8f, 500L)

    @Test fun tapAndSmallJitterRemainUnclaimed() {
        val touch = gesture()
        assertEquals(Waiting, touch.update(0f, 0f, 0))
        assertEquals(Waiting, touch.update(7f, 2f, 30))
    }

    @Test fun deliberateRightSwipeRequiresTheLeftEdge() {
        for (start in listOf(0f, 12f, 24f)) assertEquals(Open, gesture(start).update(12f, 2f, 40))
        for (start in listOf(-1f, 25f, 200f)) assertEquals(Ignore, gesture(start).update(100f, 0f, 40))
    }

    @Test fun verticalAndDiagonalIntentsAreRejectedForTheWholeTouch() {
        for ((x, y) in listOf(1f to 9f, 1f to -9f, 9f to 9f, 9f to -9f, 12f to 6f, -12f to 0f)) {
            val touch = gesture()
            assertEquals(Ignore, touch.update(x, y, 40))
            assertEquals(Ignore, touch.update(100f, 0f, 80))
        }
    }

    @Test fun childConsumptionWinsBeforeOpening() {
        val touch = gesture()
        assertEquals(Ignore, touch.update(12f, 0f, 40, consumed = true))
        assertEquals(Ignore, touch.update(100f, 0f, 80))
    }

    @Test fun longPressAndMultitouchLeaveTextSelectionAndOtherGesturesAlone() {
        assertEquals(Ignore, gesture().update(12f, 0f, 500))
        assertEquals(Ignore, gesture().update(12f, 0f, 40, pointers = 2))
    }

    @Test fun openedGestureDoesNotChangeItsDecisionOnSubsequentMoves() {
        val touch = gesture()
        assertEquals(Open, touch.update(12f, 1f, 40))
        assertEquals(Open, touch.update(0f, 40f, 80, consumed = true))
    }
}
