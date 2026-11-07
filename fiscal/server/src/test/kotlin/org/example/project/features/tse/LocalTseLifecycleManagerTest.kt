package org.example.project.features.tse

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseHealthState
import org.example.project.core.error.PosErrorCode
import org.example.project.features.tse.data.provider.local.LocalTseLifecycleManager
import org.example.project.features.tse.domain.model.TseInitResult
import org.example.project.features.tse.domain.provider.TseStatusBroadcaster
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.operations.signing.TseSignOperation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase D guard: a NORMAL-mode TSE whose hardware is not located/connectable must NOT be
 * auto-switched to DISABLED — the configured mode stays NORMAL and a connected=false,
 * mode="NORMAL", health=UNREACHABLE status is broadcast (so transactions follow
 * failureBehavior -> QUEUE -> Nachsignieren).
 *
 * The NORMAL branch is exercised directly via the injectable `configSource`/`locate` constructor
 * parameters (2026-09-18 e2e finding #1: the branch was never tested, and it left TseStatus in its
 * SIMULATION default, so a NORMAL shop without hardware signed with SIM-… signatures).
 *
 * The broadcast is captured via TseStatusBroadcaster's injectable test sink.
 */
class LocalTseLifecycleManagerTest {
    private val captured = mutableListOf<NotificationDto.TseStatusUpdateDto>()

    @BeforeEach
    fun installSink() {
        captured.clear()
        TseStatusBroadcaster.resetState()
        TseStatusBroadcaster.setBroadcastSinkForTest { update -> captured.add(update) }
    }

    @AfterEach
    fun removeSink() {
        TseStatusBroadcaster.setBroadcastSinkForTest(null)
        TseStatusBroadcaster.resetState()
    }

    @Test
    fun `NORMAL offline broadcasts NORMAL not-connected UNREACHABLE and never DISABLED`() =
        runBlocking {
            val manager = LocalTseLifecycleManager()

            // Invoke the private broadcastInitialState the same way both NORMAL-offline branches do
            // after the Phase D fix: connected=false, healthy=false, mode="NORMAL".
            val method =
                LocalTseLifecycleManager::class.java
                    .getDeclaredMethod(
                        "broadcastInitialState",
                        Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        String::class.java,
                    )
            method.isAccessible = true
            method.invoke(manager, false, false, "NORMAL")

            // broadcastInitialState launches on an IO coroutine — give it a moment to emit.
            var waited = 0
            while (captured.isEmpty() && waited < 2000) {
                delay(20)
                waited += 20
            }

            val normalOffline = captured.firstOrNull { !it.connected && it.mode == "NORMAL" }
            assertNotNull(normalOffline, "expected a connected=false, mode=NORMAL broadcast; got $captured")
            assertEquals(TseHealthState.UNREACHABLE, normalOffline.health)
            assertTrue(
                captured.none { it.mode == "DISABLED" },
                "NORMAL-offline must never broadcast a DISABLED mode; got $captured",
            )
        }

    private fun normalOfflineManager(): LocalTseLifecycleManager =
        LocalTseLifecycleManager(
            configSource = { TseConfigDto.TseConfig(mode = TseMode.NORMAL, timeAdminPin = "12345") },
            locate = { null }, // no mount point → the "hardware not detected" branch
        )

    @Test
    fun `NORMAL with no hardware leaves TseStatus in NORMAL, not the SIMULATION field default`() {
        val manager = normalOfflineManager()
        try {
            val result = manager.initialize(configure = false)

            assertIs<TseInitResult.Unreachable>(result)
            assertEquals(TseMode.NORMAL, manager.getTseStatus().getMode())
            assertEquals(TseMode.NORMAL, manager.lastKnownConfiguredMode())
        } finally {
            manager.close()
        }
    }

    @Test
    fun `NORMAL with no hardware refuses to sign instead of simulating`() {
        val manager = normalOfflineManager()
        try {
            manager.initialize(configure = false)

            val error =
                assertFailsWith<PosException> {
                    TseSignOperation(command = "signorders", value = "x", clientId = "TER-TEST")
                        .execute(manager.getTseStatus())
                }
            assertEquals(PosErrorCode.TSE_HARDWARE_NOT_FOUND, error.code)
        } finally {
            manager.close()
        }
        Unit
    }

    /** A NORMAL manager whose device is found and answers, reporting [state]; counts configure calls. */
    private class AttachedDevice(
        state: String,
        configureSucceeds: Boolean = false,
    ) {
        var configureCalls = 0
        val manager =
            LocalTseLifecycleManager(
                configSource = { TseConfigDto.TseConfig(mode = TseMode.NORMAL, timeAdminPin = "61023") },
                locate = { "/media/test/SWISSBIT" },
                attachDevice = { true },
                readInitializationState = { state },
                configureDevice = { _, _ ->
                    configureCalls++
                    configureSucceeds
                },
            )
    }

    @Test
    fun `an UNINITIALIZED TSE reports NotCommissioned and is never configured, so no login is attempted`() {
        val device = AttachedDevice(state = "WORM_INIT_UNINITIALIZED")
        try {
            val result = device.manager.initialize()

            assertEquals(TseInitResult.NotCommissioned, result, "an uncommissioned TSE is not an authentication failure")
            assertEquals(0, device.configureCalls, "configure is the step that logs in; it must not run before setup")
            assertEquals(TseMode.NORMAL, device.manager.getTseStatus().getMode())
        } finally {
            device.manager.close()
        }
    }

    @Test
    fun `a commissioned TSE whose login fails still reports AuthFailed`() {
        val device = AttachedDevice(state = "WORM_INIT_INITIALIZED", configureSucceeds = false)
        try {
            val result = device.manager.initialize()

            assertIs<TseInitResult.AuthFailed>(result)
            assertEquals(1, device.configureCalls)
        } finally {
            device.manager.close()
        }
    }
}
