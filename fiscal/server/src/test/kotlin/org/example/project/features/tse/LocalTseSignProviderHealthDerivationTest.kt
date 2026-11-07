package org.example.project.features.tse

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseHealthState
import org.example.project.features.tse.data.provider.local.deriveTseHealth
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class LocalTseSignProviderHealthDerivationTest {
    @Test
    fun `truth table for deriveTseHealth`() {
        val cases: List<Quad<TseMode, TseDto.HealthCheckResultDto?, Boolean, TseHealthState>> =
            listOf(
                Quad(TseMode.DISABLED, null, false, TseHealthState.UNREACHABLE),
                Quad(TseMode.DISABLED, healthyResult(), true, TseHealthState.UNREACHABLE),
                Quad(TseMode.SIMULATION, null, false, TseHealthState.HEALTHY),
                Quad(TseMode.SIMULATION, unhealthyResult(), true, TseHealthState.HEALTHY),
                Quad(TseMode.NORMAL, null, false, TseHealthState.UNREACHABLE),
                Quad(TseMode.NORMAL, null, true, TseHealthState.UNREACHABLE),
                Quad(TseMode.NORMAL, healthyResult(), false, TseHealthState.HEALTHY),
                Quad(TseMode.NORMAL, healthyResult(), true, TseHealthState.MAINTENANCE),
                Quad(TseMode.NORMAL, unhealthyResult(), true, TseHealthState.MAINTENANCE),
                Quad(TseMode.NORMAL, unhealthyResult(), false, TseHealthState.DEGRADED),
            )

        for ((mode, raw, inMaintenance, expected) in cases) {
            val actual = deriveTseHealth(mode, raw, inMaintenance)
            assertEquals(
                expected,
                actual,
                "mode=$mode rawHealthy=${raw?.healthy} inMaintenance=$inMaintenance → expected $expected got $actual",
            )
        }
    }

    private fun healthyResult() =
        TseDto.HealthCheckResultDto(
            healthy = true,
            health = TseHealthState.HEALTHY,
            maintenanceOps = emptySet(),
            initialized = true,
            validTime = true,
            passedSelfTest = true,
            remainingSignatures = 100_000L,
            availableCapacity = 1_000_000L,
            certificateExpirationDate = 0L,
            timestamp = Clock.System.now().toEpochMilliseconds(),
        )

    private fun unhealthyResult() = healthyResult().copy(healthy = false, validTime = false)

    private data class Quad<A, B, C, D>(
        val a: A,
        val b: B,
        val c: C,
        val d: D,
    )
}
