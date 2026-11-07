package org.example.project.features.product.database.tables

import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Join: a choice group attached to a product (mirrors product_extras_assignment).
 */
object ProductChoiceGroupTable : Table("product_choice_group") {
    val productId = reference("product_id", ProductTable)
    val groupId = reference("group_id", ChoiceGroupTable)
    val sortPosition = integer("sort_position").default(0)
    val updatedAt = datetime("updated_at").clientDefault { currentTimeStamp() }
    override val primaryKey = PrimaryKey(productId, groupId, name = "product_choice_group_pkey")

    init {
        index("idx_pcg_product", false, productId)
        index("idx_pcg_group", false, groupId)
    }
}
