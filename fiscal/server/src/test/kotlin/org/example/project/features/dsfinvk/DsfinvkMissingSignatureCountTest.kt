package org.example.project.features.dsfinvk

import org.example.project.features.dsfinvk.data.service.MissingSignatureTally
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The DSFinV-K pre-export check must count every kind of Vorgang the export carries.
 *
 * It counted `bills` only. Over 2026-09-01…2026-09-20 of the restored archive it reported
 * *"5 transactions are missing TSE signatures"* while `transactions_tse.csv` came out with **15**
 * rows carrying a non-empty `TSE_TA_FEHLER`: 5 bills, 5 Kassenbuch entries, 2 orders and 3
 * cancellations (E2E-2026-09-20 #11). The route documents that the check "reports what would make
 * the export non-compliant", so an operator reading it was told the package was three times cleaner
 * than it was.
 *
 * The warning also has to name the kinds: bills drain through `TsePendingSweeper`, Kassenbuch
 * entries do not, so "5 missing" and "5 Kassenbuch entries missing" call for different actions.
 */
@DisplayName("DSFinV-K pre-export check — missing signatures across every exported entity")
class DsfinvkMissingSignatureCountTest {
    @Test
    fun `counts every kind, not just bills`() {
        val tally = MissingSignatureTally(bills = 5, orders = 2, kassenbuchEntries = 5, cancellations = 3)

        assertEquals(15, tally.total)
    }

    @Test
    fun `the warning names each kind so the operator knows which queue to drain`() {
        val tally = MissingSignatureTally(bills = 5, orders = 2, kassenbuchEntries = 5, cancellations = 3)

        assertEquals(
            "15 transactions are missing TSE signatures (5 bills, 2 orders, 5 cash book entries, 3 cancellations)",
            tally.warning(),
        )
    }

    @Test
    fun `kinds with nothing missing are left out of the breakdown`() {
        val tally = MissingSignatureTally(bills = 0, orders = 0, kassenbuchEntries = 4, cancellations = 0)

        assertEquals("4 transactions are missing TSE signatures (4 cash book entries)", tally.warning())
    }

    @Test
    fun `a clean period produces no warning at all`() {
        val tally = MissingSignatureTally(bills = 0, orders = 0, kassenbuchEntries = 0, cancellations = 0)

        assertEquals(0, tally.total)
        assertNull(tally.warning(), "nothing missing must not add a warning line")
    }
}
