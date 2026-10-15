package dev.myct

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class VaultTest {
    @Test fun parsesOnlyMetadataAndLockedStates() {
        val locked = VaultStatus.parse(
            """{"service_available":true,"login_unlocked":false,"state":"locked","unlock_backend_compatible":true}"""
        )
        assertEquals(VaultKind.LOCKED, locked.kind)
        assertTrue(locked.compatible)
        val unlocked = VaultStatus.parse(
            """{"service_available":true,"login_unlocked":true,"state":"unlocked"}"""
        )
        assertEquals(VaultKind.UNLOCKED, unlocked.kind)
        assertFalse(unlocked.compatible)
        assertEquals(VaultKind.UNAVAILABLE, VaultStatus.parse(
            """{"service_available":false,"login_unlocked":false,"state":"unavailable","code":"credential_service_unavailable"}"""
        ).kind)
    }

    @Test fun rejectsInconsistentAndOversizedStatus() {
        listOf("{}", "[]",
            """{"service_available":true,"login_unlocked":true,"state":"locked"}""",
            """{"service_available":false,"login_unlocked":false,"state":"unlocked"}""",
            """{"service_available":true,"login_unlocked":true,"state":"unknown"}"""
        ).forEach { assertTrue(it, runCatching { VaultStatus.parse(it) }.isFailure) }
        assertTrue(runCatching {
            readVaultStatus(ByteArrayInputStream("x".repeat(4097).toByteArray()))
        }.isFailure)
    }

    @Test fun stateDoesNotExposeSecretItems() {
        val status = readVaultStatus(ByteArrayInputStream(
            """{"service_available":true,"login_unlocked":false,"state":"locked","unlock_backend_compatible":false}""".toByteArray()
        ))
        assertEquals(VaultKind.LOCKED, status.kind)
        assertFalse(status.compatible)
        assertTrue(VaultViewState(VaultPhase.UNLOCKED).message.contains("desbloqueado"))
        assertTrue(VaultViewState(VaultPhase.NO_SSH).message.contains("Conecte"))
    }
}
