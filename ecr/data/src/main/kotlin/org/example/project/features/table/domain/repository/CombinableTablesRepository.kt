package org.example.project.features.table.domain.repository

interface CombinableTablesRepository {
    fun getPartners(tableId: Long): List<Long>

    fun getAll(): List<Pair<Long, Long>>

    fun getPairsForTables(tableIds: Set<Long>): List<Pair<Long, Long>>

    fun setPartners(
        tableId: Long,
        partnerIds: List<Long>,
    )

    fun removeAllForTable(tableId: Long)
}
