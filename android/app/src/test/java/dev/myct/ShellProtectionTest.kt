package dev.myct

import org.junit.Assert.*
import org.junit.Test

class ShellProtectionTest {
    @Test fun vaultRemainsProtectedAcrossNavigationWithGlobalCaptureAllowed() {
        // Includes leaving/returning to Vault; opening the drawer/options retains this same section.
        val route = listOf(AppSection.Terminal, AppSection.Vault, AppSection.Files,
            AppSection.Health, AppSection.Downloads, AppSection.Vault)
        assertEquals(listOf(false, true, false, false, false, true),
            route.map { captureProtectionRequired(false, it) })
    }

    @Test fun globalProtectionCoversEveryDestination() {
        AppSection.entries.forEach { assertTrue(captureProtectionRequired(true, it)) }
        assertTrue(captureProtectionRequired(false, AppSection.Vault))
    }
}
