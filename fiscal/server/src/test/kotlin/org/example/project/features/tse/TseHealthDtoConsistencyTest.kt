package org.example.project.features.tse

import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseHealthState
import org.example.project.features.tse.data.provider.local.deriveTseHealth
import org.example.project.features.tse.data.provider.local.withDerivedHealth
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * F50: `GET /api/tse/health` answered `"healthy":false` with `"health":"HEALTHY"` and no maintenance
 * ops, because `LocalTseAdminProvider.healthCheck` returned the driver's result without the
 * derivation `checkHealth` applies, and the DTO defaulted `health` to HEALTHY.
 */
@DisplayName("TSE health DTO — a non-healthy device never says HEALTHY")
class TseHealthDtoConsistencyTest {
    /** What `HealthCheckOperation` builds: `health`/`maintenanceOps` left to their defaults. */
    private fun driverResult(healthy: Boolean) =
        TseDto.HealthCheckResultDto(
            healthy = healthy,
            initialized = true,
            validTime = healthy,
            passedSelfTest = healthy,
            remainingSignatures = 19_000_000L,
            availableCapacity = 1_000_000L,
        )

    @Test
    fun `the DTO default follows healthy`() {
        assertEquals(TseHealthState.DEGRADED, driverResult(healthy = false).health)
        assertEquals(TseHealthState.HEALTHY, driverResult(healthy = true).health)
    }

    @Test
    fun `a payload without health decodes to the verdict of healthy`() {
        val json =
            """{"healthy":false,"initialized":true,"validTime":false,"passedSelfTest":false,""" +
                """"remainingSignatures":1,"availableCapacity":1}"""

        val decoded = Json.decodeFromString(TseDto.HealthCheckResultDto.serializer(), json)

        assertEquals(TseHealthState.DEGRADED, decoded.health)
    }

    @Test
    fun `a NORMAL non-healthy result never maps to HEALTHY, in or out of maintenance`() {
        for (inMaintenance in listOf(false, true)) {
            val raw = driverResult(healthy = false)
            val ops = if (inMaintenance) setOf("recovery-selftest") else emptySet()

            val mapped = raw.withDerivedHealth(deriveTseHealth(TseMode.NORMAL, raw, inMaintenance), ops)

            assertNotEquals(TseHealthState.HEALTHY, mapped.health, "inMaintenance=$inMaintenance")
            assertEquals(ops, mapped.maintenanceOps)
        }
    }

    @Test
    fun `maintenance is reported with its ops`() {
        val raw = driverResult(healthy = false)

        val mapped = raw.withDerivedHealth(deriveTseHealth(TseMode.NORMAL, raw, true), setOf("recovery-timesync"))

        assertEquals(TseHealthState.MAINTENANCE, mapped.health)
        assertEquals(setOf("recovery-timesync"), mapped.maintenanceOps)
    }
}
