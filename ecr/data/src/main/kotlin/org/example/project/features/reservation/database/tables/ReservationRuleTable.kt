package org.example.project.features.reservation.database.tables

import org.example.project.core.domain.enums.reservation.ReservationRuleType
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.datetime
import org.jetbrains.exposed.v1.datetime.time

object ReservationRuleTable : LongIdTable("reservation_rules") {
    val ruleDate = date("rule_date").uniqueIndex()
    val ruleType = enumerationByName("rule_type", 20, ReservationRuleType::class)
    val durationMinutes = integer("duration_minutes").nullable()
    val timeSlots = text("time_slots").nullable()
    val startTime = time("start_time").nullable()
    val endTime = time("end_time").nullable()
    val createdAt = datetime("created_at")
}
