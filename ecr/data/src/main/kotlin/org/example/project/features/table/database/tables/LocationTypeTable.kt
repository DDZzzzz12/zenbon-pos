package org.example.project.features.table.database.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

object LocationTypeTable : LongIdTable("location_types", "id") {
    val name = varchar("name", 100)
    val englishName = varchar("english_name", 100)
    val chineseName = varchar("chinese_name", 100)
    val isVirtual = bool("is_virtual").default(false)
    val isTakeaway = bool("is_takeaway").default(false)
    val sortOrder = integer("sort_order").default(0)
    val isSystem = bool("is_system").default(false)
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
}
