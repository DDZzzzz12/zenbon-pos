package org.example.project.features.tse.findings

import org.example.project.core.domain.dto.tse.TseExplanationDto
import org.example.project.core.domain.enums.tse.TseExplanationCategory
import org.example.project.core.domain.enums.tse.TseExplanationSource
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.tse.database.tables.TseExplanationTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/**
 * Append-only access to `tse_explanation`: there is deliberately no update and no delete (design
 * 2026-09-22 D7). A correction is a new row naming the one it replaces — that is what keeps the
 * register usable as evidence. Requires an ambient transaction.
 */
class TseExplanationRepository {
    fun insert(
        serialHex: String,
        tanrFrom: Long,
        tanrTo: Long,
        category: TseExplanationCategory,
        text: String,
        source: TseExplanationSource,
        employeeId: String?,
        supersedesId: Long?,
    ): TseExplanationDto.ExplanationDto {
        val id =
            TseExplanationTable.insertAndGetId {
                it[tseSerialHex] = serialHex
                it[this.tanrFrom] = tanrFrom
                it[this.tanrTo] = tanrTo
                it[this.category] = category
                it[this.text] = text
                it[explanationSource] = source
                it[createdAt] = currentTimeStamp()
                it[createdByEmployeeId] = employeeId
                it[this.supersedesId] = supersedesId
            }
        return byId(id.value)!!
    }

    fun byId(id: Long): TseExplanationDto.ExplanationDto? =
        TseExplanationTable
            .selectAll()
            .where { TseExplanationTable.id eq id }
            .firstOrNull()
            ?.toDto()

    /** Whether a later entry already names [id] as the one it replaces. */
    fun isSuperseded(id: Long): Boolean =
        TseExplanationTable
            .selectAll()
            .where { TseExplanationTable.supersedesId eq id }
            .limit(1)
            .any()

    /** Explanations not corrected by a later entry. */
    fun current(): List<TseExplanationDto.ExplanationDto> {
        val all = all()
        val corrected = all.mapNotNull { it.supersedesId }.toSet()
        return all.filter { it.id !in corrected }.sortedWith(compareBy({ it.tseSerialHex }, { it.tanrFrom }))
    }

    /**
     * The whole register in the order it was written, superseded entries included. This is what
     * the register's export must show: a correction replaces an entry for the findings view, but
     * the earlier text stays part of the record (E2E round 4, R4-5).
     */
    fun all(): List<TseExplanationDto.ExplanationDto> {
        val rows = TseExplanationTable.selectAll().orderBy(TseExplanationTable.id).toList()
        val numbers = employeeNumbersFor(rows.mapNotNull { it[TseExplanationTable.createdByEmployeeId] })
        return rows.map { it.toDto(numbers) }
    }

    /** Employee id → employee number, so the register names its authors the way the till does. */
    private fun employeeNumbersFor(ids: List<String>): Map<String, String> {
        val uuids = ids.distinct().mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        if (uuids.isEmpty()) return emptyMap()
        return EmployeeTable
            .select(EmployeeTable.id, EmployeeTable.employeeNumber)
            .where { EmployeeTable.id inList uuids }
            .associate { it[EmployeeTable.id].value.toString() to it[EmployeeTable.employeeNumber] }
    }

    private fun ResultRow.toDto(employeeNumbers: Map<String, String> = emptyMap()) =
        TseExplanationDto.ExplanationDto(
            id = this[TseExplanationTable.id].value,
            tseSerialHex = this[TseExplanationTable.tseSerialHex],
            tanrFrom = this[TseExplanationTable.tanrFrom],
            tanrTo = this[TseExplanationTable.tanrTo],
            category = this[TseExplanationTable.category],
            text = this[TseExplanationTable.text],
            source = this[TseExplanationTable.explanationSource],
            createdAt = this[TseExplanationTable.createdAt].toString(),
            createdByEmployeeId = this[TseExplanationTable.createdByEmployeeId],
            createdByEmployeeNumber = this[TseExplanationTable.createdByEmployeeId]?.let { employeeNumbers[it] },
            supersedesId = this[TseExplanationTable.supersedesId],
        )
}
