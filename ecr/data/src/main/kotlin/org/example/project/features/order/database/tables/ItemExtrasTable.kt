package org.example.project.features.order.database.tables

import org.example.project.features.product.database.tables.ChoiceGroupTable
import org.example.project.features.product.database.tables.ProductExtrasTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import java.math.BigDecimal

/**
 * Captured option on an order item. Snapshots name + price delta + group name at sale time
 * so historical receipts / DSFinV-K never drift when options are later edited.
 * productExtrasId is nullable: the line survives if the option is later deleted.
 *
 * The snapshot columns carry DB-level defaults so that restoring a pre-feature backup
 * (whose item_extras CSV only has order_item_id + product_extras_id) does not violate the
 * NOT NULL constraints — the COPY relies on column defaults for columns absent from the CSV.
 */
object ItemExtrasTable : LongIdTable("item_extras", "id") {
    val orderItemId = reference("order_item_id", OrderItemTable)
    val productExtrasId = reference("product_extras_id", ProductExtrasTable).nullable()
    val optionNameSnapshot = varchar("option_name_snapshot", 255).default("")
    val priceDeltaSnapshot = decimal("price_delta_snapshot", precision = 19, scale = 4).default(BigDecimal.ZERO)
    val groupNameSnapshot = varchar("group_name_snapshot", 255).nullable()
    val quantity = integer("quantity").default(1)

    /**
     * The choice group this option was picked from, kept for RESOLUTION rather than display —
     * [groupNameSnapshot] remains the authoritative label for receipts.
     *
     * Re-submitting a captured line (re-order, split, edit-after-send) has to name a group:
     * `OrderServiceImpl.resolveSelections` rejects a null `groupId` and reads the authoritative
     * price from the `(group_id, option_id)` placement, because the same option can sit in several
     * groups at different deltas. Without this column a reloaded option could not be re-priced.
     *
     * Nullable: legacy flat extras belong to no group, and rows written before V107 can only be
     * back-filled where the (group name, option) pair is unambiguous. Groups are soft-deleted
     * (`is_active = false`), so the reference does not dangle in practice.
     */
    val groupId = reference("group_id", ChoiceGroupTable).nullable()

    init {
        index("idx_item_extras_order_item", false, orderItemId)
        index("idx_item_extras_group", false, groupId)
    }
}
