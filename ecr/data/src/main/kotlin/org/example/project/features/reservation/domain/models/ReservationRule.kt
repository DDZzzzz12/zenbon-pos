package org.example.project.features.reservation.domain.models

import org.example.project.features.reservation.database.tables.ReservationRuleTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

class ReservationRule(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<ReservationRule>(ReservationRuleTable)

    var ruleDate by ReservationRuleTable.ruleDate
    var ruleType by ReservationRuleTable.ruleType
    var durationMinutes by ReservationRuleTable.durationMinutes
    var timeSlots by ReservationRuleTable.timeSlots
    var startTime by ReservationRuleTable.startTime
    var endTime by ReservationRuleTable.endTime
    var createdAt by ReservationRuleTable.createdAt
}
