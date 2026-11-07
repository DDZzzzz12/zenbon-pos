package org.example.project.features.timetracking.database.tables

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object WageTypeTable : UUIDTable("wage_type") {
    val code = integer("code").uniqueIndex()
    val name = varchar("name", 100)
    val description = varchar("description", 255).nullable()
    val isDefault = bool("is_default").default(false)
}
