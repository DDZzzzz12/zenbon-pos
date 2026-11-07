package org.example.project.features.table.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.table.database.tables.RTableTable
import org.example.project.features.table.domain.models.LocationType
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.repository.RTableRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.slf4j.LoggerFactory

class RTableRepositoryImpl : RTableRepository {
    private val logger = LoggerFactory.getLogger(RTableRepositoryImpl::class.java)

    override fun getAllTables(
        limit: Int?,
        offset: Int?,
        status: TableStatus?,
        locationTypeId: Long?,
        includeInactive: Boolean,
    ): List<RTable> {
        var query =
            if (includeInactive) {
                RTable.Companion.all()
            } else {
                RTable.Companion.find { RTableTable.isActive eq true }
            }

        // Apply status filter if provided
        status?.let {
            query =
                if (includeInactive) {
                    RTable.Companion.find { RTableTable.tableStatus eq status }
                } else {
                    RTable.Companion.find { (RTableTable.isActive eq true) and (RTableTable.tableStatus eq status) }
                }
        }

        // Apply location type filter if provided
        locationTypeId?.let {
            query =
                if (status != null) {
                    if (includeInactive) {
                        RTable.Companion.find { (RTableTable.tableStatus eq status) and (RTableTable.locationTypeId eq locationTypeId) }
                    } else {
                        RTable.Companion.find {
                            (RTableTable.isActive eq true) and (RTableTable.tableStatus eq status) and
                                (RTableTable.locationTypeId eq locationTypeId)
                        }
                    }
                } else {
                    if (includeInactive) {
                        RTable.Companion.find { RTableTable.locationTypeId eq locationTypeId }
                    } else {
                        RTable.Companion.find { (RTableTable.isActive eq true) and (RTableTable.locationTypeId eq locationTypeId) }
                    }
                }
        }

        if (offset != null) {
            if (offset > 0) {
                query = query.limit(count = limit ?: Int.MAX_VALUE).offset(start = offset.toLong())
            }
        } else if (limit != null) {
            query = query.limit(limit)
        }
        val tables = query.toList()
        logger.debug("Retrieved ${tables.size} tables with limit=$limit, offset=$offset, status=$status, locationTypeId=$locationTypeId")
        return tables
    }

    override fun getTableById(id: Long): RTable? {
        val table = RTable.Companion.findById(id)
        if (table != null) {
            logger.debug("Found table with id: $id")
        } else {
            logger.warn("Table not found with id: $id")
        }
        return table
    }

    override fun getTableByIdForUpdate(id: Long): RTable? {
        val table =
            RTable.Companion
                .find { RTableTable.id eq id }
                .forUpdate()
                .firstOrNull()
        if (table != null) {
            logger.debug("Locked table row for update, id: $id")
        } else {
            logger.warn("Table not found for update lock with id: $id")
        }
        return table
    }

    override fun getTableByNumber(tableNumber: String): RTable? {
        val table = RTable.Companion.find { RTableTable.tableNumber eq tableNumber }.firstOrNull()
        if (table != null) {
            logger.debug("Found table with number: $tableNumber")
        } else {
            logger.warn("Table not found with number: $tableNumber")
        }
        return table
    }

    override fun createTable(request: RTableDto.CreateRTableRequestDto): RTable {
        // Validate request
        request.validate()

        // Check for duplicate table number
        val existingTable = RTable.find { RTableTable.tableNumber eq request.tableNumber }.firstOrNull()
        if (existingTable != null) {
            logger.warn("Attempt to create table with duplicate number: ${request.tableNumber}")
            throw IllegalArgumentException("Duplicate table number: ${request.tableNumber}")
        }

        val locationType =
            LocationType.findById(request.locationTypeId)
                ?: throw NoSuchElementException("LocationType not found: ${request.locationTypeId}")

        val table =
            RTable.Companion.new {
                tableNumber = request.tableNumber
                seats = request.seats
                locationTypeRef = locationType
                tableStatus = TableStatus.FREE // New tables start as FREE
                updateAt = currentTimeStamp()
                currentSession = null
                // Floor plan position fields
                positionX = request.positionX
                positionY = request.positionY
                rotation = request.rotation
                width = request.width
                height = request.height
                assignmentPriority = request.assignmentPriority
            }

        logger.info("Created new table: ${table.tableNumber} with ${table.seats} seats")
        return table
    }

