package org.example.project.features.table.database.tables

import org.jetbrains.exposed.v1.core.Table

object CombinableTablesTable : Table("combinable_tables") {
    val tableAId = long("table_a_id").references(RTableTable.id)
    val tableBId = long("table_b_id").references(RTableTable.id)

    override val primaryKey = PrimaryKey(tableAId, tableBId)
}
