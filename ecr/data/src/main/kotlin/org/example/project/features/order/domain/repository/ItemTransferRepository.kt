package org.example.project.features.order.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.ItemTransfer
import org.example.project.features.table.domain.models.TableSession

interface ItemTransferRepository {
    /**
     * Records a single transfer event covering [itemIds]. One row is written.
     * Replaces the previous per-item createTransfer.
     */
    fun createTransferBatch(
        itemIds: List<Long>,
        fromTable: TableSession,
        toTable: TableSession,
        employee: Employee,
        reason: String? = null,
    ): ItemTransfer

    fun getTransfersByTableSessionId(tableSessionId: Long): List<ItemTransfer>

    fun getTransfersBetween(
        startDateTime: LocalDateTime,
        endDateTime: LocalDateTime,
    ): List<ItemTransfer>
}
