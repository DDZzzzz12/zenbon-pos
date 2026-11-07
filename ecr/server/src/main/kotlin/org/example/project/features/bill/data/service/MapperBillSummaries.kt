package org.example.project.features.bill.data.service

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillInvoiceLink
import org.example.project.features.bill.domain.service.BillSummaries
import org.example.project.features.bill.mapper.toSummaryDtos
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.table.mapper.toSessionSummaryDto

/** [BillSummaries] over the bill and table-session mappers; runs in the caller's transaction. */
class MapperBillSummaries(
    private val invoices: BillInvoiceLink,
) : BillSummaries {
    override fun summariesOf(bills: List<Bill>): List<BillDto.BillSummaryDto> = bills.toSummaryDtos(invoices)

    override fun sessionSummaryOf(
        session: TableSession,
        employee: Employee?,
        hrNames: Boolean,
    ): TableSessionDto.SessionSummaryDto = session.toSessionSummaryDto(invoices, employee, hrNames = hrNames)
}
