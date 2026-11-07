package org.example.project.features.product.database.tables

import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

object ProductExtrasAssignmentTable : Table("product_extras_assignment") {
    val productId = reference("product_id", ProductTable)
    val extrasId = reference("extras_id", ProductExtrasTable)
    val updatedAt = datetime("updated_at").clientDefault { currentTimeStamp() }
    override val primaryKey = PrimaryKey(productId, extrasId)
}
