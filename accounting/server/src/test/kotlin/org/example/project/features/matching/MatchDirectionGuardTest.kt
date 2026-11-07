package org.example.project.features.matching

import kotlinx.datetime.LocalDate
import org.example.project.features.matching.data.service.TransactionFacts
import org.example.project.features.matching.data.service.isEligible
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The direction guard: AR matches credits only, AP matches debits only. Without it a €340 supplier
 * invoice and a €340 customer invoice compete for the same transaction row.
 */
@DisplayName("Match direction guard")
class MatchDirectionGuardTest {
    private fun payment(creditDebit: String = "DEBIT") =
        TransactionFacts(
            id = 1L,
            valueDate = LocalDate(2026, 3, 1),
            amount = BigDecimal("119.00"),
            creditDebit = creditDebit,
            reversalIndicator = false,
            hasBankAccount = true,
            isDuplicate = false,
        )

    @Test
    fun `the default direction is DEBIT, which admits debits and rejects credits`() {
        assertTrue(isEligible(payment("DEBIT")), "AP is the default and expects outgoing payments")
        assertFalse(isEligible(payment("CREDIT")), "a credit is not an outgoing payment")
    }

    @Test
    fun `passing CREDIT admits credits and rejects debits`() {
        assertTrue(isEligible(payment("CREDIT"), direction = "CREDIT"), "AR expects incoming payments")
        assertFalse(isEligible(payment("DEBIT"), direction = "CREDIT"), "a debit is not a customer payment")
    }

    @Test
    fun `an unrecognised direction is rejected in both directions`() {
        assertFalse(isEligible(payment("BOTH")))
        assertFalse(isEligible(payment("BOTH"), direction = "CREDIT"))
    }

    @Test
    fun `the other guards still hold under the CREDIT direction`() {
        val reversal =
            payment("CREDIT").copy(reversalIndicator = true)
        assertFalse(isEligible(reversal, direction = "CREDIT"), "a reversal un-settles in either direction")

        val unresolved =
            payment("CREDIT").copy(hasBankAccount = false)
        assertFalse(isEligible(unresolved, direction = "CREDIT"), "no account means no shop in either direction")

        val duplicate =
            payment("CREDIT").copy(isDuplicate = true)
        assertFalse(isEligible(duplicate, direction = "CREDIT"), "a duplicate would pay the same money twice in either direction")
    }
}
