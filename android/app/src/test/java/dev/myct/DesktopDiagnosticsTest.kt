package dev.myct

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.zip.DataFormatException

class DesktopDiagnosticsTest {
    @Test fun backClosesKeyboardThenRestoresControlsThenLeavesTheSection() {
        assertEquals(DesktopBackAction.CLOSE_KEYBOARD, desktopBackAction(true, true))
        assertEquals(DesktopBackAction.CLOSE_KEYBOARD, desktopBackAction(true, false))
        assertEquals(DesktopBackAction.RESTORE_CONTROLS, desktopBackAction(false, true))
        assertEquals(DesktopBackAction.EXIT_SECTION, desktopBackAction(false, false))
    }
    @Test fun desktopRemembersThePreviousDestinationAndRestorationDefaultsToHome() {
        val nav = SectionNavigation(AppSection.Files)
        nav.request(AppSection.Desktop); nav.showRequested()
        assertEquals(AppSection.Files, nav.desktopExit())
        nav.request(nav.desktopExit()); nav.showRequested(); assertEquals(AppSection.Files, nav.displayed)
        nav.request(AppSection.Desktop); nav.showRequested(); nav.request(nav.desktopExit()); nav.showRequested()
        assertEquals(AppSection.Files, nav.displayed)
        assertEquals(AppSection.Home, SectionNavigation(AppSection.Desktop).desktopExit())
    }
    @Test fun onlyCompletedStepsAreReportedAndFailurePreservesTheLastSuccess() {
        val trace = DesktopTrace()
        trace.step(DesktopStep.SCREEN, true); trace.step(DesktopStep.SSH, true)
        trace.step(DesktopStep.TUNNEL, true); trace.step(DesktopStep.TCP, false)
        trace.fail(DesktopFailure.TCP)
        assertEquals(DesktopStep.TUNNEL, trace.state.lastCompleted)
        trace.step(DesktopStep.RENDER, true) // A late callback cannot turn an error into success.
        assertFalse(DesktopStep.RENDER in trace.state.completed)
    }
    @Test fun failureCategoriesDoNotIncludeRawExceptionOrServerText() {
        assertEquals(DesktopFailure.TUNNEL, desktopFailure(ConnectException("private detail"), DesktopStep.TUNNEL))
        assertEquals(DesktopFailure.TCP, desktopFailure(ConnectException(), DesktopStep.TCP))
        assertEquals(DesktopFailure.AUTH, desktopFailure(RfbException("secret must never surface", DesktopFailure.AUTH), DesktopStep.AUTH))
        assertEquals(DesktopFailure.SECURITY, desktopFailure(RfbException("", DesktopFailure.SECURITY), DesktopStep.SECURITY))
        assertEquals(DesktopFailure.VERSION, desktopFailure(RfbException("", DesktopFailure.VERSION), DesktopStep.VERSION))
        assertEquals(DesktopFailure.PROTOCOL, desktopFailure(RfbException(""), DesktopStep.FRAMEBUFFER))
        assertEquals(DesktopFailure.DECODE, desktopFailure(DataFormatException(), DesktopStep.DECODE))
        assertEquals(DesktopFailure.RENDER, desktopFailure(DesktopRenderFailure(), DesktopStep.RENDER))
        assertEquals(DesktopFailure.DISCONNECTED, desktopFailure(EOFException(), DesktopStep.AUTH))
        assertEquals(DesktopFailure.DISCONNECTED, desktopFailure(java.io.IOException(), DesktopStep.RENDER))
        assertEquals(DesktopFailure.TIMEOUT, desktopFailure(SocketTimeoutException(), DesktopStep.UPDATE))
    }
}
