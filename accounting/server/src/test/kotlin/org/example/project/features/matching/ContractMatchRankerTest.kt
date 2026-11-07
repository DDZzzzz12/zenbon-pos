package org.example.project.features.matching

import kotlinx.datetime.LocalDate
import org.example.project.features.matching.data.service.ContractCandidate
import org.example.project.features.matching.data.service.ContractMatchRanker
import org.example.project.features.matching.data.service.MatchSignal
import org.example.project.features.matching.data.service.MatchStrength
import org.example.project.features.matching.data.service.MatchSuggestion
import org.example.project.features.matching.data.service.TransactionFacts
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The contract half of the ladder: signals **3** (SEPA mandate / creditor identifier) and **5**
 * (IBAN + amount inside the tolerance band + near the due date).
 *
 * The case that matters most is 3 — neither identifier recorded, so signal 3 says nothing. That is
 * the state every real installation is in on day one, and a fallback to something weaker there would
 * hand out `EXACT` suggestions built on a guess.
 */
@DisplayName("Contract match ranker")
class ContractMatchRankerTest {
    private val supplierId = 42L
    private val otherSupplierId = 43L
    private val dueDate = LocalDate(2026, 3, 1)

    private fun payment(
        amount: String = "1250.00",
        valueDate: LocalDate = dueDate,
        mandateId: String? = null,
        creditorId: String? = null,
        counterpartyIban: String? = "DE02120300000000202051",
    ) = TransactionFacts(
        id = 1L,
        valueDate = valueDate,
        amount = BigDecimal(amount),
        creditDebit = "DEBIT",
        counterpartyIban = counterpartyIban,
        mandateId = mandateId,
        creditorId = creditorId,
    )

    private fun instalment(
        instalmentId: Long = 100L,
        contractId: Long = 7L,
        supplierId: Long? = this.supplierId,
        sepaMandateId: String? = null,
        expectedAmount: String? = "1250.00",
        tolerancePct: String? = null,
        toleranceAbs: String? = null,
        dueDate: LocalDate = this.dueDate,
        openAmount: String? = "1250.00",
    ) = ContractCandidate(
        instalmentId = instalmentId,
        contractId = contractId,
        supplierId = supplierId,
        sepaMandateId = sepaMandateId,
        expectedAmount = expectedAmount?.let { BigDecimal(it) },
        tolerancePct = tolerancePct?.let { BigDecimal(it) },
        toleranceAbs = toleranceAbs?.let { BigDecimal(it) },
        dueDate = dueDate,
        openAmount = openAmount?.let { BigDecimal(it) },
    )

    // 1
    @Test
    fun `a mandate id equal to the contract's recorded mandate names that instalment exactly`() {
        val suggestions =
            ContractMatchRanker.rank(
                transaction = payment(mandateId = "MNDT-2026-0001", counterpartyIban = null),
                candidates = listOf(instalment(sepaMandateId = "MNDT-2026-0001")),
            )

        assertEquals(1, suggestions.size)
        val only = suggestions.single()
        assertEquals(100L, only.contractInstalmentId)
        assertTrue(only.invoiceIds.isEmpty(), "a contract suggestion names no invoice")
        assertEquals(MatchSignal.MANDATE_OR_CREDITOR, only.signal)
        assertEquals(MatchStrength.EXACT, only.strength)
    }

    // 2
    @Test
    fun `a creditor id resolving to the supplier that owns the instalment is exact too`() {
        val suggestions =
            ContractMatchRanker.rank(
                transaction = payment(creditorId = "DE98ZZZ09999999999", counterpartyIban = null),
                candidates = listOf(instalment(), instalment(instalmentId = 101L, supplierId = otherSupplierId)),
                // The resolution is a database lookup, so the caller does it and passes the answer in.
                sepaCreditorSupplierId = supplierId,
            )

        assertEquals(listOf(100L), suggestions.map { it.contractInstalmentId })
        assertEquals(MatchSignal.MANDATE_OR_CREDITOR, suggestions.single().signal)
        assertEquals(MatchStrength.EXACT, suggestions.single().strength)
    }

