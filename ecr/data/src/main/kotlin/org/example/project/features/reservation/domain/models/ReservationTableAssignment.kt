package org.example.project.features.reservation.domain.models

import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.reservation.database.tables.ReservationTableAssignmentTable
import org.example.project.features.table.domain.models.RTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

class ReservationTableAssignment(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<ReservationTableAssignment>(ReservationTableAssignmentTable)

    var reservation by Reservation referencedOn ReservationTableAssignmentTable.reservationId
    var table by RTable referencedOn ReservationTableAssignmentTable.tableId
    var groupNumber by ReservationTableAssignmentTable.groupNumber
    var assignedByEmployee by Employee referencedOn ReservationTableAssignmentTable.assignedBy
    var assignedAt by ReservationTableAssignmentTable.assignedAt

    val reservationId get() = readValues[ReservationTableAssignmentTable.reservationId].value
    val tableId get() = readValues[ReservationTableAssignmentTable.tableId].value
    val assignedBy get() = readValues[ReservationTableAssignmentTable.assignedBy].value
}
