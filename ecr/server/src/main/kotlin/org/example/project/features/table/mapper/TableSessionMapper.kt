/**
 * Table Session entity to DTO mapping functions.
 *
 * Provides conversion methods for TableSession entities to their corresponding DTOs
 * for API responses and data transfer operations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.table.mapper

import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.core.utils.durationBetween
import org.example.project.core.utils.formatHumanReadable
import org.example.project.features.bill.domain.service.BillInvoiceLink
import org.example.project.features.bill.mapper.toSummaryDtos
import org.example.project.features.customer.mapper.toSummary
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.employee.mapper.toInfoDto
import org.example.project.features.table.domain.models.TableSession

/**
 * Converts TableSession entity to TableSessionResponseDto.
 */
fun TableSession.toResponse(): TableSessionDto.TableSessionResponseDto =
    TableSessionDto.TableSessionResponseDto(
        id = this.id.value,
        tableNumber = this.table.tableNumber,
        employeeId =
            this.employeeId.id.value
                .toString(),
        employeeNumber = this.employeeId.employeeNumber,
        startTime = this.startTime,
        endTime = this.endTime,
        completed = this.completed,
        isDelivery = this.isDelivery(),
        customer = this.customer?.toSummary(),
        deliveryAddress = this.deliveryAddress?.toSummary(),
        isTraining = this.isTraining,
    )

/**
 * Converts a session to its summary. `employeeInfo.lastName` names the operator by the personnel
 * last name only with [hrNames] (the caller holds `EMPLOYEE_READ`, `EmployeeHrNames.visibleTo`),
 * otherwise by the username: fail closed.
 */
fun TableSession.toSessionSummaryDto(
    invoices: BillInvoiceLink,
    employee: Employee? = null,
    forActiveSession: Boolean = false,
    hrNames: Boolean = false,
): TableSessionDto.SessionSummaryDto {
    if (forActiveSession) {
        return TableSessionDto.SessionSummaryDto(
            id = this.id.value,
            tableNumber = this.table.tableNumber,
            employeeInfo = this.employeeId.toInfoDto(hrLastName = hrNames),
            startTime = this.startTime,
            note = this.note,
            isTraining = this.isTraining,
        )
    }
    return TableSessionDto.SessionSummaryDto(
        id = this.id.value,
        tableNumber = this.table.tableNumber,
        employeeInfo = this.employeeId.toInfoDto(hrLastName = hrNames),
        startTime = this.startTime,
        endTime = this.endTime,
        itemCount = this.orderItems.count(),
        orderCount = this.orders.count(),
        durationMinutes = if (this.endTime != null)durationBetween(this.startTime, this.endTime!!).formatHumanReadable() else null,
        bills =
            if (employee == null) {
                this.bills.toList().toSummaryDtos(invoices)
            } else {
                this.bills.filter { it.employeeId == employee }.toSummaryDtos(invoices)
            },
        note = this.note,
        isTraining = this.isTraining,
    )
}
