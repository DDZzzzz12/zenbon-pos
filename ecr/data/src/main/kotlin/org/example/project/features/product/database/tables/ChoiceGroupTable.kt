package org.example.project.features.product.database.tables

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * A reusable selection group attached to products (e.g. "Entrée", "Size", "Add-ons").
 * required ⇔ minSelections >= 1 ; pick-one ⇔ maxSelections == 1.
 */
object ChoiceGroupTable : IntIdTable("choice_group", "id") {
    val name = varchar("name", 255)
    val enName = varchar("en_name", 255).nullable()
    val chName = varchar("ch_name", 255).nullable()
    val minSelections = integer("min_selections").default(0)
    val maxSelections = integer("max_selections").default(1)
    val isActive = bool("is_active").default(true)
    val updatedAt = datetime("updated_at")
}
