package org.example.project.core.mapper.websocket

import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.toDecimalString
import java.math.BigDecimal

/** Open (unpaid) total of a session as a 2-dp plain string, or null when there is nothing open. */
fun sessionOpenAmount(session: TableSession): String? =
    session.orderItems
        .filter { it.itemStatus == ItemStatus.OPEN }
        .sumOf { it.finalPrice }
        .takeIf { it > BigDecimal.ZERO }
        ?.toDecimalString()

fun createTableStatusUpdateDto(
    table: RTable,
    status: TableStatus,
    employee: Employee,
    sessionId: Long? = null,
    sessionStartTime: String? = null,
    isTraining: Boolean = false,
): NotificationDto.TableStatusUpdateDto =
    NotificationDto.TableStatusUpdateDto(
        timestamp = currentTimeStamp().toString(),
        tableId = table.id.value,
        tableNumber = table.tableNumber,
        status = status.toString(),
        sessionId = sessionId,
        sessionStartTime = sessionStartTime,
        employeeId = employee.id.value.toString(),
        employeeNumber = employee.employeeNumber,
        isTraining = isTraining,
        openAmount = table.currentSession?.let { sessionOpenAmount(it) },
    )
