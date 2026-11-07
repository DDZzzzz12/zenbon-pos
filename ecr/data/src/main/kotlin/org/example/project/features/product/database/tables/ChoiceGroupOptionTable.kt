package org.example.project.features.product.database.tables

import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Join: an option (product_extras row) placed inside a choice group.
 * The price delta is per placement, so the same option can cost differently per group.
 */
object ChoiceGroupOptionTable : Table("choice_group_option") {
    val groupId = reference("group_id", ChoiceGroupTable)
    val optionId = reference("option_id", ProductExtrasTable)
    val priceDelta = decimal("price_delta", precision = 19, scale = 4)
    val sortPosition = integer("sort_position").default(0)
    val isDefault = bool("is_default").default(false)
    val updatedAt = datetime("updated_at").clientDefault { currentTimeStamp() }
    override val primaryKey = PrimaryKey(groupId, optionId, name = "choice_group_option_pkey")

    init {
        index("idx_cgo_group", false, groupId)
        index("idx_cgo_option", false, optionId)
    }
}
