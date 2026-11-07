package org.example.project.features.tse

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.tse.data.provider.local.LocalTseLifecycleManager
import org.example.project.features.tse.data.provider.local.LocalTseSignProvider
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 2026-09-18 e2e finding #1: a configured-NORMAL shop must never be served a simulated signature. */
class LocalTseSignProviderModeGuardTest {
    private fun provider(): Pair<LocalTseLifecycleManager, LocalTseSignProvider> {
        val manager =
            LocalTseLifecycleManager(
                configSource = { TseConfigDto.TseConfig(mode = TseMode.NORMAL, timeAdminPin = "12345") },
                locate = { null },
            )
        manager.initialize(configure = false)
        return manager to LocalTseSignProvider(manager)
    }

    @Test
    fun `configured NORMAL is not reported connected even if TseStatus drifted to SIMULATION`() {
        val (manager, provider) = provider()
        try {
            manager.getTseStatus().setMode(TseMode.SIMULATION) // simulate the drift
            assertFalse(provider.isConnected())
        } finally {
            manager.close()
        }
    }

    @Test
    fun `configured NORMAL refuses quickSign when TseStatus drifted to SIMULATION`() {
        val (manager, provider) = provider()
        try {
            manager.getTseStatus().setMode(TseMode.SIMULATION)
            val error =
                assertFailsWith<PosException> {
                    runBlocking { provider.quickSign("Bestellung-V1", "data", "TER-TEST") }
                }
            assertEquals(PosErrorCode.TSE_UNREACHABLE, error.code)
        } finally {
            manager.close()
        }
        Unit
    }

    @Test
    fun `a switch to NORMAL written without re-initialising still stops simulated signing`() {
        // restartConnection() persists mode NORMAL without calling initialize(); the guard must
        // compare against the persisted config, not the mode seen at the last initialize().
        var configured = TseMode.SIMULATION
        val manager =
            LocalTseLifecycleManager(
                configSource = { TseConfigDto.TseConfig(mode = configured, timeAdminPin = "12345") },
                locate = { null },
            )
        val provider = LocalTseSignProvider(manager)
        try {
            manager.initialize(configure = false) // live mode SIMULATION
            configured = TseMode.NORMAL
            val error =
                assertFailsWith<PosException> {
                    runBlocking { provider.quickSign("Bestellung-V1", "data", "TER-TEST") }
                }
            assertEquals(PosErrorCode.TSE_UNREACHABLE, error.code)
        } finally {
            manager.close()
        }
        Unit
    }

    @Test
    fun `a configured SIMULATION shop is connected before any initialize`() {
        val manager =
            LocalTseLifecycleManager(
                configSource = { TseConfigDto.TseConfig(mode = TseMode.SIMULATION, timeAdminPin = "12345") },
                locate = { null },
            )
        try {
            assertTrue(LocalTseSignProvider(manager).isConnected())
        } finally {
            manager.close()
        }
    }
}
