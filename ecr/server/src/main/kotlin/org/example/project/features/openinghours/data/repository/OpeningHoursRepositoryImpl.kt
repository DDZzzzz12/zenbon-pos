@file:OptIn(kotlin.time.ExperimentalTime::class)

package org.example.project.features.openinghours.data.repository

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.features.openinghours.database.tables.OpeningHoursOverrideTable
import org.example.project.features.openinghours.database.tables.OpeningHoursTable
import org.example.project.features.openinghours.domain.models.OpeningHours
import org.example.project.features.openinghours.domain.models.OpeningHoursOverride
import org.example.project.features.openinghours.domain.repository.OpeningHoursRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.slf4j.LoggerFactory
import kotlin.time.Clock

class OpeningHoursRepositoryImpl : OpeningHoursRepository {
    private val logger = LoggerFactory.getLogger(OpeningHoursRepositoryImpl::class.java)

    override fun getAll(shopId: Long): List<OpeningHours> {
        logger.info("OpeningHoursRepositoryImpl.getAll: fetching all weekly schedules for shopId=$shopId")
        return OpeningHours
            .find { OpeningHoursTable.shopId eq shopId }
            .sortedBy { it.dayOfWeek }
    }

    override fun getByDayOfWeek(
        dayOfWeek: Int,
        shopId: Long,
    ): List<OpeningHours> {
        logger.info("OpeningHoursRepositoryImpl.getByDayOfWeek: dayOfWeek=$dayOfWeek, shopId=$shopId")
        return OpeningHours
            .find {
                (OpeningHoursTable.dayOfWeek eq dayOfWeek) and (OpeningHoursTable.shopId eq shopId)
            }.toList()
    }

    override fun createSlot(
        dayOfWeek: Int,
        openTime: LocalTime,
        closeTime: LocalTime,
        isClosed: Boolean,
        shopId: Long,
    ): OpeningHours {
        logger.info("OpeningHoursRepositoryImpl.createSlot: dayOfWeek=$dayOfWeek, isClosed=$isClosed, shopId=$shopId")
        return OpeningHours.new {
            this.dayOfWeek = dayOfWeek
            this.openTime = openTime
            this.closeTime = closeTime
            this.isClosed = isClosed
            this.shopId =
                org.jetbrains.exposed.v1.core.dao.id
                    .EntityID(shopId, org.example.project.features.setup.database.tables.ShopInfoTable)
            this.createdAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        }
    }

    override fun deleteAll(shopId: Long) {
        logger.info("OpeningHoursRepositoryImpl.deleteAll: clearing all weekly schedules for shopId=$shopId")
        OpeningHoursTable.deleteWhere { OpeningHoursTable.shopId eq shopId }
    }

    override fun getOverrideById(id: Long): OpeningHoursOverride? {
        logger.info("OpeningHoursRepositoryImpl.getOverrideById: id=$id")
        return OpeningHoursOverride.findById(id)
    }

    override fun getOverrideByDate(
        date: LocalDate,
        shopId: Long,
    ): OpeningHoursOverride? {
        logger.info("OpeningHoursRepositoryImpl.getOverrideByDate: date=$date, shopId=$shopId")
        return OpeningHoursOverride
            .find {
                (OpeningHoursOverrideTable.overrideDate eq date) and (OpeningHoursOverrideTable.shopId eq shopId)
            }.firstOrNull()
    }

    override fun getOverridesByDateRange(
        startDate: LocalDate,
        endDate: LocalDate,
        shopId: Long,
    ): List<OpeningHoursOverride> {
        logger.info("OpeningHoursRepositoryImpl.getOverridesByDateRange: $startDate to $endDate, shopId=$shopId")
        return OpeningHoursOverride
            .find {
                (OpeningHoursOverrideTable.overrideDate greaterEq startDate) and
                    (OpeningHoursOverrideTable.overrideDate lessEq endDate) and
                    (OpeningHoursOverrideTable.shopId eq shopId)
            }.sortedBy { it.overrideDate }
    }

    override fun createOverride(
        date: LocalDate,
        openTime: LocalTime?,
        closeTime: LocalTime?,
        isClosed: Boolean,
        reason: String?,
        shopId: Long,
    ): OpeningHoursOverride {
        logger.info("OpeningHoursRepositoryImpl.createOverride: date=$date, isClosed=$isClosed, shopId=$shopId")
        return OpeningHoursOverride.new {
            this.overrideDate = date
            this.openTime = openTime
            this.closeTime = closeTime
            this.isClosed = isClosed
            this.reason = reason
            this.shopId =
                org.jetbrains.exposed.v1.core.dao.id
                    .EntityID(shopId, org.example.project.features.setup.database.tables.ShopInfoTable)
            this.createdAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        }
    }

    override fun updateOverride(override: OpeningHoursOverride): OpeningHoursOverride {
        logger.info("OpeningHoursRepositoryImpl.updateOverride: id=${override.id.value}")
        return override
    }

    override fun deleteOverride(id: Long) {
        logger.info("OpeningHoursRepositoryImpl.deleteOverride: id=$id")
        OpeningHoursOverrideTable.deleteWhere { OpeningHoursOverrideTable.id eq id }
    }
}
