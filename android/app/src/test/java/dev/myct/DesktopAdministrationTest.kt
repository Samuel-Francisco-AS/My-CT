package dev.myct

import org.junit.Assert.*
import org.junit.Test

class DesktopAdministrationTest {
    private fun parse(value: String, status: Int = 0) = parseDesktopAdministration(SshExecResult(value, "", status))
    @Test fun strictPreflightDistinguishesMissingUnsafeLegacyAndPermissionWithoutEnablingControls() {
        listOf("missing" to DesktopAdminAccess.MISSING, "unsafe" to DesktopAdminAccess.UNSAFE,
            "incompatible" to DesktopAdminAccess.INCOMPATIBLE, "denied" to DesktopAdminAccess.DENIED,
            "error" to DesktopAdminAccess.ERROR).forEach { (wire, expected) ->
            val access = parse("MYCT_DESKTOP_ADMIN_V1 $wire 0 0\n")
            assertEquals(expected, access.access); assertFalse(access.canStart); assertFalse(access.canStop)
        }
        assertTrue(parse("MYCT_DESKTOP_ADMIN_V1 missing 0 0").message.contains("helper não instalado"))
    }
    @Test fun authorizationIsPerOperationAndUnknownRepliesFailClosed() {
        assertTrue(parse("MYCT_DESKTOP_ADMIN_V1 ready 1 1").canStart)
        assertTrue(parse("MYCT_DESKTOP_ADMIN_V1 ready 1 1").canStop)
        assertFalse(parse("MYCT_DESKTOP_ADMIN_V1 ready 1 0").canStop)
        assertFalse(parse("MYCT_DESKTOP_ADMIN_V1 ready 0 1").canStart)
        listOf("", "active", "MYCT_DESKTOP_ADMIN_V2 ready 1 1", "MYCT_DESKTOP_ADMIN_V1 missing 1 1",
            "MYCT_DESKTOP_ADMIN_V1 ready 1 1\nextra").forEach {
            assertEquals(DesktopAdminAccess.ERROR, parse(it).access)
        }
        assertFalse(parse("MYCT_DESKTOP_ADMIN_V1 ready 1 1", 1).canStart)
    }
    @Test fun serviceStateAndCapabilityMessagesAreIndependent() {
        assertEquals("ativa", desktopServiceLabel(DesktopService.AVAILABLE))
        assertEquals("desligada", desktopServiceLabel(DesktopService.OFF))
        assertEquals(DesktopService.AVAILABLE, parseDesktopService(SshExecResult("active", "", 0)))
        assertFalse(DesktopAdministration().canStop)
    }
}
