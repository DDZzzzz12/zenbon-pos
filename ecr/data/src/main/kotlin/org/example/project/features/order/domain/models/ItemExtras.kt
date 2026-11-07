package org.example.project.features.order.domain.models

import org.example.project.features.order.database.tables.ItemExtrasTable
import org.example.project.features.product.domain.models.ProductExtras
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/** A captured (snapshotted) option on an order item. */
class ItemExtras(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<ItemExtras>(ItemExtrasTable)

    var orderItem by OrderItem referencedOn ItemExtrasTable.orderItemId
    var productExtras by ProductExtras optionalReferencedOn ItemExtrasTable.productExtrasId
    var optionNameSnapshot by ItemExtrasTable.optionNameSnapshot
    var priceDeltaSnapshot by ItemExtrasTable.priceDeltaSnapshot
    var groupNameSnapshot by ItemExtrasTable.groupNameSnapshot
    var quantity by ItemExtrasTable.quantity

    /**
     * Raw column rather than a `ChoiceGroup` reference on purpose: the mappers read this for every
     * option on every order-item row, and an entity reference would fire one lazy SELECT apiece.
     * Nothing here needs the group itself — only its id, to re-resolve the placement on re-submit.
     */
    var groupIdSnapshot by ItemExtrasTable.groupId
}
