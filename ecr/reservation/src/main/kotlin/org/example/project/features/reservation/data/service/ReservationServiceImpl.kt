/**
 * Implementation of ReservationService.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 8.
 */
@file:OptIn(kotlin.time.ExperimentalTime::class)

package org.example.project.features.reservation.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.dto.customer.CustomerDto
import org.example.project.core.domain.dto.reservation.ReservationDto
import org.example.project.core.domain.enums.reservation.ReservationRuleType
import org.example.project.core.domain.enums.reservation.ReservationStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.customer.domain.repository.CustomerRepository
import org.example.project.features.openinghours.domain.service.OpeningHoursService
import org.example.project.features.reservation.domain.repository.ReservationRepository
import org.example.project.features.reservation.domain.repository.ReservationRuleRepository
import org.example.project.features.reservation.domain.repository.ReservationTableAssignmentRepository
import org.example.project.features.reservation.domain.service.ReservationService
import org.example.project.features.reservation.mapper.toResponse
import org.example.project.features.table.database.tables.LocationTypeTable
import org.example.project.features.table.database.tables.RTableTable
import org.example.project.features.table.domain.models.LocationType
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.repository.CombinableTablesRepository
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.or
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Clock

class ReservationServiceImpl(
    private val repository: ReservationRepository,
    private val ruleRepository: ReservationRuleRepository,
    private val customerRepository: CustomerRepository,
    private val openingHoursService: OpeningHoursService,
    private val assignmentRepository: ReservationTableAssignmentRepository,
    private val combinableTablesRepository: CombinableTablesRepository,
) : ReservationService {
    private val logger = LoggerFactory.getLogger(ReservationServiceImpl::class.java)

    override suspend fun getById(id: Long): ReservationDto.ResponseDto =
        dbQuery {
            val reservation = repository.getById(id) ?: throw reservationNotFound(id)
            reservation.toResponse()
        }

    override suspend fun getByDate(
        date: String,
        status: ReservationStatus?,
        limit: Int?,
        offset: Int,
    ): List<ReservationDto.ResponseDto> {
        logger.info("getByDate: date=$date, status=$status")
        val localDate =
            try {
                LocalDate.parse(date)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("Invalid date '$date' (expected ISO yyyy-MM-dd)")
            }
        return dbQuery {
            repository.getByDate(localDate, status, limit, offset).map { it.toResponse() }
        }
    }

    override suspend fun create(
        employeeId: String,
        request: ReservationDto.CreateRequestDto,
    ): ReservationDto.ResponseDto {
        logger.info("create: guest=${request.guestName}, date=${request.reservationDate}")
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid request")
        }
        val localDate =
            try {
                LocalDate.parse(request.reservationDate)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("Invalid reservationDate (expected ISO yyyy-MM-dd)")
            }
        val localTime =
            try {
                LocalTime.parse(request.reservationTime)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("Invalid reservationTime (expected HH:mm)")
            }
        val empId =
            try {
                UUID.fromString(employeeId)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("Invalid employeeId")
            }

        return dbQuery {
            // Validate reservation time is within opening hours
            val hours = openingHoursService.resolveForDate(localDate)
            if (hours != null && !hours.isOpen) {
                throw validationFailed(
                    "Restaurant is closed on $localDate${hours.reason?.let { ": $it" } ?: ""}",
                )
            }
            if (hours != null && hours.isOpen && hours.slots.isNotEmpty()) {
                if (!hours.isTimeWithin(localTime)) {
                    throw validationFailed(
                        "Reservation time ${request.reservationTime} is outside opening hours " +
                            "(${hours.openTime}-${hours.closeTime})",
                    )
                }
            }

            // Validate against reservation rules
            val rule = ruleRepository.getByDate(localDate)
            if (rule != null) {
                when (rule.ruleType) {
                    ReservationRuleType.FIXED_DURATION -> {
                        val ruleStart = rule.startTime ?: throw validationFailed("Rule start time is missing")
                        val ruleEnd = rule.endTime ?: throw validationFailed("Rule end time is missing")
                        if (localTime < ruleStart || localTime > ruleEnd) {
                            throw validationFailed(
                                "Reservation time $localTime is outside the allowed window $ruleStart - $ruleEnd",
                            )
                        }
                    }
                    ReservationRuleType.TIME_SLOTS -> {
                        val slots =
                            rule.timeSlots?.let {
                                kotlinx.serialization.json.Json
                                    .decodeFromString<List<String>>(it)
                            } ?: emptyList()
                        if (request.reservationTime !in slots) {
                            throw validationFailed(
                                "Reservation time ${request.reservationTime} is not in the available time slots: $slots",
                            )
                        }
                    }
                }
            }

            // Auto-resolve or create customer if customerId not provided but phone is
            val resolvedCustomerId =
                if (request.customerId != null) {
                    try {
                        UUID.fromString(request.customerId)
                    } catch (e: IllegalArgumentException) {
                        throw validationFailed("Invalid customerId")
                    }
                } else if (!request.guestPhone.isNullOrBlank()) {
                    val existingCustomer = customerRepository.getCustomerByPhone(request.guestPhone!!)
                    if (existingCustomer != null) {
                        logger.info(
                            "create: found existing customer by phone=${request.guestPhone}, customerId=${existingCustomer.id.value}",
                        )
                        existingCustomer.id.value
                    } else {
                        val nameParts = request.guestName.trim().split("\\s+".toRegex())
                        val firstName = nameParts.first()
                        val lastName = if (nameParts.size > 1) nameParts.drop(1).joinToString(" ") else firstName
                        val customerNumber = "RES-${System.currentTimeMillis()}"
                        val newCustomer =
                            customerRepository.createCustomer(
                                CustomerDto.CreateCustomerRequestDto(
                                    customerNumber = customerNumber,
                                    firstName = firstName,
                                    lastName = lastName,
                                    phone = request.guestPhone,
                                ),
                            )
                        logger.info(
                            "create: auto-created customer '$customerNumber' for guest=${request.guestName}, customerId=${newCustomer.id.value}",
                        )
                        newCustomer.id.value
                    }
                } else {
                    null
                }

            val reservation =
                repository.create(
                    customerId = resolvedCustomerId,
                    guestName = request.guestName,
                    guestPhone = request.guestPhone,
                    partySize = request.partySize,
                    reservationDate = localDate,
                    reservationTime = localTime,
                    durationMinutes = request.durationMinutes ?: rule?.durationMinutes,
                    notes = request.notes,
                    createdBy = empId,
                )
            request.tableGroups?.let { reservation.tableGroups = it }
            request.maxTablesPerGroup?.let { reservation.maxTablesPerGroup = it }
            request.locationPreference?.let { reservation.locationPreference = it }
            reservation.toResponse()
        }
    }

    override suspend fun update(
        id: Long,
        employeeId: String,
        request: ReservationDto.UpdateRequestDto,
    ): ReservationDto.ResponseDto {
        logger.info("update: id=$id")
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid request")
        }
        val empId =
            try {
                UUID.fromString(employeeId)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("Invalid employeeId")
            }

        return dbQuery {
            val reservation = repository.getById(id) ?: throw reservationNotFound(id)

            val terminalStatuses =
                listOf(
                    ReservationStatus.COMPLETED,
                    ReservationStatus.CANCELLED,
                    ReservationStatus.NO_SHOW,
                )
            if (reservation.status in terminalStatuses) {
                throw reservationConflict(
                    "Cannot update reservation in terminal status: ${reservation.status}",
                )
            }

            request.guestName?.let { reservation.guestName = it }
            request.guestPhone?.let { reservation.guestPhone = it }
            request.partySize?.let { reservation.partySize = it }
            try {
                request.reservationDate?.let { reservation.reservationDate = LocalDate.parse(it) }
                request.reservationTime?.let { reservation.reservationTime = LocalTime.parse(it) }
            } catch (e: IllegalArgumentException) {
                throw validationFailed(e.message ?: "Invalid date/time format")
            }
            request.durationMinutes?.let { reservation.durationMinutes = it }
            request.notes?.let { reservation.notes = it }
            request.customerId?.let {
                reservation.customer =
                    org.example.project.features.customer.domain.models.Customer.findById(
                        UUID.fromString(it),
                    )
            }
            request.tableGroups?.let { reservation.tableGroups = it }
            request.maxTablesPerGroup?.let { reservation.maxTablesPerGroup = it }
            request.locationPreference?.let { reservation.locationPreference = it }
            reservation.updatedByEmployee =
                org.example.project.features.employee.domain.models.Employee
                    .findById(empId)

            val updated = repository.update(reservation)
            updated.toResponse()
        }
    }

    override suspend fun updateStatus(
        id: Long,
        employeeId: String,
        request: ReservationDto.UpdateStatusRequestDto,
    ): ReservationDto.ResponseDto {
        logger.info("updateStatus: id=$id to=${request.status}")
        val empId =
            try {
                UUID.fromString(employeeId)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("Invalid employeeId")
            }

        return dbQuery {
            val reservation = repository.getById(id) ?: throw reservationNotFound(id)

            validateStatusTransition(reservation.status, request.status)

            reservation.status = request.status
            reservation.updatedByEmployee =
                org.example.project.features.employee.domain.models.Employee
                    .findById(empId)

            val updated = repository.update(reservation)

            // Release assignments on terminal status (same transaction)
            val terminalStatuses =
                listOf(
                    ReservationStatus.CANCELLED,
                    ReservationStatus.NO_SHOW,
                    ReservationStatus.COMPLETED,
                )
            if (request.status in terminalStatuses) {
                assignmentRepository.deleteByReservationId(id)
            }

            updated.toResponse()
        }
    }

    override suspend fun delete(id: Long) {
        logger.info("delete: id=$id")
        dbQuery {
            val reservation = repository.getById(id) ?: throw reservationNotFound(id)

            if (reservation.status == ReservationStatus.SEATED) {
                throw reservationConflict("Cannot delete a reservation that is currently SEATED")
            }

            assignmentRepository.deleteByReservationId(id)
            repository.delete(id)
        }
    }

    override suspend fun checkAvailability(
        date: String,
        time: String?,
        partySize: Int?,
    ): ReservationDto.AvailabilityResponseDto {
        logger.info("checkAvailability: date=$date, time=$time, partySize=$partySize")
        val localDate =
            try {
                LocalDate.parse(date)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("Invalid date '$date' (expected ISO yyyy-MM-dd)")
            }

        return dbQuery {
            // Step 1: Resolve opening hours
            val hours = openingHoursService.resolveForDate(localDate)
            if (hours != null && !hours.isOpen) {
                return@dbQuery ReservationDto.AvailabilityResponseDto(
                    date = date,
                    time = time,
                    isOpen = false,
                    openTime = null,
                    closeTime = null,
                    totalTables = 0,
                    availableTables = 0,
                    hasRule = false,
                    warningMessage = hours.reason ?: "Restaurant is closed",
                )
            }

            val openTime = hours?.openTime
            val closeTime = hours?.closeTime

            // Step 2: Determine time window
            val rule = ruleRepository.getByDate(localDate)
            val defaultDuration = rule?.durationMinutes ?: 120
            val startTime =
                try {
                    time?.let { LocalTime.parse(it) } ?: openTime ?: LocalTime(0, 0)
                } catch (e: IllegalArgumentException) {
                    throw validationFailed("Invalid time '$time' (expected HH:mm)")
                }
            val endTime =
                try {
                    time?.let {
                        val t = LocalTime.parse(it)
                        addMinutes(t, defaultDuration)
                    } ?: closeTime ?: LocalTime(23, 59)
                } catch (e: IllegalArgumentException) {
                    throw validationFailed("Invalid time '$time' (expected HH:mm)")
                }

            // Step 3: Find occupied tables
            val excludedTypeIds =
                LocationType
                    .find { (LocationTypeTable.isVirtual eq true) or (LocationTypeTable.isTakeaway eq true) }
                    .map { it.id.value }
            val occupiedTableIds =
                assignmentRepository
                    .getOccupiedTableIds(
                        date = localDate,
                        startTime = startTime,
                        endTime = endTime,
                        defaultDurationMinutes = defaultDuration,
                    ).toMutableSet()

            // Also check active walk-in sessions for today
            val today =
                Clock.System
                    .now()
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .date
            if (localDate == today) {
                val activeSessions =
                    RTable
                        .find {
                            RTableTable.currentSessionId neq null
                        }.map { it.id.value }
                occupiedTableIds.addAll(activeSessions)
            }

            // Step 4: Build available table set
            val allActiveTables =
                RTable
                    .find {
                        (RTableTable.isActive eq true) and
                            (RTableTable.seats.isNotNull()) and
                            (RTableTable.locationTypeId notInList excludedTypeIds)
                    }.toList()

            val totalTables = allActiveTables.size
            val availableTables = allActiveTables.filter { it.id.value !in occupiedTableIds }
            val availableIds = availableTables.map { it.id.value }.toSet()

            // Load combinable pairs for available tables
            val combinablePairs = combinableTablesRepository.getPairsForTables(availableIds)
            val combinableMap = mutableMapOf<Long, MutableList<String>>()
            combinablePairs.forEach { (a, b) ->
                combinableMap.getOrPut(a) { mutableListOf() }.add(b.toString())
                combinableMap.getOrPut(b) { mutableListOf() }.add(a.toString())
            }

            val availableTableDetails =
                availableTables.map { table ->
                    ReservationDto.AvailableTableDto(
                        tableId = table.id.value.toString(),
                        tableNumber = table.tableNumber,
                        seats = table.seats ?: 0,
                        locationType = table.locationTypeRef.name,
                        combinableWith = combinableMap[table.id.value] ?: emptyList(),
                    )
                }

            // Step 5: Calculate accommodation
            val canAccommodate =
                partySize?.let { ps ->
                    val singleFit = availableTables.any { (it.seats ?: 0) >= ps }
                    if (singleFit) return@let true

                    if (combinablePairs.isNotEmpty()) {
                        val availableTableMap = availableTables.associateBy { it.id.value }
                        val adjacency = mutableMapOf<Long, MutableSet<Long>>()
                        combinablePairs.forEach { (a, b) ->
                            adjacency.getOrPut(a) { mutableSetOf() }.add(b)
                            adjacency.getOrPut(b) { mutableSetOf() }.add(a)
                        }
                        for (startId in availableIds) {
                            if (startId !in adjacency) continue
                            val startSeats = availableTableMap[startId]?.seats ?: continue
                            if (canReachTarget(startId, startSeats, ps, 3, adjacency, availableTableMap)) return@let true
                        }
                    }
                    false
                }

            // Step 6: Build response
            val availableSlots =
                if (rule?.ruleType == ReservationRuleType.TIME_SLOTS) {
                    rule.timeSlots?.let {
                        kotlinx.serialization.json.Json
                            .decodeFromString<List<String>>(it)
                    }
                } else {
                    null
                }

            val warningMessage =
                when {
                    availableTables.isEmpty() -> "No tables available for this date/time"
                    availableTables.size <= 2 -> "Limited availability (${availableTables.size} tables remaining)"
                    canAccommodate == false -> "No single table or combination can seat a party of $partySize"
                    else -> null
                }

            ReservationDto.AvailabilityResponseDto(
                date = date,
                time = time,
                isOpen = true,
                openTime = openTime?.toString(),
                closeTime = closeTime?.toString(),
                totalTables = totalTables,
                availableTables = availableTables.size,
                canAccommodate = canAccommodate,
                availableTableDetails = availableTableDetails,
                hasRule = rule != null,
                ruleType = rule?.ruleType?.name,
                availableSlots = availableSlots,
                warningMessage = warningMessage,
            )
        }
    }

    private fun canReachTarget(
        startId: Long,
        startSeats: Int,
        targetSeats: Int,
        maxTables: Int,
        adjacency: Map<Long, Set<Long>>,
        tableMap: Map<Long, RTable>,
    ): Boolean {
        if (startSeats >= targetSeats) return true
        if (maxTables <= 1) return false
        val visited = mutableSetOf(startId)
        return dfsCheck(visited, startSeats, targetSeats, maxTables, adjacency, tableMap)
    }

    private fun dfsCheck(
        visited: MutableSet<Long>,
        currentSeats: Int,
        targetSeats: Int,
        maxTables: Int,
        adjacency: Map<Long, Set<Long>>,
        tableMap: Map<Long, RTable>,
    ): Boolean {
        if (currentSeats >= targetSeats) return true
        if (visited.size >= maxTables) return false
        for (nodeId in visited.toList()) {
            for (neighbor in adjacency[nodeId] ?: emptySet()) {
                if (neighbor in visited) continue
                val seats = tableMap[neighbor]?.seats ?: continue
                visited.add(neighbor)
                if (dfsCheck(visited, currentSeats + seats, targetSeats, maxTables, adjacency, tableMap)) return true
                visited.remove(neighbor)
            }
        }
        return false
    }

    private fun addMinutes(
        time: LocalTime,
        minutes: Int,
    ): LocalTime {
        val totalMinutes = time.hour * 60 + time.minute + minutes
        return if (totalMinutes >= 24 * 60) {
            LocalTime(23, 59)
        } else {
            LocalTime(totalMinutes / 60, totalMinutes % 60)
        }
    }

    override suspend fun search(
        query: String,
        limit: Int?,
        offset: Int,
    ): List<ReservationDto.ResponseDto> =
        dbQuery {
            repository.search(query, limit, offset).map { it.toResponse() }
        }

    private fun validateStatusTransition(
        current: ReservationStatus,
        target: ReservationStatus,
    ) {
        val allowedTransitions =
            mapOf(
                ReservationStatus.CONFIRMED to
                    setOf(
                        ReservationStatus.SEATED,
                        ReservationStatus.CANCELLED,
                        ReservationStatus.NO_SHOW,
                    ),
                ReservationStatus.SEATED to
                    setOf(
                        ReservationStatus.COMPLETED,
                        ReservationStatus.CANCELLED,
                    ),
            )

        val allowed = allowedTransitions[current]
        if (allowed == null || target !in allowed) {
            throw reservationConflict(
                "Invalid status transition from $current to $target",
            )
        }
    }

    // --- PosException helpers ---

    private fun reservationNotFound(id: Long) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Reservation $id not found",
        )

    private fun reservationConflict(msg: String) =
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
