package org.example.project.features.openinghours.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.example.project.core.domain.dto.openinghours.OpeningHoursDto
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.domain.enums.websocket.DataChangeOperation
import org.example.project.core.error.PosErrorCode
import org.example.project.core.websocket.NotificationService
import org.example.project.features.openinghours.domain.repository.OpeningHoursRepository
import org.example.project.features.openinghours.domain.service.OpeningHoursService
import org.example.project.features.openinghours.mapper.toResponse
import org.example.project.features.setup.domain.repository.ShopInfoRepository
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory

class OpeningHoursServiceImpl(
    private val repository: OpeningHoursRepository,
    private val shopInfoRepository: ShopInfoRepository,
) : OpeningHoursService {
    private val logger = LoggerFactory.getLogger(OpeningHoursServiceImpl::class.java)

    private fun resolveShopId(): Long {
        val shopInfo =
            shopInfoRepository.findAll().firstOrNull()
                ?: throw PosException(
                    PosErrorCode.VALIDATION_FAILED,
                    HttpStatusCode.BadRequest,
                    "No shop info configured",
                )
        return shopInfo.id.value
    }

    override suspend fun getAll(): List<OpeningHoursDto.WeeklyResponseDto> {
        logger.info("getAll: fetching all weekly schedules")
        return dbQuery {
            val shopId = resolveShopId()
            repository.getAll(shopId).map { it.toResponse() }
        }
    }

    override suspend fun bulkUpdate(request: OpeningHoursDto.BulkUpdateRequestDto): List<OpeningHoursDto.WeeklyResponseDto> {
        logger.info("bulkUpdate: updating ${request.days.size} day slots")
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid request")
        }
        val dtos =
            dbQuery {
                val shopId = resolveShopId()
                repository.deleteAll(shopId)
                request.days
                    .map { day ->
                        val openTime = LocalTime.parse(day.openTime)
                        val closeTime = LocalTime.parse(day.closeTime)
                        requireOpenBeforeClose(openTime, closeTime, day.isClosed)
                        repository.createSlot(day.dayOfWeek, openTime, closeTime, day.isClosed, shopId)
                    }.map { it.toResponse() }
            }
        NotificationService.notifyDataChange(ChangeEntityType.OPENING_HOURS, DataChangeOperation.UPDATED)
        return dtos
    }

    override suspend fun getOverridesByDateRange(
        startDate: String,
        endDate: String,
    ): List<OpeningHoursDto.OverrideResponseDto> {
        logger.info("getOverridesByDateRange: $startDate to $endDate")
        val start =
            try {
                LocalDate.parse(startDate)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("Invalid startDate; expected ISO format")
            }
        val end =
            try {
                LocalDate.parse(endDate)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("Invalid endDate; expected ISO format")
            }
        return dbQuery {
            val shopId = resolveShopId()
            repository.getOverridesByDateRange(start, end, shopId).map { it.toResponse() }
        }
    }

    override suspend fun createOverride(request: OpeningHoursDto.CreateOverrideRequestDto): OpeningHoursDto.OverrideResponseDto {
        logger.info("createOverride: date=${request.overrideDate}")
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid request")
        }
        val date =
            try {
                LocalDate.parse(request.overrideDate)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("Invalid overrideDate; expected ISO format")
            }
        val openTime =
            request.openTime?.let {
                try {
                    LocalTime.parse(it)
                } catch (e: IllegalArgumentException) {
                    throw validationFailed("Invalid openTime; expected ISO format")
                }
            }
        val closeTime =
            request.closeTime?.let {
                try {
                    LocalTime.parse(it)
                } catch (e: IllegalArgumentException) {
                    throw validationFailed("Invalid closeTime; expected ISO format")
                }
            }

        requireOpenBeforeClose(openTime, closeTime, request.isClosed)

        val dto =
            dbQuery {
                val shopId = resolveShopId()
                val existing = repository.getOverrideByDate(date, shopId)
                if (existing != null) {
                    throw duplicateOverride(date.toString())
                }
                repository.createOverride(date, openTime, closeTime, request.isClosed, request.reason, shopId).toResponse()
            }
        NotificationService.notifyDataChange(ChangeEntityType.OPENING_HOURS, DataChangeOperation.CREATED, dto.id)
        return dto
    }

    override suspend fun updateOverride(
        id: Long,
        request: OpeningHoursDto.UpdateOverrideRequestDto,
    ): OpeningHoursDto.OverrideResponseDto {
        logger.info("updateOverride: id=$id")
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid request")
        }
        val dto =
            dbQuery {
                val existing = repository.getOverrideById(id) ?: throw overrideNotFound(id)
                request.openTime?.let {
                    existing.openTime =
                        try {
                            LocalTime.parse(it)
                        } catch (e: IllegalArgumentException) {
                            throw validationFailed("Invalid openTime; expected ISO format")
                        }
                }
                request.closeTime?.let {
                    existing.closeTime =
                        try {
                            LocalTime.parse(it)
                        } catch (e: IllegalArgumentException) {
                            throw validationFailed("Invalid closeTime; expected ISO format")
                        }
                }
                request.isClosed?.let { existing.isClosed = it }
                request.reason?.let { existing.reason = it }
                // Checked on the merged result: an update may move only one end of the slot.
                requireOpenBeforeClose(existing.openTime, existing.closeTime, existing.isClosed)
                repository.updateOverride(existing).toResponse()
            }
        NotificationService.notifyDataChange(ChangeEntityType.OPENING_HOURS, DataChangeOperation.UPDATED, id)
        return dto
    }

    override suspend fun deleteOverride(id: Long) {
        logger.info("deleteOverride: id=$id")
        dbQuery {
            repository.getOverrideById(id) ?: throw overrideNotFound(id)
            repository.deleteOverride(id)
        }
        NotificationService.notifyDataChange(ChangeEntityType.OPENING_HOURS, DataChangeOperation.DELETED, id)
    }

    /**
     * Must be called within an existing dbQuery transaction context.
     * Callers (checkAvailability, autoAssign) already wrap in dbQuery.
     */
    override suspend fun resolveForDate(date: LocalDate): OpeningHoursService.ResolvedHours? {
        logger.info("resolveForDate: date=$date")
        val shopId = resolveShopId()

        // Check override first
        val override = repository.getOverrideByDate(date, shopId)
        if (override != null) {
            return if (override.isClosed) {
                OpeningHoursService.ResolvedHours(isOpen = false, reason = override.reason)
            } else {
                OpeningHoursService.ResolvedHours(
                    isOpen = true,
                    slots =
                        listOfNotNull(
                            if (override.openTime != null && override.closeTime != null) {
                                OpeningHoursService.ResolvedHours.TimeSlot(override.openTime!!, override.closeTime!!)
                            } else {
                                null
                            },
                        ),
                )
            }
        }

        // Fall back to weekly schedule
        val dayOfWeek = date.dayOfWeek.ordinal
        val weeklySlots = repository.getByDayOfWeek(dayOfWeek, shopId)

        if (weeklySlots.isEmpty()) return null // No hours configured — always open

        if (weeklySlots.all { it.isClosed }) {
            return OpeningHoursService.ResolvedHours(isOpen = false)
        }

        val slots =
            weeklySlots.filter { !it.isClosed }.map {
                OpeningHoursService.ResolvedHours.TimeSlot(it.openTime, it.closeTime)
            }

        return OpeningHoursService.ResolvedHours(isOpen = true, slots = slots)
    }

    /**
     * A slot must open before it closes (QA admin19 FAIL-D). Overnight slots are not modelled:
     * [resolveForDate] hands each slot to `ResolvedHours.isTimeWithin`, which tests
     * `open <= t < close` within one calendar day, so a 16:30–15:00 slot would silently match no
     * time at all and refuse every reservation. Late opening is entered as a slot ending 23:59 plus
     * one on the next day starting 00:00. A closed day or an override without times has nothing to
     * check.
     */
    private fun requireOpenBeforeClose(
        openTime: LocalTime?,
        closeTime: LocalTime?,
        isClosed: Boolean,
    ) {
        if (isClosed || openTime == null || closeTime == null) return
        if (openTime < closeTime) return
        throw validationFailed(
            "openTime ($openTime) must be before closeTime ($closeTime); overnight slots are not supported — split them at midnight",
        )
    }

    private fun overrideNotFound(id: Long) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Opening hours override $id not found",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )

    /**
     * A second override for a date that already has one is a state conflict, not a malformed
     * request — the same body succeeds once the existing override is removed. Mirrors the
     * "already exists" handling in EmployeeInfoServiceImpl.
     */
    private fun duplicateOverride(date: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.Conflict,
            "Override already exists for date $date",
        )
}
