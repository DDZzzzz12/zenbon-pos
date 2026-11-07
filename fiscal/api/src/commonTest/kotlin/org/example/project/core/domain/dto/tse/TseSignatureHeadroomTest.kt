package org.example.project.core.domain.dto.tse

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A TSE is good for roughly 20 million signatures, and Swissbit advises replacing it once
 * that is reached. Certificate expiry already gets a warning; the signature budget is the
 * other thing that ends a device's usable life, and had none.
 */
class TseSignatureHeadroomTest {
    private fun info(
        remaining: Int,
        max: Int,
    ) = TseDto.TseInfoDto(
        initializationState = "WORM_INIT_INITIALIZED",
        hasValidTime = true,
        hasPassedSelfTest = true,
        isDevelopmentFirmware = false,
        capacity = 8_000_000_000,
        size = 1_000_000,
        hasChangedPuk = true,
        hasChangedAdminPin = true,
        hasChangedTimeAdminPin = true,
        timeUntilNextSelfTest = 0,
        startedTransactions = 0,
        maxStartedTransactions = 512,
        createdSignatures = max - remaining,
        maxSignatures = max,
        remainingSignatures = remaining,
        maxTimeSynchronizationDelay = 0,
        maxUpdateDelay = 0,
        tsePublicKey = "",
        timeUntilNextTimeSynchronization = 0,
        tseSerialNumberBytes = "",
        tseSerialNumberHex = "",
        tseDescription = "",
        registeredClients = 1,
        maxRegisteredClients = 100,
        certificateExpirationDate = 0,
        tarExportSizeInSectors = 0,
        tarExportSize = 0,
        hardwareVersion = 0,
        softwareVersion = 0,
        formFactor = "uSD",
    )

    @Test
    fun `warns when few signatures remain`() {
        assertTrue(info(remaining = 100_000, max = 20_000_000).signaturesRunningLow)
    }

    @Test
    fun `does not warn on a fresh device`() {
        assertFalse(info(remaining = 20_000_000, max = 20_000_000).signaturesRunningLow)
    }

    @Test
    fun `treats an unreported maximum as no warning`() {
        // A device that does not publish a maximum must not warn on every render.
        assertFalse(info(remaining = 0, max = 0).signaturesRunningLow)
    }

    @Test
    fun `does not warn just above the threshold`() {
        // 5 % of 20 million is 1,000,000 — one above it must stay quiet, so the boundary is
        // pinned rather than left to whichever comparison the implementation happens to use.
        assertFalse(info(remaining = 1_000_001, max = 20_000_000).signaturesRunningLow)
        assertTrue(info(remaining = 999_999, max = 20_000_000).signaturesRunningLow)
    }
}
