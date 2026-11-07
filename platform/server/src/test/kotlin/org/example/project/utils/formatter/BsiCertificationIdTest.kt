package org.example.project.utils.formatter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Kassenmeldung's BSI-Zertifizierungs-ID (AEAO zu § 146a Nr. 1.16.2.2) comes from the device's
 * own certification ID, not from a product-name guess.
 */
class BsiCertificationIdTest {
    @Test
    fun `real Swissbit USB TSE reports the ID without a year - completed from the BSI list`() {
        // Captured from the real test device's TseLocator log, 2026-10-06.
        assertEquals("0563-2023", BsiCertificationId.fromTseDescription("BSI-K-TR-0563"))
    }

    @Test
    fun `a full ID passes through unchanged`() {
        assertEquals("0620-2025", BsiCertificationId.fromTseDescription("BSI-K-TR-0620-2025"))
    }

    @Test
    fun `TSE 2_0 maps to its own certificate, not the TSE 1_x one`() {
        assertEquals("0800-2026", BsiCertificationId.fromTseDescription("BSI-K-TR-0800"))
    }

    @Test
    fun `a maintenance certificate keeps its MA suffix`() {
        assertEquals("0620-2025-MA-01", BsiCertificationId.fromTseDescription("bsi-k-tr-0620-2025-ma-01"))
    }

    @Test
    fun `an unknown number without a year is not guessed`() {
        assertNull(BsiCertificationId.fromTseDescription("BSI-K-TR-0999"))
    }

    @Test
    fun `a product name is no certification ID`() {
        // The old name map turned every "Swissbit TSE" into 0620-2025, a TSE 2.0 included.
        assertNull(BsiCertificationId.fromTseDescription("Swissbit TSE"))
        assertNull(BsiCertificationId.fromTseDescription(""))
        assertNull(BsiCertificationId.fromTseDescription(null))
    }
}
