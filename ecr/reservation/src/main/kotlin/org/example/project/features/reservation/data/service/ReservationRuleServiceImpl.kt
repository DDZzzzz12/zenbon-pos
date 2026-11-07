/**
 * Implementation of ReservationRuleService.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 8.
 */
package org.example.project.features.reservation.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.reservation.ReservationRuleDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.reservation.domain.repository.ReservationRuleRepository
import org.example.project.features.reservation.domain.service.ReservationRuleService
import org.example.project.features.reservation.mapper.toResponse
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory

class ReservationRuleServiceImpl(
    private val repository: ReservationRuleRepository,
) : ReservationRuleService {
    private val logger = LoggerFactory.getLogger(ReservationRuleServiceImpl::class.java)

    override suspend fun getById(id: Long): ReservationRuleDto.ResponseDto =
        dbQuery {
            val rule = repository.getById(id) ?: throw ruleNotFound(id)
            rule.toResponse()
        }

    override suspend fun getByDateRange(
        startDate: String,
        endDate: String,
    ): List<ReservationRuleDto.ResponseDto> {
        logger.info("getByDateRange: $startDate to $endDate")
        val start =
            try {
                LocalDate.parse(startDate)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("Invalid startDate '$startDate' (expected ISO yyyy-MM-dd)")
            }
        val end =
            try {
                LocalDate.parse(endDate)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("Invalid endDate '$endDate' (expected ISO yyyy-MM-dd)")
            }
        return dbQuery {
            repository.getByDateRange(start, end).map { it.toResponse() }
        }
    }

    override suspend fun create(request: ReservationRuleDto.CreateRequestDto): ReservationRuleDto.ResponseDto {
        logger.info("create: date=${request.ruleDate}, type=${request.ruleType}")
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid request")
        }
        val ruleDate =
            try {
                LocalDate.parse(request.ruleDate)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("Invalid ruleDate '${request.ruleDate}' (expected ISO yyyy-MM-dd)")
            }

        return dbQuery {
            val existing = repository.getByDate(ruleDate)
            if (existing != null) {
                throw ruleConflict("A reservation rule already exists for date ${request.ruleDate}")
            }

            val timeSlotsJson =
                request.timeSlots?.let {
                    Json.encodeToString(ListSerializer(String.serializer()), it)
                }

            val rule =
                repository.create(
                    ruleDate = ruleDate,
                    ruleType = request.ruleType,
                    durationMinutes = request.durationMinutes,
                    timeSlots = timeSlotsJson,
                    startTime = request.startTime?.let { LocalTime.parse(it) },
                    endTime = request.endTime?.let { LocalTime.parse(it) },
                )
            rule.toResponse()
        }
    }

    override suspend fun update(
        id: Long,
        request: ReservationRuleDto.UpdateRequestDto,
    ): ReservationRuleDto.ResponseDto {
        logger.info("update: id=$id")
        return dbQuery {
            val rule = repository.getById(id) ?: throw ruleNotFound(id)

            request.ruleType?.let { rule.ruleType = it }
            request.durationMinutes?.let { rule.durationMinutes = it }
            request.timeSlots?.let { slots ->
                rule.timeSlots = Json.encodeToString(ListSerializer(String.serializer()), slots)
            }
            try {
                request.startTime?.let { rule.startTime = LocalTime.parse(it) }
                request.endTime?.let { rule.endTime = LocalTime.parse(it) }
            } catch (e: IllegalArgumentException) {
                throw validationFailed(e.message ?: "Invalid time format")
            }

            val updated = repository.update(rule)
            updated.toResponse()
        }
    }

    override suspend fun delete(id: Long) {
        logger.info("delete: id=$id")
        dbQuery {
            repository.getById(id) ?: throw ruleNotFound(id)
            repository.delete(id)
        }
    }

    // --- PosException helpers ---

    private fun ruleNotFound(id: Long) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "ReservationRule with id=$id not found",
        )

    private fun ruleConflict(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.Conflict,
            msg,
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
