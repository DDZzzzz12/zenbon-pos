package org.example.project.features.reservation.domain.models

import org.example.project.features.customer.domain.models.Customer
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.reservation.database.tables.ReservationTable
import org.example.project.features.reservation.database.tables.ReservationTableAssignmentTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

class Reservation(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<Reservation>(ReservationTable)

    var customer by Customer optionalReferencedOn ReservationTable.customerId
    var guestName by ReservationTable.guestName
    var guestPhone by ReservationTable.guestPhone
    var partySize by ReservationTable.partySize
    var reservationDate by ReservationTable.reservationDate
    var reservationTime by ReservationTable.reservationTime
    var durationMinutes by ReservationTable.durationMinutes
    var status by ReservationTable.status
    var notes by ReservationTable.notes
    var createdByEmployee by Employee referencedOn ReservationTable.createdBy
    var updatedByEmployee by Employee optionalReferencedOn ReservationTable.updatedBy
    var createdAt by ReservationTable.createdAt
    var updatedAt by ReservationTable.updatedAt
    var tableGroups by ReservationTable.tableGroups
    var maxTablesPerGroup by ReservationTable.maxTablesPerGroup
    var locationPreference by ReservationTable.locationPreference

    val tableAssignments by ReservationTableAssignment referrersOn ReservationTableAssignmentTable.reservationId
}
