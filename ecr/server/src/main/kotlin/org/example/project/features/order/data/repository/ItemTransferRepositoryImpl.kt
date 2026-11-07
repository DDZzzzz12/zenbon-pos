package org.example.project.features.order.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.database.tables.ItemTransferTable
import org.example.project.features.order.domain.models.ItemTransfer
import org.example.project.features.order.domain.repository.ItemTransferRepository
import org.example.project.features.table.domain.models.TableSession
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*

class ItemTransferRepositoryImpl : ItemTransferRepository {
    override fun createTransferBatch(
        itemIds: List<Long>,
        fromTable: TableSession,
        toTable: TableSession,
        employee: Employee,
        reason: String?,
    ): ItemTransfer {
        require(itemIds.isNotEmpty()) { "createTransferBatch: itemIds must not be empty" }
        return ItemTransfer.new {
            this.employee = employee
            this.fromTableSession = fromTable
            this.toTableSession = toTable
            this.orderItem = null
            this.orderItemIds = itemIds
            this.transferTime = currentTimeStamp()
            this.reason = reason
        }
    }

    override fun getTransfersByTableSessionId(tableSessionId: Long): List<ItemTransfer> =
        ItemTransfer
            .find {
                (ItemTransferTable.fromTableSessionId eq tableSessionId) or
                    (ItemTransferTable.toTableSessionId eq tableSessionId)
            }.toList()

    override fun getTransfersBetween(
        startDateTime: LocalDateTime,
        endDateTime: LocalDateTime,
    ): List<ItemTransfer> =
        ItemTransfer
            .find {
                ItemTransferTable.transferTime greaterEq startDateTime and
                    (ItemTransferTable.transferTime lessEq endDateTime)
            }.toList()
}
