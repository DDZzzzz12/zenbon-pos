package org.example.project.features.timetracking.database.tables

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object CostCenterTable : UUIDTable("cost_center") {
    val code = varchar("code", 20).uniqueIndex()
    val name = varchar("name", 100)
    val isActive = bool("is_active").default(true)
}