    // 3
    @Test
    fun `neither identifier recorded means silence, not a weaker fallback`() {
        // The bank quotes both on every direct debit; nothing on our side records what they mean.
        val suggestions =
            ContractMatchRanker.rank(
                transaction = payment(mandateId = "MNDT-2026-0001", creditorId = "DE98ZZZ09999999999", counterpartyIban = null),
                candidates = listOf(instalment(sepaMandateId = null)),
                sepaCreditorSupplierId = null,
            )

        assertTrue(
            suggestions.isEmpty(),
            "the commonest case today: an unrecorded identifier must produce nothing at all",
        )
    }

    // 4
    @Test
    fun `an IBAN-resolved supplier, an amount inside the band and a due date in the window is strong`() {
        val suggestions =
            ContractMatchRanker.rank(
                transaction = payment(amount = "1300.00", valueDate = LocalDate(2026, 3, 3)),
                candidates = listOf(instalment(expectedAmount = "1250.00", tolerancePct = "5.00")),
                counterpartySupplierId = supplierId,
            )

        assertEquals(1, suggestions.size)
        assertEquals(100L, suggestions.single().contractInstalmentId)
        assertEquals(MatchSignal.CONTRACT_WINDOW, suggestions.single().signal)
        assertEquals(MatchStrength.STRONG, suggestions.single().strength)
    }

    // 5
    @Test
    fun `an amount outside the tolerance band is not suggested`() {
        val suggestions =
            ContractMatchRanker.rank(
                // 5 % of 1250,00 is 62,50; 1400,00 is well past it.
                transaction = payment(amount = "1400.00"),
                candidates = listOf(instalment(expectedAmount = "1250.00", tolerancePct = "5.00")),
                counterpartySupplierId = supplierId,
            )

        assertTrue(suggestions.isEmpty())
    }

    // 6
    @Test
    fun `an amount inside the band but a due date outside the window is not suggested`() {
        val suggestions =
            ContractMatchRanker.rank(
                transaction = payment(amount = "1250.00", valueDate = LocalDate(2026, 3, 12)),
                candidates = listOf(instalment(dueDate = LocalDate(2026, 3, 1))),
                counterpartySupplierId = supplierId,
            )

        assertTrue(suggestions.isEmpty(), "eleven days past the due date is the next month's business")

        val atTheBoundary =
            ContractMatchRanker.rank(
                transaction = payment(amount = "1250.00", valueDate = LocalDate(2026, 3, 11)),
                candidates = listOf(instalment(dueDate = LocalDate(2026, 3, 1))),
                counterpartySupplierId = supplierId,
            )
        assertEquals(1, atTheBoundary.size, "exactly ten days is still inside the window")
    }

    // 7
    @Test
    fun `with no tolerance set an exact amount matches and a cent off does not`() {
        val exact =
            ContractMatchRanker.rank(
                transaction = payment(amount = "1250.0000"),
                candidates = listOf(instalment(expectedAmount = "1250.00", tolerancePct = null, toleranceAbs = null)),
                counterpartySupplierId = supplierId,
            )
        assertEquals(1, exact.size, "compareTo, not equals — 1250.0000 and 1250.00 are the same amount")

        val aCentOff =
            ContractMatchRanker.rank(
                transaction = payment(amount = "1250.01"),
                candidates = listOf(instalment(expectedAmount = "1250.00", tolerancePct = null, toleranceAbs = null)),
                counterpartySupplierId = supplierId,
            )
        assertTrue(aCentOff.isEmpty(), "a fixed rent carries no band; a changed amount is a true positive")
    }

    // 8
    @Test
    fun `two instalments of the same contract inside the window are both returned`() {
        val suggestions =
            ContractMatchRanker.rank(
                transaction = payment(amount = "1250.00", valueDate = LocalDate(2026, 3, 5)),
                candidates =
                    listOf(
                        instalment(instalmentId = 100L, dueDate = LocalDate(2026, 3, 1)),
                        instalment(instalmentId = 101L, dueDate = LocalDate(2026, 3, 10)),
                    ),
                counterpartySupplierId = supplierId,
            )

        assertEquals(
            setOf(100L, 101L),
            suggestions.mapNotNull { it.contractInstalmentId }.toSet(),
            "the caller offers a list; nothing here picks a default",
        )
    }

