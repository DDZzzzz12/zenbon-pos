package org.example.project.features.reservation.database.tables

import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.table.database.tables.RTableTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

object ReservationTableAssignmentTable : LongIdTable("reservation_table_assignments") {
    val reservationId = reference("reservation_id", ReservationTable)
    val tableId = reference("table_id", RTableTable)
    val groupNumber = integer("group_number")
    val assignedBy = reference("assigned_by", EmployeeTable)
    val assignedAt = datetime("assigned_at")

    init {
        uniqueIndex("uq_reservation_table", reservationId, tableId)
        index("idx_rta_reservation", false, reservationId)
        index("idx_rta_table", false, tableId)
    }
}