    override fun updateTable(
        id: Long,
        request: RTableDto.UpdateRTableRequestDto,
    ): RTable? {
        // Validate request
        request.validate()

        val table = RTable.findById(id)
        if (table == null) {
            logger.warn("Table not found for update with id: $id")
            return null
        }

        // Check for duplicate table number if changing
        request.tableNumber?.let { newNumber ->
            if (newNumber != table.tableNumber) {
                val existingTable = RTable.Companion.find { RTableTable.tableNumber eq newNumber }.firstOrNull()
                if (existingTable != null) {
                    logger.warn("Attempt to update table with duplicate number: $newNumber")
                    throw IllegalArgumentException("Duplicate table number: $newNumber")
                }
                table.tableNumber = newNumber
            }
        }

        // Update fields if provided
        request.seats?.let { table.seats = it }
        request.locationTypeId?.let { ltId ->
            val locationType =
                LocationType.findById(ltId)
                    ?: throw NoSuchElementException("LocationType not found: $ltId")
            table.locationTypeRef = locationType
        }
        request.getTableStatus()?.let { table.tableStatus = it }
        // Update position fields if provided
        request.positionX?.let { table.positionX = it }
        request.positionY?.let { table.positionY = it }
        request.rotation?.let { table.rotation = it }
        request.width?.let { table.width = it }
        request.height?.let { table.height = it }
        request.assignmentPriority?.let { table.assignmentPriority = it }
        table.updateAt = currentTimeStamp()

        logger.info("Updated table: ${table.tableNumber}")
        return table
    }

    override fun batchUpdatePositions(updates: List<RTableDto.TablePositionUpdate>): Int {
        var updatedCount = 0

        // Process all updates in a single transaction
        updates.forEach { update ->
            val table = RTable.Companion.findById(update.tableId)
            if (table != null) {
                table.positionX = update.positionX
                table.positionY = update.positionY
                table.rotation = update.rotation
                table.width = update.width
                table.height = update.height
                table.updateAt = currentTimeStamp()
                updatedCount++
            } else {
                logger.warn("Table not found for batch position update: ${update.tableId}")
            }
        }

        logger.info("Batch updated positions for $updatedCount out of ${updates.size} tables")
        return updatedCount
    }

    override fun deleteTable(id: Long): String {
        val table =
            RTable.Companion.findById(id)
                ?: throw NoSuchElementException("Table not found: $id")

        // Check if table has active session
        if (table.currentSession != null && !table.currentSession!!.completed) {
            throw IllegalStateException("Table has active session and cannot be deleted")
        }

        val entityName = table.tableNumber
        table.isActive = false
        table.updateAt = currentTimeStamp()
        logger.info("Deactivated table: $entityName")
        return entityName
    }

    override fun updateTableStatus(
        id: Long,
        newStatus: TableStatus,
    ): RTable? {
        val table = RTable.Companion.findById(id)
        if (table == null) {
            logger.warn("Table not found for status update with id: $id")
            return null
        }

        table.tableStatus = newStatus
        logger.info("Updated table ${table.tableNumber} status to: $newStatus")
        return table
    }

    override fun getTablesByStatus(status: TableStatus): List<RTable> {
        val tables = RTable.Companion.find { RTableTable.tableStatus eq status }.toList()
        logger.debug("Found ${tables.size} tables with status: $status")
        return tables
    }

    override fun getTablesByTypeId(locationTypeId: Long): List<RTable> {
        val tables = RTable.Companion.find { RTableTable.locationTypeId eq locationTypeId }.toList()
        logger.debug("Found ${tables.size} tables with locationTypeId: $locationTypeId")
        return tables
    }

    override fun resetTable(id: Long): RTable? {
        val table = RTable.Companion.findById(id)
        if (table == null) {
            logger.warn("Table not found for reset with id: $id")
            return null
        }

        // Use the entity's reset method
        table.reset()
        logger.info("Reset table: ${table.tableNumber}")
        return table
    }

    override fun getTablesUpdatedAfter(lastSyncTime: LocalDateTime): List<RTable> {
        val tables =
            RTable.Companion
                .find { RTableTable.updatedAt greater lastSyncTime }
                .filter { !it.isVirtual }
                .toList()

        logger.debug("Found ${tables.size} tables updated after $lastSyncTime")
        return tables
    }

    override fun searchTablesByNumber(
        query: String,
        limit: Int,
        includeInactive: Boolean,
    ): List<RTable> {
        val tables =
            if (includeInactive) {
                RTable.Companion.find { RTableTable.tableNumber like "%$query%" }
            } else {
                RTable.Companion.find { (RTableTable.tableNumber like "%$query%") and (RTableTable.isActive eq true) }
            }.filter { !it.isVirtual }
                .sortedWith(
                    compareBy {
                        when {
                            it.tableNumber.equals(query, ignoreCase = true) -> 0
                            it.tableNumber.startsWith(query, ignoreCase = true) -> 1
                            else -> 2
                        }
                    },
                ).take(limit)

        logger.debug("Found ${tables.size} tables matching query: $query")
        return tables
    }
}