    /**
     * E2E 20.4: a mandate identifies the CONTRACT, not the instalment. With September open and
     * October generated but not yet due, the September debit was offered twice as "Eindeutig" — once
     * per instalment — asking the operator to choose between two "exact" answers. Only the instalment
     * whose due date is nearest the payment is the exact one.
     */
    @Test
    fun `a mandate reaching several open instalments of one contract names only the nearest-due one`() {
        val suggestions =
            ContractMatchRanker.rank(
                transaction = payment(amount = "129.99", valueDate = LocalDate(2026, 9, 2), mandateId = "MND-2026-19", counterpartyIban = null),
                candidates =
                    listOf(
                        instalment(instalmentId = 3L, sepaMandateId = "MND-2026-19", dueDate = LocalDate(2026, 9, 1), openAmount = "129.99"),
                        instalment(instalmentId = 4L, sepaMandateId = "MND-2026-19", dueDate = LocalDate(2026, 10, 1), openAmount = "129.99"),
                    ),
            )

        assertEquals(listOf(3L), suggestions.map { it.contractInstalmentId })
        assertEquals(MatchStrength.EXACT, suggestions.single().strength)
    }

    /** The per-contract narrowing must not merge two contracts of one supplier into one answer. */
    @Test
    fun `a creditor id reaching two contracts of one supplier still names one instalment per contract`() {
        val suggestions =
            ContractMatchRanker.rank(
                transaction = payment(creditorId = "DE98ZZZ09999999999", counterpartyIban = null),
                candidates =
                    listOf(
                        instalment(instalmentId = 100L, contractId = 7L, dueDate = dueDate),
                        instalment(instalmentId = 101L, contractId = 7L, dueDate = LocalDate(2026, 4, 1)),
                        instalment(instalmentId = 200L, contractId = 8L, dueDate = LocalDate(2026, 3, 3)),
                    ),
                sepaCreditorSupplierId = supplierId,
            )

        assertEquals(setOf(100L, 200L), suggestions.mapNotNull { it.contractInstalmentId }.toSet())
    }

    // 9
    @Test
    fun `a settled instalment is never suggested`() {
        val byMandate =
            ContractMatchRanker.rank(
                transaction = payment(mandateId = "MNDT-2026-0001", counterpartyIban = null),
                candidates = listOf(instalment(sepaMandateId = "MNDT-2026-0001", openAmount = "0.00")),
            )
        assertTrue(byMandate.isEmpty(), "nothing is left to absorb the payment, whatever the identifier says")

        val byWindow =
            ContractMatchRanker.rank(
                transaction = payment(),
                candidates = listOf(instalment(openAmount = "-1.00")),
                counterpartySupplierId = supplierId,
            )
        assertTrue(byWindow.isEmpty())
    }

    @Test
    fun `an ineligible transaction is not matched against contracts either`() {
        val suggestions =
            ContractMatchRanker.rank(
                transaction = payment(mandateId = "MNDT-2026-0001").copy(reversalIndicator = true),
                candidates = listOf(instalment(sepaMandateId = "MNDT-2026-0001")),
            )

        assertTrue(suggestions.isEmpty(), "a reversal settles nothing — the guards are a filter, not a weak signal")
    }

    @Test
    fun `a suggestion naming both an invoice and an instalment, or neither, is rejected`() {
        // The invariant is enforced, not merely documented: an allocation has one typed target.
        assertFailsWith<IllegalArgumentException> {
            MatchSuggestion(
                invoiceIds = listOf(1L),
                signal = MatchSignal.MANDATE_OR_CREDITOR,
                strength = MatchStrength.EXACT,
                amount = BigDecimal("1250.00"),
                contractInstalmentId = 100L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MatchSuggestion(
                invoiceIds = emptyList(),
                signal = MatchSignal.MANDATE_OR_CREDITOR,
                strength = MatchStrength.EXACT,
                amount = BigDecimal("1250.00"),
                contractInstalmentId = null,
            )
        }
    }

    @Test
    fun `the settled amount is the payment, or the instalment's residual when that is smaller`() {
        val partlyOpen =
            ContractMatchRanker.rank(
                transaction = payment(amount = "1250.00", mandateId = "MNDT-2026-0001", counterpartyIban = null),
                candidates = listOf(instalment(sepaMandateId = "MNDT-2026-0001", openAmount = "400.00")),
            )
        assertEquals(0, BigDecimal("400.00").compareTo(partlyOpen.single().amount))
    }
}
