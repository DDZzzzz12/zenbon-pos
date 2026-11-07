package org.example.project.features.contract

import kotlinx.datetime.LocalDate
import org.example.project.features.contract.data.service.deriveInstalmentStatus
import org.example.project.features.contract.domain.models.ContractInstalmentStatus
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Only `OPEN` and `WAIVED` are ever stored; the other three are computed here. One derivation means
 * the residual and the status cannot disagree — a stored `PAID` beside a non-zero `open_amount` is
 * exactly the drift slice 3a's invariant exists to prevent.
 */
@DisplayName("Instalment status derivation")
class InstalmentStatusDerivationTest {
    private val today = LocalDate(2026, 6, 15)

    private fun derive(
        stored: String,
        due: LocalDate,
        open: String?,
        expected: String? = "100.0000",
    ) = deriveInstalmentStatus(
        stored = stored,
        dueDate = due,
        openAmount = open?.let { BigDecimal(it) },
        expectedAmount = expected?.let { BigDecimal(it) },
        asOf = today,
    )

    @Test
    fun `a waived instalment stays waived whatever its dates and amounts say`() {
        assertEquals(
            ContractInstalmentStatus.WAIVED,
            derive("WAIVED", LocalDate(2020, 1, 1), open = "100.0000"),
            "a human decision outranks every derivation",
        )
    }

    @Test
    fun `nothing outstanding is paid`() {
        assertEquals(ContractInstalmentStatus.PAID, derive("OPEN", LocalDate(2026, 7, 1), open = "0.0000"))
    }

    @Test
    fun `paid outranks overdue`() {
        assertEquals(
            ContractInstalmentStatus.PAID,
            derive("OPEN", LocalDate(2020, 1, 1), open = "0.0000"),
            "a settled instalment is not late, however long ago it was due",
        )
    }

    @Test
    fun `part paid and not yet due is partial`() {
        assertEquals(ContractInstalmentStatus.PARTIAL, derive("OPEN", LocalDate(2026, 7, 1), open = "40.0000"))
    }

    @Test
    fun `part paid and past due is overdue`() {
        assertEquals(
            ContractInstalmentStatus.OVERDUE,
            derive("OPEN", LocalDate(2026, 5, 1), open = "40.0000"),
            "lateness is the more urgent fact about a part-paid instalment",
        )
    }

    @Test
    fun `untouched and past due is overdue`() {
        assertEquals(ContractInstalmentStatus.OVERDUE, derive("OPEN", LocalDate(2026, 5, 1), open = null))
    }

    @Test
    fun `untouched and not yet due is open`() {
        assertEquals(ContractInstalmentStatus.OPEN, derive("OPEN", LocalDate(2026, 7, 1), open = null))
    }

    @Test
    fun `due today is not yet overdue`() {
        assertEquals(
            ContractInstalmentStatus.OPEN,
            derive("OPEN", today, open = null),
            "an instalment due today still has the day to be paid",
        )
    }

    @Test
    fun `an instalment with no expected amount is never partial`() {
        assertEquals(
            ContractInstalmentStatus.OPEN,
            derive("OPEN", LocalDate(2026, 7, 1), open = null, expected = null),
            "with nothing expected there is no fraction to be part of",
        )
    }
}
