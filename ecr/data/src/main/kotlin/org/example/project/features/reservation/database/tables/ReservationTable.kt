package org.example.project.features.reservation.database.tables

import org.example.project.core.domain.enums.reservation.ReservationStatus
import org.example.project.features.customer.database.tables.CustomerTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.datetime
import org.jetbrains.exposed.v1.datetime.time

object ReservationTable : LongIdTable("reservations") {
    val customerId = reference("customer_id", CustomerTable).nullable()
    val guestName = varchar("guest_name", 100)
    val guestPhone = varchar("guest_phone", 20).nullable()
    val partySize = integer("party_size")
    val reservationDate = date("reservation_date")
    val reservationTime = time("reservation_time")
    val durationMinutes = integer("duration_minutes").nullable()
    val status =
        enumerationByName("status", 20, ReservationStatus::class)
            .default(ReservationStatus.CONFIRMED)
    val notes = varchar("notes", 500).nullable()
    val createdBy = reference("created_by", EmployeeTable)
    val updatedBy = reference("updated_by", EmployeeTable).nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    val tableGroups = integer("table_groups").default(1)
    val maxTablesPerGroup = integer("max_tables_per_group").nullable()
    val locationPreference = varchar("location_preference", 20).nullable()
}
