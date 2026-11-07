package org.example.project.core.domain.dto.tse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TSE 2.0 refuses transactions while ~1 MB is still free, so "not physically full" is not
 * the same as "fine". The vendor's guidance is to export below 50 MB free.
 */
class TseStorageHeadroomTest {
    private fun info(
        capacity: Long,
        size: Long,
    ) = TseDto.TseInfoDto(
        initializationState = "WORM_INIT_INITIALIZED",
        hasValidTime = true,
        hasPassedSelfTest = true,
        isDevelopmentFirmware = false,
        capacity = capacity,
        size = size,
        hasChangedPuk = true,
        hasChangedAdminPin = true,
        hasChangedTimeAdminPin = true,
        timeUntilNextSelfTest = 0,
        startedTransactions = 0,
        maxStartedTransactions = 512,
        createdSignatures = 0,
        maxSignatures = 20_000_000,
        remainingSignatures = 20_000_000,
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
    fun `reports free bytes`() {
        assertEquals(40_000_000L, info(capacity = 100_000_000, size = 60_000_000).freeBytes)
    }

    @Test
    fun `warns below the 50 MB export threshold`() {
        assertTrue(info(capacity = 100_000_000, size = 60_000_000).shouldExportSoon)
    }

    @Test
    fun `does not warn with ample headroom`() {
        assertFalse(info(capacity = 1_000_000_000, size = 1_000_000).shouldExportSoon)
    }

    @Test
    fun `treats an unknown capacity as no warning rather than a false alarm`() {
        // capacity 0 means "not reported"; warning on it would cry wolf on every device
        // that has not published a capacity yet.
        assertFalse(info(capacity = 0, size = 0).shouldExportSoon)
    }

    @Test
    fun `handles a real 8 GB TSE without overflowing`() {
        // The reason capacity/size are Long: as Int this truncated to -589,934,592.
        val eightGb = 8_000_000_000L
        val device = info(capacity = eightGb, size = 1_000_000_000)

        assertEquals(7_000_000_000L, device.freeBytes)
        assertFalse(device.shouldExportSoon)
    }

    @Test
    fun `never reports negative free space`() {
        assertEquals(0L, info(capacity = 100, size = 500).freeBytes)
    }
}
