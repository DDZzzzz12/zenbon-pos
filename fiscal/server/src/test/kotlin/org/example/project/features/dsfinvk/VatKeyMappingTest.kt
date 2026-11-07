package org.example.project.features.dsfinvk

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Regression test for the VAT-key mapping defect (DSFinV-K compliance remediation, Task 15 / M2):
 * [DsfinvkExportDto.VatKeyMapping.getVatKey] used to bucket rates by a wide tolerance band, so any
 * non-configured/historical rate (e.g. 16%) silently fell through to key 6 ("declared 0%" under the
 * old — mislabeled — constant name), silently dropping VAT, and a rate around 5% was mis-bucketed
 * into the 5.5% average-rate key. The constants for keys 5/6/7 were also mislabeled versus
 * DSFinV-K Anlage 2 (5 = Nicht steuerbar, 6 = Umsatzsteuerfrei, 7 = UmsatzsteuerNichtErmittelbar).
 */
class VatKeyMappingTest {
    @Test
    @DisplayName("standard configured VAT rates map to Anlage-2 keys 1-5")
    fun `getVatKey maps exact configured rates`() {
        assertEquals(1, DsfinvkExportDto.VatKeyMapping.getVatKey(0.19))
        assertEquals(2, DsfinvkExportDto.VatKeyMapping.getVatKey(0.07))
        assertEquals(3, DsfinvkExportDto.VatKeyMapping.getVatKey(0.078)) // current §24 Nr.3 (2025+)
        assertEquals(4, DsfinvkExportDto.VatKeyMapping.getVatKey(0.055))
        assertEquals(5, DsfinvkExportDto.VatKeyMapping.getVatKey(0.0))
    }

    @Test
    @DisplayName("STAM-6/7: historical rates map to their fixed Anlage-2 IDs, never to key 7 or key 3")
    fun `historical rates map to their Anlage-2 historical keys`() {
        // Anlage 2 (Stand 05.12.2024): 21=16%, 22=5%, 13=10.70%, 23=9.50%, 33=9.00%, 43=8.40%.
        assertEquals(DsfinvkExportDto.VatKeyMapping.HIST_GENERAL_16, DsfinvkExportDto.VatKeyMapping.getVatKey(0.16))
        assertEquals(DsfinvkExportDto.VatKeyMapping.HIST_REDUCED_5, DsfinvkExportDto.VatKeyMapping.getVatKey(0.05))
        assertEquals(DsfinvkExportDto.VatKeyMapping.HIST_AVG3_107, DsfinvkExportDto.VatKeyMapping.getVatKey(0.107))
        assertEquals(DsfinvkExportDto.VatKeyMapping.HIST_AVG3_95, DsfinvkExportDto.VatKeyMapping.getVatKey(0.095))
        assertEquals(DsfinvkExportDto.VatKeyMapping.HIST_AVG3_90, DsfinvkExportDto.VatKeyMapping.getVatKey(0.09))
        assertEquals(DsfinvkExportDto.VatKeyMapping.HIST_AVG3_84, DsfinvkExportDto.VatKeyMapping.getVatKey(0.084))
        assertNotEquals(
            DsfinvkExportDto.VatKeyMapping.AVERAGE_3,
            DsfinvkExportDto.VatKeyMapping.getVatKey(0.084),
            "8.40% is HISTORICAL (ID 43) since 2025 — never the current key 3",
        )
    }

    @Test
    @DisplayName("constant values 1-7 match their Anlage-2 meaning via the renamed constants")
    fun `constants match Anlage 2 semantics`() {
        assertEquals(1, DsfinvkExportDto.VatKeyMapping.GENERAL)
        assertEquals(2, DsfinvkExportDto.VatKeyMapping.REDUCED)
        assertEquals(3, DsfinvkExportDto.VatKeyMapping.AVERAGE_3)
        assertEquals(4, DsfinvkExportDto.VatKeyMapping.AVERAGE_1)
        assertEquals(5, DsfinvkExportDto.VatKeyMapping.NICHT_STEUERBAR)
        assertEquals(6, DsfinvkExportDto.VatKeyMapping.UMSATZSTEUERFREI)
        assertEquals(7, DsfinvkExportDto.VatKeyMapping.NICHT_ERMITTELBAR)
    }

    @Test
    @DisplayName("a truly unknown rate still falls through to key 7, never to a declared-zero key")
    fun `getVatKey maps genuinely unknown rates to key 7`() {
        val key = DsfinvkExportDto.VatKeyMapping.getVatKey(0.0333)
        assertNotEquals(6, key, "an unrecognized rate must not be silently reported as 0% VAT")
        assertNotEquals(4, key, "an unrecognized rate must not be fuzzy-bucketed into key 4")
        assertEquals(DsfinvkExportDto.VatKeyMapping.NICHT_ERMITTELBAR, key)
    }

    @Test
    @DisplayName("STAM-4: MAPPED_RATES is exactly the set getVatKey maps to a non-7 key (drift guard)")
    fun `MAPPED_RATES mirrors getVatKey without drift`() {
        // Every rate the guard considers mappable must actually map to a real Anlage-2 key.
        DsfinvkExportDto.VatKeyMapping.MAPPED_RATES.forEach { rate ->
            assertNotEquals(
                DsfinvkExportDto.VatKeyMapping.NICHT_ERMITTELBAR,
                DsfinvkExportDto.VatKeyMapping.getVatKey(rate),
                "MAPPED_RATES lists $rate but getVatKey sends it to key 7 — the mirror has drifted",
            )
        }

        // And no rate OUTSIDE MAPPED_RATES may map to a non-7 key: MAPPED_RATES must be the COMPLETE
        // set getVatKey maps. Sweep the full 0..100% grid at the stored 0.01% granularity and assert
        // any rate getVatKey maps to a real key is present in MAPPED_RATES.
        val mapped = DsfinvkExportDto.VatKeyMapping.MAPPED_RATES
        for (hundredthsPercent in 0..10000) {
            val rate = hundredthsPercent / 10000.0 // 0.0001 steps → 0.01% granularity
            val key = DsfinvkExportDto.VatKeyMapping.getVatKey(rate)
            if (key != DsfinvkExportDto.VatKeyMapping.NICHT_ERMITTELBAR) {
                assertTrue(
                    mapped.any { kotlin.math.abs(it - rate) < 0.0001 },
                    "getVatKey maps rate $rate to key $key but it is absent from MAPPED_RATES — the mirror is incomplete",
                )
            }
        }
    }
}
