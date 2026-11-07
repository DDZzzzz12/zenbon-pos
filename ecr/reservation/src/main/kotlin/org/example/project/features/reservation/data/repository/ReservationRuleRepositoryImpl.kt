package org.example.project.features.reservation.data.repository

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.example.project.core.domain.enums.reservation.ReservationRuleType
import org.example.project.features.reservation.database.tables.ReservationRuleTable
import org.example.project.features.reservation.domain.models.ReservationRule
import org.example.project.features.reservation.domain.repository.ReservationRuleRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq

class ReservationRuleRepositoryImpl : ReservationRuleRepository {
    override fun getById(id: Long): ReservationRule? = ReservationRule.findById(id)

    override fun getByDate(date: LocalDate): ReservationRule? = ReservationRule.find { ReservationRuleTable.ruleDate eq date }.firstOrNull()

    override fun getByDateRange(
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<ReservationRule> =
        ReservationRule
            .find {
                (ReservationRuleTable.ruleDate greaterEq startDate) and
                    (ReservationRuleTable.ruleDate lessEq endDate)
            }.orderBy(ReservationRuleTable.ruleDate to SortOrder.ASC)
            .toList()

    override fun create(
        ruleDate: LocalDate,
        ruleType: ReservationRuleType,
        durationMinutes: Int?,
        timeSlots: String?,
        startTime: LocalTime?,
        endTime: LocalTime?,
    ): ReservationRule =
        ReservationRule.new {
            this.ruleDate = ruleDate
            this.ruleType = ruleType
            this.durationMinutes = durationMinutes
            this.timeSlots = timeSlots
            this.startTime = startTime
            this.endTime = endTime
            this.createdAt = currentTimeStamp()
        }

    override fun update(rule: ReservationRule): ReservationRule = rule

    override fun delete(id: Long) {
        ReservationRule[id].delete()
    }
}
