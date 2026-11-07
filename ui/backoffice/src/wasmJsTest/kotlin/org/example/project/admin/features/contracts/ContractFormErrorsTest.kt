package org.example.project.admin.features.contracts

import org.example.project.admin.features.contracts.presentation.ContractForm
import org.example.project.admin.features.contracts.presentation.ContractFormErrors
import org.example.project.core.domain.dto.contract.ContractDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun contractDto(
    expectedAmount: String?,
    tolerancePct: String?,
    toleranceAbs: String?,
) = ContractDto.ResponseDto(
    id = 1,
    name = "Miete",
    interval = "MONTHLY",
    dueDay = 1,
    startDate = "2026-01-01",
    expectedAmount = expectedAmount,
    tolerancePct = tolerancePct,
    toleranceAbs = toleranceAbs,
)

/**
 * The client half of rule 8 for `dueWeekday`: a WEEKLY contract needs a weekday in 1..7, and no
 * other interval cares about it at all.
 */
class ContractFormErrorsTest {
    @Test
    fun `a non-numeric supplier id is an error, not a silent null`() {
        assertNotNull(ContractFormErrors.of(ContractForm(supplierId = "Metro")).supplier)
        assertNotNull(ContractFormErrors.of(ContractForm(supplierId = "12x")).supplier)
    }

    @Test
    fun `a blank or numeric supplier id is valid`() {
        assertNull(ContractFormErrors.of(ContractForm(supplierId = "")).supplier)
        assertNull(ContractFormErrors.of(ContractForm(supplierId = " 42 ")).supplier)
    }

    @Test
    fun `loaded amounts drop the DECIMAL(19,4) padding zeros`() {
        val form = ContractForm.from(contractDto(expectedAmount = "149.0000", tolerancePct = "5.0000", toleranceAbs = "12.5000"))

        assertEquals("149,00", form.expectedAmount)
        assertEquals("5,00", form.tolerancePct)
        assertEquals("12,50", form.toleranceAbs)
    }

    @Test
    fun `a WEEKLY form with a blank weekday is invalid`() {
        val form = ContractForm(interval = "WEEKLY", dueWeekday = "")

        assertNotNull(ContractFormErrors.of(form).dueWeekday)
    }

    @Test
    fun `a WEEKLY form with weekday 3 is valid`() {
        val form = ContractForm(interval = "WEEKLY", dueWeekday = "3")

        assertNull(ContractFormErrors.of(form).dueWeekday)
    }

    @Test
    fun `a MONTHLY form with a blank weekday is still valid`() {
        val form = ContractForm(interval = "MONTHLY", dueWeekday = "")

        assertNull(ContractFormErrors.of(form).dueWeekday)
    }

    @Test
    fun `a WEEKLY form with weekday 8 is invalid`() {
        val form = ContractForm(interval = "WEEKLY", dueWeekday = "8")

        assertNotNull(ContractFormErrors.of(form).dueWeekday)
    }
}
