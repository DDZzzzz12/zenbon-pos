package org.example.project.features.order.domain.models

import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.database.tables.ItemTransferTable
import org.example.project.features.table.domain.models.TableSession
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

class ItemTransfer(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<ItemTransfer>(ItemTransferTable)

    /** Legacy: present only on pre-V73 rows. */
    var orderItem by OrderItem optionalReferencedOn ItemTransferTable.orderItemId

    /** New: ids of all items moved in this transfer event. */
    var orderItemIds by ItemTransferTable.orderItemIds

    var employee by Employee referencedOn ItemTransferTable.employeeId

    var fromTableSession by TableSession referencedOn ItemTransferTable.fromTableSessionId

    var toTableSession by TableSession referencedOn ItemTransferTable.toTableSessionId

    var transferTime by ItemTransferTable.transferTime

    var reason by ItemTransferTable.reason

    /**
     * Unified accessor that hides the legacy/new shape split.
     * Returns the list of order-item ids covered by this transfer row.
     */
    fun transferredItemIds(): List<Long> = orderItemIds ?: orderItem?.id?.value?.let(::listOf) ?: emptyList()
}
