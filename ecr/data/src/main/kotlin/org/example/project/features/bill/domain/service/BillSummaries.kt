package org.example.project.features.bill.domain.service

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.table.domain.models.TableSession

/**
 * The list rows the statistics screens show for bills and table sessions, built by the ecr area
 * so the backoffice reads them without depending on the bill and table-session mappers.
 *
 * **Every function runs in the caller's transaction** (it reads lazy entity relations and the
 * relation tables); it opens none of its own.
 */
interface BillSummaries {
    /** The list rows of [bills], batch-loading their relation links once for the whole list. */
    fun summariesOf(bills: List<Bill>): List<BillDto.BillSummaryDto>

    /**
     * The summary of [session] with its bills, restricted to [employee]'s bills when given.
     * [hrNames] names the operator by the personnel last name instead of the username.
     */
    fun sessionSummaryOf(
        session: TableSession,
        employee: Employee?,
        hrNames: Boolean,
    ): TableSessionDto.SessionSummaryDto
}
