package org.example.project.admin.features.contracts

import org.example.project.admin.features.contracts.presentation.ContractForm
import org.example.project.admin.features.contracts.presentation.instalmentLabel
import org.example.project.core.domain.dto.contract.ContractDto
import kotlin.test.Test
import kotlin.test.assertEquals

class ContractFormPresentationTest {
    private fun dto(
        amount: String?,
        pct: String?,
        abs: String?,
    ) = ContractDto.ResponseDto(
        id = 1,
        name = "Reinigung",
        interval = "WEEKLY",
        dueDay = 1,
        dueWeekday = 3,
        startDate = "2026-08-07",
        expectedAmount = amount,
        tolerancePct = pct,
        toleranceAbs = abs,
    )

    @Test
    fun `amounts are prefilled with two decimals and a German comma`() {
        val form = ContractForm.from(dto("149.0000", "5.0000", "2.5000"))

        assertEquals("149,00", form.expectedAmount)
        assertEquals("5,00", form.tolerancePct)
        assertEquals("2,50", form.toleranceAbs)
    }

    @Test
    fun `the round trip keeps the numeric value`() {
        val request = ContractForm.from(dto("149.0000", "5.0000", null)).toCreateRequest()

        assertEquals("149.00", request.expectedAmount)
        assertEquals("5.00", request.tolerancePct)
        assertEquals(null, request.toleranceAbs)
    }

    @Test
    fun `weekly label is day and month`() = assertEquals("02.10.", instalmentLabel("2026-10-02", "WEEKLY"))

    @Test
    fun `month stepped intervals label month and year`() {
        assertEquals("10.2026", instalmentLabel("2026-10-02", "MONTHLY"))
        assertEquals("10.2026", instalmentLabel("2026-10-02T00:00", "QUARTERLY"))
        assertEquals("10.2026", instalmentLabel("2026-10-02", "SEMIANNUAL"))
    }

    @Test
    fun `annual label is the year`() = assertEquals("2026", instalmentLabel("2026-10-02", "ANNUAL"))

    @Test
    fun `one time and unknown intervals label the short date`() {
        assertEquals("02.10.26", instalmentLabel("2026-10-02", "ONE_TIME"))
        assertEquals("02.10.26", instalmentLabel("2026-10-02", "FORTNIGHTLY"))
    }

    @Test
    fun `a malformed date is returned unchanged`() = assertEquals("soon", instalmentLabel("soon", "WEEKLY"))
}
