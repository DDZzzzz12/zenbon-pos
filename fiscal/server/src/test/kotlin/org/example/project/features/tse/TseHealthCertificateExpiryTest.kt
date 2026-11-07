package org.example.project.features.tse

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.features.tse.data.provider.local.withCertificateExpiry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `GET /api/tse/health` answered `certificateExpirationDate: 0` while `GET /api/tse/info` answered
 * the real 1783727999 (real Swissbit TSE run): the driver's health result never carried the expiry.
 */
class TseHealthCertificateExpiryTest {
    private val health =
        TseDto.HealthCheckResultDto(
            healthy = true,
            initialized = true,
            validTime = true,
            passedSelfTest = true,
            remainingSignatures = 1_000_000L,
            availableCapacity = 1L,
        )

    @Test
    fun `health reports the expiry the device info reports`() {
        assertEquals(1783727999L, health.withCertificateExpiry(1783727999L).certificateExpirationDate)
    }

    @Test
    fun `an unreadable expiry keeps 0, the documented unknown`() {
        assertEquals(0L, health.withCertificateExpiry(null).certificateExpirationDate)
        assertEquals(0L, health.withCertificateExpiry(0L).certificateExpirationDate)
    }

    @Test
    fun `an expiry the health result already carries is kept`() {
        val withDate = health.copy(certificateExpirationDate = 1900000000L)
        assertEquals(1900000000L, withDate.withCertificateExpiry(1783727999L).certificateExpirationDate)
    }

    @Test
    fun `the rest of the health verdict is untouched`() {
        assertEquals(health.copy(certificateExpirationDate = 1783727999L), health.withCertificateExpiry(1783727999L))
    }
}
