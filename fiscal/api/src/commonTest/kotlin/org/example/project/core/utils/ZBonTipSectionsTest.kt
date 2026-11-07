package org.example.project.core.utils

import org.example.project.core.domain.dto.report.ZBonDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** QA emu14 NOTE-5 — the Z-Bon tip block lists what its total sums, and the owner tip apart. */
class ZBonTipSectionsTest {
    private fun summary(
        number: String,
        tip: String,
        owner: Boolean = false,
    ) = ZBonDto.EmployeeZBonSummaryResponseDto(
        employeeNumber = number,
        role = if (owner) "ADMIN" else "CASHIER",
        amount = tip,
        ownerTip = owner,
    )

    private fun zBon(
        summaries: List<ZBonDto.EmployeeZBonSummaryResponseDto>,
        totalEmployeeTips: String?,
        adminTip: String?,
    ) = ZBonDto.ZBonResponseDto(
        id = 1,
        shopInfoId = 1,
        shopName = "Testladen",
        employeeNumber = "A001",
        startTime = "2026-10-01T00:00:00",
        endTime = "2026-10-01T23:00:00",
        cancelledAmount = "0.00",
        totalRevenue = "100.00",
        openingBalance = "0.00",
        expenses = "0.00",
        closingBalance = "0.00",
        createdAt = "2026-10-01T23:00:00",
        employeeSummaries = summaries,
        totalEmployeeTips = totalEmployeeTips,
        adminTip = adminTip,
    )

    @Test
    fun `the owner tip is not listed among the employee tips and stands on its own`() {
        val sections =
            zBonTipSections(
                zBon(
                    listOf(summary("M001", "4.00"), summary("A001", "4.00", owner = true), summary("K001", "2.00")),
                    totalEmployeeTips = "6.00",
                    adminTip = "4.00",
                ),
            )

        assertEquals(listOf("M001", "K001"), sections.employeeTips.map { it.employeeNumber })
        assertEquals("6.00", sections.employeeTipTotal)
        assertEquals("4.00", sections.ownerTip)
    }

    @Test
    fun `zero tips are not listed, in any scale`() {
        val sections =
            zBonTipSections(
                zBon(
                    listOf(summary("M001", "0.0000"), summary("K001", "2.00"), summary("A001", "0.00", owner = true)),
                    totalEmployeeTips = "2.00",
                    adminTip = "0.00",
                ),
            )

        assertEquals(listOf("K001"), sections.employeeTips.map { it.employeeNumber })
        assertNull(sections.ownerTip, "a zero owner tip gets no line")
    }

    @Test
    fun `a Z-Bon without tips has an empty block`() {
        val sections = zBonTipSections(zBon(listOf(summary("M001", "0.00")), totalEmployeeTips = "0.00", adminTip = "0.00"))

        assertTrue(sections.isEmpty)
        assertNull(sections.employeeTipTotal, "no total under an empty list")
    }

    @Test
    fun `only an owner tip still shows the block, with no employee total`() {
        val sections =
            zBonTipSections(zBon(listOf(summary("A001", "5.00", owner = true)), totalEmployeeTips = null, adminTip = "5.00"))

        assertTrue(sections.employeeTips.isEmpty())
        assertNull(sections.employeeTipTotal)
        assertEquals("5.00", sections.ownerTip)
    }
}
