package org.example.project.features.table.data.repository

import org.example.project.features.table.database.tables.CombinableTablesTable
import org.example.project.features.table.domain.repository.CombinableTablesRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import kotlin.math.max
import kotlin.math.min

class CombinableTablesRepositoryImpl : CombinableTablesRepository {
    private val logger = LoggerFactory.getLogger(CombinableTablesRepositoryImpl::class.java)

    override fun getPartners(tableId: Long): List<Long> {
        logger.info("CombinableTablesRepositoryImpl.getPartners: tableId=$tableId")
        val asA =
            CombinableTablesTable
                .select(CombinableTablesTable.tableBId)
                .where { CombinableTablesTable.tableAId eq tableId }
                .map { it[CombinableTablesTable.tableBId] }

        val asB =
            CombinableTablesTable
                .select(CombinableTablesTable.tableAId)
                .where { CombinableTablesTable.tableBId eq tableId }
                .map { it[CombinableTablesTable.tableAId] }

        return (asA + asB).distinct()
    }

    override fun getAll(): List<Pair<Long, Long>> {
        logger.info("CombinableTablesRepositoryImpl.getAll: fetching all pairs")
        return CombinableTablesTable.selectAll().map {
            it[CombinableTablesTable.tableAId] to it[CombinableTablesTable.tableBId]
        }
    }

    override fun getPairsForTables(tableIds: Set<Long>): List<Pair<Long, Long>> {
        logger.info("CombinableTablesRepositoryImpl.getPairsForTables: tableIds=${tableIds.size} tables")
        if (tableIds.isEmpty()) return emptyList()
        return CombinableTablesTable
            .selectAll()
            .where {
                (CombinableTablesTable.tableAId inList tableIds) and
                    (CombinableTablesTable.tableBId inList tableIds)
            }.map {
                it[CombinableTablesTable.tableAId] to it[CombinableTablesTable.tableBId]
            }
    }

    override fun setPartners(
        tableId: Long,
        partnerIds: List<Long>,
    ) {
        logger.info("CombinableTablesRepositoryImpl.setPartners: tableId=$tableId, partners=${partnerIds.size}")
        removeAllForTable(tableId)
        partnerIds.forEach { partnerId ->
            val a = min(tableId, partnerId)
            val b = max(tableId, partnerId)
            CombinableTablesTable.insert {
                it[tableAId] = a
                it[tableBId] = b
            }
        }
    }

    override fun removeAllForTable(tableId: Long) {
        logger.info("CombinableTablesRepositoryImpl.removeAllForTable: tableId=$tableId")
        CombinableTablesTable.deleteWhere {
            (tableAId eq tableId) or (tableBId eq tableId)
        }
    }
}
