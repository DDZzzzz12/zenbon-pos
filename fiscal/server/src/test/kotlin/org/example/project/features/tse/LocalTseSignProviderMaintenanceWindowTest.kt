package org.example.project.features.tse

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseHealthState
import org.example.project.features.tse.data.provider.local.deriveTseHealth
import org.example.project.features.tse.domain.provider.TseStatusBroadcaster
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Replays the production scenario from `nProvider Local TSE health.txt`:
 *
 *   1. Maintenance task enters scheduled-timesync (right after selftest finished).
 *   2. HealthCheckOperation runs and observes validTime=false.
 *   3. Pre-fix: provider broadcasts mode=SIMULATION.
 *   4. Post-fix: provider broadcasts mode=NORMAL, health=MAINTENANCE.
 *
 * Tests the (deriveTseHealth + broadcast) pipeline without instantiating
 * TseStatus, which would trigger Swissbit JNI loading.
 */
@OptIn(ExperimentalTime::class)
class LocalTseSignProviderMaintenanceWindowTest {
    private val captured = mutableListOf<NotificationDto.TseStatusUpdateDto>()

    @BeforeEach
    fun setup() {
        captured.clear()
        TseStatusBroadcaster.resetState()
        TseStatusBroadcaster.broadcastSink = { captured.add(it) }
    }

    @AfterEach
    fun teardown() {
        TseStatusBroadcaster.broadcastSink = null
        TseStatusBroadcaster.resetState()
    }

    /** Mirror of LocalTseSignProvider.checkHealth's middle section — no TseStatus needed. */
    private suspend fun simulateCheckHealth(
        mode: TseMode,
        rawHealthy: Boolean,
        inMaintenance: Boolean,
        ops: Set<String> = emptySet(),
    ) {
        val raw =
            if (mode == TseMode.DISABLED) {
                null
            } else {
                TseDto.HealthCheckResultDto(
                    healthy = rawHealthy,
                    health = TseHealthState.HEALTHY,
                    maintenanceOps = emptySet(),
                    initialized = true,
                    validTime = rawHealthy,
                    passedSelfTest = true,
                    remainingSignatures = 19_981_088L,
                    availableCapacity = 1_000_000L,
                    certificateExpirationDate = 0L,
                    timestamp = Clock.System.now().toEpochMilliseconds(),
                )
            }
        val health = deriveTseHealth(mode, raw, inMaintenance)
        TseStatusBroadcaster.broadcastIfStateChanged(
            connected = raw != null,
            health = health,
            mode = mode.name,
            ops = ops,
        )
    }

    @Test
    fun `unhealthy snapshot inside maintenance window broadcasts MAINTENANCE not SIMULATION`() =
        runBlocking {
            simulateCheckHealth(TseMode.NORMAL, rawHealthy = false, inMaintenance = true, ops = setOf("scheduled-timesync"))
            assertEquals(1, captured.size)
            val first = captured[0]
            assertEquals("NORMAL", first.mode, "mode must stay NORMAL — not flip to SIMULATION (this WAS the bug)")
            assertEquals(TseHealthState.MAINTENANCE, first.health)
            assertEquals(setOf("scheduled-timesync"), first.maintenanceOps)
        }

    @Test
    fun `control case — unhealthy snapshot WITHOUT maintenance flag broadcasts DEGRADED`() =
        runBlocking {
            simulateCheckHealth(TseMode.NORMAL, rawHealthy = false, inMaintenance = false)
            assertEquals(1, captured.size)
            assertEquals("NORMAL", captured[0].mode)
            assertEquals(TseHealthState.DEGRADED, captured[0].health)
            assertTrue(captured[0].maintenanceOps.isEmpty())
        }

    @Test
    fun `after maintenance ends and hardware reports healthy, broadcast converges to HEALTHY`() =
        runBlocking {
            // 1. Maintenance, unhealthy snapshot → MAINTENANCE.
            simulateCheckHealth(TseMode.NORMAL, rawHealthy = false, inMaintenance = true, ops = setOf("scheduled-timesync"))
            // 2. Maintenance ends, next health check healthy → HEALTHY.
            simulateCheckHealth(TseMode.NORMAL, rawHealthy = true, inMaintenance = false)
            assertEquals(2, captured.size)
            assertEquals(TseHealthState.MAINTENANCE, captured[0].health)
            assertEquals(TseHealthState.HEALTHY, captured[1].health)
            assertTrue(captured[1].maintenanceOps.isEmpty())
        }
}
