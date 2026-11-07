/**
 * Implementation of TableAssignmentService.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 8.
 */
package org.example.project.features.reservation.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.dto.reservation.ReservationDto
import org.example.project.core.domain.enums.reservation.ReservationStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.openinghours.domain.service.OpeningHoursService
import org.example.project.features.reservation.domain.repository.ReservationRepository
import org.example.project.features.reservation.domain.repository.ReservationRuleRepository
import org.example.project.features.reservation.domain.repository.ReservationTableAssignmentRepository
import org.example.project.features.reservation.domain.service.TableAssignmentService
import org.example.project.features.reservation.mapper.toAssignmentDto
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
import kotlin.time.ExperimentalTime

class TableAssignmentServiceImpl(
    private val reservationRepository: ReservationRepository,
    private val assignmentRepository: ReservationTableAssignmentRepository,
    private val ruleRepository: ReservationRuleRepository,
    private val combinableTablesRepository: CombinableTablesRepository,
    private val openingHoursService: OpeningHoursService,
) : TableAssignmentService {
    private val logger = LoggerFactory.getLogger(TableAssignmentServiceImpl::class.java)
    private val systemDefaultDuration = 120
    private val systemDefaultMaxTablesPerGroup = 3

    private data class ReservationSnapshot(
        val status: ReservationStatus,
        val reservationDate: LocalDate,
        val reservationTime: LocalTime,
        val durationMinutes: Int?,
        val partySize: Int,
        val tableGroups: Int,
        val maxTablesPerGroup: Int?,
        val locationPreference: String?,
    )

    @OptIn(ExperimentalTime::class)
    override suspend fun autoAssign(
        reservationId: Long,
        employeeId: String,
    ): ReservationDto.AutoAssignResponseDto {
        logger.info("autoAssign: reservationId=$reservationId, employeeId=$employeeId")
        val empId =
            try {
                UUID.fromString(employeeId)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("Invalid employeeId")
            }

        // Step 1: Load and snapshot reservation fields
        val snapshot =
            dbQuery {
                val r = reservationRepository.getById(reservationId) ?: return@dbQuery null
                ReservationSnapshot(
                    status = r.status,
                    reservationDate = r.reservationDate,
                    reservationTime = r.reservationTime,
                    durationMinutes = r.durationMinutes,
                    partySize = r.partySize,
                    tableGroups = r.tableGroups,
                    maxTablesPerGroup = r.maxTablesPerGroup,
                    locationPreference = r.locationPreference,
                )
            } ?: throw reservationNotFound(reservationId)

        val validStatuses = listOf(ReservationStatus.CONFIRMED, ReservationStatus.SEATED)
        if (snapshot.status !in validStatuses) {
            throw reservationConflict("Cannot auto-assign for reservation in status ${snapshot.status}")
        }

        // Validate opening hours
        val hours = dbQuery { openingHoursService.resolveForDate(snapshot.reservationDate) }
        if (hours != null && !hours.isOpen) {
            throw reservationConflict(
                "Restaurant is closed on ${snapshot.reservationDate}${hours.reason?.let { ": $it" } ?: ""}",
            )
        }

        // Step 2: Compute assignment plan (read-only transaction)
        data class AssignmentPlan(
            val newTableIds: List<Pair<Long, Int>>,
            val existingAssignmentDtos: List<ReservationDto.AssignmentDto>,
            val partySize: Int,
            val failureMessage: String?,
        )

        val plan =
            dbQuery {
                val existingAssignments = assignmentRepository.getByReservationId(reservationId)
                val existingByGroup = existingAssignments.groupBy { it.groupNumber }
                val existingSeatsPerGroup =
                    existingByGroup.mapValues { (_, assignments) ->
                        assignments.sumOf { it.table.seats ?: 0 }
                    }

                val date = snapshot.reservationDate
                val time = snapshot.reservationTime
                val rule = ruleRepository.getByDate(date)
                val durationMinutes = snapshot.durationMinutes ?: rule?.durationMinutes ?: systemDefaultDuration
                val endTime = addMinutes(time, durationMinutes)

                val occupiedTableIds =
                    assignmentRepository
                        .getOccupiedTableIds(
                            date = date,
                            startTime = time,
                            endTime = endTime,
                            excludeReservationId = reservationId,
                            defaultDurationMinutes = durationMinutes,
                        ).toMutableSet()

                val today =
                    Clock.System
                        .now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                        .date
                if (date == today) {
                    val activeSessions =
                        RTable
                            .find {
                                RTableTable.currentSessionId.isNotNull()
                            }.map { it.id.value }
                    occupiedTableIds.addAll(activeSessions)
                }

                val excludedTypeIds =
                    LocationType
                        .find { (LocationTypeTable.isVirtual eq true) or (LocationTypeTable.isTakeaway eq true) }
                        .map { it.id.value }

                var availableTables =
                    RTable
                        .find {
                            (RTableTable.isActive eq true) and
                                (RTableTable.seats.isNotNull()) and
                                (RTableTable.locationTypeId notInList excludedTypeIds)
                        }.filter { it.id.value !in occupiedTableIds }
                        .filter { it.seats != null }

                snapshot.locationPreference?.let { pref ->
                    val prefId = pref.toLongOrNull()
                    val locType =
                        when {
                            prefId != null -> LocationType.findById(prefId)
                            else -> LocationType.find { LocationTypeTable.name eq pref }.firstOrNull()
                        }
                    if (locType != null) {
                        availableTables = availableTables.filter { it.locationTypeRef.id.value == locType.id.value }
                    }
                }

                val availableTableMap = availableTables.associateBy { it.id.value }
                val availableIds = availableTableMap.keys.toMutableSet()

                val existingTableIds = existingAssignments.map { it.tableId }.toSet()
                availableIds.removeAll(existingTableIds)

                val tableGroups = snapshot.tableGroups
                val partySize = snapshot.partySize
                val baseGroupSize = partySize / tableGroups
                val remainder = partySize % tableGroups

                val plannedAssignments = mutableListOf<Pair<Long, Int>>()
                var failureMessage: String? = null

                for (group in 1..tableGroups) {
                    val groupSize = baseGroupSize + if (group <= remainder) 1 else 0
                    val existingSeats = existingSeatsPerGroup[group] ?: 0
                    val neededSeats = groupSize - existingSeats
                    if (neededSeats <= 0) continue

                    val singleFit =
                        availableIds
                            .mapNotNull { availableTableMap[it] }
                            .filter { (it.seats ?: 0) >= neededSeats }
                            .sortedWith(
                                compareBy<RTable> {
                                    if (it.assignmentPriority == 0) Int.MAX_VALUE else it.assignmentPriority
                                }.thenBy { it.seats ?: Int.MAX_VALUE },
                            ).firstOrNull()

                    if (singleFit != null) {
                        plannedAssignments.add(singleFit.id.value to group)
                        availableIds.remove(singleFit.id.value)
                        continue
                    }

                    val maxTables = snapshot.maxTablesPerGroup ?: systemDefaultMaxTablesPerGroup
                    val combinations =
                        findCombinations(
                            availableIds = availableIds,
                            availableTableMap = availableTableMap,
                            targetSeats = neededSeats,
                            maxTables = maxTables,
                        )

                    if (combinations.isNotEmpty()) {
                        combinations.first().forEach { tableId ->
                            plannedAssignments.add(tableId to group)
                            availableIds.remove(tableId)
                        }
                        continue
                    }

                    val maxAvailable =
                        availableIds
                            .mapNotNull { availableTableMap[it]?.seats }
                            .maxOrNull() ?: 0
                    failureMessage = "Cannot seat group of $neededSeats. Largest available: $maxAvailable seats."
                    break
                }

                AssignmentPlan(
                    newTableIds = plannedAssignments,
                    existingAssignmentDtos = existingAssignments.map { it.toAssignmentDto() },
                    partySize = snapshot.partySize,
                    failureMessage = failureMessage,
                )
            }

        if (plan.failureMessage != null) {
            throw reservationConflict(plan.failureMessage)
        }

        // Step 3: Execute the mutation in a clean transaction
        return dbQuery {
            val newAssignments =
                plan.newTableIds.map { (tableId, group) ->
                    assignmentRepository.create(
                        reservationId = reservationId,
                        tableId = tableId,
                        groupNumber = group,
                        assignedBy = empId,
                    )
                }
            val allAssignments = assignmentRepository.getByReservationId(reservationId)
            val newDtos = newAssignments.map { it.toAssignmentDto() }
            val totalSeats = allAssignments.sumOf { it.table.seats ?: 0 }
            ReservationDto.AutoAssignResponseDto(
                kept = plan.existingAssignmentDtos,
                new = newDtos,
                totalSeatsAssigned = totalSeats,
                partySize = plan.partySize,
            )
        }
    }

    override suspend fun manualAssign(
        reservationId: Long,
        employeeId: String,
        request: ReservationDto.ManualAssignRequestDto,
    ): List<ReservationDto.AssignmentDto> {
        logger.info("manualAssign: reservationId=$reservationId, tables=${request.assignments.size}")
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid assignment request")
        }
        val empId =
            try {
                UUID.fromString(employeeId)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("Invalid employeeId")
            }

        // Load and validate reservation snapshot
        val snapshot =
            dbQuery {
                val r = reservationRepository.getById(reservationId) ?: return@dbQuery null
                ReservationSnapshot(
                    status = r.status,
                    reservationDate = r.reservationDate,
                    reservationTime = r.reservationTime,
                    durationMinutes = r.durationMinutes,
                    partySize = r.partySize,
                    tableGroups = r.tableGroups,
                    maxTablesPerGroup = r.maxTablesPerGroup,
                    locationPreference = r.locationPreference,
                )
            } ?: throw reservationNotFound(reservationId)

        val validStatuses = listOf(ReservationStatus.CONFIRMED, ReservationStatus.SEATED)
        if (snapshot.status !in validStatuses) {
            throw reservationConflict("Cannot assign tables for reservation in status ${snapshot.status}")
        }

        data class TableValidationResult(
            val tableId: Long,
            val groupNumber: Int,
            val validationError: PosException?,
        )

        val occupiedTableIds =
            dbQuery {
                val rule = ruleRepository.getByDate(snapshot.reservationDate)
                val durationMinutes = snapshot.durationMinutes ?: rule?.durationMinutes ?: systemDefaultDuration
                val endTime = addMinutes(snapshot.reservationTime, durationMinutes)
                assignmentRepository.getOccupiedTableIds(
                    date = snapshot.reservationDate,
                    startTime = snapshot.reservationTime,
                    endTime = endTime,
                    excludeReservationId = reservationId,
                    defaultDurationMinutes = durationMinutes,
                )
            }

        val validations =
            request.assignments.map { assignment ->
                val tableId = assignment.tableId.toLong()
                dbQuery {
                    val table = RTable.findById(tableId)
                    if (table == null) {
                        TableValidationResult(
                            tableId,
                            assignment.groupNumber,
                            PosException(
                                PosErrorCode.TABLE_NOT_FOUND,
                                HttpStatusCode.NotFound,
                                "Table ${assignment.tableId} not found",
                            ),
                        )
                    } else {
                        val isVirtualOrTakeaway = table.locationTypeRef.isVirtual || table.locationTypeRef.isTakeaway
                        when {
                            !table.isActive ->
                                TableValidationResult(
                                    tableId,
                                    assignment.groupNumber,
                                    validationFailed("Table ${table.tableNumber} is not active"),
                                )
                            isVirtualOrTakeaway ->
                                TableValidationResult(
                                    tableId,
                                    assignment.groupNumber,
                                    validationFailed("Table ${table.tableNumber} is virtual"),
                                )
                            tableId in occupiedTableIds ->
                                TableValidationResult(
                                    tableId,
                                    assignment.groupNumber,
                                    reservationConflict("Table ${table.tableNumber} is occupied in this time window"),
                                )
                            else -> TableValidationResult(tableId, assignment.groupNumber, null)
                        }
                    }
                }
            }

        val firstError = validations.firstOrNull { it.validationError != null }
        if (firstError != null) {
            throw firstError.validationError!!
        }

        // All validation passed — execute the mutation
        return dbQuery {
            validations.map { v ->
                assignmentRepository
                    .create(
                        reservationId = reservationId,
                        tableId = v.tableId,
                        groupNumber = v.groupNumber,
                        assignedBy = empId,
                    ).toAssignmentDto()
            }
        }
    }

    override suspend fun unassignAll(reservationId: Long) {
        logger.info("unassignAll: reservationId=$reservationId")
        val exists = dbQuery { reservationRepository.getById(reservationId) != null }
        if (!exists) throw reservationNotFound(reservationId)
        dbQuery { assignmentRepository.deleteByReservationId(reservationId) }
    }

    override suspend fun getAssignments(reservationId: Long): List<ReservationDto.AssignmentDto> {
        logger.info("getAssignments: reservationId=$reservationId")
        val exists = dbQuery { reservationRepository.getById(reservationId) != null }
        if (!exists) throw reservationNotFound(reservationId)
        return dbQuery {
            assignmentRepository.getByReservationId(reservationId).map { it.toAssignmentDto() }
        }
    }

    override fun releaseAssignments(reservationId: Long) {
        logger.info("releaseAssignments: reservationId=$reservationId")
        assignmentRepository.deleteByReservationId(reservationId)
    }

    override suspend fun bulkAutoAssign(
        reservationIds: List<Long>,
        employeeId: String,
    ): ReservationDto.BulkAutoAssignResponseDto {
        logger.info("bulkAutoAssign: count=${reservationIds.size}, employeeId=$employeeId")
        val results =
            reservationIds.map { reservationId ->
                try {
                    val response = autoAssign(reservationId, employeeId)
                    ReservationDto.BulkAssignResult(
                        reservationId = reservationId,
                        success = true,
                        assignedTables = response.new.map { it.tableNumber },
                    )
                } catch (e: PosException) {
                    ReservationDto.BulkAssignResult(
                        reservationId = reservationId,
                        success = false,
                        errorMessage = e.message ?: e.code.name,
                    )
                }
            }

        val successCount = results.count { it.success }
        val failCount = results.count { !it.success }
        logger.info("bulkAutoAssign: success=$successCount, failed=$failCount")

        return ReservationDto.BulkAutoAssignResponseDto(
            results = results,
            successCount = successCount,
            failCount = failCount,
        )
    }

    // --- Chain Search Algorithm ---

    private fun findCombinations(
        availableIds: Set<Long>,
        availableTableMap: Map<Long, RTable>,
        targetSeats: Int,
        maxTables: Int,
    ): List<List<Long>> {
        val pairs = combinableTablesRepository.getPairsForTables(availableIds)
        if (pairs.isEmpty()) return emptyList()

        val adjacency = mutableMapOf<Long, MutableSet<Long>>()
        pairs.forEach { (a, b) ->
            adjacency.getOrPut(a) { mutableSetOf() }.add(b)
            adjacency.getOrPut(b) { mutableSetOf() }.add(a)
        }

        val results = mutableListOf<List<Long>>()

        for (startId in availableIds) {
            if (startId !in adjacency) continue
            val startSeats = availableTableMap[startId]?.seats ?: continue

            val visited = mutableSetOf(startId)
            dfs(
                currentSet = mutableListOf(startId),
                currentSeats = startSeats,
                visited = visited,
                frontier = adjacency[startId]?.toMutableSet() ?: mutableSetOf(),
                targetSeats = targetSeats,
                maxTables = maxTables,
                adjacency = adjacency,
                availableTableMap = availableTableMap,
                results = results,
            )
        }

        val deduplicated = results.map { it.sorted() }.distinct()

        return deduplicated.sortedWith(
            compareBy<List<Long>> { it.size }
                .thenBy { combo ->
                    combo.sumOf {
                        (availableTableMap[it]?.assignmentPriority ?: 0).let { p ->
                            if (p == 0) Int.MAX_VALUE / combo.size else p
                        }
                    }
                }.thenBy { combo -> combo.sumOf { availableTableMap[it]?.seats ?: 0 } - targetSeats },
        )
    }

    private fun dfs(
        currentSet: MutableList<Long>,
        currentSeats: Int,
        visited: MutableSet<Long>,
        frontier: MutableSet<Long>,
        targetSeats: Int,
        maxTables: Int,
        adjacency: Map<Long, Set<Long>>,
        availableTableMap: Map<Long, RTable>,
        results: MutableList<List<Long>>,
    ) {
        if (currentSeats >= targetSeats) {
            results.add(currentSet.toList())
            return
        }
        if (currentSet.size >= maxTables) return

        val candidates = frontier.filter { it !in visited }.toList()
        for (candidate in candidates) {
            val candidateSeats = availableTableMap[candidate]?.seats ?: continue

            visited.add(candidate)
            currentSet.add(candidate)

            val newFrontier = (frontier + (adjacency[candidate] ?: emptySet()) - visited).toMutableSet()

            dfs(
                currentSet = currentSet,
                currentSeats = currentSeats + candidateSeats,
                visited = visited,
                frontier = newFrontier,
                targetSeats = targetSeats,
                maxTables = maxTables,
                adjacency = adjacency,
                availableTableMap = availableTableMap,
                results = results,
            )

            currentSet.removeAt(currentSet.lastIndex)
            visited.remove(candidate)
        }
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
